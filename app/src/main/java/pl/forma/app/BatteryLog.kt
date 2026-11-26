package pl.forma.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent log of every read / notification of the Battery Level characteristic (0x2A19), written by
 * BleManager, so readings taken by NightService (screen off, UI closed) are kept too.
 * File: filesDir/battery-log.csv, one line per reading: "<epochMs>,<level>,<read|notify>,<device>".
 * Capped at MAX_ENTRIES; when the file grows past TRIM_AT lines, entries older than 24 h are
 * downsampled to one per 10 min and the oldest are dropped. Best effort: never throws.
 */
object BatteryLog {
    private const val FILE_NAME = "battery-log.csv"
    private const val MAX_ENTRIES = 2000
    private const val TRIM_AT = 2300
    private const val DAY_MS = 86_400_000L
    private const val BUCKET_MS = 600_000L

    private class Entry(val t: Long, val level: Int, val via: String, val device: String)

    private val lock = Any()
    private var count = -1

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    private fun parse(line: String): Entry? {
        val p = line.split(",", limit = 4)
        if (p.size < 3) return null
        val t = p[0].trim().toLongOrNull() ?: return null
        val level = p[1].trim().toIntOrNull() ?: return null
        if (level < 0 || level > 100) return null
        val via = if (p[2].trim() == "notify") "notify" else "read"
        val device = if (p.size > 3) p[3].trim() else ""
        return Entry(t, level, via, device)
    }

    private fun readAll(f: File): List<Entry> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Entry>()
        f.forEachLine { line ->
            val e = parse(line)
            if (e != null) out.add(e)
        }
        return out
    }

    fun add(ctx: Context, t: Long, level: Int, via: String, device: String?) {
        if (level < 0 || level > 100) return
        val safeVia = if (via == "notify") "notify" else "read"
        val dev = (device ?: "").replace(Regex("[,\\r\\n]"), " ").trim()
        synchronized(lock) {
            try {
                val f = file(ctx)
                if (count < 0) count = readAll(f).size
                f.appendText("$t,$level,$safeVia,$dev\n")
                count++
                if (count > TRIM_AT) compact(f)
            } catch (e: Exception) {
                // logging must never break the BLE path
            }
        }
    }

    private fun compact(f: File) {
        val all = readAll(f)
        if (all.isEmpty()) { count = 0; return }
        val newest = all.maxOf { it.t }
        val cutoff = newest - DAY_MS
        val seen = HashSet<Long>()
        val merged = ArrayList<Entry>()
        for (e in all) {
            if (e.t >= cutoff) merged.add(e)
            else if (seen.add(e.t / BUCKET_MS)) merged.add(e)
        }
        merged.sortBy { it.t }
        val kept = if (merged.size > MAX_ENTRIES) merged.subList(merged.size - MAX_ENTRIES, merged.size) else merged
        val sb = StringBuilder()
        for (e in kept) sb.append(e.t).append(',').append(e.level).append(',').append(e.via).append(',').append(e.device).append('\n')
        f.writeText(sb.toString())
        count = kept.size
    }

    /** [{t, level, via, device, connected:true}, ...] oldest first. */
    fun toJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        synchronized(lock) {
            try {
                for (e in readAll(file(ctx))) {
                    val o = JSONObject()
                    o.put("t", e.t)
                    o.put("level", e.level)
                    o.put("via", e.via)
                    o.put("device", e.device)
                    o.put("connected", true)
                    arr.put(o)
                }
            } catch (e: Exception) {
                // return what we have
            }
        }
        return arr
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            try { file(ctx).delete() } catch (e: Exception) { /* ignore */ }
            count = 0
        }
    }
}
