package pl.forma.app

import java.util.zip.CRC32

/**
 * WHOOP 4.0 proprietary BLE protocol ("tryb rozszerzony"), diagnostic spike. Self-contained: no Android classes, no dependencies.
 *
 * Written from the FACTS documented by the community (bWanShiTong/reverse-engineering-whoop-post, OpenStrap protocol notes,
 * NOOP / whoop-reader READMEs) and from our own check of their published test vectors. No third-party code is copied.
 * See WHOOP-EXT-NOTES.md for sources, confidence per field and the SAFE-READ whitelist.
 *
 * Frame on the wire (verified against every published command AND notification example, 11 of 11 CRCs match):
 *   0xAA | len (u16 LE) | crc8(len bytes) | payload | crc32(payload) (u32 LE)
 *   len = payload size + 4; crc8 = poly 0x07, init 0, no reflection; crc32 = standard IEEE/zlib CRC-32 over the payload ONLY.
 *   (The "custom CRC, xorout 0xF43F44AC" description in the community write-ups is the same thing seen through a different
 *   window: it only works if the header is included; the plain zlib CRC over the payload gives identical bytes.)
 *   payload = type (u8) | seq/counter (u8) | command or record data...
 *
 * Nothing in this file throws on unexpected bytes: every parser is wrapped and returns a "nieznany"/"błąd" record instead.
 */
object WhoopExtProtocol {

    // ---------- UUIDs ----------
    /** All WHOOP proprietary UUIDs share this tail; the first 32-bit group (6108000x) identifies the characteristic. */
    const val UUID_TAIL = "-8d6d-82b8-614a-1c8cb0f8dcc6"
    const val CH_CMD_TO_STRAP = "61080002"   // write
    const val CH_CMD_FROM_STRAP = "61080003" // notify, command responses
    const val CH_EVENTS = "61080004"         // notify, events (one project calls this one the data stream: we subscribe to all)
    const val CH_DATA = "61080005"           // notify, realtime + historical data
    const val CH_MEMFAULT = "61080007"       // notify, diagnostics (unknown purpose)

    fun isWhoopUuid(uuid: java.util.UUID): Boolean = uuid.toString().lowercase().endsWith(UUID_TAIL)
    fun shortId(uuid: java.util.UUID): String = uuid.toString().substring(0, 8).lowercase()

    fun charName(shortId: String): String = when (shortId) {
        CH_CMD_TO_STRAP -> "CMD_TO_STRAP"
        CH_CMD_FROM_STRAP -> "CMD_FROM_STRAP"
        CH_EVENTS -> "EVENTS_FROM_STRAP"
        CH_DATA -> "DATA_FROM_STRAP"
        CH_MEMFAULT -> "MEMFAULT"
        else -> shortId
    }

    // ---------- packet types (payload[0]) ----------
    const val PKT_COMMAND = 0x23
    const val PKT_COMMAND_RESPONSE = 0x24 // from memory of other community notes, not in the sources we could open: unverified
    const val PKT_REALTIME = 0x28
    const val PKT_HISTORICAL = 0x2F
    const val PKT_EVENT = 0x30
    const val PKT_METADATA = 0x31

    // ---------- SAFE-READ whitelist ----------
    /**
     * The ONLY commands this app can build. Anything else (erase 0x19, reboot 0x1D, alarm 0x42, haptics, HR broadcast 0x0E,
     * clock set, batch ACK 0x17, IMU / raw-data modes, ...) has no constant here, so it cannot be sent by accident.
     */
    enum class SafeCommand(val id: Int, val value: Int, val label: String) {
        /** 0x03 value 1: start the 1 Hz realtime stream (HR + RR). Volatile toggle, documented as stopping with value 0. */
        START_REALTIME(0x03, 0x01, "start odczytu na żywo"),
        /** 0x03 value 0: the explicit stop-stream. */
        STOP_REALTIME(0x03, 0x00, "stop odczytu na żywo"),
        /** 0x1A: battery level request (GET_BATTERY_LEVEL). ID from memory of other community notes; consistent with every other ID we could cross-check. */
        GET_BATTERY(0x1A, 0x00, "odczyt baterii"),
        /** 0x16: ask the band to send stored records (SEND_HISTORICAL_DATA). We never send the batch ACK (0x17), so nothing is released on the band. */
        REQUEST_HISTORY(0x16, 0x00, "prośba o dane z pamięci")
    }

    /** Set to false to hide the "Pobierz dane z pamięci (próba)" button and make the builder refuse REQUEST_HISTORY. */
    const val HISTORY_PULL_ENABLED = true

    /** Builds a command frame. Returns null for a command that is switched off. Only whitelisted commands exist. */
    fun buildCommand(cmd: SafeCommand, seq: Int): ByteArray? {
        if (cmd == SafeCommand.REQUEST_HISTORY && !HISTORY_PULL_ENABLED) return null
        val payload = byteArrayOf(
            PKT_COMMAND.toByte(),
            (seq and 0xFF).toByte(),
            (cmd.id and 0xFF).toByte(),
            (cmd.value and 0xFF).toByte()
        )
        return frame(payload)
    }

    // ---------- framing and CRC ----------
    fun crc8(data: ByteArray, off: Int, len: Int): Int {
        var c = 0
        for (i in off until off + len) {
            c = c xor (data[i].toInt() and 0xFF)
            for (k in 0 until 8) {
                c = if ((c and 0x80) != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF
            }
        }
        return c
    }

    fun crc32(data: ByteArray, off: Int, len: Int): Long {
        val c = CRC32()
        c.update(data, off, len)
        return c.value
    }

    internal fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    internal fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    internal fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun frame(payload: ByteArray): ByteArray {
        val len = payload.size + 4
        val out = ByteArray(4 + payload.size + 4)
        out[0] = 0xAA.toByte()
        out[1] = (len and 0xFF).toByte()
        out[2] = ((len shr 8) and 0xFF).toByte()
        out[3] = crc8(out, 1, 2).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        val c = crc32(payload, 0, payload.size)
        val o = 4 + payload.size
        out[o] = (c and 0xFF).toByte()
        out[o + 1] = ((c shr 8) and 0xFF).toByte()
        out[o + 2] = ((c shr 16) and 0xFF).toByte()
        out[o + 3] = ((c shr 24) and 0xFF).toByte()
        return out
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Known-answer test with two vectors published by the community (start / stop activity). true = our CRC code agrees with them. */
    fun selfTest(): Boolean {
        return try {
            hex(frame(byteArrayOf(0x23, 0x8C.toByte(), 0x03, 0x01))) == "aa0800a8238c03017d5ec627" &&
                hex(frame(byteArrayOf(0x23, 0x8D.toByte(), 0x03, 0x00))) == "aa0800a8238d0300dc040351"
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Re-assembles frames from notification chunks (a frame can be longer than one notification; the 96-byte historical
     * records certainly are with the default 23-byte MTU). One instance per characteristic. Garbage is skipped byte by byte.
     */
    class FrameAssembler {
        private var buf = ByteArray(0)
        var droppedBytes: Long = 0
            private set
        var badCrcFrames: Int = 0
            private set

        /** Returns the payloads (type, seq, data...) of all complete frames whose CRC-32 is correct. */
        fun feed(chunk: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            try {
                buf = buf + chunk
                if (buf.size > 16384) {
                    droppedBytes += buf.size.toLong()
                    buf = ByteArray(0)
                    return out
                }
                var pos = 0
                while (true) {
                    while (pos < buf.size && WhoopExtProtocol.u8(buf, pos) != 0xAA) {
                        pos++
                        droppedBytes++
                    }
                    if (buf.size - pos < 4) break
                    val len = WhoopExtProtocol.u16le(buf, pos + 1)
                    if (WhoopExtProtocol.crc8(buf, pos + 1, 2) != WhoopExtProtocol.u8(buf, pos + 3) || len < 4 || len > 4096) {
                        pos++
                        droppedBytes++
                        continue
                    }
                    val total = 4 + len
                    if (buf.size - pos < total) break
                    val payloadLen = len - 4
                    val calc = WhoopExtProtocol.crc32(buf, pos + 4, payloadLen)
                    val rx = WhoopExtProtocol.u32le(buf, pos + 4 + payloadLen)
                    if (calc == rx) {
                        out.add(buf.copyOfRange(pos + 4, pos + 4 + payloadLen))
                    } else {
                        badCrcFrames++
                    }
                    pos += total
                }
                buf = if (pos >= buf.size) ByteArray(0) else buf.copyOfRange(pos, buf.size)
            } catch (t: Throwable) {
                buf = ByteArray(0)
            }
            return out
        }
    }

    // ---------- decoded result ----------
    /** Everything optional: null = unknown ("?" in the UI). */
    class Decoded(
        val kind: String,
        val epoch: Long? = null,
        val hr: Int? = null,
        val rr: List<Int> = emptyList(),
        val tempC: Double? = null,
        val tempRaw: Int? = null,
        val spo2: Int? = null,
        val ax: Double? = null,
        val ay: Double? = null,
        val az: Double? = null,
        val batteryPct: Double? = null,
        val batchNo: Long? = null,
        val info: String = ""
    )

    /** Decodes one verified payload. Never throws. */
    fun decode(p: ByteArray): Decoded {
        return try {
            if (p.isEmpty()) return Decoded("pusty")
            when (u8(p, 0)) {
                PKT_REALTIME -> decodeRealtime(p)
                PKT_HISTORICAL -> decodeHistorical(p)
                PKT_METADATA -> decodeMetadata(p)
                PKT_COMMAND_RESPONSE -> decodeResponse(p)
                PKT_EVENT -> decodeEvent(p)
                else -> Decoded("nieznany", info = "typ 0x" + Integer.toHexString(u8(p, 0)) + ", " + p.size + " B")
            }
        } catch (t: Throwable) {
            Decoded("błąd", info = t.javaClass.simpleName)
        }
    }

    private fun plausibleRr(v: Int): Boolean = v in 250..2500

    private fun readRr(p: ByteArray, countOff: Int, firstOff: Int): List<Int> {
        val out = ArrayList<Int>()
        if (countOff >= p.size) return out
        val n = minOf(u8(p, countOff), 4)
        for (i in 0 until n) {
            val o = firstOff + 2 * i
            if (o + 1 >= p.size) break
            val v = u16le(p, o)
            if (plausibleRr(v)) out.add(v)
        }
        return out
    }

    /**
     * Realtime (type 0x28), payload offsets from the community examples (28 bytes on the wire, 20-byte payload):
     * [1]=?  [2..5]=unix time  [6..7]=sub-second?  [8]=HR bpm  [9]=RR count  [10..17]=4 x u16 RR ms  [18..19]=?
     * HR and RR: DECODED (the HR value tracks the standard HR service; RR agrees with HR in the historical examples).
     * No temperature / SpO2 / accelerometer in this packet type: nothing in the sources puts them here.
     */
    private fun decodeRealtime(p: ByteArray): Decoded {
        if (p.size < 10) return Decoded("żywe", info = "za krótki (" + p.size + " B)")
        val hr = u8(p, 8)
        return Decoded(
            kind = "żywe",
            epoch = u32le(p, 2),
            hr = if (hr in 25..230) hr else null,
            rr = readRr(p, 9, 10)
        )
    }

    /**
     * Stored record (type 0x2F, 1 Hz). Offsets (payload index) as published by OpenStrap's decoder:
     * [3..6]=counter  [7..10]=unix time  [11..12]=sub-second  [17]=HR (verified by them)  [18]=RR count  [19..26]=RR u16 ms
     * [36..47]=3 x float32 LE accel in g (EMPIRICAL)  [70]=skin temp raw, degrees C = raw / 4 (EMPIRICAL)  [72]=SpO2 % (EMPIRICAL).
     * "Empirical" = inferred from how bytes move (about 1 g at rest, temperature rises when worn), never confirmed.
     * Plausibility ranges below turn nonsense into null instead of showing a wrong number.
     * Note: the published 96-byte example has an 88-byte payload while OpenStrap expects >= 89: possibly an off-by-one in
     * their notes or another firmware layout. The CSV from the user's band will tell.
     */
    private fun decodeHistorical(p: ByteArray): Decoded {
        if (p.size < 19) return Decoded("pamięć", info = "za krótki (" + p.size + " B)")
        val hr = u8(p, 17)
        var tempC: Double? = null
        var tempRaw: Int? = null
        var spo2: Int? = null
        var ax: Double? = null
        var ay: Double? = null
        var az: Double? = null
        if (p.size > 72) {
            tempRaw = u8(p, 70)
            val t = tempRaw / 4.0
            if (t in 20.0..45.0) tempC = t
            val s = u8(p, 72)
            if (s in 50..100) spo2 = s
        }
        if (p.size >= 48) {
            val x = Float.fromBits(u32le(p, 36).toInt()).toDouble()
            val y = Float.fromBits(u32le(p, 40).toInt()).toDouble()
            val z = Float.fromBits(u32le(p, 44).toInt()).toDouble()
            if (ok(x) && ok(y) && ok(z)) {
                ax = x
                ay = y
                az = z
            }
        }
        return Decoded(
            kind = "pamięć",
            epoch = u32le(p, 7),
            hr = if (hr in 25..230) hr else null,
            rr = readRr(p, 18, 19),
            tempC = tempC,
            tempRaw = tempRaw,
            spo2 = spo2,
            ax = ax,
            ay = ay,
            az = az,
            info = "licznik " + u32le(p, 3)
        )
    }

    private fun ok(v: Double): Boolean = !v.isNaN() && !v.isInfinite() && Math.abs(v) <= 16.0

    /**
     * Sync status (type 0x31, header aa1c00ab31 in the examples): [1]=counter [2]=0x02 [3..6]=unix time [13..16]=batch number.
     * We only DISPLAY the batch number. Echoing it back (command 0x17) is the batch ACK, which we never send.
     */
    private fun decodeMetadata(p: ByteArray): Decoded {
        if (p.size >= 17 && u8(p, 2) == 0x02) {
            return Decoded("synchronizacja", epoch = u32le(p, 3), batchNo = u32le(p, 13), info = "numer paczki " + u32le(p, 13))
        }
        return Decoded("synchronizacja", info = "typ 0x31, " + p.size + " B")
    }

    /**
     * Command response (type 0x24, CMD_FROM_STRAP): [1]=seq [2]=command echo [3]=status?. Layout NOT confirmed by any source we could open.
     * For GET_BATTERY (0x1A) we try u16 LE at [4] divided by 10 (percent in tenths) and fall back to u8 at [4] as plain percent.
     * Both are GUESSES: the UI shows the result as "przypuszczalnie" and the CSV keeps the raw bytes.
     */
    private fun decodeResponse(p: ByteArray): Decoded {
        if (p.size < 3) return Decoded("odpowiedź", info = "za krótka")
        val cmd = u8(p, 2)
        if (cmd == SafeCommand.GET_BATTERY.id && p.size >= 6) {
            val tenths = u16le(p, 4) / 10.0
            val plain = u8(p, 4)
            val pct: Double? = if (tenths in 0.0..100.0) tenths else if (plain in 0..100) plain.toDouble() else null
            return Decoded("odpowiedź", batteryPct = pct, info = "bateria: kandydaci u16/10=" + tenths + ", u8=" + plain)
        }
        return Decoded("odpowiedź", info = "polecenie 0x" + Integer.toHexString(cmd))
    }

    /** Event (type 0x30): [1]=counter [2..3]=event id u16 [4..7]=unix time (may not match real time if the band clock is unset). */
    private fun decodeEvent(p: ByteArray): Decoded {
        if (p.size < 8) return Decoded("zdarzenie", info = "za krótkie")
        return Decoded("zdarzenie", epoch = u32le(p, 4), info = "id 0x" + Integer.toHexString(u16le(p, 2)))
    }
}
