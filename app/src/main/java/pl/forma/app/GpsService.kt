package pl.forma.app

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The raw GPS fixes of the running workout (Spacer / Bieg). Kept in memory and appended to a small file, so the web view can fetch them later
 * (screen off, app in the background, even after the process was restarted). Filtering and analysis happen in the web code (gps-core.js).
 * The first line of the file is "#<session id>"; a different id starts a new track.
 */
object GpsTrack {
    private const val FILE = "gps_track.csv"
    private const val MAX_POINTS = 30000
    private val lock = Any()
    private var id: String? = null
    private val pts = ArrayList<DoubleArray>() // t (epoch ms), lat, lon, accuracy (-1 = unknown)

    /** wait | ok | off (GPS switched off in the phone) */
    @Volatile var state: String = "wait"

    /** Same session id: keeps the points (a restarted service continues the track). A new id empties the track. */
    fun begin(ctx: Context, sessionId: String) {
        synchronized(lock) {
            if (id == sessionId) return
            val f = File(ctx.applicationContext.filesDir, FILE)
            pts.clear()
            id = sessionId
            try {
                var keep = false
                if (f.exists()) {
                    val lines = f.readLines()
                    if (lines.isNotEmpty() && lines[0] == "#$sessionId") {
                        keep = true
                        for (i in 1 until lines.size) {
                            val p = lines[i].split(",")
                            if (p.size == 4) {
                                val t = p[0].toDoubleOrNull()
                                val la = p[1].toDoubleOrNull()
                                val lo = p[2].toDoubleOrNull()
                                val ac = p[3].toDoubleOrNull()
                                if (t != null && la != null && lo != null && ac != null) pts.add(doubleArrayOf(t, la, lo, ac))
                            }
                        }
                    }
                }
                if (!keep) f.writeText("#$sessionId\n")
            } catch (e: Exception) {
                Log.e("FormaGps", "track file", e)
            }
            state = "wait"
        }
    }

    fun add(ctx: Context, t: Long, lat: Double, lon: Double, acc: Double) {
        synchronized(lock) {
            if (pts.size >= MAX_POINTS) return
            pts.add(doubleArrayOf(t.toDouble(), lat, lon, acc))
            try {
                File(ctx.applicationContext.filesDir, FILE).appendText("$t,$lat,$lon,$acc\n")
            } catch (e: Exception) {
                // the in-memory copy still works
            }
            state = "ok"
        }
    }

    fun count(): Int = synchronized(lock) { pts.size }

    /** {ok, next, state, running, pts:[[t, lat, lon, acc|null], ...]} with the points from index [from] on. */
    fun since(from: Int): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        synchronized(lock) {
            val start = from.coerceIn(0, pts.size)
            for (i in start until pts.size) {
                val p = pts[i]
                val a = JSONArray()
                a.put(p[0].toLong())
                a.put(p[1])
                a.put(p[2])
                if (p[3] >= 0.0) a.put(Math.round(p[3] * 10.0) / 10.0) else a.put(JSONObject.NULL)
                arr.put(a)
            }
            o.put("next", pts.size)
        }
        o.put("ok", true)
        o.put("state", state)
        o.put("running", GpsService.running)
        o.put("pts", arr)
        return o
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            pts.clear()
            id = null
            try {
                File(ctx.applicationContext.filesDir, FILE).delete()
            } catch (e: Exception) { /* ignore */ }
        }
    }
}

/**
 * Foreground service (type location) that runs only for the time of a Spacer / Bieg workout with the GPS switch on.
 * Started by FormaBridge.gpsStart (the user pressed "Start"), stopped by gpsStop ("Zakończ" / discard) or after 8 h at the latest.
 * Needs only "while in use" location (no background location permission): the service is started while the app is in front.
 */
class GpsService : Service() {
    companion object {
        const val ACTION_START = "pl.forma.app.action.GPS_START"
        const val EXTRA_SID = "sid"
        const val NOTIF_ID = 4007
        private const val TAG = "FormaGps"
        private const val MAX_RUN_MS = 8L * 60L * 60L * 1000L

        /** true from start() until stop(). */
        @Volatile var running = false
        @Volatile var instance: GpsService? = null

        fun hasFine(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasCoarse(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context, sessionId: String): Boolean {
            if (!hasFine(ctx)) return false
            GpsTrack.begin(ctx, sessionId)
            if (running) return true
            running = true
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GpsService::class.java).setAction(ACTION_START).putExtra(EXTRA_SID, sessionId))
                true
            } catch (e: Exception) {
                running = false
                Log.e(TAG, "startForegroundService failed", e)
                false
            }
        }

        /** Same pattern as LiveService.stop(): never stopService() directly (it could race with startForeground). */
        fun stop() {
            if (!running) return
            running = false
            Handler(Looper.getMainLooper()).post { instance?.finishSelf() }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false
    private var lm: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { running = false; finishSelf() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val t = if (loc.time > 0L) loc.time else System.currentTimeMillis()
            val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0
            GpsTrack.add(applicationContext, t, loc.latitude, loc.longitude, acc)
        }

        override fun onProviderEnabled(provider: String) {
            GpsTrack.state = "wait"
        }

        override fun onProviderDisabled(provider: String) {
            GpsTrack.state = "off"
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // not used (kept so that Android 8-10 do not hit an unimplemented method)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = startForegroundSafe(Notifications.gps(this))
        if (!ok || !running || intent?.getStringExtra(EXTRA_SID).isNullOrEmpty()) {
            running = false
            finishSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:gps")
            wl.setReferenceCounted(false)
            wl.acquire(MAX_RUN_MS)
            wakeLock = wl
        }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_RUN_MS)
        requestFixes()
        return START_NOT_STICKY
    }

    private fun requestFixes() {
        try {
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            if (!GpsService.hasFine(this)) {
                GpsTrack.state = "off"
                return
            }
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsTrack.state = "off"
            // every second, no minimum distance; the callbacks run on the main thread (cheap: one list add and one file append)
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "no location permission", e)
            GpsTrack.state = "off"
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
            GpsTrack.state = "off"
        }
    }

    private fun startForegroundSafe(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    fun finishSelf() {
        if (finished) return
        finished = true
        handler.removeCallbacks(autoStop)
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) { /* ignore */ }
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
        handler.removeCallbacks(autoStop)
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) { /* ignore */ }
        releaseWakeLock()
        if (instance === this) instance = null
        running = false
        super.onDestroy()
    }
}
