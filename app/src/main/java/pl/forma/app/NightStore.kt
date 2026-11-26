package pl.forma.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Night files: filesDir/nights/<YYYY-MM-DD_HHmm>.csv
 *   #forma-night,1
 *   #start,<epochMs>,<device>
 *   t_epoch_ms,rr_ms,hr,n        (n = number of RR values in the BLE packet)
 *   <t>,<rr>,<hr>,<n>            RAW values with the receive time, nothing filtered
 *   #gap,<fromMs>,<toMs>         no data for more than 5 s
 *   #end,<epochMs>
 */
object NightStore {
    private val ID_RE = Regex("^[0-9A-Za-z_\\-]+$")

    data class Info(
        val id: String,
        val start: Long,
        val end: Long,
        val beats: Int,
        val gaps: Int,
        val running: Boolean,
        val incomplete: Boolean,
        val device: String
    )

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "nights")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun validId(id: String): Boolean = ID_RE.matches(id)

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.csv")

    fun newId(ctx: Context, startMs: Long): String {
        val base = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startMs))
        var id = base
        var i = 2
        while (file(ctx, id).exists()) { id = "$base-$i"; i++ }
        return id
    }

    fun info(f: File, runningId: String?): Info {
        val id = f.name.removeSuffix(".csv")
        var start = 0L
        var end = -1L
        var beats = 0
        var gaps = 0
        var lastT = 0L
        var device = ""
        try {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val c = line[0]
                    if (c.isDigit()) {
                        beats++
                        val comma = line.indexOf(',')
                        if (comma > 0) lastT = line.substring(0, comma).toLongOrNull() ?: lastT
                    } else if (line.startsWith("#start,")) {
                        val p = line.split(',')
                        start = p.getOrNull(1)?.toLongOrNull() ?: 0L
                        device = p.drop(2).joinToString(",")
                    } else if (line.startsWith("#end,")) {
                        end = line.split(',').getOrNull(1)?.toLongOrNull() ?: -1L
                    } else if (line.startsWith("#gap,")) {
                        gaps++
                    }
                }
            }
        } catch (e: Exception) { /* unreadable file -> empty info */ }
        val running = id == runningId
        val incomplete = end < 0
        if (start == 0L) start = f.lastModified()
        if (end < 0) end = if (lastT > 0) lastT else f.lastModified()
        return Info(id, start, end, beats, gaps, running, incomplete, device)
    }

    fun list(ctx: Context, runningId: String?): List<Info> {
        val files = dir(ctx).listFiles { x -> x.isFile && x.name.endsWith(".csv") } ?: return emptyList()
        return files.map { info(it, runningId) }.sortedBy { it.start }
    }

    fun toJson(i: Info): JSONObject {
        val o = JSONObject()
        o.put("id", i.id)
        o.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(i.start)))
        o.put("start", i.start)
        o.put("end", i.end)
        o.put("beats", i.beats)
        o.put("gaps", i.gaps)
        o.put("running", i.running)
        o.put("incomplete", i.incomplete)
        o.put("device", i.device)
        return o
    }

    /** JSON array of [tEpochMs, rrMs, hr, n]. */
    fun dataJson(ctx: Context, id: String): String {
        if (!validId(id)) return "[]"
        val f = file(ctx, id)
        if (!f.exists()) return "[]"
        val sb = StringBuilder(1 shl 16)
        sb.append('[')
        var first = true
        try {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || !line[0].isDigit()) continue
                    val p = line.split(',')
                    if (p.size < 4) continue
                    if (p[0].toLongOrNull() == null || p[1].toDoubleOrNull() == null) continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append('[').append(p[0]).append(',').append(p[1]).append(',')
                        .append(p[2].toIntOrNull() ?: 0).append(',').append(p[3].toIntOrNull() ?: 1).append(']')
                }
            }
        } catch (e: Exception) { /* return what we have */ }
        sb.append(']')
        return sb.toString()
    }

    fun delete(ctx: Context, id: String, runningId: String?): Boolean {
        if (!validId(id) || id == runningId) return false
        return file(ctx, id).delete()
    }

    /** Closes a night file that was left open (process killed) and posts the morning notification. */
    fun finalizeOrphan(ctx: Context, interrupted: Boolean = false) {
        if (!Prefs.nightRunning(ctx)) return
        val id = Prefs.nightId(ctx)
        if (id != null && validId(id)) {
            val f = file(ctx, id)
            if (f.exists()) {
                val hasEnd = try { f.readLines().any { it.startsWith("#end,") } } catch (e: Exception) { true }
                if (!hasEnd) {
                    try { f.appendText("#end," + System.currentTimeMillis() + "\n") } catch (e: Exception) { /* ignore */ }
                }
            }
        }
        Prefs.clearNight(ctx)
        WakeAlarm.cancel(ctx)
        if (interrupted) {
            Notifications.post(ctx, Notifications.RESULT_ID, "Forma", "Pomiar nocy przerwany przez restart telefonu", "#/dzis")
        } else {
            Notifications.postResult(ctx)
        }
        BleManager.notifyNight("stopped", id)
    }
}

/** Buffered appender for one night file. */
class NightWriter(file: File, startMs: Long, device: String, append: Boolean) {
    private val w = BufferedWriter(FileWriter(file, append), 16384)

    init {
        if (!append) {
            val dev = device.replace(',', ' ').replace('\n', ' ').replace('\r', ' ')
            w.write("#forma-night,1\n#start,$startMs,$dev\nt_epoch_ms,rr_ms,hr,n\n")
            w.flush()
        }
    }

    @Synchronized
    fun row(t: Long, rr: Double, hr: Int, n: Int) {
        w.write(t.toString())
        w.write(",")
        w.write(String.format(Locale.US, "%.2f", rr))
        w.write(",")
        w.write(hr.toString())
        w.write(",")
        w.write(n.toString())
        w.write("\n")
    }

    @Synchronized
    fun gap(from: Long, to: Long) {
        w.write("#gap,$from,$to\n")
    }

    @Synchronized
    fun flush() {
        try { w.flush() } catch (e: Exception) { /* ignore */ }
    }

    @Synchronized
    fun close(endMs: Long) {
        try {
            w.write("#end,$endMs\n")
            w.flush()
            w.close()
        } catch (e: Exception) { /* ignore */ }
    }
}
