package pl.forma.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Diagnostyka WHOOP - tryb rozszerzony": isolated test screen for WHOOP's proprietary BLE service.
 * Commands are limited to the whitelist in WhoopExtProtocol.SafeCommand plus ONE gated command, the batch ACK (0x17), which is built
 * only after the raw packets of that batch were written and fsynced (memory sync flow). No erase / reset / settings commands exist.
 * It owns its own BluetoothGatt and never touches BleManager / NightService / LiveService (it only READS their state to warn).
 * Everything GATT-related runs on the main thread (callbacks hop to it first), file writes on one background thread.
 */
@SuppressLint("MissingPermission")
class WhoopExtDiagActivity : AppCompatActivity() {

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val OP_TIMEOUT_MS = 8_000L
        const val MTU_WANTED = 247
        const val CMD_TIMEOUT_MS = 5_000L
        const val CSV_MAX_BYTES = 400L * 1024L * 1024L
        const val JSONL_MAX_BYTES = 800L * 1024L * 1024L
        const val MIN_FREE_BYTES = 40L * 1024L * 1024L
        const val MAX_BATCHES = 500
        const val S_IDLE = 0
        const val S_WAIT_RESP = 1
        const val S_RECEIVING = 2
        const val S_WAIT_FLUSH = 3
        const val S_WAIT_ACK = 4
        const val S_WAIT_NEXT = 5
        const val K_IDLE = 0
        const val K_RANGE = 1
        const val K_ACK = 2
        const val OLD_BEFORE_UNIX = 1_640_995_200L // 2022-01-01: the stored backlog is from 2021
        const val LOG_LINES = 40
        const val NAME_PREFIX = "WHOOP"

        val BG = Color.parseColor("#0B0E11")
        val CARD = Color.parseColor("#161B22")
        val TXT = Color.parseColor("#E6EDF3")
        val DIM = Color.parseColor("#8B949E")
        val WARN = Color.parseColor("#F0B429")
        val BAD = Color.parseColor("#FF7B72")
        val GOOD = Color.parseColor("#56D364")
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var releasedOwn = false

    // ---------- BLE state (main thread only) ----------
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private val notifyChars = ArrayList<BluetoothGattCharacteristic>()
    private val assemblers = HashMap<String, WhoopExtProtocol.FrameAssembler>()
    private val ops = java.util.ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private var connected = false
    private var ready = false
    private var streaming = false
    private var seq = 0x40

    // ---------- measured values ----------
    private var packetCount = 0
    private var frameCount = 0
    private var otherFrames = 0
    private var hr: Int? = null
    private var hrSource = ""
    private var rr: List<Int> = emptyList()
    private var tempC: Double? = null
    private var tempRaw: Int? = null
    private var tempSrc = ""
    private var spo2: Int? = null
    private var ax: Double? = null
    private var ay: Double? = null
    private var az: Double? = null
    private var battery: Double? = null
    private var lastBatch: Long? = null
    private var lastEventText = ""
    private var lastLogText = ""
    private var seenKinds = HashSet<String>()
    private val timeMap = WhoopExtProtocol.TimeMap()

    // ---------- per-type counters and band-time ranges (this connection) ----------
    private class Counts {
        var d = 0L      // 0x2F sensor records
        var log = 0L    // 0x32 text log records
        var other = 0L
        fun add(o: Counts) { d += o.d; log += o.log; other += o.other }
    }

    private class Range {
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        fun add(v: Long) { if (v < min) min = v; if (v > max) max = v }
        fun has(): Boolean = min <= max
        fun reset() { min = Long.MAX_VALUE; max = Long.MIN_VALUE }
    }

    private var cur = Counts()          // batch being received
    private var endCounts = Counts()    // batch that just ended and waits for its ACK
    private val tot = Counts()          // acknowledged in this connection
    private val dataUnix = Range()
    private val dataUp = Range()
    private val logUnix = Range()
    private val logUp = Range()
    private val lcSeen = HashSet<Long>()
    private var lcCount = 0
    private var liveD = 0L
    private var liveLog = 0L

    // ---------- command feedback ----------
    private class PendingCmd(val label: String, val cmdId: Int, val seq: Int)
    private val pendingCmds = HashMap<Int, PendingCmd>()

    // ---------- memory sync state ----------
    private var syncActive = false
    private var syncState = S_IDLE
    private var syncStartedStream = false
    private var batchesSession = 0
    private var nextRequests = 0
    private var ackEcho = false
    private var replays = 0
    private var pendingEnd: WhoopExtProtocol.BatchEnd? = null
    private var ackGateTrim = -1L
    private var lastAckedTrim = -1L
    private var syncSummary = ""

    // ---------- backlog skip + preview (no ACK) ----------
    private var skipState = K_IDLE
    private var skipTrim = -1L
    private var skipAnchor = -1L
    private var pvActive = false
    private var pvAfterSkip = false
    private var pvD = 0L
    private var pvLog = 0L
    private var pvOther = 0L
    private var pvOld = 0L
    private var pvGotAny = false
    private val pvData = Range()
    private val pvLogR = Range()
    private val pvFirst = ArrayList<String>()
    private val pvWatch = Runnable { previewTimeout() }

    // ---------- files ----------
    private var csv: FileLog? = null
    private var csvFile: File? = null
    private var jsonl: FileLog? = null
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private var savedRaw: Uri? = null
    private var savedJsonl: Uri? = null
    private var savedDaily: Uri? = null
    private var archiveText = ""
    private val sp by lazy { getSharedPreferences("forma_whoop_ext", Context.MODE_PRIVATE) }

    // ---------- views ----------
    private lateinit var statusView: TextView
    private lateinit var ownWarnView: TextView
    private lateinit var servicesView: TextView
    private lateinit var valuesView: TextView
    private lateinit var cmdStatusView: TextView
    private lateinit var syncView: TextView
    private lateinit var archiveView: TextView
    private lateinit var logView: TextView
    private lateinit var saveInfoView: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnHello: Button
    private lateinit var btnHistory: Button
    private lateinit var btnSync: Button
    private lateinit var btnSyncStop: Button
    private lateinit var btnSkip: Button
    private lateinit var btnCheck: Button
    private lateinit var skipView: TextView
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var btnArchiveRebuild: Button
    private lateinit var btnArchiveSave: Button
    private lateinit var btnShareDaily: Button
    private lateinit var btnShareJsonl: Button
    private val logLines = ArrayDeque<String>()
    private var refreshPending = false

    private var pendingAfterPermission: (() -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val act = pendingAfterPermission
        pendingAfterPermission = null
        if (neededPermissions().all { hasPerm(it) }) {
            act?.invoke()
        } else {
            setStatus("Brak uprawnień Bluetooth. Zezwól w ustawieniach telefonu (Aplikacje > Forma > Uprawnienia).", BAD)
        }
    }

    // =====================================================================================================
    // lifecycle
    // =====================================================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostyka WHOOP – tryb rozszerzony"
        setContentView(buildUi())
        refreshOwnWarning()
        updateButtons()
        renderValues()
        log(if (WhoopExtProtocol.selfTest()) "Test sumy kontrolnej: OK" else "Test sumy kontrolnej: BŁĄD (nie wysyłaj poleceń)")
        archiveRefresh(false)
    }

    override fun onResume() {
        super.onResume()
        refreshOwnWarning()
    }

    override fun onDestroy() {
        destroyed = true
        stopScanInternal()
        main.removeCallbacksAndMessages(null)
        val g = gatt
        gatt = null
        if (g != null) {
            // explicit stop-stream first (best effort), then close shortly after so the write can leave the radio
            val cc = cmdChar
            if (streaming && ready && cc != null) {
                try {
                    val b = WhoopExtProtocol.buildCommand(WhoopExtProtocol.SafeCommand.STOP_REALTIME, nextSeq())
                    if (b != null) writeRaw(g, cc, b)
                } catch (t: Throwable) { /* ignore */ }
                Handler(Looper.getMainLooper()).postDelayed({ closeQuietly(g) }, 600L)
            } else {
                closeQuietly(g)
            }
        }
        val c = csv
        csv = null
        if (c != null) c.closeLater()
        val j = jsonl
        jsonl = null
        if (j != null) j.closeLater()
        io.shutdown()
        super.onDestroy()
    }

    private fun closeQuietly(g: BluetoothGatt) {
        try { g.disconnect() } catch (t: Throwable) { /* ignore */ }
        try { g.close() } catch (t: Throwable) { /* ignore */ }
    }

    // =====================================================================================================
    // UI
    // =====================================================================================================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        if (mono) t.typeface = Typeface.MONOSPACE
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { try { onClick() } catch (t: Throwable) { log("Błąd: " + t.javaClass.simpleName) } }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    private fun card(vararg children: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundColor(CARD)
        l.setPadding(dp(12), dp(8), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        l.layoutParams = lp
        for (c in children) l.addView(c)
        return l
    }

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(40), dp(16), dp(32))
        col.setBackgroundColor(BG)

        col.addView(text("Diagnostyka WHOOP – tryb rozszerzony", 20f, TXT, bold = true))
        col.addView(
            card(
                text("Tryb testowy.", 15f, WARN, bold = true),
                text(
                    "Ten ekran sprawdza, co opaska WHOOP 4.0 udostępnia przez swoją własną usługę Bluetooth (dane czujników z pamięci, " +
                        "dziennik opaski, tętno na żywo). Odczyty i podgląd niczego nie zmieniają w opasce.",
                    13f, TXT
                ),
                text(
                    "Wyjątek: „Synchronizuj pamięć opaski”. Forma najpierw zapisuje każdą paczkę danych w plikach na telefonie " +
                        "(i sprawdza zapis), dopiero potem potwierdza jej odbiór, a opaska zwalnia to miejsce. Potwierdzonych danych nie da się " +
                        "już pobrać z opaski drugi raz, więc zachowaj pliki z archiwum.",
                    13f, WARN
                ),
                text(
                    "Zanim zaczniesz: zatrzymaj pomiar nocny w Formie i zamknij aplikację WHOOP. Pomiar na żywo Forma odłączy sama.",
                    13f, TXT
                )
            )
        )
        ownWarnView = text("", 13f, BAD, bold = true)
        col.addView(ownWarnView)

        statusView = text("Naciśnij „Połącz”.", 14f, TXT, bold = true)
        btnConnect = button("Połącz") { onConnectClicked(false) }
        btnScan = button("Szukaj opaski (skan)") { onConnectClicked(true) }
        btnDisconnect = button("Rozłącz") { userDisconnect() }
        col.addView(card(statusView, btnConnect, btnScan, btnDisconnect))

        servicesView = text("Usługi opaski: (brak połączenia)", 11f, DIM, mono = true)
        col.addView(card(text("Usługi i charakterystyki", 14f, TXT, bold = true), servicesView))

        valuesView = text("", 13f, TXT, mono = true)
        cmdStatusView = text("Polecenia: brak.", 13f, DIM)
        btnStart = button("Start odczytu na żywo") { sendSafe(WhoopExtProtocol.SafeCommand.START_REALTIME) }
        btnStop = button("Stop") { sendSafe(WhoopExtProtocol.SafeCommand.STOP_REALTIME) }
        btnHello = button("Informacje o opasce (HELLO)") { sendSafe(WhoopExtProtocol.SafeCommand.GET_HELLO) }
        btnHistory = button("Podgląd pamięci (bez potwierdzania)") { confirmPeek() }
        val liveCard = card(text("Odczyty", 14f, TXT, bold = true), valuesView, cmdStatusView, btnStart, btnStop, btnHello)
        if (WhoopExtProtocol.HISTORY_PULL_ENABLED) {
            liveCard.addView(btnHistory)
            liveCard.addView(text("Podgląd: jedna prośba o dane z pamięci, bez potwierdzenia. Nic nie jest zwalniane w opasce.", 12f, DIM))
        } else {
            btnHistory.visibility = View.GONE
        }
        liveCard.addView(
            text(
                "Bateria: opaska nie odpowiada na zapytanie o baterię (polecenie 0x1A, 9 prób), więc przycisk jest ukryty. " +
                    "Jej układ pomiaru baterii zgłasza błędy (LC709203), a procent z usługi standardowej bywa nieprawdziwy. " +
                    "Zamiast tego pokazujemy wartości przypuszczalne ze zdarzeń i z HELLO, jeśli opaska je przyśle.",
                12f, DIM
            )
        )
        col.addView(liveCard)

        syncView = text("", 13f, TXT, mono = true)
        btnSync = button("Synchronizuj pamięć opaski") { confirmSync() }
        btnSyncStop = button("Stop synchronizacji") { stopSync() }
        if (WhoopExtProtocol.HISTORY_PULL_ENABLED) {
            col.addView(card(text("Synchronizacja pamięci opaski", 14f, TXT, bold = true), syncView, btnSync, btnSyncStop))
        } else {
            btnSync.visibility = View.GONE
            btnSyncStop.visibility = View.GONE
        }

        skipView = text("", 12f, TXT, mono = true)
        btnSkip = button("Przeskocz zaległość (usuń stary dziennik)") { confirmSkip() }
        btnCheck = button("Sprawdź nowe dane") { startPreview(false) }
        if (WhoopExtProtocol.HISTORY_PULL_ENABLED) {
            col.addView(
                card(
                    text("Zaległość w pamięci opaski (eksperyment)", 14f, TXT, bold = true),
                    text(
                        "Opaska trzyma ponad 37 tys. starych wpisów dziennika technicznego z 2021 r. (błędy układu baterii), które blokują " +
                            "dostęp do świeżych danych. Przeskok próbuje je usunąć bez pobierania. Działanie nie jest opisane w żadnym " +
                            "znanym źródle, więc może się nie udać. Nie da się go cofnąć.",
                        12f, DIM
                    ),
                    skipView, btnSkip, btnCheck,
                    text("Sprawdź nowe dane: jedna prośba o dane bez potwierdzania; nic nie jest usuwane. Użyj po nocy noszenia opaski.", 12f, DIM)
                )
            )
        } else {
            btnSkip.visibility = View.GONE
            btnCheck.visibility = View.GONE
        }

        archiveView = text("", 12f, TXT, mono = true)
        btnArchiveRebuild = button("Przelicz archiwum dzienne") { archiveRefresh(false) }
        btnArchiveSave = button("Zapisz archiwum w Pobranych/Forma") { archiveRefresh(true) }
        btnShareDaily = button("Udostępnij: dzienne (whoop_archive_daily.csv)") { shareUri(savedDaily, "text/csv") }
        btnShareJsonl = button("Udostępnij: rekordy (whoop_archive_records.jsonl)") { shareUri(savedJsonl, "application/json") }
        col.addView(
            card(
                text("Archiwum danych z opaski", 14f, TXT, bold = true), archiveView,
                btnArchiveRebuild, btnArchiveSave, btnShareDaily, btnShareJsonl
            )
        )

        saveInfoView = text("", 12f, DIM)
        btnSave = button("Zapisz surowy plik w Pobranych") { saveRawToDownloads() }
        btnShare = button("Udostępnij surowy plik (CSV)") { shareUri(savedRaw, "text/csv") }
        col.addView(card(text("Surowe pakiety tej sesji (CSV)", 14f, TXT, bold = true), saveInfoView, btnSave, btnShare))

        logView = text("", 11f, DIM, mono = true)
        col.addView(card(text("Dziennik", 14f, TXT, bold = true), logView))

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(col)
        return sv
    }

    private fun setStatus(s: String, color: Int = TXT) {
        if (destroyed) return
        statusView.text = s
        statusView.setTextColor(color)
        log(s)
    }

    private fun waiting(cmdId: Int): Boolean {
        for (p in pendingCmds.values) if (p.cmdId == cmdId) return true
        return false
    }

    private fun updateButtons() {
        if (destroyed) return
        val syncing = syncActive
        btnConnect.isEnabled = !connected && scanner == null
        btnScan.isEnabled = !connected && scanner == null
        btnDisconnect.isEnabled = connected || scanner != null
        btnStart.isEnabled = ready && !streaming && !syncing && !waiting(0x03)
        btnStop.isEnabled = ready && streaming && !syncing && !waiting(0x03)
        btnHello.isEnabled = ready && !syncing && !waiting(WhoopExtProtocol.SafeCommand.GET_HELLO.id)
        val idleSkip = skipState == K_IDLE && !pvActive
        val noHist = !waiting(WhoopExtProtocol.SafeCommand.REQUEST_HISTORY.id)
        btnHistory.isEnabled = ready && !syncing && idleSkip && noHist
        btnSync.isEnabled = ready && !syncing && idleSkip && noHist
        btnSkip.isEnabled = ready && !syncing && idleSkip && noHist
        btnCheck.isEnabled = ready && !syncing && idleSkip && noHist
        btnSyncStop.isEnabled = syncing
        btnSave.isEnabled = csvFile != null
        btnShare.isEnabled = savedRaw != null
        btnShareDaily.isEnabled = savedDaily != null
        btnShareJsonl.isEnabled = savedJsonl != null
        btnArchiveSave.isEnabled = !syncing
        btnArchiveRebuild.isEnabled = !syncing
    }

    private fun refreshOwnWarning() {
        if (destroyed) return
        val parts = ArrayList<String>()
        try {
            val st = BleManager.state
            if (st == "connected" || st == "connecting" || st == "reconnecting") parts.add("Forma jest teraz połączona z opaską (tętno)")
        } catch (t: Throwable) { /* ignore */ }
        try {
            if (Prefs.nightRunning(this)) parts.add("trwa pomiar nocny")
        } catch (t: Throwable) { /* ignore */ }
        if (parts.isEmpty()) {
            ownWarnView.visibility = View.GONE
        } else {
            ownWarnView.visibility = View.VISIBLE
            ownWarnView.text = "Uwaga: " + parts.joinToString("; ") +
                ". Zatrzymaj to najpierw w Formie, inaczej opaska może odmówić drugiego połączenia. Tego ekranu to nie zatrzyma – nic nie ruszamy."
        }
    }

    private fun scheduleRefresh() {
        if (refreshPending || destroyed) return
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            if (!destroyed) renderValues()
        }, 250L)
    }

    private fun f1(v: Double?, unit: String): String = if (v == null) "?" else String.format(Locale.US, "%.1f", v) + unit
    private fun f2(v: Double?): String = if (v == null) "?" else String.format(Locale.US, "%.2f", v)

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun fmtMs(ms: Long): String = tsFmt.format(Date(ms))

    /** One band timestamp as text: Unix seconds are shown as a date, uptime seconds are mapped through the live-packet offset when possible. */
    private fun fmtTs(raw: Long): String {
        if (raw >= WhoopExtProtocol.UNIX_MIN) return fmtMs(raw * 1000L)
        val m = timeMap.toPhoneMs(raw)
        return if (m != null) fmtMs(m) + " (przybliżony)" else "$raw s od startu opaski"
    }

    private fun rangeText(u: Range, up: Range): String {
        val parts = ArrayList<String>()
        if (u.has()) parts.add("zegar opaski: " + fmtTs(u.min) + " – " + fmtTs(u.max))
        if (up.has()) parts.add("od startu opaski: " + fmtTs(up.min) + " – " + fmtTs(up.max))
        return if (parts.isEmpty()) "-" else parts.joinToString("; ")
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))

    private fun stateText(): String = when (syncState) {
        S_WAIT_RESP -> "czekam na odpowiedź opaski"
        S_RECEIVING -> "odbieram paczkę"
        S_WAIT_FLUSH -> "zapisuję paczkę do pliku (przed potwierdzeniem)"
        S_WAIT_ACK -> "potwierdzam paczkę"
        S_WAIT_NEXT -> "czekam na następną paczkę"
        else -> if (syncActive) "start" else "nieaktywna"
    }

    private fun renderValues() {
        val sb = StringBuilder()
        sb.append("Tętno:        ").append(if (hr == null) "?" else hr.toString() + " /min" + hrSource).append('\n')
        sb.append("RR (ms):      ").append(if (rr.isEmpty()) "?" else rr.joinToString(" ")).append('\n')
        sb.append("Temp. skóry:  ").append(f1(tempC, " °C"))
        if (tempC != null && tempSrc.isNotEmpty()) sb.append("  ").append(tempSrc)
        if (tempRaw != null) sb.append("  (surowo ").append(tempRaw).append(")")
        sb.append('\n')
        sb.append("SpO2:         ").append(if (spo2 == null) "?" else "$spo2 %").append(if (spo2 != null) "  (empirycznie)" else "").append('\n')
        sb.append("Ruch x/y/z:   ").append(f2(ax)).append(" / ").append(f2(ay)).append(" / ").append(f2(az)).append(if (ax != null) " g  (empirycznie)" else "").append('\n')
        sb.append("Bateria:      ").append(if (battery == null) "?" else f1(battery, " %") + "  (przypuszczalnie)").append('\n')
        sb.append("Paczka sync:  ").append(lastBatch?.toString() ?: "?").append('\n')
        if (lastEventText.isNotEmpty()) sb.append("Zdarzenie:    ").append(lastEventText).append('\n')
        sb.append('\n')
        sb.append("Pakiety (powiadomienia): ").append(packetCount).append('\n')
        sb.append("Ramki poprawne:          ").append(frameCount).append('\n')
        sb.append("Dane czujników 0x2F:     ").append(liveD).append('\n')
        sb.append("Dziennik opaski 0x32:    ").append(liveLog).append('\n')
        sb.append("Inne ramki:              ").append(otherFrames)
        var bad = 0
        var dropped = 0L
        for (a in assemblers.values) {
            bad += a.badCrcFrames
            dropped += a.droppedBytes
        }
        if (bad > 0 || dropped > 0) sb.append('\n').append("Błędne CRC: ").append(bad).append(", pominięte bajty: ").append(dropped)
        if (lcCount > 0) {
            sb.append('\n').append("Błąd układu pomiaru baterii w opasce (LC709203): ").append(lcCount).append(" wpisów")
        }
        if (lastLogText.isNotEmpty()) sb.append('\n').append("Ostatni wpis dziennika: ").append(lastLogText)
        valuesView.text = sb.toString()

        val sy = StringBuilder()
        sy.append("Stan: ").append(stateText()).append('\n')
        sy.append("Paczki potwierdzone: ").append(batchesSession).append(" (łącznie ").append(sp.getLong("t_batches", 0L)).append(")\n")
        sy.append("Rekordy (potwierdzone, ta sesja): 0x2F ").append(tot.d).append(", 0x32 ").append(tot.log).append(", inne ").append(tot.other).append('\n')
        sy.append("Rekordy (łącznie): 0x2F ").append(sp.getLong("t_d", 0L)).append(", 0x32 ").append(sp.getLong("t_log", 0L)).append(", inne ").append(sp.getLong("t_other", 0L)).append('\n')
        sy.append("W bieżącej paczce: 0x2F ").append(cur.d).append(", 0x32 ").append(cur.log).append(", inne ").append(cur.other).append('\n')
        sy.append("Dane 0x2F, czas: ").append(rangeText(dataUnix, dataUp)).append('\n')
        sy.append("Dziennik 0x32, czas: ").append(rangeText(logUnix, logUp)).append('\n')
        val f = csvFile
        val j = File(filesDir, "whoop_archive_records.jsonl")
        sy.append("Zapisano: surowy plik ").append(if (f == null) "-" else mb(f.length())).append(", rekordy ").append(if (j.exists()) mb(j.length()) else "-")
        if (syncSummary.isNotEmpty()) sy.append('\n').append(syncSummary)
        syncView.text = sy.toString()

        archiveView.text = archiveText.ifEmpty { "Archiwum jeszcze nie przeliczone." }
        logView.text = logLines.joinToString("\n")
        saveInfoView.text = if (f == null) "Plik powstanie po połączeniu." else "Plik: " + f.name + " (" + (f.length() / 1024) + " kB)" +
            (if (savedRaw != null) "\nZapisano w Pobranych/Forma." else "")
    }

    private fun log(msg: String) {
        if (destroyed) return
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
        logLines.addLast(line)
        while (logLines.size > LOG_LINES) logLines.removeFirst()
        csvAppend("INFO", "-", msg)
        scheduleRefresh()
    }

    // =====================================================================================================
    // permissions, scan, connect
    // =====================================================================================================
    private fun hasPerm(p: String): Boolean = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissions(then: () -> Unit) {
        val missing = neededPermissions().filter { !hasPerm(it) }
        if (missing.isEmpty()) {
            then()
        } else {
            pendingAfterPermission = then
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun onConnectClicked(forceScan: Boolean) {
        ensurePermissions { startConnectFlow(forceScan) }
    }

    private fun startConnectFlow(forceScan: Boolean) {
        refreshOwnWarning()
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            setStatus("Bluetooth jest wyłączony. Włącz go i spróbuj ponownie.", BAD)
            return
        }
        if (try { Prefs.nightRunning(this) } catch (t: Throwable) { false }) {
            setStatus("Trwa pomiar nocny. Zatrzymaj go w Formie i spróbuj ponownie.", BAD)
            return
        }
        // Forma's own connection shares the same Bluetooth link inside this app; its watchdog/reconnect
        // closes the link and the test gets status 22. Release it first, then connect after a short pause.
        val ownBusy = try {
            val st = BleManager.state
            st == "connected" || st == "connecting" || st == "reconnecting"
        } catch (t: Throwable) { false }
        if (ownBusy && !releasedOwn) {
            releasedOwn = true
            try { LiveService.stop() } catch (t: Throwable) { /* ignore */ }
            try { BleManager.disconnect() } catch (t: Throwable) { /* ignore */ }
            setStatus("Odłączam Formę od opaski, za chwilę łączę…")
            main.postDelayed({ if (!destroyed) startConnectFlow(forceScan) }, 3000)
            return
        }
        resetSession()
        var remembered: String? = null
        if (!forceScan) {
            val id = try { Prefs.preferredId(this) } catch (t: Throwable) { null }
            val name = try { Prefs.preferredName(this) } catch (t: Throwable) { null }
            if (id != null && (name == null || name.startsWith(NAME_PREFIX, true))) remembered = id
        }
        if (remembered != null) {
            setStatus("Łączę z zapamiętaną opaską…")
            connectTo(remembered)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        val ad = adapter()
        val sc = ad?.bluetoothLeScanner
        if (sc == null) {
            setStatus("Skaner Bluetooth jest niedostępny.", BAD)
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { handleScanResult(result) }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    stopScanInternal()
                    setStatus("Skanowanie nie powiodło się (kod $errorCode).", BAD)
                    updateButtons()
                }
            }
        }
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            scanner = sc
            scanCb = cb
            setStatus("Szukam opaski WHOOP (do 20 s)… Opaska musi mieć włączone nadawanie tętna i nie być połączona z innym urządzeniem.")
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (t: Throwable) {
            setStatus("Nie udało się rozpocząć skanowania: " + t.javaClass.simpleName, BAD)
        }
        updateButtons()
    }

    private val scanTimeout = Runnable {
        if (scanner != null) {
            stopScanInternal()
            setStatus("Nie znaleziono opaski WHOOP. Sprawdź, czy jest blisko, ma włączone nadawanie tętna i nie jest połączona z innym urządzeniem.", WARN)
            updateButtons()
        }
    }

    private fun handleScanResult(r: ScanResult) {
        if (scanner == null || destroyed) return
        val dev = r.device ?: return
        val name = r.scanRecord?.deviceName ?: dev.name ?: ""
        if (!name.startsWith(NAME_PREFIX, true)) return
        stopScanInternal()
        setStatus("Znaleziono: $name. Łączę…")
        connectTo(dev.address)
    }

    private fun stopScanInternal() {
        main.removeCallbacks(scanTimeout)
        try {
            val cb = scanCb
            if (cb != null) scanner?.stopScan(cb)
        } catch (t: Throwable) { /* adapter off etc. */ }
        scanner = null
        scanCb = null
    }

    private fun connectTo(address: String) {
        val ad = adapter()
        val dev: BluetoothDevice? = try {
            if (ad != null && BluetoothAdapter.checkBluetoothAddress(address)) ad.getRemoteDevice(address) else null
        } catch (t: Throwable) {
            null
        }
        if (dev == null) {
            setStatus("Nieprawidłowy adres opaski. Użyj „Szukaj opaski (skan)”.", BAD)
            updateButtons()
            return
        }
        try {
            gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            gatt = null
            setStatus("Nie udało się rozpocząć połączenia: " + t.javaClass.simpleName, BAD)
            updateButtons()
            return
        }
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        updateButtons()
    }

    private val connectTimeout = Runnable {
        if (!connected && gatt != null) {
            closeSession()
            setStatus("Opaska nie odpowiada (25 s). Zamknij aplikację WHOOP, zatrzymaj pomiar w Formie i spróbuj ponownie.", BAD)
        }
    }

    private fun userDisconnect() {
        stopScanInternal()
        if (syncActive) finishSync("Synchronizację przerwano rozłączeniem. Potwierdzone paczki: $batchesSession. Reszta zostaje w opasce.", true)
        val g = gatt
        if (g != null && streaming && ready) {
            // explicit stop-stream before leaving, so the band does not keep streaming into nothing
            sendSafe(WhoopExtProtocol.SafeCommand.STOP_REALTIME)
            main.postDelayed({ closeSession(); setStatus("Rozłączono.") }, 500L)
        } else {
            closeSession()
            setStatus("Rozłączono.")
        }
        updateButtons()
    }

    /** Fresh counters and a new CSV file for a new connection attempt. */
    private fun resetSession() {
        closeSession()
        packetCount = 0
        frameCount = 0
        otherFrames = 0
        hr = null
        hrSource = ""
        rr = emptyList()
        tempC = null
        tempRaw = null
        tempSrc = ""
        spo2 = null
        ax = null
        ay = null
        az = null
        battery = null
        lastBatch = null
        lastEventText = ""
        lastLogText = ""
        seenKinds = HashSet()
        assemblers.clear()
        cur = Counts()
        endCounts = Counts()
        tot.d = 0L; tot.log = 0L; tot.other = 0L
        dataUnix.reset(); dataUp.reset(); logUnix.reset(); logUp.reset()
        lcSeen.clear()
        lcCount = 0
        liveD = 0L
        liveLog = 0L
        batchesSession = 0
        syncSummary = ""
        lastAckedTrim = sp.getLong("last_trim", -1L)
        savedRaw = null
        openCsv()
        updateButtons()
    }

    private fun closeSession() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(opTimeoutRun)
        if (syncActive) {
            // link lost or user left: nothing more is sent; an unacknowledged batch simply stays on the band
            main.removeCallbacks(syncWatch)
            syncActive = false
            syncState = S_IDLE
            pendingEnd = null
            ackGateTrim = -1L
            syncSummary = "Połączenie przerwane: synchronizacja zatrzymana. Potwierdzone paczki: $batchesSession. Reszta zostaje w opasce."
            log(syncSummary)
        }
        if (skipState != K_IDLE || pvActive) {
            main.removeCallbacks(pvWatch)
            skipState = K_IDLE
            pvActive = false
            setSkipText("Połączenie przerwane podczas przeskoku lub podglądu. Nic więcej nie wysłano.", BAD)
        }
        pendingCmds.clear()
        ops.clear()
        opBusy = false
        val g = gatt
        gatt = null
        if (g != null) closeQuietly(g)
        connected = false
        ready = false
        streaming = false
        cmdChar = null
        notifyChars.clear()
        updateButtons()
    }

    // =====================================================================================================
    // GATT callbacks (each one hops to the main thread first)
    // =====================================================================================================
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (destroyed || g !== gatt) {
                    closeQuietly(g)
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                    main.removeCallbacks(connectTimeout)
                    connected = true
                    setStatus("Połączono. Odczytuję usługi opaski…")
                    updateButtons()
                    val started = try { g.discoverServices() } catch (t: Throwable) { false }
                    if (!started) {
                        closeSession()
                        setStatus("Nie udało się odczytać usług opaski.", BAD)
                    }
                } else {
                    val wasConnected = connected
                    closeSession()
                    setStatus(
                        if (wasConnected) "Opaska rozłączyła się (kod $status)." else "Nie udało się połączyć (kod $status). Zamknij aplikację WHOOP i spróbuj ponownie.",
                        BAD
                    )
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (!destroyed && g === gatt) handleServices(g, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (destroyed || g !== gatt) return@post
                if (status != 0) log("Włączenie powiadomień nieudane (" + WhoopExtProtocol.shortId(d.characteristic.uuid) + ", kod $status)")
                opDone()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (destroyed || g !== gatt) return@post
                log("MTU: $mtu (kod $status)")
                opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (destroyed || g !== gatt) return@post
                if (status != 0) {
                    log("Zapis polecenia nieudany (kod $status). Opaska może wymagać sparowania w ustawieniach Bluetooth telefonu.")
                    if (syncActive && syncState == S_WAIT_ACK) {
                        finishSync("Zapis potwierdzenia nieudany (kod $status). Dane zostają w opasce.", false)
                    }
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val raw: ByteArray? = try { ch.value?.copyOf() } catch (t: Throwable) { null }
            if (raw == null) return
            val v: ByteArray = raw
            val id = WhoopExtProtocol.shortId(ch.uuid)
            main.post { if (!destroyed && g === gatt) onRx(id, v) }
        }
    }

    private fun handleServices(g: BluetoothGatt, status: Int) {
        if (status != 0) {
            closeSession()
            setStatus("Odczyt usług nieudany (kod $status).", BAD)
            return
        }
        val sb = StringBuilder()
        var whoopServices = 0
        cmdChar = null
        notifyChars.clear()
        try {
            for (svc in g.services ?: emptyList()) {
                val isW = WhoopExtProtocol.isWhoopUuid(svc.uuid)
                sb.append(if (isW) "* " else "  ").append(svc.uuid.toString()).append('\n')
                if (isW) whoopServices++
                for (ch in svc.characteristics) {
                    val sid = WhoopExtProtocol.shortId(ch.uuid)
                    sb.append("    ").append(if (isW) WhoopExtProtocol.charName(sid) else sid).append(' ').append(propText(ch.properties)).append('\n')
                    if (isW) {
                        if (sid == WhoopExtProtocol.CH_CMD_TO_STRAP) cmdChar = ch
                        val p = ch.properties
                        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 || (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            notifyChars.add(ch)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("(błąd odczytu listy usług)")
        }
        servicesView.text = sb.toString().ifEmpty { "(brak usług)" }
        log("Usługi opaski:\n" + sb.toString().replace(',', ';'))
        if (whoopServices == 0 || notifyChars.isEmpty()) {
            setStatus("Opaska nie udostępnia rozszerzonej usługi. Nic więcej tu nie zrobimy; możesz się rozłączyć.", WARN)
            ready = false
            updateButtons()
            return
        }
        if (cmdChar == null) log("Uwaga: brak charakterystyki poleceń (61080002). Odczyt pasywny zadziała, polecenia nie.")
        setStatus("Włączam powiadomienia rozszerzonej usługi…")
        enqueue { g.requestMtu(MTU_WANTED) }
        for (ch in ArrayList(notifyChars)) {
            enqueue { enableNotify(g, ch) }
        }
        enqueue {
            onReady()
            false
        }
    }

    private fun propText(p: Int): String {
        val l = ArrayList<String>()
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) l.add("odczyt")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) l.add("zapis")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) l.add("zapis-bez-odp")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) l.add("notify")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) l.add("indicate")
        return "[" + l.joinToString(" ") + "]"
    }

    private fun onReady() {
        if (destroyed || gatt == null) return
        ready = true
        setStatus("Gotowe. Rozszerzona usługa WHOOP jest dostępna. Wybierz odczyt poniżej.", GOOD)
        updateButtons()
    }

    // =====================================================================================================
    // GATT operation queue (main thread). Every started operation must end in opDone(); a timeout covers lost callbacks.
    // =====================================================================================================
    private fun enqueue(op: () -> Boolean) {
        ops.add(op)
        pump()
    }

    private fun pump() {
        if (opBusy || destroyed) return
        val op = ops.poll() ?: return
        opBusy = true
        main.removeCallbacks(opTimeoutRun)
        main.postDelayed(opTimeoutRun, OP_TIMEOUT_MS)
        val started = try { op() } catch (t: Throwable) { false }
        if (!started) {
            main.removeCallbacks(opTimeoutRun)
            opBusy = false
            pump()
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeoutRun)
        opBusy = false
        pump()
    }

    private val opTimeoutRun = Runnable {
        if (opBusy) {
            opBusy = false
            pump()
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val cccd = ch.getDescriptor(CCCD) ?: return false
        g.setCharacteristicNotification(ch, true)
        val p = ch.properties
        val indicate = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 && (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        cccd.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(cccd)
    }

    @Suppress("DEPRECATION")
    private fun writeRaw(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        ch.writeType =
            if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = bytes
        return g.writeCharacteristic(ch)
    }

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFF
        return seq
    }

    // =====================================================================================================
    // commands: SAFE-READ whitelist + the gated batch ACK. Every command shows "Wysłano / Odpowiedź / Brak odpowiedzi po 5 s".
    // =====================================================================================================
    private fun cmdStatus(s: String, color: Int = DIM) {
        if (destroyed) return
        cmdStatusView.text = s
        cmdStatusView.setTextColor(color)
    }

    private fun trackCmd(label: String, cmdId: Int, s: Int) {
        pendingCmds[s] = PendingCmd(label, cmdId, s)
        cmdStatus("Wysłano: $label…", WARN)
        main.postDelayed({ cmdTimeout(s) }, CMD_TIMEOUT_MS)
        updateButtons()
    }

    private fun cmdTimeout(s: Int) {
        if (destroyed) return
        val p = pendingCmds.remove(s) ?: return
        cmdStatus("Brak odpowiedzi po 5 s: " + p.label, BAD)
        log("Brak odpowiedzi po 5 s na: " + p.label)
        if (p.cmdId == WhoopExtProtocol.CMD_BATCH_ACK && syncActive && syncState == S_WAIT_ACK) syncAfterAck(false)
        if (p.cmdId == WhoopExtProtocol.SafeCommand.GET_DATA_RANGE.id && skipState == K_RANGE) {
            skipFail("Opaska nie podała zakresu – przeskok niemożliwy (brak odpowiedzi po 5 s). Nic nie zmieniono.")
        }
        if (p.cmdId == WhoopExtProtocol.CMD_BATCH_ACK && skipState == K_ACK) {
            skipFail(
                "Brak odpowiedzi opaski na przeskok po 5 s. Niczego więcej nie wysłano i nie ponawiam. " +
                    "Nie wiadomo, czy opaska to przyjęła: użyj „Sprawdź nowe dane”."
            )
        }
        updateButtons()
    }

    private fun onCmdResponse(d: WhoopExtProtocol.Decoded) {
        val echo = d.seqEcho
        var p: PendingCmd? = if (echo != null) pendingCmds.remove(echo) else null
        if (p == null) {
            val c = d.cmd
            if (c != null) {
                for (x in pendingCmds.values) {
                    if (x.cmdId == c) { p = x; break }
                }
                if (p != null) pendingCmds.remove(p.seq)
            }
        }
        val label = p?.label ?: ("polecenie 0x" + Integer.toHexString(d.cmd ?: 0))
        val st = d.status
        cmdStatus("Odpowiedź na „" + label + "”: status " + st + (if (st == 2) " (zwykle: przyjęte)" else ""), if (st == 2) GOOD else WARN)
        log("Odpowiedź: " + d.info)
        val c = d.cmd
        if (c == WhoopExtProtocol.CMD_BATCH_ACK && syncActive && syncState == S_WAIT_ACK) syncAfterAck(true)
        if (c == WhoopExtProtocol.SafeCommand.GET_DATA_RANGE.id && skipState == K_RANGE) onRangeResponse(d)
        if (c == WhoopExtProtocol.CMD_BATCH_ACK && skipState == K_ACK) onSkipAckResponse(d)
        if (c == WhoopExtProtocol.SafeCommand.REQUEST_HISTORY.id && pvActive && st != 2) {
            previewFinish("Opaska odrzuciła prośbę o dane (status $st). Nic nie zmieniono.", false)
        }
        if (c == WhoopExtProtocol.SafeCommand.REQUEST_HISTORY.id && syncActive && st != 2) {
            finishSync("Opaska odrzuciła prośbę o dane (status $st). Nic nie zmieniono.", false)
        }
        updateButtons()
    }

    private fun sendSafe(cmd: WhoopExtProtocol.SafeCommand, force: Boolean = false) {
        val g = gatt
        val ch = cmdChar
        if (g == null || ch == null || !ready) {
            log("Polecenie pominięte: brak gotowego połączenia.")
            return
        }
        if (!force && waiting(cmd.id)) return
        val s = nextSeq()
        val bytes = WhoopExtProtocol.buildCommand(cmd, s)
        if (bytes == null) {
            log("Polecenie wyłączone w tej wersji: " + cmd.label)
            return
        }
        csvAppend("TX", WhoopExtProtocol.CH_CMD_TO_STRAP, WhoopExtProtocol.hex(bytes))
        log("Wysyłam: " + cmd.label)
        trackCmd(cmd.label, cmd.id, s)
        enqueue { writeRaw(g, ch, bytes) }
        if (cmd == WhoopExtProtocol.SafeCommand.START_REALTIME) streaming = true
        if (cmd == WhoopExtProtocol.SafeCommand.STOP_REALTIME) streaming = false
        updateButtons()
    }

    private fun confirmPeek() {
        if (!ready) return
        AlertDialog.Builder(this)
            .setTitle("Podgląd pamięci opaski")
            .setMessage(
                "Opaska zostanie poproszona o wysłanie zapisanych rekordów. Nic nie jest potwierdzane ani zwalniane w opasce. " +
                    "Rekordy trafią do pliku i archiwum. Kontynuować?"
            )
            .setPositiveButton("Tak, spróbuj") { _, _ -> sendSafe(WhoopExtProtocol.SafeCommand.REQUEST_HISTORY) }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // =====================================================================================================
    // receive path
    // =====================================================================================================
    private fun onRx(charId: String, data: ByteArray) {
        packetCount++
        csvAppend("RX", charId, WhoopExtProtocol.hex(data)) // queued BEFORE decoding, so a batch-end is always preceded by its packets in the file
        try {
            val asm = assemblers.getOrPut(charId) { WhoopExtProtocol.FrameAssembler() }
            for (payload in asm.feed(data)) {
                frameCount++
                apply(WhoopExtProtocol.decode(payload), payload)
            }
        } catch (t: Throwable) {
            log("Błąd dekodowania: " + t.javaClass.simpleName)
        }
        scheduleRefresh()
    }

    private fun apply(d: WhoopExtProtocol.Decoded, payload: ByteArray) {
        if (payload.isEmpty()) return
        if (seenKinds.add(d.kind)) {
            log("Pierwszy pakiet typu: " + d.kind + " (typ 0x" + Integer.toHexString(payload[0].toInt() and 0xFF) + ", " + payload.size + " B) " + d.info)
        }
        val now = System.currentTimeMillis()
        if (pvActive) previewNote(d, payload)
        when (d.kind) {
            "żywe" -> {
                if (d.hr != null) { hr = d.hr; hrSource = " (na żywo)" }
                if (d.rr.isNotEmpty()) rr = d.rr
                val e = d.epoch
                if (e != null) timeMap.observe(e, now)
            }
            "pamięć" -> onSensorRecord(d, now)
            "dziennik" -> onLogRecord(d)
            "synchronizacja" -> onMeta(d)
            "odpowiedź" -> onCmdResponse(d)
            "zdarzenie" -> {
                otherFrames++
                cur.other++
                lastEventText = d.info
                if (d.batteryPct != null) battery = d.batteryPct
                if (d.tempC != null) { tempC = d.tempC; tempSrc = "(zdarzenie, przypuszczalnie)" }
            }
            else -> {
                otherFrames++
                cur.other++
            }
        }
    }

    private fun onSensorRecord(d: WhoopExtProtocol.Decoded, now: Long) {
        liveD++
        cur.d++
        val raw = d.epoch
        if (d.hr != null) { hr = d.hr; hrSource = " (z pamięci)" }
        if (d.rr.isNotEmpty()) rr = d.rr
        if (d.tempRaw != null) tempRaw = d.tempRaw
        if (d.tempC != null) { tempC = d.tempC; tempSrc = "(z pamięci, empirycznie)" }
        if (d.spo2 != null) spo2 = d.spo2
        if (d.ax != null && d.ay != null && d.az != null) { ax = d.ax; ay = d.ay; az = d.az }
        if (raw != null) {
            if (raw >= WhoopExtProtocol.UNIX_MIN) dataUnix.add(raw) else dataUp.add(raw)
            val clk = if (raw >= WhoopExtProtocol.UNIX_MIN) "unix" else "uptime"
            val rec = WhoopArchive.Rec(
                timeMap.toPhoneMs(raw), raw, clk, d.recCounter ?: 0L, d.hr, d.rr, d.tempC, d.tempRaw, d.spo2, d.ax, d.ay, d.az, now
            )
            val j = ensureJsonl()
            if (j != null) j.append(WhoopArchive.toJson(rec))
        }
        syncTouch()
    }

    private fun onLogRecord(d: WhoopExtProtocol.Decoded) {
        liveLog++
        cur.log++
        val raw = d.epoch
        if (raw != null) {
            if (raw >= WhoopExtProtocol.UNIX_MIN) logUnix.add(raw) else logUp.add(raw)
        }
        val txt = d.text ?: ""
        if (txt.isNotEmpty()) lastLogText = txt
        if (txt.contains("LC709203")) {
            val key = ((d.recCounter ?: 0L) shl 32) xor (raw ?: 0L)
            if (lcSeen.add(key)) lcCount++
        }
        syncTouch()
    }

    private fun onMeta(d: WhoopExtProtocol.Decoded) {
        val be = d.batchEnd
        if (be != null) lastBatch = be.trim
        when (d.metaSub) {
            1 -> {
                if (syncActive) { log("Początek paczki."); syncTouch() }
            }
            2 -> {
                if (be != null && syncActive) onBatchEnd(be)
            }
            3 -> {
                if (syncActive && (syncState == S_WAIT_RESP || syncState == S_WAIT_NEXT)) {
                    log("Opaska: komunikat 'zakończono' (może poprzedzać paczkę).")
                    armWatch(10_000L)
                }
            }
            else -> otherFrames++
        }
    }

    // =====================================================================================================
    // memory sync: 0x16 -> batch -> raw packets written + fsync -> ACK 0x17 -> repeat. Interruptible at any point.
    // The ACK is built only by sendAck(), which runs only from afterFlush() after both files were flushed and fsynced.
    // =====================================================================================================
    private val syncWatch = Runnable { onSyncTimeout() }

    private fun armWatch(ms: Long) {
        main.removeCallbacks(syncWatch)
        main.postDelayed(syncWatch, ms)
    }

    private fun confirmSync() {
        if (!ready || syncActive) return
        AlertDialog.Builder(this)
            .setTitle("Synchronizacja pamięci opaski")
            .setMessage(
                "Forma pobierze zapisane dane z pamięci opaski paczka po paczce. Każdą paczkę najpierw zapisze w plikach na telefonie " +
                    "i sprawdzi zapis, a dopiero potem potwierdzi jej odbiór. Po potwierdzeniu opaska zwalnia to miejsce i nie da się " +
                    "pobrać tych danych drugi raz. Pliki zostaną zapisane też w Pobranych/Forma. Możesz w każdej chwili nacisnąć Stop. Kontynuować?"
            )
            .setPositiveButton("Tak, synchronizuj") { _, _ -> beginSync() }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun beginSync() {
        if (!ready || syncActive) return
        syncActive = true
        syncState = S_IDLE
        batchesSession = 0
        nextRequests = 0
        replays = 0
        ackEcho = false
        cur = Counts()
        endCounts = Counts()
        pendingEnd = null
        ackGateTrim = -1L
        syncSummary = ""
        lastAckedTrim = sp.getLong("last_trim", -1L)
        syncStartedStream = false
        var delay = 0L
        if (!streaming) {
            // the live stream gives the band-clock offset needed to date the records
            syncStartedStream = true
            sendSafe(WhoopExtProtocol.SafeCommand.START_REALTIME)
            delay = 3_000L
        } else if (!timeMap.hasOffset()) {
            delay = 3_000L
        }
        setStatus("Synchronizacja pamięci opaski rozpoczęta.")
        updateButtons()
        armWatch(delay)
    }

    private fun requestBatch() {
        if (!syncActive) return
        syncState = S_WAIT_RESP
        sendSafe(WhoopExtProtocol.SafeCommand.REQUEST_HISTORY, true)
        armWatch(10_000L)
    }

    /** Called for every history record / batch start. */
    private fun syncTouch() {
        if (!syncActive) return
        if (syncState == S_WAIT_RESP || syncState == S_WAIT_NEXT) syncState = S_RECEIVING
        if (syncState == S_RECEIVING) armWatch(12_000L)
    }

    private fun onSyncTimeout() {
        if (!syncActive || destroyed) return
        when (syncState) {
            S_IDLE -> requestBatch()
            S_WAIT_RESP -> {
                if (batchesSession == 0) finishSync("Opaska nie przysłała żadnych danych z pamięci (jest pusta albo nie odpowiada).", false)
                else finishSync("Synchronizacja zakończona: opaska nie ma więcej danych. Potwierdzone paczki: $batchesSession.", true)
            }
            S_RECEIVING -> finishSync("Przerwano: opaska przestała wysyłać paczkę. Nic nie potwierdzono, dane zostają w opasce.", false)
            S_WAIT_NEXT -> {
                if (nextRequests == 0) {
                    nextRequests = 1
                    requestBatch()
                } else {
                    finishSync("Synchronizacja zakończona: opaska nie ma więcej danych. Potwierdzone paczki: $batchesSession.", true)
                }
            }
            else -> { /* flush / ack have their own completion paths */ }
        }
    }

    private fun onBatchEnd(be: WhoopExtProtocol.BatchEnd) {
        if (syncState == S_WAIT_FLUSH || syncState == S_WAIT_ACK) return
        if (be.trim == lastAckedTrim) {
            replays++
            if (replays >= 2) {
                finishSync("Opaska ponownie wysłała tę samą paczkę mimo potwierdzenia. Zatrzymano; dane są w pliku i zostają w opasce.", false)
                return
            }
            ackEcho = true
            log("Opaska powtórzyła potwierdzoną paczkę " + be.trim + ". Próbuję potwierdzenia z ogonem opaski.")
        } else {
            replays = 0
        }
        if (batchesSession >= MAX_BATCHES) {
            finishSync("Osiągnięto limit $MAX_BATCHES paczek w jednej sesji. Uruchom synchronizację ponownie.", true)
            return
        }
        syncState = S_WAIT_FLUSH
        endCounts = cur
        cur = Counts()
        pendingEnd = be
        main.removeCallbacks(syncWatch)
        log("Koniec paczki " + be.trim + ": zapisuję i sprawdzam plik przed potwierdzeniem…")
        flushThenAck(be)
    }

    private fun flushThenAck(be: WhoopExtProtocol.BatchEnd) {
        if (filesDir.usableSpace < MIN_FREE_BYTES) {
            finishSync("Za mało miejsca na telefonie. Nic nie potwierdzono, dane zostają w opasce.", false)
            return
        }
        val rawLog = csv
        if (rawLog == null) {
            finishSync("Brak pliku na surowe pakiety. Nic nie potwierdzono.", false)
            return
        }
        val rec = jsonl
        rawLog.syncDisk { ok1 ->
            if (rec == null) afterFlush(ok1, be) else rec.syncDisk { ok2 -> afterFlush(ok1 && ok2, be) }
        }
    }

    private fun afterFlush(ok: Boolean, be: WhoopExtProtocol.BatchEnd) {
        if (!syncActive || syncState != S_WAIT_FLUSH || pendingEnd !== be) return
        if (!ok) {
            finishSync("Zapis pliku nie powiódł się lub plik jest zbyt duży. Nic nie potwierdzono, dane zostają w opasce.", false)
            return
        }
        ackGateTrim = be.trim
        sendAck(be)
    }

    private fun sendAck(be: WhoopExtProtocol.BatchEnd) {
        val g = gatt
        val ch = cmdChar
        if (!syncActive || syncState != S_WAIT_FLUSH || ackGateTrim != be.trim || g == null || ch == null) return
        ackGateTrim = -1L
        val s = nextSeq()
        val bytes = WhoopExtProtocol.buildBatchAck(be, s, ackEcho)
        csvAppend("TX", WhoopExtProtocol.CH_CMD_TO_STRAP, WhoopExtProtocol.hex(bytes))
        log("Potwierdzam paczkę " + be.trim + (if (ackEcho) " (wariant z ogonem opaski)" else "") + ".")
        syncState = S_WAIT_ACK
        trackCmd("potwierdzenie paczki " + be.trim, WhoopExtProtocol.CMD_BATCH_ACK, s)
        enqueue { writeRaw(g, ch, bytes) }
        lastAckedTrim = be.trim
        batchesSession++
        tot.add(endCounts)
        sp.edit()
            .putLong("last_trim", be.trim)
            .putLong("t_batches", sp.getLong("t_batches", 0L) + 1L)
            .putLong("t_d", sp.getLong("t_d", 0L) + endCounts.d)
            .putLong("t_log", sp.getLong("t_log", 0L) + endCounts.log)
            .putLong("t_other", sp.getLong("t_other", 0L) + endCounts.other)
            .apply()
        updateButtons()
    }

    private fun syncAfterAck(confirmed: Boolean) {
        if (!syncActive || syncState != S_WAIT_ACK) return
        log(if (confirmed) "Opaska przyjęła potwierdzenie." else "Brak odpowiedzi na potwierdzenie. Sprawdzę, czy opaska wyśle kolejną paczkę.")
        syncState = S_WAIT_NEXT
        nextRequests = 0
        armWatch(8_000L)
    }

    private fun stopSync() {
        if (!syncActive) return
        finishSync("Synchronizację zatrzymano. Potwierdzone paczki: $batchesSession. Reszta zostaje w opasce i można wznowić.", true)
    }

    private fun finishSync(msg: String, ok: Boolean) {
        main.removeCallbacks(syncWatch)
        val was = syncActive
        syncActive = false
        syncState = S_IDLE
        pendingEnd = null
        ackGateTrim = -1L
        if (!was) return
        syncSummary = msg
        if (syncStartedStream) {
            syncStartedStream = false
            if (streaming) sendSafe(WhoopExtProtocol.SafeCommand.STOP_REALTIME)
        }
        setStatus(msg, if (ok) GOOD else BAD)
        updateButtons()
        archiveRefresh(true)
    }

    // =====================================================================================================
    // backlog skip: GET_DATA_RANGE (0x22, read-only) -> check the numbers against our own last ACK -> ONE batch ACK (0x17) whose trim is the
    // band's newest record index -> wait for its 0x24 status -> ONE 0x16 preview WITHOUT ACK. No retries, no guessed values, never 0x19.
    // =====================================================================================================
    private fun setSkipText(msg: String, color: Int = TXT) {
        if (destroyed) return
        skipView.text = msg
        skipView.setTextColor(color)
        log(msg)
    }

    private fun confirmSkip() {
        if (!ready || syncActive || skipState != K_IDLE || pvActive) return
        AlertDialog.Builder(this)
            .setTitle("Przeskoczyć zaległość?")
            .setMessage(
                "Forma poprosi opaskę o zakres zapisanych danych, a potem potwierdzi odbiór wszystkiego, co w niej leży, BEZ pobierania. " +
                    "To jest to samo potwierdzenie, które normalnie zwalnia miejsce po pobraniu paczki.\n\n" +
                    "Stare wpisy (dziennik techniczny z 2021 r.) zostaną usunięte z opaski na stałe. Nie da się ich potem pobrać ani cofnąć. " +
                    "To eksperyment: nie mamy pewności, że opaska zareaguje tak, jak oczekujemy."
            )
            .setPositiveButton("Dalej") { _, _ -> confirmSkipSecond() }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun confirmSkipSecond() {
        AlertDialog.Builder(this)
            .setTitle("Ostatnie potwierdzenie")
            .setMessage(
                "Naprawdę usunąć stary dziennik z opaski na stałe, bez pobierania? Tej operacji nie można cofnąć. " +
                    "Jeśli opaska poda niezrozumiały zakres, Forma niczego nie wyśle."
            )
            .setPositiveButton("Tak, usuń na stałe") { _, _ -> beginSkip() }
            .setNegativeButton("Nie, anuluj", null)
            .show()
    }

    private fun beginSkip() {
        if (!ready || syncActive || skipState != K_IDLE || pvActive) return
        skipAnchor = sp.getLong("last_trim", -1L)
        skipTrim = -1L
        skipState = K_RANGE
        setSkipText("Pytam opaskę o zakres danych (polecenie tylko do odczytu)…", WARN)
        sendSafe(WhoopExtProtocol.SafeCommand.GET_DATA_RANGE, true)
        updateButtons()
    }

    private fun skipFail(msg: String) {
        skipState = K_IDLE
        skipTrim = -1L
        setSkipText(msg, BAD)
        updateButtons()
    }

    private fun onRangeResponse(d: WhoopExtProtocol.Decoded) {
        val raw = d.raw
        if (raw == null) {
            skipFail("Opaska nie podała zakresu – przeskok niemożliwy (pusta odpowiedź). Nic nie zmieniono.")
            return
        }
        val res = WhoopExtProtocol.parseDataRange(raw, skipAnchor)
        csvAppend("NOTE", "-", "zakres status=" + d.status + " ostatnie_potwierdzenie=" + skipAnchor + " liczby: " + res.u32s)
        val r = res.range
        if (r == null) {
            skipFail("Opaska nie podała zakresu – przeskok niemożliwy (" + res.reason + "). Odpowiedź zapisano w pliku CSV. Nic nie zmieniono.")
            return
        }
        csvAppend("NOTE", "-", "zakres rozpoznany: ostatnio_zsynchronizowane=" + r.lastSynced + " najnowszy=" + r.newest + " przesuniecie=" + r.offset)
        if (r.newest - r.lastSynced < 24L) {
            skipFail("Zakres: ostatnio potwierdzone " + r.lastSynced + ", najnowszy " + r.newest + ". Nie ma czego przeskakiwać. Nic nie wysłano.")
            return
        }
        setSkipText(
            "Zakres z opaski: ostatnio potwierdzone " + r.lastSynced + ", najnowszy " + r.newest +
                " (zaległość " + (r.newest - r.lastSynced) + "). Zapisuję plik i wysyłam jedno potwierdzenie…", WARN
        )
        val rawLog = csv
        if (rawLog == null) {
            skipFail("Brak pliku na surowe pakiety. Nic nie wysłano.")
            return
        }
        val newest = r.newest
        rawLog.syncDisk { ok ->
            if (!ok) skipFail("Zapis pliku nie powiódł się. Nic nie wysłano.") else sendSkipAck(newest)
        }
    }

    /** The only place that builds the skip ACK. Runs once, after the range check and after the CSV was flushed and fsynced. */
    private fun sendSkipAck(trim: Long) {
        val g = gatt
        val ch = cmdChar
        if (skipState != K_RANGE || g == null || ch == null || !ready) return
        val s = nextSeq()
        val bytes = WhoopExtProtocol.buildSkipAck(trim, s)
        if (bytes == null) {
            skipFail("Liczba z opaski poza dozwolonym zakresem. Nic nie wysłano.")
            return
        }
        skipTrim = trim
        skipState = K_ACK
        csvAppend("TX", WhoopExtProtocol.CH_CMD_TO_STRAP, WhoopExtProtocol.hex(bytes))
        trackCmd("potwierdzenie z przeskokiem " + trim, WhoopExtProtocol.CMD_BATCH_ACK, s)
        enqueue { writeRaw(g, ch, bytes) }
        updateButtons()
    }

    private fun onSkipAckResponse(d: WhoopExtProtocol.Decoded) {
        val st = d.status
        if (st == 1) {
            sp.edit().putLong("last_trim", skipTrim).apply()
            lastAckedTrim = skipTrim
            skipState = K_IDLE
            setSkipText("Opaska przyjęła potwierdzenie z przeskokiem (status 1). Sprawdzam, co teraz jest w pamięci (bez potwierdzania)…", GOOD)
            startPreview(true)
        } else {
            skipFail("Opaska odpowiedziała na przeskok statusem $st (oczekiwano 1). Zatrzymano; niczego więcej nie wysłano i nie ponawiam.")
        }
    }

    // ---------- preview: one 0x16 without ACK ----------
    private fun startPreview(afterSkip: Boolean) {
        if (!ready || syncActive || pvActive || (skipState != K_IDLE)) return
        pvActive = true
        pvAfterSkip = afterSkip
        pvD = 0L; pvLog = 0L; pvOther = 0L; pvOld = 0L
        pvGotAny = false
        pvData.reset(); pvLogR.reset(); pvFirst.clear()
        if (!afterSkip) setSkipText("Pytam o dane z pamięci (bez potwierdzania)…", WARN)
        sendSafe(WhoopExtProtocol.SafeCommand.REQUEST_HISTORY, true)
        main.removeCallbacks(pvWatch)
        main.postDelayed(pvWatch, 10_000L)
        updateButtons()
    }

    private fun previewNote(d: WhoopExtProtocol.Decoded, payload: ByteArray) {
        val k = d.kind
        if (k == "żywe" || k == "odpowiedź") return
        pvGotAny = true
        main.removeCallbacks(pvWatch)
        main.postDelayed(pvWatch, 8_000L)
        val t = d.epoch
        val type = "0x" + String.format(Locale.US, "%02X", payload[0].toInt() and 0xFF)
        when (k) {
            "pamięć" -> {
                pvD++
                if (t != null) { pvData.add(t); if (t in 1L until OLD_BEFORE_UNIX && t >= WhoopExtProtocol.UNIX_MIN) pvOld++ }
            }
            "dziennik" -> {
                pvLog++
                if (t != null) { pvLogR.add(t); if (t in WhoopExtProtocol.UNIX_MIN until OLD_BEFORE_UNIX) pvOld++ }
            }
            "synchronizacja" -> {
                if (d.metaSub == 2) {
                    previewFinish(null, true)
                    return
                }
            }
            else -> pvOther++
        }
        if (pvFirst.size < 6) pvFirst.add(type + " " + k + (if (t != null) " " + fmtTs(t) else ""))
    }

    private fun previewTimeout() {
        if (!pvActive) return
        previewFinish(null, true)
    }

    private fun previewFinish(msg: String?, ok: Boolean) {
        if (!pvActive) return
        pvActive = false
        main.removeCallbacks(pvWatch)
        val sb = StringBuilder()
        if (msg != null) {
            sb.append(msg)
        } else {
            sb.append("Podgląd (bez potwierdzania): dane czujników 0x2F: ").append(pvD).append(", dziennik 0x32: ").append(pvLog)
                .append(", inne: ").append(pvOther).append('\n')
            if (pvData.has()) sb.append("Czas 0x2F: ").append(fmtTs(pvData.min)).append(" – ").append(fmtTs(pvData.max)).append('\n')
            if (pvLogR.has()) sb.append("Czas 0x32: ").append(fmtTs(pvLogR.min)).append(" – ").append(fmtTs(pvLogR.max)).append('\n')
            if (pvFirst.isNotEmpty()) sb.append("Pierwsze rekordy: ").append(pvFirst.joinToString("; ")).append('\n')
            if (!pvGotAny) {
                sb.append("Opaska nie przysłała żadnych rekordów: pamięć jest pusta albo opaska czeka na nowe dane.")
            } else if (pvD > 0) {
                sb.append("Są dane czujników 0x2F: to coś więcej niż stary dziennik.")
            } else if (pvOld > 0) {
                sb.append("Nadal widać stare wpisy z 2021 r.")
                if (pvAfterSkip) sb.append(" Przeskok nie zadziałał (albo opaska odtworzyła je).")
            } else {
                sb.append("Brak danych czujników 0x2F; widać tylko wpisy dziennika.")
            }
        }
        csvAppend("NOTE", "-", "podglad 0x2F=" + pvD + " 0x32=" + pvLog + " inne=" + pvOther + " stare2021=" + pvOld)
        setSkipText(sb.toString(), if (ok) TXT else BAD)
        updateButtons()
    }

    // =====================================================================================================
    // archive: per-record JSONL (appended as records arrive) + per-day CSV (rebuilt from the JSONL, deduplicated)
    // =====================================================================================================
    private fun ensureJsonl(): FileLog? {
        var j = jsonl
        if (j == null) {
            try {
                j = FileLog(File(filesDir, "whoop_archive_records.jsonl"), null, JSONL_MAX_BYTES)
                jsonl = j
            } catch (t: Throwable) {
                log("Nie udało się otworzyć pliku archiwum rekordów.")
            }
        }
        return j
    }

    /** Streams the JSONL, groups by (date, clock), writes whoop_archive_daily.csv. Runs on the io thread. Returns the summary text. */
    private fun buildDaily(): String {
        val src = File(filesDir, "whoop_archive_records.jsonl")
        val out = File(filesDir, "whoop_archive_daily.csv")
        val groups = java.util.TreeMap<String, WhoopArchive.DayAcc>()
        var total = 0L
        var unmapped = 0L
        if (src.exists()) {
            src.bufferedReader(Charsets.UTF_8).useLines { seq ->
                for (ln in seq) {
                    val r = WhoopArchive.parseJson(ln) ?: continue
                    total++
                    val t = r.tMs
                    if (t == null) {
                        unmapped++
                        continue
                    }
                    val d = WhoopArchive.dateOf(t)
                    val key = d + "|" + r.clk
                    var acc = groups[key]
                    if (acc == null) {
                        acc = WhoopArchive.DayAcc(d, r.clk)
                        groups[key] = acc
                    }
                    acc.add(r)
                }
            }
        }
        val tmp = File(filesDir, "whoop_archive_daily.csv.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(WhoopArchive.DAILY_HEADER)
            w.write("\n")
            for (a in groups.values) {
                w.write(a.row())
                w.write("\n")
            }
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, true)
            tmp.delete()
        }
        val dates = java.util.TreeSet<String>()
        for (a in groups.values) dates.add(a.date)
        return if (total == 0L) {
            "Dni z danymi czujników (rekordy 0x2F, nie dziennik): 0. Opaska nie zwróciła jeszcze rekordów czujników."
        } else {
            "Dni z danymi czujników (rekordy 0x2F, nie dziennik): " + dates.size +
                (if (dates.isNotEmpty()) " (od " + dates.first() + " do " + dates.last() + ")" else "") +
                "\nRekordów: " + total + (if (unmapped > 0) ", bez czasu: $unmapped" else "") +
                "\nPlik dzienny: " + out.length() / 1024 + " kB, rekordy: " + src.length() / 1024 + " kB"
        }
    }

    private class ExportResult(val uri: Uri?, val where: String)

    private fun exportFile(src: File, mime: String): ExportResult {
        if (Build.VERSION.SDK_INT >= 29) {
            val u = exportViaMediaStore(src, mime)
            return ExportResult(u, if (u != null) "Pobrane/Forma/" + src.name else "błąd zapisu " + src.name)
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return ExportResult(null, "brak miejsca na plik " + src.name)
        dir.mkdirs()
        val dst = File(dir, src.name)
        src.copyTo(dst, true)
        return ExportResult(null, dst.absolutePath)
    }

    /** Rebuilds the daily CSV; with export = true also saves records, daily and the raw CSV of this session to Downloads/Forma. */
    private fun archiveRefresh(export: Boolean) {
        archiveText = "Przeliczam archiwum…"
        scheduleRefresh()
        val rawFile = csvFile
        val rawLog = csv
        val recLog = jsonl
        io.execute {
            var text = ""
            var ru: Uri? = null
            var ju: Uri? = null
            var du: Uri? = null
            try {
                rawLog?.flushNow()
                recLog?.flushNow()
                text = buildDaily()
                if (export) {
                    val places = ArrayList<String>()
                    val jf = File(filesDir, "whoop_archive_records.jsonl")
                    val df = File(filesDir, "whoop_archive_daily.csv")
                    if (jf.exists()) { val r = exportFile(jf, "application/json"); ju = r.uri; places.add(r.where) }
                    if (df.exists()) { val r = exportFile(df, "text/csv"); du = r.uri; places.add(r.where) }
                    if (rawFile != null && rawFile.exists()) { val r = exportFile(rawFile, "text/csv"); ru = r.uri; places.add(r.where) }
                    text += "\nZapisano: " + places.joinToString(", ")
                }
            } catch (t: Throwable) {
                text = "Archiwum: błąd (" + t.javaClass.simpleName + ")"
            }
            runOnUiThread {
                if (!destroyed) {
                    archiveText = text
                    if (ru != null) savedRaw = ru
                    if (ju != null) savedJsonl = ju
                    if (du != null) savedDaily = du
                    updateButtons()
                    renderValues()
                }
            }
        }
    }

    // =====================================================================================================
    // files: raw CSV of every packet and the record archive. Append on one background thread; syncDisk() = flush + fsync before an ACK.
    // =====================================================================================================
    private inner class FileLog(val file: File, header: String?, private val maxBytes: Long) {
        private val fos = FileOutputStream(file, true)
        private val w = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8))
        private var lines = 0
        private var bytes = file.length()

        /** false after any failed or dropped write (disk full, size cap): the sync then refuses to ACK. */
        @Volatile var healthy = true

        init {
            if (header != null && bytes == 0L) write(header)
        }

        private fun write(line: String) {
            try {
                if (bytes + line.length + 1 > maxBytes) {
                    healthy = false
                    return
                }
                w.write(line)
                w.write("\n")
                bytes += line.length + 1
                lines++
                if (lines % 25 == 0) w.flush()
            } catch (t: Throwable) {
                healthy = false
            }
        }

        fun append(line: String) {
            try { io.execute { write(line) } } catch (t: Throwable) { /* executor already shut down */ }
        }

        /** Flushes and fsyncs on the io thread (after every queued append), then calls back on the UI thread with the result. */
        fun syncDisk(cb: (Boolean) -> Unit) {
            try {
                io.execute {
                    var ok = healthy
                    try {
                        w.flush()
                        fos.fd.sync()
                    } catch (t: Throwable) {
                        ok = false
                    }
                    val result = ok
                    runOnUiThread { if (!destroyed) cb(result) }
                }
            } catch (t: Throwable) { /* executor already shut down: no callback, so no ACK */ }
        }

        fun flushNow() {
            try { w.flush() } catch (t: Throwable) { /* ignore */ }
        }

        fun closeLater() {
            try {
                io.execute {
                    try { w.flush() } catch (t: Throwable) { /* ignore */ }
                    try { w.close() } catch (t: Throwable) { /* ignore */ }
                }
            } catch (t: Throwable) { /* ignore */ }
        }
    }

    private fun openCsv() {
        csv?.closeLater()
        try {
            val name = "whoop_ext_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val f = File(filesDir, name)
            csvFile = f
            csv = FileLog(f, "time_iso,epoch_ms,dir,characteristic,hex_or_text", CSV_MAX_BYTES)
        } catch (t: Throwable) {
            csv = null
            csvFile = null
            log("Nie udało się utworzyć pliku CSV.")
        }
    }

    private fun csvAppend(dir: String, charId: String, content: String) {
        val c = csv ?: return
        val now = System.currentTimeMillis()
        val clean = content.replace('\n', ' ').replace(',', ';')
        val name = if (charId == "-") "-" else WhoopExtProtocol.charName(charId)
        c.append(isoFmt.format(Date(now)) + "," + now + "," + dir + "," + name + "," + clean)
    }

    // =====================================================================================================
    // save to Downloads/Forma / share (no FileProvider needed: MediaStore gives a content:// URI)
    // =====================================================================================================
    private fun saveRawToDownloads() {
        val f = csvFile
        val c = csv
        if (f == null || c == null) {
            log("Nie ma jeszcze pliku do zapisania.")
            return
        }
        log("Zapisuję plik…")
        io.execute {
            var r: ExportResult? = null
            try {
                c.flushNow()
                r = exportFile(f, "text/csv")
            } catch (t: Throwable) { /* reported below */ }
            val res = r
            runOnUiThread {
                if (!destroyed) {
                    if (res != null && res.where.isNotEmpty() && (res.uri != null || Build.VERSION.SDK_INT < 29)) {
                        savedRaw = res.uri
                        setStatus("Zapisano: " + res.where, GOOD)
                    } else {
                        setStatus("Zapis pliku nieudany.", BAD)
                    }
                    updateButtons()
                    renderValues()
                }
            }
        }
    }

    /** Saves src into Downloads/Forma, REPLACING an earlier file of the same name that this app wrote. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exportViaMediaStore(src: File, mime: String): Uri? {
        val resolver = contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rel = Environment.DIRECTORY_DOWNLOADS + "/Forma/"
        var uri: Uri? = null
        try {
            resolver.query(
                coll, arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(src.name, rel), null
            )?.use { c ->
                if (c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0))
            }
        } catch (t: Throwable) {
            uri = null
        }
        var out: java.io.OutputStream? = null
        val existing = uri
        if (existing != null) {
            out = try { resolver.openOutputStream(existing, "wt") } catch (t: Throwable) { null }
        }
        var target: Uri? = if (out != null) existing else null
        if (out == null) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Forma")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val created = resolver.insert(coll, values) ?: return null
            out = resolver.openOutputStream(created)
            if (out == null) {
                resolver.delete(created, null, null)
                return null
            }
            target = created
        }
        val stream: java.io.OutputStream = out ?: return null
        stream.use { o -> FileInputStream(src).use { input -> input.copyTo(o) } }
        val done = ContentValues()
        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
        try { resolver.update(target!!, done, null, null) } catch (t: Throwable) { /* replaced file has no pending flag */ }
        return target
    }

    private fun shareUri(uri: Uri?, mime: String) {
        if (uri == null) return
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Udostępnij plik"))
        } catch (t: Throwable) {
            log("Nie udało się otworzyć udostępniania: " + t.javaClass.simpleName)
        }
    }
}
