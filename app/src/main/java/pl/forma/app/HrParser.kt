package pl.forma.app

/** One Heart Rate Measurement (0x2A37) notification. RR values are in milliseconds (raw, unfiltered). */
data class HrSample(
    val hr: Int,
    val rr: List<Double>,
    val contactSupported: Boolean,
    val contactDetected: Boolean,
    val t: Long
)

/** Same layout as hrv-core.js parseHeartRateMeasurement. */
object HrParser {
    fun parse(data: ByteArray?, t: Long): HrSample? {
        if (data == null || data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        var off = 1
        val hr: Int
        if (flags and 0x01 != 0) {
            if (data.size < off + 2) return null
            hr = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
            off += 2
        } else {
            hr = data[off].toInt() and 0xFF
            off += 1
        }
        if (flags and 0x08 != 0) off += 2 // energy expended
        val rr = ArrayList<Double>()
        if (flags and 0x10 != 0) {
            while (off + 1 < data.size) {
                val raw = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
                rr.add(raw * 1000.0 / 1024.0)
                off += 2
            }
        }
        return HrSample(hr, rr, (flags and 0x04) != 0, (flags and 0x02) != 0, t)
    }
}
