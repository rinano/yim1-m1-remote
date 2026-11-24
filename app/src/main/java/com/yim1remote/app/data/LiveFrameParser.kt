package com.yim1remote.app.data

/**
 * Content of one live-view frame: the JSON camera-state block plus the JPEG
 * preview bytes.
 */
data class ParsedLiveFrame(
    val paramsJson: String?,
    val jpeg: ByteArray,
    /** Camera frame counter if the framing exposes one (diagnostics only). */
    val frameIndex: Long = -1L,
) {
    /** Starts with the JPEG SOI marker `FF D8`. */
    val hasJpegHeader: Boolean
        get() = jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()

    /** Ends with the JPEG EOI marker `FF D9`. */
    val hasJpegFooter: Boolean
        get() = jpeg.size >= 2 &&
            jpeg[jpeg.size - 2] == 0xFF.toByte() &&
            jpeg[jpeg.size - 1] == 0xD9.toByte()

    /** Basic sanity check before asking `BitmapFactory` to decode. */
    fun isDecodableJpeg(requireEoi: Boolean = false): Boolean =
        hasJpegHeader && (!requireEoi || hasJpegFooter) && jpeg.size >= 32
}

/**
 * Pure parser for the YI M1 UDP live-view stream (no Android classes, fully
 * unit-testable; [LiveViewReceiver] only binds the socket and decodes the JPEG).
 *
 * Two wire formats exist, both delivering the same content — an 800×600 JPEG
 * preview plus a plaintext JSON snapshot of the camera state. The app has no
 * BLE, so it cannot read the firmware version the official app used to choose a
 * path; [classifyDatagram] decides per datagram from the header words
 * (verified in `research/verified/liveview-udp.md` §3):
 *
 * ```
 * w0 = u32be(0)  w1 = u32be(4)  w2 = u32be(8)
 * if (0 < w1 <= 1024 && w2 < w1) -> FORMAT_B (fragmented, firmware >= 3.2)
 * if (w2 == 0x79CE4283 || w1 > 1024 || datagram > 1500 bytes) -> FORMAT_A (whole frame)
 * ```
 *
 * ## Format A — whole-frame datagram (real 3.1-cn capture, `liveview-udp.md` §1)
 * ```
 * 0     4   u32be frameIndex        +1 per frame
 * 4     4   u32be timestamp         +3003 per frame (≈90 kHz, ~30 fps)
 * 8     4   u32be session marker    constant 0x79CE4283
 * 12    224 binary parameter table  proprietary/undecoded — do not parse
 * 236   2048 JSON state region      '{' object + NUL padding
 * 2284  ... JPEG                    SOI FF D8 FF … EOI FF D9 at end of datagram
 * ```
 * The 236 offset is what the official APK computes:
 * `extLength = u16be(data[14..15]); jsonOffset = 16 + extLength*4` (for the
 * capture `u16be == 55` → 236). Because a firmware revision may shift it, the
 * parser validates the APK offset and falls back to scanning for the first `{`
 * after the 12-byte header, brace-matching the JSON, then finding the JPEG by
 * marker. The mock server's simpler `[12-byte header][2048 JSON][JPEG]` frames
 * are handled by the same fallback.
 *
 * ## Format B — fragmented "small packets" (firmware >= 3.2, `liveview-udp.md` §2)
 * ```
 * 0   u32be frameIndex
 * 4   u32be totalPackets   (upper bound 1024)
 * 8   u32be packetIndex    0-based
 * 12  payload chunk        ~1.4 KB typical
 * ```
 * Reassembled frame = `[2048-byte NUL-padded JSON][JPEG]` (JSON starts at 0).
 * [MultiPacketAssembler] implements the hardened two-frame-window reassembly:
 * tolerates reordering, drops incomplete older frames when a newer one
 * completes, sanity-bounds `totalPackets`.
 *
 * In both formats the JSON is NUL-padded, so the parser brace-matches instead of
 * assuming a JSON length. Malformed datagrams return null and are counted as
 * dropped by the receiver.
 */
object LiveFrameParser {

    /** The camera always puts its JSON state block in a fixed 2048-byte region. */
    const val PARAM_BLOCK_SIZE = 2048

    /** Format B datagrams are ≤ 1500 bytes; Format A whole frames are ~20 KB. */
    const val MULTI_PACKET_MAX_DATAGRAM = 1500

    /** `u32be` word #3 of every Format A datagram (verified 40/40 frames). */
    const val FORMAT_A_SESSION_MARKER = 0x79CE4283L

    /** Hard sanity bound for `totalPackets` in a Format B header. */
    const val FORMAT_B_MAX_PACKETS = 1024

    /** Native frame capacity was 102400 bytes; be tolerant but bounded. */
    const val MAX_ASSEMBLED_FRAME_BYTES = 300 * 1024

    /** Header size of a Format A / Format B datagram. */
    const val FORMAT_A_HEADER_SIZE = 12
    const val FORMAT_B_HEADER_SIZE = 12

    /** Wire format a datagram belongs to. */
    enum class LiveViewFormat { FORMAT_A, FORMAT_B, UNKNOWN }

    // ---------------------------------------------------------------- detect

    /**
     * Detects the live-view wire format from a datagram's first 12 bytes
     * (verified heuristic, `research/verified/liveview-udp.md` §3).
     */
    fun classifyDatagram(data: ByteArray, length: Int = data.size): LiveViewFormat {
        if (length < FORMAT_A_HEADER_SIZE) return LiveViewFormat.UNKNOWN
        val word1 = readU32BigEndian(data, 4)
        val word2 = readU32BigEndian(data, 8)
        if (length <= MULTI_PACKET_MAX_DATAGRAM &&
            0 < word1 && word1 <= FORMAT_B_MAX_PACKETS && word2 < word1
        ) {
            return LiveViewFormat.FORMAT_B
        }
        if (word2 == FORMAT_A_SESSION_MARKER || word1 > FORMAT_B_MAX_PACKETS ||
            length > MULTI_PACKET_MAX_DATAGRAM
        ) {
            return LiveViewFormat.FORMAT_A
        }
        return LiveViewFormat.UNKNOWN
    }

    // -------------------------------------------------------------- format A

    /**
     * The JSON-state offset computed by the official APK's `LiveView.a(byte[])`
     * header math, or `-1` when the buffer is too short.
     *
     * The "RTP-like" fields are actually the little-endian-looking top bytes of
     * the big-endian `frameIndex` word; for the verified capture
     * (`frameIndex = 0x90E00E3A`) `byte0 = 0x90` sets the marker bit and
     * `byte1 = 0xE0` gives payload type 96, which triggers the extension branch
     * exactly as the APK expects.
     */
    fun apkJsonOffset(data: ByteArray, length: Int = data.size): Int {
        if (length < 16) return -1
        val byte0 = data[0].toInt() and 0xFF
        val csrcCount = byte0 and 0x0F
        val marker = (byte0 ushr 4) and 0x01
        val extensionByte = (byte0 ushr 5) and 0x01
        val payloadType = (data[1].toInt() and 0xFF) and 0x7F
        var headerLength = csrcCount * 4 + 12 + extensionByte
        if (marker == 1 && payloadType >= 96) {
            val offset = headerLength + 2
            if (length < offset + 2) return -1
            val extraWords = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            headerLength = offset + 2 + extraWords * 4
        }
        return headerLength
    }

    /**
     * Parses one whole-frame Format A datagram.
     *
     * Strategy (verified + defensive):
     * 1. compute the APK's JSON offset and accept it when a JSON object starts there;
     * 2. otherwise scan for the first `{` after the 12-byte header and brace-match;
     * 3. prefer `jsonStart + 2048` as the JPEG offset (verified 40/40), else scan
     *    for `FF D8 FF`.
     */
    fun parseFormatADatagram(data: ByteArray, length: Int = data.size): ParsedLiveFrame? {
        if (length < FORMAT_A_HEADER_SIZE + 2) return null
        val frameIndex = readU32BigEndian(data, 0)
        var jsonStart = apkJsonOffset(data, length)
        var jsonSpan: IntRange? = if (jsonStart in FORMAT_A_HEADER_SIZE until length) {
            findJsonObject(data, jsonStart, minOf(length, jsonStart + PARAM_BLOCK_SIZE + 64))
        } else {
            null
        }
        if (jsonSpan == null) {
            val scanLimit = minOf(length, FORMAT_A_HEADER_SIZE + 4096)
            var index = FORMAT_A_HEADER_SIZE
            while (index < scanLimit) {
                if (data[index] == '{'.code.toByte()) {
                    val span = findJsonObject(data, index, minOf(length, index + PARAM_BLOCK_SIZE + 64))
                    if (span != null) {
                        jsonStart = index
                        jsonSpan = span
                        break
                    }
                }
                index++
            }
        }
        val span = jsonSpan ?: return null
        val paramsJson = String(data, span.first, span.last - span.first + 1, Charsets.UTF_8)
        var jpegStart = jsonStart + PARAM_BLOCK_SIZE
        if (jpegStart > length - 2 || !isJpegSoi(data, jpegStart)) {
            jpegStart = findJpegMarker(data, span.last + 1, length)
        }
        if (jpegStart < 0) jpegStart = findJpegMarker(data, jsonStart, length)
        if (jpegStart < 0 || jpegStart > length - 2) return null
        if (!isJpegSoi(data, jpegStart)) return null
        return ParsedLiveFrame(
            paramsJson = paramsJson,
            jpeg = data.copyOfRange(jpegStart, length),
            frameIndex = frameIndex,
        )
    }

    // -------------------------------------------------------------- format B

    /**
     * Splits a reassembled Format B payload into state JSON + JPEG.
     *
     * The JSON is the first 2048-byte NUL-padded region (verify by brace-match),
     * the JPEG follows at offset 2048. A marker scan is the fallback for firmware
     * that shifts the boundary. A missing EOI means a corrupted assembly and the
     * frame is rejected.
     */
    fun parseFormatBPayload(payload: ByteArray): ParsedLiveFrame? {
        if (payload.size < PARAM_BLOCK_SIZE + 2) return null
        var jsonStart = 0
        var jsonSpan: IntRange? = findJsonObject(
            payload,
            0,
            minOf(payload.size, PARAM_BLOCK_SIZE + 64),
        )
        if (jsonSpan == null) {
            val scanLimit = minOf(payload.size, 4096)
            var index = 0
            while (index < scanLimit) {
                if (payload[index] == '{'.code.toByte()) {
                    val span = findJsonObject(payload, index, minOf(payload.size, index + PARAM_BLOCK_SIZE + 64))
                    if (span != null) {
                        jsonStart = index
                        jsonSpan = span
                        break
                    }
                }
                index++
            }
        }
        val span = jsonSpan ?: return null
        val paramsJson = String(payload, span.first, span.last - span.first + 1, Charsets.UTF_8)
        var jpegStart = PARAM_BLOCK_SIZE
        if (jpegStart > payload.size - 2 || !isJpegSoi(payload, jpegStart)) {
            jpegStart = findJpegMarker(payload, span.last + 1, payload.size)
        }
        if (jpegStart < 0) jpegStart = findJpegMarker(payload, jsonStart, payload.size)
        if (jpegStart < 0 || jpegStart > payload.size - 2) return null
        if (!isJpegSoi(payload, jpegStart)) return null
        if (!isJpegEoi(payload, jpegStart, payload.size - jpegStart)) return null
        return ParsedLiveFrame(
            paramsJson = paramsJson,
            jpeg = payload.copyOfRange(jpegStart, payload.size),
            frameIndex = -1L,
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Brace-matches a JSON object starting at [start].
     *
     * @return inclusive byte range of the object (`{ … }`), or null when the
     *         buffer does not contain a balanced object inside [limitExclusive].
     *         Quotes and backslash escapes are respected, so braces inside
     *         strings do not confuse the matcher.
     */
    fun findJsonObject(data: ByteArray, start: Int, limitExclusive: Int): IntRange? {
        if (start < 0 || start >= data.size || start >= limitExclusive) return null
        if (data[start] != '{'.code.toByte()) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < limitExclusive && index < data.size) {
            val value = data[index].toInt() and 0xFF
            if (inString) {
                when {
                    escaped -> escaped = false
                    value == '\\'.code -> escaped = true
                    value == '"'.code.toInt() -> inString = false
                }
            } else {
                when (value) {
                    '{'.code -> depth++
                    '}'.code -> {
                        depth--
                        if (depth == 0) return start..index
                    }
                    '"'.code -> inString = true
                }
            }
            index++
        }
        return null
    }

    /** Finds the SOI marker `FF D8 FF` in `[from, to)`, or -1. */
    fun findJpegMarker(data: ByteArray, from: Int, to: Int): Int {
        var index = from.coerceAtLeast(0)
        val end = minOf(to, data.size) - 2
        while (index < end) {
            if (data[index] == 0xFF.toByte() &&
                data[index + 1] == 0xD8.toByte() &&
                data[index + 2] == 0xFF.toByte()
            ) {
                return index
            }
            index++
        }
        return -1
    }

    fun isJpegSoi(data: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + 2 <= data.size &&
            data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte()

    fun isJpegEoi(data: ByteArray, offset: Int, length: Int): Boolean =
        length >= 2 && offset >= 0 && offset + length <= data.size &&
            data[offset + length - 2] == 0xFF.toByte() &&
            data[offset + length - 1] == 0xD9.toByte()

    /** Big-endian unsigned 32-bit read (header words). */
    fun readU32BigEndian(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) return -1L
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}

/**
 * Reassembles Format B (firmware >= 3.2) fragmented frames.
 *
 * Hardened behaviour verified from the reference implementations
 * (`LiveViewReceiver.swift:262-320`, `camera_session.py:1033-1196`, see
 * `research/verified/liveview-udp.md` §2):
 *
 *  * datagrams are `{u32be frameIndex, u32be totalPackets, u32be packetIndex, payload}`;
 *  * chunks are collected by index regardless of arrival order → reordering is safe;
 *  * a **two-frame window** is kept: if a third frame starts, the oldest
 *    incomplete frame is dropped;
 *  * duplicates are ignored;
 *  * `totalPackets` is sanity-bounded (≤ 1024) and the assembled frame size is
 *    capped (300 KiB) so corrupt headers cannot allocate unbounded memory;
 *  * an older incomplete frame is dropped as soon as a newer frame completes.
 *
 * Not thread-safe: feed it from the single UDP receive loop only.
 */
class MultiPacketAssembler {

    private class PartialFrame(val frameIndex: Long, val totalPackets: Int) {
        val chunks: Array<ByteArray?> = arrayOfNulls(totalPackets)
        var receivedCount: Int = 0
        var byteCount: Int = 0
    }

    var completedFrames: Int = 0
        private set
    var droppedFrames: Int = 0
        private set
    var lastCompletedFrameIndex: Long = -1L
        private set

    /** Frame index of the frame currently being assembled (diagnostics). */
    var currentFrameIndex: Long = -1L
        private set

    private val window = LinkedHashMap<Long, PartialFrame>()

    fun reset() {
        window.clear()
        currentFrameIndex = -1L
    }

    /**
     * Feeds one datagram.
     *
     * @return the assembled `[2048 JSON][JPEG]` payload when this datagram
     *         completed a frame, otherwise null.
     */
    fun feed(data: ByteArray, length: Int = data.size): ByteArray? {
        if (length <= LiveFrameParser.FORMAT_B_HEADER_SIZE) return null
        val frameIndex = LiveFrameParser.readU32BigEndian(data, 0)
        val totalPackets = LiveFrameParser.readU32BigEndian(data, 4)
        val packetIndex = LiveFrameParser.readU32BigEndian(data, 8)
        if (frameIndex < 0 || totalPackets <= 0 ||
            totalPackets > LiveFrameParser.FORMAT_B_MAX_PACKETS
        ) {
            return null
        }
        if (packetIndex < 0 || packetIndex >= totalPackets) return null
        // A frame that was already completed can only be a stale duplicate.
        if (lastCompletedFrameIndex >= 0 && frameIndex <= lastCompletedFrameIndex) return null

        var partial = window[frameIndex]
        if (partial == null) {
            if (window.size >= MAX_WINDOW_FRAMES) {
                // Window overflow: drop the oldest (smallest frameIndex) partial frame.
                val oldest = window.keys.minOrNull() ?: return null
                window.remove(oldest)
                droppedFrames++
            }
            partial = PartialFrame(frameIndex, totalPackets.toInt())
            window[frameIndex] = partial
        } else if (partial.totalPackets != totalPackets.toInt()) {
            // Header inconsistency: the frame cannot be reassembled reliably.
            window.remove(frameIndex)
            droppedFrames++
            return null
        }
        currentFrameIndex = frameIndex

        val chunkIndex = packetIndex.toInt()
        if (partial.chunks[chunkIndex] != null) return null // duplicate chunk
        val payloadLength = length - LiveFrameParser.FORMAT_B_HEADER_SIZE
        if (partial.byteCount + payloadLength > LiveFrameParser.MAX_ASSEMBLED_FRAME_BYTES) {
            window.remove(frameIndex)
            droppedFrames++
            return null
        }
        val chunk = data.copyOfRange(LiveFrameParser.FORMAT_B_HEADER_SIZE, length)
        partial.chunks[chunkIndex] = chunk
        partial.receivedCount++
        partial.byteCount += payloadLength

        if (partial.receivedCount < partial.totalPackets) return null

        // Frame complete: concatenate chunks in index order.
        val assembled = ByteArray(partial.byteCount)
        var offset = 0
        for (chunkItem in partial.chunks) {
            val bytes = chunkItem ?: return null
            System.arraycopy(bytes, 0, assembled, offset, bytes.size)
            offset += bytes.size
        }
        window.remove(frameIndex)
        // A newer completed frame makes all older partial frames useless.
        val stale = window.keys.filter { it < frameIndex }
        for (key in stale) {
            window.remove(key)
            droppedFrames++
        }
        completedFrames++
        lastCompletedFrameIndex = frameIndex
        currentFrameIndex = -1L
        return assembled
    }

    private companion object {
        /** Two-frame window: tolerate reordering without unbounded memory. */
        const val MAX_WINDOW_FRAMES = 2
    }
}
