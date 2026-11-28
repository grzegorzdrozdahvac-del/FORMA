package pl.forma.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TreeMap

/**
 * Etap 9, all-day mode: compact 1 Hz heart-rate log (with RR) and a phone battery log.
 *
 * Files in filesDir, one per local day: "hrlog-yyyy-MM-dd.csv", line = "epochSec,hr[,rr1;rr2...]" (RR in whole ms).
 * 14 days are kept (older files are deleted on the first write of a day). Samples are buffered in memory and appended
 * every FLUSH_MS from a single worker thread, so the BLE callback (main thread) never touches the disk.
 * minutesJson() folds the log into per-minute summaries for the web app: [t, avg, max, min, rr, n, steps] with t in epoch ms.
 * Best effort everywhere: logging must never break the BLE path.
 */
object HrLog {
    private const val KEEP_DAYS = 14
    private const val FLUSH_MS = 15_000L
    private const val MIN_GAP_MS = 800L
    private const val MINUTE_MS = 60_000L
    private const val PHONE_FILE = "phone-battery.csv"
    private const val PHONE_KEEP_MS = 5L * 24L * 3_600_000L
    private const val PHONE_EVERY_MS = 5L * 60_000L

    private val lock = Any()
    private val buf = StringBuilder()
    private var lastAdd = 0L
    private var lastFlush = 0L
    private var lastPrune = ""
    private var lastPhone = 0L
    @Volatile var samples = 0L
    @Volatile var lastHrAt = 0L

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun dayName(ms: Long): String = fmt.get()!!.format(Date(ms))
    private fun fileFor(ctx: Context, day: String) = File(ctx.applicationContext.filesDir, "hrlog-$day.csv")

    /** Called for every heart-rate notification while the mode is on. Cheap: appends to a string buffer. */
    fun add(ctx: Context, hr: Int, rr: List<Double>, t: Long) {
        if (hr < 30 || hr > 230) return
        if (t - lastAdd < MIN_GAP_MS) return
        lastAdd = t
        lastHrAt = t
        samples++
        val app = ctx.applicationContext
        var due = false
        synchronized(lock) {
            buf.append(t / 1000L).append(',').append(hr)
            if (rr.isNotEmpty()) {
                buf.append(',')
                var first = true
                for (r in rr) {
                    if (!(r > 250.0 && r < 2500.0)) continue
                    if (!first) buf.append(';')
                    buf.append(Math.round(r))
                    first = false
                }
            }
            buf.append('\n')
            if (t - lastFlush >= FLUSH_MS) { lastFlush = t; due = true }
        }
        if (due) worker.execute { flush(app) }
    }

    /** Writes the buffer; lines are split per local day by their own timestamp. */
    fun flush(ctx: Context) {
        val text: String
        synchronized(lock) {
            if (buf.isEmpty()) return
            text = buf.toString()
            buf.setLength(0)
        }
        try {
            val perDay = TreeMap<String, StringBuilder>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val sec = line.substringBefore(',').toLongOrNull() ?: continue
                perDay.getOrPut(dayName(sec * 1000L)) { StringBuilder() }.append(line).append('\n')
            }
            for ((day, sb) in perDay) fileFor(ctx, day).appendText(sb.toString())
            val today = dayName(System.currentTimeMillis())
            if (today != lastPrune) { lastPrune = today; prune(ctx) }
        } catch (e: Exception) {
            // keep going
        }
    }

    private fun prune(ctx: Context) {
        try {
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS.toLong()).toString()
            ctx.applicationContext.filesDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("hrlog-") && n.endsWith(".csv")) {
                    val day = n.substring(6, n.length - 4)
                    if (day < cutoff) f.delete()
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    fun clear(ctx: Context) {
        synchronized(lock) { buf.setLength(0) }
        try {
            ctx.applicationContext.filesDir.listFiles()?.forEach { f ->
                if ((f.name.startsWith("hrlog-") && f.name.endsWith(".csv")) || f.name == PHONE_FILE) f.delete()
            }
        } catch (e: Exception) { /* ignore */ }
        samples = 0L
    }

    private class Acc {
        var sum = 0L
        var n = 0
        var max = 0
        var min = 999
        var rr = 0
    }

    /** { minutes: [[t, avg, max, min, rr, n, steps|null], ...] } for the local days from fromIso (yyyy-MM-dd) to now. */
    fun minutesJson(ctx: Context, fromIso: String): JSONObject {
        val app = ctx.applicationContext
        flush(app)
        val zone = ZoneId.systemDefault()
        val from: LocalDate = try { LocalDate.parse(fromIso) } catch (e: Exception) { LocalDate.now().minusDays(2) }
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val agg = TreeMap<Long, Acc>()
        var d = from
        val last = LocalDate.now()
        while (!d.isAfter(last)) {
            val f = fileFor(app, d.toString())
            if (f.exists()) {
                try {
                    f.forEachLine { line ->
                        val p = line.split(',', limit = 3)
                        if (p.size >= 2) {
                            val sec = p[0].toLongOrNull()
                            val hr = p[1].toIntOrNull()
                            if (sec != null && hr != null && sec * 1000L >= fromMs) {
                                val m = (sec * 1000L) / MINUTE_MS * MINUTE_MS
                                val a = agg.getOrPut(m) { Acc() }
                                a.sum += hr
                                a.n++
                                if (hr > a.max) a.max = hr
                                if (hr < a.min) a.min = hr
                                if (p.size == 3 && p[2].isNotEmpty()) a.rr += p[2].split(';').size
                            }
                        }
                    }
                } catch (e: Exception) { /* unreadable file: keep what we have */ }
            }
            d = d.plusDays(1)
        }
        val steps = HashMap<Long, Long>()
        try {
            val sm = StepCounter.minutesJson(app, fromIso).optJSONArray("minutes")
            if (sm != null) for (i in 0 until sm.length()) {
                val a = sm.optJSONArray(i) ?: continue
                steps[a.optLong(0)] = a.optLong(1)
            }
        } catch (e: Exception) { /* steps are optional */ }
        val have = steps.isNotEmpty()
        val arr = JSONArray()
        for ((m, a) in agg) {
            if (a.n == 0) continue
            val row = JSONArray()
            row.put(m)
            row.put(Math.round(a.sum.toDouble() / a.n))
            row.put(a.max)
            row.put(a.min)
            row.put(a.rr)
            row.put(a.n)
            // steps are logged only when the phone counted something; "no row" while the sensor works means 0
            if (have) row.put(steps[m] ?: 0L) else row.put(JSONObject.NULL)
            arr.put(row)
        }
        val o = JSONObject()
        o.put("minutes", arr)
        return o
    }

    // ---------- phone battery (for "Test 1 dnia": drain %/h with and without the mode) ----------

    /** Appends "epochMs,level,charging" at most every 5 minutes; call from the service tick. */
    fun logPhoneBattery(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPhone < PHONE_EVERY_MS) return
        lastPhone = now
        try {
            val app = ctx.applicationContext
            val i: Intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = Math.round(level * 100f / scale)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val f = File(app.filesDir, PHONE_FILE)
            synchronized(lock) {
                f.appendText("$now,$pct,${if (chg) 1 else 0}\n")
                if (f.length() > 120_000L) {
                    val keep = f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0L) > now - PHONE_KEEP_MS }
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    /** [[t, level, charging(bool)], ...] oldest first. */
    fun phoneBatteryJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        try {
            val f = File(ctx.applicationContext.filesDir, PHONE_FILE)
            if (f.exists()) synchronized(lock) {
                f.forEachLine { line ->
                    val p = line.split(',')
                    val t = p.getOrNull(0)?.toLongOrNull()
                    val lv = p.getOrNull(1)?.toIntOrNull()
                    if (t != null && lv != null) {
                        val a = JSONArray()
                        a.put(t)
                        a.put(lv)
                        a.put(p.getOrNull(2) == "1")
                        arr.put(a)
                    }
                }
            }
        } catch (e: Exception) { /* return what we have */ }
        return arr
    }
}
