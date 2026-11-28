package pl.forma.app

import android.app.Notification
import android.app.NotificationManager
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

/**
 * Lightweight foreground service (type connectedDevice) for live sessions: the 2-minute morning measurement and workouts.
 * It keeps the process and the BLE link alive with the screen off and shows "Forma - połączono z opaską".
 * Started and stopped by the web app through FormaBridge.setLiveUse("session" / other). The night recording uses NightService instead.
 */
class LiveService : Service() {
    companion object {
        const val ACTION_START = "pl.forma.app.action.LIVE_START"
        const val NOTIF_ID = 4006
        private const val TAG = "FormaLive"
        private const val WAKE_LOCK_MS = 4L * 60L * 60L * 1000L

        /** true from start() until stop(); read by MainActivity (keep the WebView and the event listener alive with the screen off). */
        @Volatile var running = false
        @Volatile var instance: LiveService? = null

        /** Etap 9: all-day mode is on - the service stays up (setLiveUse("off") must not end it) and logs every heart-rate sample. */
        @Volatile var keepAlive = false
        /** true while the web app holds a live session (morning measurement / workout) on this service. */
        @Volatile var sessionUse = false
        private const val TICK_MS = 60_000L

        /** All-day mode on: the band stays connected (BleManager reconnects by itself), HR is logged to HrLog. */
        fun startAllDay(ctx: Context, until: Long) {
            Prefs.setAllDay(ctx, true, until)
            keepAlive = true
            HrLog.logPhoneBattery(ctx, true)
            start(ctx)
            instance?.armAllDay()
        }

        /** All-day mode off: ends the service unless a live session still needs it. */
        fun stopAllDay(ctx: Context) {
            Prefs.setAllDay(ctx, false, 0L)
            keepAlive = false
            HrLog.logPhoneBattery(ctx, true)
            if (!sessionUse) stop()
            else instance?.updateNotification()
        }

        /** After a reboot / app update: back to all-day mode if it was on and has not expired. */
        fun resumeAllDay(ctx: Context) {
            if (!Prefs.allDayOn(ctx)) return
            val until = Prefs.allDayUntil(ctx)
            if (until > 0L && System.currentTimeMillis() >= until) { Prefs.setAllDay(ctx, false, 0L); return }
            keepAlive = true
            start(ctx)
        }

        fun start(ctx: Context) {
            if (running) return
            running = true
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                running = false
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        /**
         * Never calls stopService directly: a stop right after startForegroundService would crash the app with
         * "did not then call startForeground". If the service has not started yet, onStartCommand sees running == false and ends itself.
         */
        fun stop() {
            if (keepAlive) return
            if (!running) return
            running = false
            Handler(Looper.getMainLooper()).post { instance?.finishSelf() }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false

    private val listener = object : BleManager.Listener {
        override fun onState(state: String, name: String?) { updateNotification() }
        override fun onHr(sample: HrSample) {
            if (keepAlive) HrLog.add(this@LiveService, sample.hr, sample.rr, sample.t)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            if (keepAlive) {
                val until = Prefs.allDayUntil(this@LiveService)
                if (until > 0L && System.currentTimeMillis() >= until) {
                    // the 24 h test is over: switch the mode off by itself (the web app sees it in getAllDayStatus)
                    stopAllDay(this@LiveService)
                    return
                }
                HrLog.logPhoneBattery(this@LiveService)
                HrLog.flush(this@LiveService)
                if (!BleManager.isConnected()) BleManager.connectPreferred()
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    fun armAllDay() {
        handler.removeCallbacks(tick)
        if (keepAlive) handler.postDelayed(tick, TICK_MS)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        BleManager.init(this)
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running && intent == null && Prefs.allDayOn(this)) {
            // restarted by the system after the process was killed: all-day mode is still wanted
            val until = Prefs.allDayUntil(this)
            if (until == 0L || System.currentTimeMillis() < until) { running = true; keepAlive = true }
        }
        val ok = startForegroundSafe(Notifications.live(this, BleManager.isConnected()))
        if (!ok || !running) {
            running = false
            finishSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null && !keepAlive) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:live")
            wl.setReferenceCounted(false)
            wl.acquire(WAKE_LOCK_MS)
            wakeLock = wl
        }
        BleManager.addListener(listener)
        if (keepAlive) {
            if (!BleManager.isConnected()) BleManager.connectPreferred()
            armAllDay()
            return START_STICKY
        }
        return START_NOT_STICKY
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
            false
        }
    }

    fun updateNotification() {
        if (finished) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, Notifications.live(this, BleManager.isConnected()))
        } catch (e: Exception) { /* ignore */ }
    }

    fun finishSelf() {
        if (finished) return
        finished = true
        handler.removeCallbacks(tick)
        HrLog.flush(this)
        BleManager.removeListener(listener)
        releaseWakeLock()
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
        BleManager.removeListener(listener)
        releaseWakeLock()
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }
}
