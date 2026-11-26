package pl.forma.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Foreground service (connectedDevice) that records raw RR intervals through the night. */
class NightService : Service() {
    companion object {
        const val ACTION_START = "pl.forma.app.action.NIGHT_START"
        const val EXTRA_WAKE = "wake"
        const val NOTIF_ID = 4001
        private const val TAG = "FormaNight"
        private const val GAP_MS = 5_000L
        private val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

        @Volatile var instance: NightService? = null

        // Status read by the JS bridge (set immediately in start() so the UI does not flicker).
        @Volatile var running = false
        @Volatile var startedAt = 0L
        @Volatile var beats = 0
        @Volatile var lastHr = 0
        @Volatile var wake: String = ""
        @Volatile var wakeAt = 0L
        @Volatile var currentId: String? = null

        fun start(ctx: Context, wakeHHmm: String) {
            running = true
            startedAt = System.currentTimeMillis()
            beats = 0
            lastHr = 0
            wake = wakeHHmm
            wakeAt = WakeAlarm.nextWakeMillis(wakeHHmm)
            val i = Intent(ctx, NightService::class.java).setAction(ACTION_START).putExtra(EXTRA_WAKE, wakeHHmm)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                running = false
                Log.e(TAG, "startForegroundService failed", e)
                BleManager.notifyError("Nie udało się uruchomić pomiaru nocy: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var began = false
    private var writer: NightWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSampleAt = 0L
    private var attempt = 0
    private var ownsConnection = false
    private var lowChecked = false
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        BleManager.init(this)
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            beginNight(intent?.getStringExtra(EXTRA_WAKE) ?: "07:00", false)
        } else if (intent == null && Prefs.nightRunning(this)) {
            beginNight(Prefs.nightWake(this), true) // sticky restart after process death
        } else if (!began) {
            // nothing to do: still satisfy the startForeground contract, then stop
            startForegroundSafe(Notifications.night(this, "Forma – pomiar nocy"))
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundSafe(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            BleManager.notifyError("Nie udało się uruchomić usługi pomiaru nocy: " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun beginNight(wakeHHmm: String, resume: Boolean) {
        if (began) return
        began = true
        if (!startForegroundSafe(Notifications.night(this, statusText()))) {
            began = false
            running = false
            stopSelf()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:night").apply {
            setReferenceCounted(false)
            acquire(14L * 60L * 60L * 1000L)
        }
        val now = System.currentTimeMillis()
        val id: String
        val start: Long
        val wakeAtMs: Long
        val append: Boolean
        if (resume) {
            id = Prefs.nightId(this) ?: NightStore.newId(this, now)
            start = if (Prefs.nightStart(this) > 0) Prefs.nightStart(this) else now
            wakeAtMs = Prefs.nightWakeAt(this)
            append = NightStore.file(this, id).exists()
            ownsConnection = Prefs.nightOwnsConnection(this)
        } else {
            id = NightStore.newId(this, now)
            start = now
            wakeAtMs = WakeAlarm.nextWakeMillis(wakeHHmm, now)
            append = false
            ownsConnection = !BleManager.isConnected()
        }
        val device = BleManager.deviceName ?: Prefs.lastDeviceName(this) ?: ""
        writer = NightWriter(NightStore.file(this, id), start, device, append)
        Prefs.setNight(this, id, start, wakeHHmm, wakeAtMs, ownsConnection)
        currentId = id
        startedAt = start
        wake = wakeHHmm
        wakeAt = wakeAtMs
        if (resume) beats = NightStore.info(NightStore.file(this, id), id).beats
        lastSampleAt = now
        running = true
        stopping = false
        if (wakeAtMs > now) WakeAlarm.schedule(this, wakeAtMs) else handler.post { finishNight() }

        BleManager.addListener(listener)
        BleManager.setNightUse(true) // BALANCED connection priority at night: saves the band's battery
        if (BleManager.isConnected()) checkLowBattery() else connectNow()
        handler.postDelayed(flushTick, 30_000L)
        handler.postDelayed(notifTick, 60_000L)
        updateNotification()
        BleManager.notifyNight("started", id)
    }

    /** BleManager retries by itself (backoff, autoConnect) while the connection is wanted; this only starts it when nothing is going on. */
    private fun connectNow() {
        if (BleManager.state == "disconnected") {
            if (!BleManager.connectPreferred()) {
                BleManager.notifyError("Brak zapamiętanej opaski. Połącz opaskę w Formie przed startem nocy.")
            }
        }
    }

    private val listener = object : BleManager.Listener {
        override fun onHr(sample: HrSample) {
            val w = writer ?: return
            val now = sample.t
            if (lastSampleAt != 0L && now - lastSampleAt > GAP_MS) w.gap(lastSampleAt, now)
            lastSampleAt = now
            val n = sample.rr.size
            for (rr in sample.rr) {
                w.row(now, rr, sample.hr, n)
                beats++
            }
            if (sample.hr > 0) lastHr = sample.hr
        }

        override fun onState(state: String, name: String?) {
            if (!running || stopping) return
            if (state == "connected") {
                attempt = 0
                handler.removeCallbacks(reconnectTick)
                checkLowBattery()
            } else if (state == "disconnected") {
                scheduleReconnect()
            }
        }

        override fun onBattery(level: Int) {
            checkLowBattery()
        }
    }

    private fun checkLowBattery() {
        if (lowChecked) return
        val level = BleManager.batteryLevel ?: return
        lowChecked = true
        if (level < 15) BleManager.notifyWarning("lowBattery", level)
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectTick)
        val d = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
        handler.postDelayed(reconnectTick, d)
    }

    private val reconnectTick = Runnable {
        if (running && !stopping && !BleManager.isConnected()) {
            attempt++
            if (BleManager.state == "disconnected" && BleManager.connectPreferred()) { /* BleManager supervises it from here */ }
            else scheduleReconnect()
        }
    }

    private val flushTick = object : Runnable {
        override fun run() {
            writer?.flush()
            if (running) handler.postDelayed(this, 30_000L)
        }
    }

    private val notifTick = object : Runnable {
        override fun run() {
            updateNotification()
            if (running) handler.postDelayed(this, 60_000L)
        }
    }

    private fun statusText(): String {
        val hr = if (lastHr > 0) lastHr.toString() else "–"
        val bat = BleManager.batteryLevel?.toString() ?: "–"
        return "Forma – pomiar nocy · $hr ud/min · $beats uderzeń · bateria $bat%"
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, Notifications.night(this, statusText()))
        } catch (e: Exception) { /* ignore */ }
    }

    /** Called by the wake alarm, the notification action and stopNight(). */
    fun requestStop() {
        handler.post { finishNight() }
    }

    private fun finishNight() {
        if (!running || stopping) return
        stopping = true
        running = false
        handler.removeCallbacksAndMessages(null)
        BleManager.removeListener(listener)
        BleManager.setNightUse(false)
        val id = currentId
        writer?.close(System.currentTimeMillis())
        writer = null
        WakeAlarm.cancel(this)
        Prefs.clearNight(this)
        if (ownsConnection) BleManager.disconnect()
        releaseWakeLock()
        Notifications.postResult(this)
        BleManager.notifyNight("stopped", id)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (e: Exception) { /* ignore */ }
        wakeLock = null
    }

    override fun onDestroy() {
        // System-initiated destroy while running: keep the file consistent, sticky restart resumes it.
        if (running) writer?.flush()
        handler.removeCallbacksAndMessages(null)
        BleManager.removeListener(listener)
        BleManager.setNightUse(false)
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
