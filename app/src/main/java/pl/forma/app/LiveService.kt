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
            if (!running) return
            running = false
            Handler(Looper.getMainLooper()).post { instance?.finishSelf() }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false

    private val listener = object : BleManager.Listener {
        override fun onState(state: String, name: String?) { updateNotification() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        BleManager.init(this)
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = startForegroundSafe(Notifications.live(this, BleManager.isConnected()))
        if (!ok || !running) {
            running = false
            finishSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:live")
            wl.setReferenceCounted(false)
            wl.acquire(WAKE_LOCK_MS)
            wakeLock = wl
        }
        BleManager.addListener(listener)
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

    private fun updateNotification() {
        if (finished) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, Notifications.live(this, BleManager.isConnected()))
        } catch (e: Exception) { /* ignore */ }
    }

    fun finishSelf() {
        if (finished) return
        finished = true
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
