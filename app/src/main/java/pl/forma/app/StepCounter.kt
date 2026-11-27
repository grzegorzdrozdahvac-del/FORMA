package pl.forma.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Step counting from Sensor.TYPE_STEP_COUNTER (total since boot; counts only while the phone is carried).
 * Deltas are stored per local hour in filesDir/steps.csv as "epochHourStartMs,steps" lines (several lines per hour are summed on read).
 * A persistent SensorEventListener works while the process lives; StepAlarmReceiver takes a one-shot reading every hour.
 */
object StepCounter {
    private const val FILE = "steps.csv"
    private const val FILE_MIN = "steps_min.csv"
    private const val MIN_MS = 60_000L
    private const val FLUSH_MS = 5 * 60_000L
    private const val HOUR_MS = 3_600_000L

    private var appCtx: Context? = null
    private var listening = false
    private var lastTotal = -1L
    private var pendHour = 0L
    private var pendSteps = 0L
    private var lastFlush = 0L
    private val minBuf = TreeMap<Long, Long>() // epoch minute start -> steps, written to steps_min.csv on flush

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            val c = appCtx ?: return
            if (e.values.isEmpty()) return
            // the sensor batches events: the event's own timestamp gives the real minute
            val now = System.currentTimeMillis()
            val at = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L
            onTotal(c, e.values[0].toLong(), if (at in (now - 6L * HOUR_MS)..now) at else now, true)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun sensorManager(ctx: Context): SensorManager? =
        ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun available(ctx: Context): Boolean = sensorManager(ctx)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** ACTIVITY_RECOGNITION is a runtime permission from API 29; below it the step counter needs none. */
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hourStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Registers the persistent listener when tracking is on, the sensor exists and the permission is granted. Safe to call often. */
    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (listening || !Prefs.stepsTrack(app) || !permitted(app)) return
        val mgr = sensorManager(app) ?: return
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        // 5 minutes of hardware batching keeps the cost negligible
        listening = mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 300_000_000)
    }

    @Synchronized
    fun stop(ctx: Context) {
        val mgr = sensorManager(ctx)
        if (mgr != null) mgr.unregisterListener(listener)
        listening = false
        flushLocked(ctx.applicationContext)
    }

    @Synchronized
    fun flush(ctx: Context) { flushLocked(ctx.applicationContext) }

    @Synchronized
    private fun onTotal(ctx: Context, total: Long, atMs: Long, minutes: Boolean) {
        if (lastTotal < 0L) lastTotal = Prefs.stepsLastTotal(ctx)
        if (lastTotal < 0L) { // first reading ever: only a baseline
            lastTotal = total
            Prefs.setStepsLastTotal(ctx, total)
            return
        }
        // the counter restarts from 0 after a reboot: then everything since boot is new
        val delta: Long = if (total >= lastTotal) total - lastTotal else total
        val hour = hourStart(atMs)
        if (pendSteps > 0L && hour != pendHour) flushLocked(ctx) // persists the previous total
        lastTotal = total
        if (delta <= 0L) return
        if (minutes) { val m = atMs - atMs % MIN_MS; minBuf[m] = (minBuf[m] ?: 0L) + delta }
        pendHour = hour
        pendSteps += delta
        if (atMs - lastFlush >= FLUSH_MS) flushLocked(ctx)
    }

    private fun flushLocked(ctx: Context) {
        try {
            if (pendSteps > 0L) {
                File(ctx.filesDir, FILE).appendText(pendHour.toString() + "," + pendSteps + "\n")
                pendSteps = 0L
            }
            if (minBuf.isNotEmpty()) {
                val sb = StringBuilder()
                for ((t, n) in minBuf) sb.append(t).append(',').append(n).append('\n')
                File(ctx.filesDir, FILE_MIN).appendText(sb.toString())
                minBuf.clear()
            }
            lastFlush = System.currentTimeMillis()
            if (lastTotal >= 0L) Prefs.setStepsLastTotal(ctx, lastTotal)
        } catch (e: Exception) {
            // disk problems: keep the pending value for the next flush
        }
    }

    /** One-shot reading for the hourly alarm (worker thread). atMs decides which hour gets the steps taken since the last reading. */
    fun sampleOnce(ctx: Context, atMs: Long, timeoutMs: Long): Boolean {
        val app = ctx.applicationContext
        if (!permitted(app)) return false
        val mgr = sensorManager(app) ?: return false
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        val latch = CountDownLatch(1)
        var got = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                if (got || e.values.isEmpty()) return
                got = true
                onTotal(app, e.values[0].toLong(), atMs, false) // atMs is only the hour bucket here, not a real time
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        mgr.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // continue with what we have
        }
        mgr.unregisterListener(l)
        flush(app)
        return got
    }

    /** Last total seen (for the snooze comparison), -1 when unknown. */
    @Synchronized
    fun currentTotal(ctx: Context): Long {
        if (lastTotal < 0L) lastTotal = Prefs.stepsLastTotal(ctx.applicationContext)
        return lastTotal
    }

    @Synchronized
    fun readHourly(ctx: Context, fromMs: Long): TreeMap<Long, Long> {
        val app = ctx.applicationContext
        flushLocked(app)
        val out = TreeMap<Long, Long>()
        val f = File(app.filesDir, FILE)
        if (!f.exists()) return out
        try {
            f.forEachLine { line ->
                val p = line.split(",")
                if (p.size == 2) {
                    val t = p[0].trim().toLongOrNull()
                    val n = p[1].trim().toLongOrNull()
                    if (t != null && n != null && n > 0L && t >= fromMs) out[t] = (out[t] ?: 0L) + n
                }
            }
        } catch (e: Exception) {
            // unreadable file: return what we have
        }
        return out
    }

    /** Steps recorded in the given local hour. */
    fun stepsInHour(ctx: Context, hourStartMs: Long): Long = readHourly(ctx, hourStartMs)[hourStartMs] ?: 0L

    /** { minutes:[[epochMinuteStartMs, steps], ...] } since fromIso (local day yyyy-MM-dd). Used by the web app to detect walks. */
    @Synchronized
    fun minutesJson(ctx: Context, fromIso: String): JSONObject {
        val app = ctx.applicationContext
        flushLocked(app)
        val fromMs: Long = try {
            LocalDate.parse(fromIso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis() - 3L * 24L * HOUR_MS
        }
        val agg = TreeMap<Long, Long>()
        val f = File(app.filesDir, FILE_MIN)
        if (f.exists()) {
            try {
                f.forEachLine { line ->
                    val p = line.split(",")
                    if (p.size == 2) {
                        val t = p[0].trim().toLongOrNull()
                        val n = p[1].trim().toLongOrNull()
                        if (t != null && n != null && n > 0L && t >= fromMs) agg[t] = (agg[t] ?: 0L) + n
                    }
                }
            } catch (e: Exception) {
                // unreadable file: return what we have
            }
        }
        val arr = JSONArray()
        for ((t, n) in agg) {
            val a = JSONArray()
            a.put(t)
            a.put(n)
            arr.put(a)
        }
        val o = JSONObject()
        o.put("minutes", arr)
        return o
    }

    /** Drops minute data older than 14 days once the file grows. */
    @Synchronized
    fun compactMinutes(ctx: Context) {
        val app = ctx.applicationContext
        val f = File(app.filesDir, FILE_MIN)
        if (!f.exists() || f.length() < 200_000L) return
        val keep = minutesJson(app, LocalDate.now().minusDays(14).toString()).getJSONArray("minutes")
        try {
            val sb = StringBuilder()
            for (k in 0 until keep.length()) {
                val a = keep.getJSONArray(k)
                sb.append(a.getLong(0)).append(',').append(a.getLong(1)).append('\n')
            }
            val tmp = File(app.filesDir, "steps_min.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            // keep the old file
        }
    }

    /** Rewrites the file with one line per hour and drops data older than 120 days. */
    @Synchronized
    fun compact(ctx: Context) {
        val app = ctx.applicationContext
        val f = File(app.filesDir, FILE)
        if (!f.exists() || f.length() < 60_000L) return
        val agg = readHourly(app, System.currentTimeMillis() - 120L * 24L * HOUR_MS)
        try {
            val sb = StringBuilder()
            for ((t, n) in agg) sb.append(t).append(',').append(n).append('\n')
            val tmp = File(app.filesDir, "steps.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            // keep the old file
        }
    }

    private fun dateOf(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** { available, permission, hourly:[{t,steps}], daily:[{date,steps}] } from the local day fromIso (yyyy-MM-dd). */
    fun toJson(ctx: Context, fromIso: String): JSONObject {
        val fromMs: Long = try {
            LocalDate.parse(fromIso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis() - 14L * 24L * HOUR_MS
        }
        val hourly = readHourly(ctx, fromMs)
        val daily = TreeMap<String, Long>()
        val arr = JSONArray()
        for ((t, n) in hourly) {
            val h = JSONObject()
            h.put("t", t)
            h.put("steps", n)
            arr.put(h)
            val d = dateOf(t)
            daily[d] = (daily[d] ?: 0L) + n
        }
        val darr = JSONArray()
        for ((d, n) in daily) {
            val o = JSONObject()
            o.put("date", d)
            o.put("steps", n)
            darr.put(o)
        }
        val o = JSONObject()
        o.put("available", available(ctx))
        o.put("permission", permitted(ctx))
        o.put("tracking", Prefs.stepsTrack(ctx))
        o.put("hourly", arr)
        o.put("daily", darr)
        return o
    }
}
