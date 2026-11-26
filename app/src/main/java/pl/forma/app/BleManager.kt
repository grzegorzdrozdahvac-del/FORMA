package pl.forma.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The only place that talks BLE. Shared by the live mode (JS bridge) and NightService.
 * Standard Heart Rate service only (0x180D / 0x2A37) plus Battery (0x180F / 0x2A19).
 * All GATT state is touched on the main thread; GATT callbacks hop to it first.
 */
@SuppressLint("MissingPermission")
object BleManager {
    interface Listener {
        fun onScanResult(id: String, name: String, rssi: Int) {}
        fun onScanFinished() {}
        fun onState(state: String, name: String?) {}
        fun onHr(sample: HrSample) {}
        fun onBattery(level: Int) {}
        /** Every battery read / notification, with its own timestamp (also fed to BatteryLog). via = "read" | "notify". */
        fun onBatteryEntry(level: Int, via: String, t: Long) {}
        /** 0x2A1A / 0x2BED charging flag, only when the band exposes those characteristics. */
        fun onCharging(charging: Boolean) {}
        /** GATT diagnostics JSON (services, characteristics, properties, battery details); sent again when the 0x2904 descriptor arrives. */
        fun onGattInfo(json: String) {}
        fun onError(message: String) {}
        fun onNight(state: String, id: String?) {}
        fun onWarning(code: String, level: Int) {}
    }

    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BATT_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATT_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val CPF: UUID = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb")
    val BATT_POWER_STATE: UUID = UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb")
    val BATT_LEVEL_STATUS: UUID = UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb")

    private const val CONNECT_TIMEOUT_MS = 25_000L
    private const val BATT_POLL_MS = 60_000L
    private const val OP_TIMEOUT_MS = 10_000L
    private const val STALE_MS = 15_000L
    private const val WATCHDOG_TICK_MS = 5_000L
    private const val AUTO_TICK_MS = 30_000L
    private const val FAST_ATTEMPTS = 3
    private const val MTU_WANTED = 185
    private const val MIN_WAIT_GATT_MS = 600L
    private const val TIMEOUT_STATUS = -1
    private const val STALE_STATUS = -2
    private const val FAIL_STATUS = -3
    /** Mirror of ble-core.js NATIVE_BACKOFF (2, 4, 8, 15, 30 s, then every 30 s). */
    private val BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var state: String = "disconnected"
        private set
    @Volatile var deviceName: String? = null
        private set
    @Volatile var deviceAddress: String? = null
        private set
    @Volatile var batteryLevel: Int? = null
        private set
    /** null = the band does not report a charging flag (0x2A1A / 0x2BED missing or unreadable). */
    @Volatile var chargingFlag: Boolean? = null
        private set
    /** Last GATT diagnostics as a JSON string (kept after disconnect, replaced on the next connection). */
    @Volatile var gattInfoJson: String? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var scanning = false
    private var hrChar: BluetoothGattCharacteristic? = null
    private var battChar: BluetoothGattCharacteristic? = null
    private var battNotify = false
    private var powerStateChar: BluetoothGattCharacteristic? = null
    private var levelStatusChar: BluetoothGattCharacteristic? = null
    private var cpfPresent = false
    private var cpfHex: String? = null

    private val ops = java.util.ArrayDeque<() -> Boolean>()
    private var opBusy = false

    // ---------- link supervision (all touched on the main thread unless noted) ----------
    /** The user wants to be connected: unexpected losses are retried until disconnect() is called. */
    private var wantConnected = false
    private var targetAddress: String? = null
    private var reconnectAuto = false
    private var autoMode = false
    private var count133 = 0
    private var lastHrAt = 0L
    private var liveUse = false
    private var nightUse = false
    @Volatile var reconnectAttempt: Int = 0
        private set
    @Volatile var lastReason: String? = null
        private set
    @Volatile var lastStatus: Int? = null
        private set

    private class ConnEvent(val t: Long, val kind: String, val status: Int?, val reason: String, val attempt: Int)
    private val connLog = java.util.ArrayDeque<ConnEvent>()

    fun init(ctx: Context) {
        if (!::appCtx.isInitialized) appCtx = ctx.applicationContext
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun isConnected(): Boolean = state == "connected"

    // ---------- permissions ----------
    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(appCtx, p) == PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_CONNECT) else true

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun adapter(): BluetoothAdapter? =
        (appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---------- listener fan-out (also used by NightService) ----------
    fun notifyNight(s: String, id: String?) { listeners.forEach { it.onNight(s, id) } }
    fun notifyWarning(code: String, level: Int) { listeners.forEach { it.onWarning(code, level) } }
    fun notifyError(msg: String) { listeners.forEach { it.onError(msg) } }

    private fun setState(s: String) {
        state = s
        listeners.forEach { it.onState(s, deviceName) }
    }

    // ---------- scan ----------
    /**
     * Unfiltered scan; the callback keeps a device when it advertises the Heart Rate service (0x180D)
     * OR its name starts with WHOOP / Polar. With all = true every named device is listed.
     */
    fun startScan(timeoutMs: Long, all: Boolean) {
        main.post {
            stopScanInternal()
            val ad = adapter()
            if (ad == null || !ad.isEnabled) {
                notifyError("Bluetooth jest wyłączony. Włącz Bluetooth i spróbuj ponownie.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            if (!hasScanPermission()) {
                notifyError("Brak uprawnienia do skanowania Bluetooth.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            val sc = ad.bluetoothLeScanner
            if (sc == null) {
                notifyError("Skaner Bluetooth jest niedostępny.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) { handleScan(result, all) }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    for (r in results) handleScan(r, all)
                }
                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        notifyError("Skanowanie nie powiodło się (kod $errorCode).")
                        stopScanInternal()
                    }
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                sc.startScan(null, settings, cb)
                scanner = sc
                scanCb = cb
                scanning = true
                main.postDelayed(scanTimeout, timeoutMs)
            } catch (e: Exception) {
                notifyError("Nie udało się rozpocząć skanowania: " + (e.message ?: e.javaClass.simpleName))
                listeners.forEach { it.onScanFinished() }
            }
        }
    }

    private val scanTimeout = Runnable { stopScanInternal() }

    private fun handleScan(r: ScanResult, all: Boolean) {
        val dev = r.device ?: return
        val name = r.scanRecord?.deviceName ?: dev.name ?: ""
        val hasHrService = r.scanRecord?.serviceUuids?.contains(ParcelUuid(HR_SERVICE)) == true
        val nameOk = name.startsWith("WHOOP", true) || name.startsWith("Polar", true)
        val keep = if (all) name.isNotEmpty() else (hasHrService || nameOk)
        if (!keep) return
        val shown = if (name.isEmpty()) "Urządzenie z tętnem" else name
        listeners.forEach { it.onScanResult(dev.address, shown, r.rssi) }
    }

    fun stopScan() { main.post { stopScanInternal() } }

    private fun stopScanInternal() {
        main.removeCallbacks(scanTimeout)
        val wasScanning = scanning
        try {
            val cb = scanCb
            if (cb != null) scanner?.stopScan(cb)
        } catch (e: Exception) { /* adapter turned off etc. */ }
        scanner = null
        scanCb = null
        scanning = false
        if (wasScanning) listeners.forEach { it.onScanFinished() }
    }

    // ---------- connection log (ring buffer, 100 entries) ----------
    private fun logConn(kind: String, status: Int?, reason: String) {
        val e = ConnEvent(System.currentTimeMillis(), kind, status, reason, reconnectAttempt)
        synchronized(connLog) {
            connLog.addLast(e)
            while (connLog.size > 100) connLog.removeFirst()
        }
    }

    /** [{t, kind: connected|disconnect|stale|attempt|user, code, reason, attempt}], oldest first. Callable from any thread. */
    fun connectionLogJson(): JSONArray {
        val a = JSONArray()
        synchronized(connLog) {
            for (e in connLog) {
                val o = JSONObject()
                o.put("t", e.t)
                o.put("kind", e.kind)
                o.put("code", e.status ?: JSONObject.NULL)
                o.put("reason", e.reason)
                o.put("attempt", e.attempt)
                a.put(o)
            }
        }
        return a
    }

    private fun reasonText(status: Int): String = when (status) {
        0 -> "łącze zamknięte"
        8 -> "przekroczono czas łącza (poza zasięgiem?)"
        19 -> "opaska zakończyła połączenie"
        22 -> "telefon zakończył połączenie"
        34 -> "błąd synchronizacji łącza (LMP)"
        62 -> "nie udało się nawiązać połączenia"
        133 -> "błąd połączenia GATT (133)"
        257 -> "błąd ogólny GATT (257)"
        TIMEOUT_STATUS -> "opaska nie odpowiada (czas łączenia)"
        STALE_STATUS -> "brak tętna przez 15 s - łącze zawieszone"
        FAIL_STATUS -> "nie udało się rozpocząć połączenia"
        else -> "kod GATT $status"
    }

    // ---------- connect / disconnect ----------
    /** Explicit connect (the user picked this device): it becomes the preferred band. */
    fun connect(address: String) {
        main.post { startConnect(address, true) }
    }

    /** Auto-connect to the remembered band only (never to another heart-rate device). false = no band remembered. */
    fun connectPreferred(): Boolean {
        val a = Prefs.preferredId(appCtx) ?: return false
        main.post { startConnect(a, false) }
        return true
    }

    private fun startConnect(address: String, explicit: Boolean) {
        stopScanInternal()
        if (!hasConnectPermission()) { notifyError("Brak uprawnienia Bluetooth (urządzenia w pobliżu)."); return }
        if (state == "connected" && deviceAddress == address) { wantConnected = true; setState("connected"); return }
        if ((state == "connecting" || state == "reconnecting") && deviceAddress == address && wantConnected) return
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            notifyError("Bluetooth jest wyłączony.")
            wantConnected = false
            cancelReconnect()
            closeGatt()
            setState("disconnected")
            return
        }
        cancelReconnect()
        closeGatt()
        val dev: BluetoothDevice? = try {
            ad.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (dev == null) {
            notifyError("Nieprawidłowy adres urządzenia.")
            wantConnected = false
            setState("disconnected")
            return
        }
        targetAddress = address
        wantConnected = true
        reconnectAttempt = 0
        count133 = 0
        lastReason = null
        lastStatus = null
        deviceAddress = address
        deviceName = dev.name ?: (if (Prefs.preferredId(appCtx) == address) Prefs.preferredName(appCtx) else null)
        if (explicit) Prefs.setPreferred(appCtx, address, deviceName)
        batteryLevel = null
        setState("connecting")
        openGatt(dev, false)
    }

    /** First attempt and every fast retry: autoConnect = false. After FAST_ATTEMPTS failures: autoConnect = true (the OS reconnects when the band is back). */
    private fun openGatt(dev: BluetoothDevice, auto: Boolean) {
        autoMode = auto
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(autoTick)
        val g: BluetoothGatt? = try {
            dev.connectGatt(appCtx, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            notifyError("Nie udało się połączyć: " + (e.message ?: e.javaClass.simpleName))
            null
        }
        gatt = g
        if (g == null) {
            handleLinkLoss(null, FAIL_STATUS)
            return
        }
        if (auto) main.postDelayed(autoTick, AUTO_TICK_MS) else main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (wantConnected && state != "connected") handleLinkLoss(gatt, TIMEOUT_STATUS)
    }

    /** autoConnect = true has no timeout of its own: only refresh the attempt counter for the UI every 30 s. */
    private val autoTick = object : Runnable {
        override fun run() {
            if (wantConnected && autoMode && state != "connected") {
                reconnectAttempt++
                setState("reconnecting")
                main.postDelayed(this, AUTO_TICK_MS)
            }
        }
    }

    private val reconnectRun = Runnable {
        val addr = targetAddress
        if (wantConnected && addr != null) {
            val ad = adapter()
            if (ad == null || !ad.isEnabled) {
                lastReason = "Bluetooth jest wyłączony"
                setState("reconnecting")
                scheduleReconnect(BACKOFF_MS[BACKOFF_MS.size - 1], false)
            } else {
                val dev: BluetoothDevice? = try {
                    ad.getRemoteDevice(addr)
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (dev == null) {
                    wantConnected = false
                    setState("disconnected")
                } else {
                    setState("reconnecting")
                    openGatt(dev, reconnectAuto)
                }
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long, auto: Boolean) {
        main.removeCallbacks(reconnectRun)
        reconnectAuto = auto
        main.postDelayed(reconnectRun, delayMs)
    }

    private fun cancelReconnect() {
        main.removeCallbacks(reconnectRun)
        main.removeCallbacks(autoTick)
    }

    private fun backoffFor(attempt: Int): Long {
        val i = minOf(maxOf(attempt, 1), BACKOFF_MS.size) - 1
        return BACKOFF_MS[i]
    }

    /** Hidden BluetoothGatt.refresh() clears the stack's GATT cache; best effort, never fatal. */
    private fun refreshGatt(g: BluetoothGatt) {
        try {
            val m = g.javaClass.getMethod("refresh")
            m.invoke(g)
        } catch (e: Throwable) { /* hidden API may be blocked */ }
    }

    /**
     * The link is gone or never came up (callback status, connect timeout, stale watchdog). Always closes the gatt, waits (>= 600 ms
     * for 133/8/19/22) and retries with backoff while the user still wants the connection.
     */
    private fun handleLinkLoss(g: BluetoothGatt?, status: Int) {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(autoTick)
        main.removeCallbacks(battPoll)
        main.removeCallbacks(opTimeoutRun)
        main.removeCallbacks(watchdog)
        val wasConnected = state == "connected"
        if (!wantConnected) {
            if (g != null) { try { g.close() } catch (e: Exception) { /* ignore */ } }
            if (g == null || gatt === g) { resetLinkFields(); gatt = null }
            if (state != "disconnected") setState("disconnected")
            return
        }
        val reason = reasonText(status)
        count133 = if (status == 133) count133 + 1 else 0
        reconnectAttempt++
        lastReason = reason
        lastStatus = status
        val kind = if (status == STALE_STATUS) "stale" else if (wasConnected) "disconnect" else "attempt"
        logConn(kind, status, reason)
        if (g != null) {
            if (status == 133 && count133 >= 2) refreshGatt(g)
            try { g.close() } catch (e: Exception) { /* ignore */ }
        }
        if (g == null || gatt === g) gatt = null
        resetLinkFields()
        val minWait = if (status == 133 || status == 8 || status == 19 || status == 22) MIN_WAIT_GATT_MS else 0L
        val delay = maxOf(backoffFor(reconnectAttempt), minWait)
        setState("reconnecting")
        scheduleReconnect(delay, reconnectAttempt > FAST_ATTEMPTS)
    }

    /** A condition retrying cannot fix (no Heart Rate service, no CCCD): stop wanting the connection and tell the user. */
    private fun giveUp(g: BluetoothGatt, message: String) {
        notifyError(message)
        wantConnected = false
        handleLinkLoss(g, 0)
    }

    /** User-initiated: no automatic reconnect afterwards. */
    fun disconnect() {
        main.post {
            wantConnected = false
            cancelReconnect()
            main.removeCallbacks(connectTimeout)
            val g = gatt
            if (state != "disconnected") logConn("user", null, "rozłączono na prośbę użytkownika")
            if (g == null) {
                if (state != "disconnected") setState("disconnected")
            } else {
                try { g.disconnect() } catch (e: Exception) { /* ignore */ }
                main.postDelayed({ if (gatt === g) { closeGatt(); setState("disconnected") } }, 1500)
            }
        }
    }

    private fun resetLinkFields() {
        ops.clear()
        opBusy = false
        hrChar = null
        battChar = null
        battNotify = false
        powerStateChar = null
        levelStatusChar = null
        batteryLevel = null
        chargingFlag = null
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(autoTick)
        main.removeCallbacks(battPoll)
        main.removeCallbacks(opTimeoutRun)
        main.removeCallbacks(watchdog)
        resetLinkFields()
        try { gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null
    }

    // ---------- connection priority: HIGH while the user measures / trains / opens Diagnostyka, BALANCED at night and otherwise ----------
    fun setLiveUse(on: Boolean) { main.post { liveUse = on; applyPriority() } }
    fun setNightUse(on: Boolean) { main.post { nightUse = on; applyPriority() } }

    private fun applyPriority() {
        val g = gatt ?: return
        val p = if (liveUse && !nightUse) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        try { g.requestConnectionPriority(p) } catch (e: Exception) { /* optional */ }
    }

    /** No heart-rate notification for STALE_MS while "connected": the link is a zombie, force a reconnect. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (state == "connected") {
                if (System.currentTimeMillis() - lastHrAt > STALE_MS) {
                    handleLinkLoss(gatt, STALE_STATUS)
                } else {
                    main.postDelayed(this, WATCHDOG_TICK_MS)
                }
            }
        }
    }

    // ---------- GATT operation queue (main thread); every callback must end in opDone(), a 10 s timeout covers lost callbacks ----------
    private fun enqueue(op: () -> Boolean) { ops.add(op); pump() }

    private fun pump() {
        if (opBusy) return
        val op = ops.poll() ?: return
        opBusy = true
        main.removeCallbacks(opTimeoutRun)
        main.postDelayed(opTimeoutRun, OP_TIMEOUT_MS)
        val started = try { op() } catch (e: Exception) { false }
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
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(cccd)
    }

    private val battPoll = object : Runnable {
        override fun run() {
            val g = gatt
            val bc = battChar
            if (g != null && bc != null && state == "connected") {
                // active read every 60 s even when notifications are on: the read is the ground truth we log
                enqueue { g.readCharacteristic(bc) }
                val ps = powerStateChar
                if (ps != null) enqueue { g.readCharacteristic(ps) }
                val ls = levelStatusChar
                if (ls != null) enqueue { g.readCharacteristic(ls) }
                main.postDelayed(this, BATT_POLL_MS)
            }
        }
    }

    private fun handleBattery(level: Int, via: String) {
        if (level < 0 || level > 100) return
        val t = System.currentTimeMillis()
        batteryLevel = level
        BatteryLog.add(appCtx, t, level, via, deviceName)
        listeners.forEach { it.onBattery(level) }
        listeners.forEach { it.onBatteryEntry(level, via, t) }
    }

    /** 0x2A1A Battery Power State: bits 4-5 = charge state (3 = charging). Layout from memory, unverified on a real band. */
    private fun handlePowerState(v: ByteArray) {
        if (v.isEmpty()) return
        val cs = ((v[0].toInt() and 0xFF) shr 4) and 3
        if (cs == 0) return
        setCharging(cs == 3)
    }

    /** 0x2BED Battery Level Status: flags(u8), power state(u16 LE); bits 5-6 of power state = charge state (1 = charging). Unverified. */
    private fun handleLevelStatus(v: ByteArray) {
        if (v.size < 3) return
        val ps = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        val cs = (ps shr 5) and 3
        if (cs == 0) return
        setCharging(cs == 1)
    }

    private fun setCharging(on: Boolean) {
        val was = chargingFlag
        chargingFlag = on
        if (was != on) listeners.forEach { it.onCharging(on) }
    }

    private fun propNames(p: Int): JSONArray {
        val a = JSONArray()
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) a.put("read")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) a.put("write")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) a.put("write_no_response")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) a.put("notify")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) a.put("indicate")
        return a
    }

    private fun hexOf(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02x", x.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Full service / characteristic list with properties, plus what matters for the battery question. */
    private fun buildGattInfo(g: BluetoothGatt): String {
        val root = JSONObject()
        root.put("device", deviceName ?: JSONObject.NULL)
        val svcs = JSONArray()
        val list: List<BluetoothGattService> = g.services ?: emptyList()
        for (svc in list) {
            val so = JSONObject()
            so.put("uuid", svc.uuid.toString())
            val chs = JSONArray()
            for (ch in svc.characteristics) {
                val co = JSONObject()
                co.put("uuid", ch.uuid.toString())
                co.put("props", propNames(ch.properties))
                val ds = JSONArray()
                for (d in ch.descriptors) ds.put(d.uuid.toString())
                co.put("descriptors", ds)
                chs.put(co)
            }
            so.put("characteristics", chs)
            svcs.put(so)
        }
        root.put("services", svcs)
        val bc = battChar
        val b = JSONObject()
        b.put("present", bc != null)
        b.put("props", if (bc != null) propNames(bc.properties) else JSONArray())
        b.put("cpfPresent", cpfPresent)
        b.put("cpfHex", cpfHex ?: JSONObject.NULL)
        b.put("has2BED", levelStatusChar != null)
        b.put("has2A1A", powerStateChar != null)
        root.put("battery", b)
        return root.toString()
    }

    private fun publishGattInfo(g: BluetoothGatt) {
        val json = try { buildGattInfo(g) } catch (e: Exception) { return }
        gattInfoJson = json
        listeners.forEach { it.onGattInfo(json) }
    }

    private fun setupServices(g: BluetoothGatt) {
        val hrSvc = g.getService(HR_SERVICE)
        val hr = hrSvc?.getCharacteristic(HR_CHAR)
        if (hr == null) {
            giveUp(g, "To urządzenie nie udostępnia tętna (usługa 0x180D). WHOOP: włącz „HR Broadcast” w aplikacji WHOOP.")
            return
        }
        if (hr.getDescriptor(CCCD) == null) {
            giveUp(g, "Charakterystyka tętna nie obsługuje powiadomień.")
            return
        }
        hrChar = hr
        applyPriority() // HIGH during live use, BALANCED otherwise (night / idle)
        enqueue { enableNotify(g, hr) } // success -> onDescriptorWrite marks the link as connected
        enqueue { g.requestMtu(MTU_WANTED) } // result (or failure) arrives in onMtuChanged -> opDone; a lost callback is covered by the op timeout
        val battSvc = g.getService(BATT_SERVICE)
        val bc = battSvc?.getCharacteristic(BATT_CHAR)
        cpfPresent = false
        cpfHex = null
        powerStateChar = battSvc?.getCharacteristic(BATT_POWER_STATE)
        levelStatusChar = battSvc?.getCharacteristic(BATT_LEVEL_STATUS)
        if (bc != null) {
            battChar = bc
            enqueue { g.readCharacteristic(bc) }
            battNotify = (bc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (battNotify) enqueue { enableNotify(g, bc) }
            val cpf = bc.getDescriptor(CPF)
            if (cpf != null) {
                cpfPresent = true
                enqueue { g.readDescriptor(cpf) }
            }
            val ps = powerStateChar
            if (ps != null) enqueue { g.readCharacteristic(ps) }
            val ls = levelStatusChar
            if (ls != null) enqueue { g.readCharacteristic(ls) }
            main.removeCallbacks(battPoll)
            main.postDelayed(battPoll, BATT_POLL_MS) // always poll, notifications or not
        }
        publishGattInfo(g)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { try { g.close() } catch (e: Exception) { /* ignore */ }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // arm the setup timeout again (also for autoConnect, which has none while the band is out of range)
                    main.removeCallbacks(connectTimeout)
                    main.removeCallbacks(autoTick)
                    main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
                    if (!g.discoverServices()) handleLinkLoss(g, FAIL_STATUS)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    handleLinkLoss(g, status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { handleLinkLoss(g, status); return@post }
                setupServices(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.characteristic?.uuid
            main.post {
                if (g !== gatt) return@post
                if (uuid == HR_CHAR) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        main.removeCallbacks(connectTimeout)
                        val addr = deviceAddress
                        if (addr != null) {
                            Prefs.setLastDevice(appCtx, addr, deviceName)
                            if (Prefs.preferredId(appCtx) == addr) Prefs.setPreferred(appCtx, addr, deviceName)
                        }
                        lastHrAt = System.currentTimeMillis()
                        logConn("connected", null, if (reconnectAttempt > 0) "połączono ponownie (po $reconnectAttempt próbach)" else "połączono")
                        setState("connected")
                        main.removeCallbacks(watchdog)
                        main.postDelayed(watchdog, WATCHDOG_TICK_MS)
                    } else {
                        // enabling the notification failed: a failed attempt (close, wait, retry); the queue is dropped with the gatt
                        handleLinkLoss(g, status)
                        return@post
                    }
                }
                opDone()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                opDone() // success or not, the MTU is optional
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val uuid = ch.uuid
            val v = ch.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && v != null && v.isNotEmpty()) {
                    if (uuid == BATT_CHAR) handleBattery(v[0].toInt() and 0xFF, "read")
                    else if (uuid == BATT_POWER_STATE) handlePowerState(v)
                    else if (uuid == BATT_LEVEL_STATUS) handleLevelStatus(v)
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.uuid
            val v = d.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (uuid == CPF) {
                    cpfHex = if (status == BluetoothGatt.GATT_SUCCESS && v != null) hexOf(v) else "błąd odczytu (status $status)"
                    publishGattInfo(g)
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val t = System.currentTimeMillis()
            val uuid = ch.uuid
            val v = ch.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (uuid == HR_CHAR) {
                    lastHrAt = t
                    if (reconnectAttempt != 0 || count133 != 0) { reconnectAttempt = 0; count133 = 0 } // real data flows: the link is healthy again
                    val s = HrParser.parse(v, t)
                    if (s != null) listeners.forEach { it.onHr(s) }
                } else if (uuid == BATT_CHAR && v != null && v.isNotEmpty()) {
                    handleBattery(v[0].toInt() and 0xFF, "notify")
                } else if (uuid == BATT_POWER_STATE && v != null) {
                    handlePowerState(v)
                } else if (uuid == BATT_LEVEL_STATUS && v != null) {
                    handleLevelStatus(v)
                }
            }
        }
    }
}
