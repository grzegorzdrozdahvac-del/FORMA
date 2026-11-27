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
    const val PKT_COMMAND_RESPONSE = 0x24 // seen on a real band: payload = 24 | counter | cmd | seq echo | status | data
    const val PKT_REALTIME = 0x28
    const val PKT_HISTORICAL = 0x2F
    const val PKT_EVENT = 0x30
    const val PKT_METADATA = 0x31
    const val PKT_FW_LOG = 0x32 // plain-text firmware console log records (seen on a real band)

    /** Band clock: a value below this is seconds since band boot (RTC not set), at or above it is Unix seconds. */
    const val UNIX_MIN = 1_000_000_000L

    // ---------- SAFE-READ whitelist ----------
    /**
     * The ONLY commands this app can build (plus the batch ACK below, which has its own gated builder).
     * Anything else (erase 0x19, reboot 0x1D, alarm 0x42, haptics, HR broadcast 0x0E, clock set 0x0A, IMU / raw modes, ...)
     * has no constant here, so it cannot be sent by accident.
     */
    enum class SafeCommand(val id: Int, val value: Int, val label: String) {
        /** 0x03 value 1: start the 1 Hz realtime stream (HR + RR). Confirmed on a real band: answered by a 0x24 response, status 2. */
        START_REALTIME(0x03, 0x01, "start odczytu na żywo"),
        /** 0x03 value 0: the explicit stop-stream. Confirmed on a real band (status 2). */
        STOP_REALTIME(0x03, 0x00, "stop odczytu na żywo"),
        /**
         * 0x23 value 0: GET_HELLO_HARVARD, the handshake / device-info read that the official app sends on connect.
         * One secondary source (zordhalo/whoopsie-protocol) says battery is inside the reply; layout unverified, so the UI only shows candidates.
         * Replaces the battery command 0x1A, which the user's band never answered (9 tries).
         */
        GET_HELLO(0x23, 0x00, "odczyt informacji o opasce (HELLO)"),
        /**
         * 0x22 value 0: GET_DATA_RANGE, "query stored data range / returns current trim offset and batch range" (zordhalo/whoopsie-protocol,
         * AStrasler/whoop4-ble, b-nnett/goose PR 26: gen4 needs one 0x00 argument byte). Read-only per all three. Request frame example from
         * whoopsie, seq 2: aa0800a823022200824df537 (CRC verified by our own code). The reply layout is NOT documented: see parseDataRange().
         */
        GET_DATA_RANGE(0x22, 0x00, "odczyt zakresu danych w pamięci"),
        /** 0x16 value 0: ask the band to send its stored records (SEND_HISTORICAL_DATA). Answered by 0x24 (status 2), then 0x31 / 0x2F / 0x32 packets. */
        REQUEST_HISTORY(0x16, 0x00, "prośba o dane z pamięci")
    }

    /** Opcode of HISTORICAL_DATA_RESULT, the batch ACK. Only buildBatchAck() makes it, and only for a parsed batch-end packet. */
    const val CMD_BATCH_ACK = 0x17

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

    // ---------- batch end / ACK ----------
    /**
     * HISTORY_END metadata packet (type 0x31, sub-type 2), payload layout (checked on a real band and on the community example):
     * [0]=0x31 [1]=counter [2]=0x02 [3..6]=band time [7..12]=? [13..16]=trim/batch number u32 LE [17..23]=? (04 00.. or 38 00.. seen).
     * `tail` = payload[13..20] (8 bytes) as sent by the band.
     */
    class BatchEnd(val counter: Int, val bandTime: Long, val trim: Long, val tail: ByteArray)

    fun parseBatchEnd(p: ByteArray): BatchEnd? {
        return try {
            if (p.size < 17 || u8(p, 0) != PKT_METADATA || u8(p, 2) != 0x02) return null
            val tail = p.copyOfRange(13, minOf(p.size, 21))
            BatchEnd(u8(p, 1), u32le(p, 3), u32le(p, 13), tail)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Batch ACK (command 0x17): payload = 23 | seq | 17 | 01 | trim (u32 LE) | 4 trailing bytes. 12-byte payload, so the frame is
     * 20 bytes with header aa 10 00 57.
     * Sources: reverse-engineering-whoop-post ("aa10005723{count}1701{batch}00000000{crc}"), AStrasler/whoop4-ble docs
     * (ACK = [01][LE u32 trim][00 00 00 00], trim read at byte 10 of the metadata data = payload[13]), ZHOOP ("[01] + end_data").
     * Default trailing bytes: zeros. echoTail = true sends the band's own 4 bytes after the trim instead (variant described by
     * zordhalo/whoopsie-protocol); the app falls back to it only if the band replays a batch after a zero-tail ACK.
     */
    fun buildBatchAck(end: BatchEnd, seq: Int, echoTail: Boolean): ByteArray {
        val payload = ByteArray(12)
        payload[0] = PKT_COMMAND.toByte()
        payload[1] = (seq and 0xFF).toByte()
        payload[2] = CMD_BATCH_ACK.toByte()
        payload[3] = 0x01
        for (i in 0 until 4) payload[4 + i] = ((end.trim shr (8 * i)) and 0xFF).toByte()
        if (echoTail && end.tail.size >= 8) {
            for (i in 0 until 4) payload[8 + i] = end.tail[4 + i]
        }
        return frame(payload)
    }

    // ---------- skip of the stored backlog ----------
    /**
     * ACK for a trim value that did not come from a batch-end packet (used once, to fast-forward over an old backlog).
     * Same 20-byte frame as buildBatchAck with zero trailing bytes (the zero-tail form was accepted by the band 245+ times, status 1).
     * Refuses a trim outside 1..0x0FFFFFFF.
     */
    fun buildSkipAck(trim: Long, seq: Int): ByteArray? {
        if (trim < 1L || trim > 0x0FFFFFFFL) return null
        return buildBatchAck(BatchEnd(0, 0L, trim, ByteArray(8)), seq, false)
    }

    class DataRange(val lastSynced: Long, val newest: Long, val offset: Int)

    /** reason is empty on success. u32s lists every little-endian u32 of the reply data for the log / CSV. */
    class RangeResult(val range: DataRange?, val reason: String, val u32s: String)

    /**
     * GET_DATA_RANGE reply (type 0x24, cmd 0x22): [1]=counter [2]=0x22 [3]=seq echo [4]=status [5..]=data. The layout of the data is NOT
     * documented anywhere we could read (goose PR 26 only says "last_synced" sits near payload[10..14] and a "page_current" value exists,
     * e.g. 34274 and 61458 on their band). So the layout is identified from the band's own numbers instead of being assumed:
     * we look for an adjacent pair of u32 values (a, b) where ONE equals our last acknowledged trim (anchor .. anchor + 48, the band's
     * "last synced" pointer) and the OTHER is larger and below 0x10000000: that larger one is the newest record index.
     * Success only when exactly one such pair (one distinct newest value) exists; otherwise the caller must not skip.
     */
    fun parseDataRange(resp: ByteArray, anchor: Long): RangeResult {
        return try {
            val sb = StringBuilder()
            var o = 5
            while (o + 4 <= resp.size) {
                sb.append('[').append(o).append("]=").append(u32le(resp, o)).append(' ')
                o += 1
            }
            if (anchor < 0L) return RangeResult(null, "brak punktu odniesienia (nie znamy ostatniego potwierdzonego numeru paczki)", sb.toString())
            val found = ArrayList<DataRange>()
            var i = 5
            while (i + 8 <= resp.size) {
                val a = u32le(resp, i)
                val b = u32le(resp, i + 4)
                if (a >= anchor && a <= anchor + 48L && b > a && b < 0x10000000L) found.add(DataRange(a, b, i))
                if (b >= anchor && b <= anchor + 48L && a > b && a < 0x10000000L) found.add(DataRange(b, a, i))
                i += 1
            }
            if (found.isEmpty()) return RangeResult(null, "w odpowiedzi nie ma pary liczb pasującej do ostatniego potwierdzenia ($anchor)", sb.toString())
            var newest = found[0].newest
            for (r in found) if (r.newest != newest) return RangeResult(null, "odpowiedź niejednoznaczna", sb.toString())
            RangeResult(found[0], "", sb.toString())
        } catch (t: Throwable) {
            RangeResult(null, "błąd analizy odpowiedzi", "")
        }
    }

    // ---------- band clock -> phone clock ----------
    /**
     * Live 0x28 packets carry a band-seconds counter (uptime when the band RTC is unset). Comparing it with the phone receive time
     * gives an offset; the minimum over a recent window is used because delivery delay only ever makes the difference larger.
     * Values >= UNIX_MIN are Unix seconds already and need no offset. A band reboot (counter jumps back) clears the window.
     * Records stored before the current boot cannot be mapped correctly (their uptime base is gone): callers label results "przybliżony".
     */
    class TimeMap {
        private val window = ArrayDeque<Long>()
        private var lastCounter = -1L

        fun observe(counter: Long, rxMs: Long) {
            if (counter <= 0L || counter >= UNIX_MIN) return
            if (lastCounter >= 0L && counter + 5L < lastCounter) window.clear()
            lastCounter = counter
            window.addLast(rxMs / 1000L - counter)
            while (window.size > 30) window.removeFirst()
        }

        fun hasOffset(): Boolean = window.isNotEmpty()

        /** Phone time in ms, or null when ts is uptime and no live packet has been seen yet. */
        fun toPhoneMs(ts: Long): Long? {
            if (ts >= UNIX_MIN) return ts * 1000L
            if (window.isEmpty()) return null
            var m = Long.MAX_VALUE
            for (v in window) if (v < m) m = v
            return (ts + m) * 1000L
        }
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
        val cmd: Int? = null,
        val seqEcho: Int? = null,
        val status: Int? = null,
        val metaSub: Int? = null,
        val batchEnd: BatchEnd? = null,
        val text: String? = null,
        val recCounter: Long? = null,
        val eventId: Int? = null,
        val raw: ByteArray? = null,
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
                PKT_FW_LOG -> decodeLog(p)
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
            recCounter = u32le(p, 3),
            info = "licznik " + u32le(p, 3)
        )
    }

    private fun ok(v: Double): Boolean = !v.isNaN() && !v.isInfinite() && Math.abs(v) <= 16.0

    /**
     * Sync metadata (type 0x31): [1]=counter [2]=sub-type: 1 = HISTORY_START, 2 = HISTORY_END, 3 = HISTORY_COMPLETE (names from community notes),
     * [3..6]=band time. Seen on a real band: a sub-type 3 packet can arrive BEFORE a start, so it is informational only.
     * For sub-type 2 see BatchEnd (trim / batch number at [13..16]).
     */
    private fun decodeMetadata(p: ByteArray): Decoded {
        if (p.size < 7) return Decoded("synchronizacja", info = "typ 0x31, " + p.size + " B")
        val sub = u8(p, 2)
        val ts = u32le(p, 3)
        val end = if (sub == 2) parseBatchEnd(p) else null
        val name = when (sub) {
            1 -> "początek paczki"
            2 -> "koniec paczki"
            3 -> "komunikat 'zakończono'"
            else -> "podtyp $sub"
        }
        return Decoded(
            kind = "synchronizacja",
            epoch = ts,
            metaSub = sub,
            batchEnd = end,
            batchNo = end?.trim,
            info = name + (if (end != null) ", numer paczki " + end.trim else "")
        )
    }

    /**
     * Command response (type 0x24, CMD_FROM_STRAP), layout CONFIRMED on a real band: [1]=counter [2]=command id [3]=echo of our seq
     * [4]=status (2 on every accepted command seen: 0x03, 0x16) [5..]=data (0x16: 0b 00 00).
     * No response was ever seen for 0x1A (battery).
     * For HELLO (0x23) a secondary source puts battery as u32 LE / 10 somewhere near the start of the data: we only report candidates
     * for three possible offsets in `info`, never as a value.
     */
    private fun decodeResponse(p: ByteArray): Decoded {
        if (p.size < 5) return Decoded("odpowiedź", info = "za krótka")
        val cmd = u8(p, 2)
        val echo = u8(p, 3)
        val st = u8(p, 4)
        var info = "polecenie 0x" + Integer.toHexString(cmd) + ", status " + st
        if (cmd == SafeCommand.GET_HELLO.id) {
            val cands = StringBuilder()
            for (o in intArrayOf(4, 5, 6)) {
                if (o + 4 <= p.size) cands.append(" [").append(o).append("]=").append(u32le(p, o) / 10.0)
            }
            info += "; HELLO " + p.size + " B, kandydaci bateria u32/10:" + cands
        }
        return Decoded("odpowiedź", cmd = cmd, seqEcho = echo, status = st, raw = p.copyOf(), info = info)
    }

    /**
     * Event (type 0x30): [1]=counter [2..3]=event id u16 [4..7]=band time [8..]=data.
     * Event 0x03 BATTERY_LEVEL: u32 LE / 10 percent; event 0x11 TEMPERATURE_LEVEL: i16 LE / 10 degrees C (zordhalo/whoopsie-protocol,
     * AStrasler/whoop4-ble confirms that temperature arrives as an event). Both are GUESSES for the user's firmware and range-checked.
     */
    private fun decodeEvent(p: ByteArray): Decoded {
        if (p.size < 8) return Decoded("zdarzenie", info = "za krótkie")
        val id = u16le(p, 2)
        var battery: Double? = null
        var temp: Double? = null
        if (id == 0x03 && p.size >= 12) {
            val v = u32le(p, 8) / 10.0
            if (v in 0.0..100.0) battery = v
        }
        if (id == 0x11 && p.size >= 10) {
            var raw = u16le(p, 8)
            if (raw > 32767) raw -= 65536
            val t = raw / 10.0
            if (t in 20.0..45.0) temp = t
        }
        return Decoded("zdarzenie", epoch = u32le(p, 4), eventId = id, batteryPct = battery, tempC = temp, info = "id 0x" + Integer.toHexString(id))
    }

    /**
     * Firmware console log (type 0x32, seen on a real band): [1..2]=record counter u16 LE [4..7]=timestamp [8..11]=sub-second
     * [12]=flags [13..]=ASCII text, NUL terminated (a log line is cut into ~46-byte fragments across records).
     */
    private fun decodeLog(p: ByteArray): Decoded {
        if (p.size < 14) return Decoded("dziennik", info = "za krótki")
        val sb = StringBuilder()
        var i = 13
        while (i < p.size) {
            val c = u8(p, i)
            if (c == 0) break
            sb.append(if (c == 10) ' ' else if (c in 32..126) c.toChar() else '?')
            i++
        }
        return Decoded("dziennik", epoch = u32le(p, 4), recCounter = u16le(p, 1).toLong(), text = sb.toString(), info = sb.toString())
    }
}

/**
 * Archive of decoded stored records: per-record JSON lines and a per-day summary CSV. Pure Kotlin (no Android classes).
 *
 * DAILY CSV COLUMNS (stable, in this order; see WHOOP-EXT-NOTES.md):
 *   date          local date yyyy-MM-dd of the record time (phone time zone)
 *   clock         "unix" = band stamped the record with Unix seconds; "uptime" = band seconds-since-boot mapped to phone time (approximate)
 *   records       number of unique 0x2F records that day
 *   first_ts,last_ts   first / last record time, Unix seconds
 *   hr_min,hr_avg      heart rate over the whole day (records with a plausible HR)
 *   hr_rest_est        lowest 5-minute average HR in the night window 00:00-06:00 local (buckets need >= 30 samples)
 *   night_records      records inside 00:00-06:00 local
 *   rmssd_ms,ln_rmssd  RMSSD of night RR (rules below); empty when fewer than 30 usable successive differences
 *   rr_used            number of night RR values kept after cleaning
 *   temp_avg_c         mean skin temperature, EMPIRICAL (record byte 70 / 4), empty if no plausible values
 *   spo2_avg           mean SpO2, EMPIRICAL (record byte 72), empty if none
 *   motion_min         minutes with motion, EMPIRICAL: a clock minute (>= 10 samples) counts when max - min of |accel| exceeds 0.10 g
 * RR cleaning: keep 300..2000 ms, then drop a beat that deviates > 20 percent from the median of its 5 neighbouring beats (itself included);
 * RMSSD uses only differences between consecutive kept beats whose record times are <= 3 s apart.
 */
object WhoopArchive {
    const val DAILY_HEADER = "date,clock,records,first_ts,last_ts,hr_min,hr_avg,hr_rest_est,night_records,rmssd_ms,ln_rmssd,rr_used,temp_avg_c,spo2_avg,motion_min"

    /** One decoded stored record. tMs = phone-clock milliseconds (null = uptime stamp that could not be mapped). clk = "unix" | "uptime". */
    class Rec(
        val tMs: Long?, val raw: Long, val clk: String, val ctr: Long, val hr: Int?, val rr: List<Int>,
        val tempC: Double?, val tempRaw: Int?, val spo2: Int?, val ax: Double?, val ay: Double?, val az: Double?,
        val rxMs: Long? = null
    )

    private fun num(v: Double): String = java.lang.Double.toString(Math.round(v * 10000.0) / 10000.0)

    fun toJson(r: Rec): String {
        val sb = StringBuilder(160)
        sb.append("{\"clk\":\"").append(r.clk).append("\",\"raw\":").append(r.raw).append(",\"ctr\":").append(r.ctr)
        if (r.tMs != null) sb.append(",\"t\":").append(r.tMs)
        if (r.rxMs != null) sb.append(",\"rx\":").append(r.rxMs)
        if (r.hr != null) sb.append(",\"hr\":").append(r.hr)
        if (r.rr.isNotEmpty()) sb.append(",\"rr\":[").append(r.rr.joinToString(",")).append("]")
        if (r.tempC != null) sb.append(",\"temp\":").append(num(r.tempC))
        if (r.tempRaw != null) sb.append(",\"tr\":").append(r.tempRaw)
        if (r.spo2 != null) sb.append(",\"spo2\":").append(r.spo2)
        if (r.ax != null && r.ay != null && r.az != null) {
            sb.append(",\"ax\":").append(num(r.ax)).append(",\"ay\":").append(num(r.ay)).append(",\"az\":").append(num(r.az))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun field(line: String, key: String): String? {
        val k = "\"" + key + "\":"
        val i = line.indexOf(k)
        if (i < 0) return null
        var j = i + k.length
        val st = j
        while (j < line.length && line[j] != ',' && line[j] != '}' && line[j] != ']') j++
        return line.substring(st, j).trim('"')
    }

    /** Reads one line written by toJson. Never throws; null for a damaged line. */
    fun parseJson(line: String): Rec? {
        return try {
            val raw = field(line, "raw")?.toLong() ?: return null
            val clk = field(line, "clk") ?: return null
            val rr = ArrayList<Int>()
            val ri = line.indexOf("\"rr\":[")
            if (ri >= 0) {
                val e = line.indexOf(']', ri)
                if (e > ri) for (x in line.substring(ri + 6, e).split(',')) { val v = x.trim().toIntOrNull(); if (v != null) rr.add(v) }
            }
            Rec(
                field(line, "t")?.toLong(), raw, clk, field(line, "ctr")?.toLong() ?: 0L, field(line, "hr")?.toInt(), rr,
                field(line, "temp")?.toDouble(), field(line, "tr")?.toInt(), field(line, "spo2")?.toInt(),
                field(line, "ax")?.toDouble(), field(line, "ay")?.toDouble(), field(line, "az")?.toDouble(),
                field(line, "rx")?.toLong()
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** Local-time helpers (phone time zone at that instant). */
    private fun localMs(tMs: Long): Long = tMs + java.util.TimeZone.getDefault().getOffset(tMs)

    private val dateCache = HashMap<Long, String>()

    fun dateOf(tMs: Long): String {
        val day = Math.floorDiv(localMs(tMs), 86400000L)
        val hit = dateCache[day]
        if (hit != null) return hit
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val v = f.format(java.util.Date(day * 86400000L))
        dateCache[day] = v
        return v
    }

    /** Accumulates one (date, clock) group. Feed records in any order; duplicates (same raw stamp and counter) are ignored. */
    class DayAcc(val date: String, val clk: String) {
        private val seen = HashSet<Long>()
        private var n = 0
        private var first = Long.MAX_VALUE
        private var last = Long.MIN_VALUE
        private var hrMin = Int.MAX_VALUE
        private var hrSum = 0L
        private var hrN = 0
        private var nightN = 0
        private val nightBuckets = HashMap<Int, LongArray>()
        private val nightRr = ArrayList<Long>()
        private var tempSum = 0.0
        private var tempN = 0
        private var spoSum = 0L
        private var spoN = 0
        private val magMin = DoubleArray(1440) { Double.POSITIVE_INFINITY }
        private val magMax = DoubleArray(1440) { Double.NEGATIVE_INFINITY }
        private val magN = IntArray(1440)

        fun records(): Int = n

        fun add(r: Rec) {
            val t = r.tMs ?: return
            if (!seen.add((r.raw shl 20) xor (r.ctr and 0xFFFFF))) return
            n++
            if (t < first) first = t
            if (t > last) last = t
            val lm = localMs(t)
            val minuteOfDay = (((lm / 60000L) % 1440L) + 1440L).toInt() % 1440
            val hr = r.hr
            if (hr != null && hr in 25..230) {
                if (hr < hrMin) hrMin = hr
                hrSum += hr
                hrN++
                if (minuteOfDay < 360) {
                    val b = nightBuckets.getOrPut(minuteOfDay / 5) { LongArray(2) }
                    b[0] += hr.toLong()
                    b[1] += 1L
                }
            }
            if (minuteOfDay < 360) {
                nightN++
                val tSec = t / 1000L
                for (k in 0 until minOf(r.rr.size, 4)) {
                    nightRr.add((tSec shl 24) or (k.toLong() shl 16) or (r.rr[k].toLong() and 0xFFFFL))
                }
            }
            val tc = r.tempC
            if (tc != null) { tempSum += tc; tempN++ }
            val sp = r.spo2
            if (sp != null) { spoSum += sp; spoN++ }
            if (r.ax != null && r.ay != null && r.az != null) {
                val m = Math.sqrt(r.ax * r.ax + r.ay * r.ay + r.az * r.az)
                if (m.isFinite()) {
                    if (m < magMin[minuteOfDay]) magMin[minuteOfDay] = m
                    if (m > magMax[minuteOfDay]) magMax[minuteOfDay] = m
                    magN[minuteOfDay]++
                }
            }
        }

        private fun f(v: Double, d: Int): String = String.format(java.util.Locale.US, "%." + d + "f", v)

        /** Returns {rmssd or NaN, rr kept}. */
        private fun rmssd(): DoubleArray {
            nightRr.sort()
            val tS = ArrayList<Long>()
            val v = ArrayList<Int>()
            for (x in nightRr) {
                val rr = (x and 0xFFFFL).toInt()
                if (rr in 300..2000) { tS.add(x shr 24); v.add(rr) }
            }
            val keepT = ArrayList<Long>()
            val keepV = ArrayList<Int>()
            for (i in 0 until v.size) {
                val lo = maxOf(0, i - 2)
                val hi = minOf(v.size - 1, i + 2)
                val w = ArrayList<Int>()
                for (j in lo..hi) w.add(v[j])
                w.sort()
                val med = w[w.size / 2].toDouble()
                if (Math.abs(v[i] - med) / med <= 0.20) { keepT.add(tS[i]); keepV.add(v[i]) }
            }
            var sum = 0.0
            var cnt = 0
            for (i in 1 until keepV.size) {
                if (keepT[i] - keepT[i - 1] <= 3L) {
                    val d = (keepV[i] - keepV[i - 1]).toDouble()
                    sum += d * d
                    cnt++
                }
            }
            return doubleArrayOf(if (cnt >= 30) Math.sqrt(sum / cnt) else Double.NaN, keepV.size.toDouble())
        }

        fun row(): String {
            var rest = Double.NaN
            for (b in nightBuckets.values) if (b[1] >= 30L) { val a = b[0].toDouble() / b[1]; if (rest.isNaN() || a < rest) rest = a }
            val r = rmssd()
            var motion = 0
            for (i in 0 until 1440) if (magN[i] >= 10 && magMax[i] - magMin[i] > 0.10) motion++
            val sb = StringBuilder()
            sb.append(date).append(',').append(clk).append(',').append(n).append(',')
            sb.append(if (n > 0) (first / 1000L).toString() else "").append(',').append(if (n > 0) (last / 1000L).toString() else "").append(',')
            sb.append(if (hrN > 0) hrMin.toString() else "").append(',').append(if (hrN > 0) f(hrSum.toDouble() / hrN, 1) else "").append(',')
            sb.append(if (rest.isNaN()) "" else f(rest, 1)).append(',').append(nightN).append(',')
            sb.append(if (r[0].isNaN()) "" else f(r[0], 1)).append(',').append(if (r[0].isNaN()) "" else f(Math.log(r[0]), 3)).append(',')
            sb.append(r[1].toInt()).append(',')
            sb.append(if (tempN > 0) f(tempSum / tempN, 2) else "").append(',').append(if (spoN > 0) f(spoSum.toDouble() / spoN, 1) else "").append(',')
            sb.append(motion)
            return sb.toString()
        }
    }
}
