package com.yim1remote.app.data

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parser tests for the UDP live-view stream.
 *
 * Format A fixtures reproduce the exact byte layout of the real 3.1-cn capture
 * in `research/vendor/yi-m1-controller-app/app/testdata/liveview/pack_000.bin`
 * (frameIndex `0x90E00E3A`, 224-byte table with `u16be[14..15] == 55`, JSON at
 * offset 236, JPEG at 2284). Format B fixtures are synthetic fragments built
 * with the verified `{frameIndex, totalPackets, packetIndex}` header.
 */
class LiveFrameParserTest {

    // ------------------------------------------------------------- fixture bits

    private fun u32be(value: Long): ByteArray =
        byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

    private fun fakeJpeg(payloadLength: Int = 64): ByteArray = ByteArray(payloadLength).apply {
        this[0] = 0xFF.toByte()
        this[1] = 0xD8.toByte()
        this[2] = 0xFF.toByte()
        this[3] = 0xDB.toByte()
        for (index in 4 until payloadLength - 2) this[index] = 0x20
        this[payloadLength - 2] = 0xFF.toByte()
        this[payloadLength - 1] = 0xD9.toByte()
    }

    private fun padToBlock(json: ByteArray, blockSize: Int = LiveFrameParser.PARAM_BLOCK_SIZE): ByteArray =
        ByteArray(blockSize).also { block ->
            require(json.size <= blockSize)
            System.arraycopy(json, 0, block, 0, json.size)
        }

    /** Builds the verified Format A layout: `[12 header][224 table][2048 JSON][JPEG]`. */
    private fun formatADatagram(
        frameIndex: Long = 0x90E00E3AL,
        timestamp: Long = 10_936_926L,
        json: ByteArray = """{"ExposureMode":"S","BatteryLevel":"25"}""".toByteArray(),
        jpeg: ByteArray = fakeJpeg(),
    ): ByteArray {
        val table = ByteArray(224)
        table[0] = 0x00
        table[1] = 0x01
        table[2] = 0x00
        table[3] = 55 // u16be(data[14..15]) == 55 -> APK json offset 16 + 55*4 = 236
        val output = ByteArray(12 + 224 + 2048 + jpeg.size)
        System.arraycopy(u32be(frameIndex), 0, output, 0, 4)
        System.arraycopy(u32be(timestamp), 0, output, 4, 4)
        System.arraycopy(u32be(LiveFrameParser.FORMAT_A_SESSION_MARKER), 0, output, 8, 4)
        System.arraycopy(table, 0, output, 12, table.size)
        System.arraycopy(padToBlock(json), 0, output, 12 + table.size, 2048)
        System.arraycopy(jpeg, 0, output, 12 + table.size + 2048, jpeg.size)
        return output
    }

    /** Builds the mock-server / very old firmware layout: `[12 zero header][2048 JSON][JPEG]`. */
    private fun simpleLegacyDatagram(
        json: ByteArray = """{"BatteryLevel":"87","ExposureMode":"M"}""".toByteArray(),
        jpeg: ByteArray = fakeJpeg(),
    ): ByteArray {
        val output = ByteArray(12 + 2048 + jpeg.size)
        System.arraycopy(padToBlock(json), 0, output, 12, 2048)
        System.arraycopy(jpeg, 0, output, 12 + 2048, jpeg.size)
        return output
    }

    private fun formatBFragment(
        frameIndex: Long,
        totalPackets: Int,
        packetIndex: Int,
        payload: ByteArray,
    ): ByteArray = u32be(frameIndex) + u32be(totalPackets.toLong()) +
        u32be(packetIndex.toLong()) + payload

    private fun formatBPayload(
        json: ByteArray = """{"ExposureMode":"A","BatteryLevel":"55"}""".toByteArray(),
        jpeg: ByteArray = fakeJpeg(200),
    ): ByteArray = padToBlock(json) + jpeg

    // ----------------------------------------------------------------- detect

    @Test
    fun `verified format A datagram is detected by its header words`() {
        val datagram = formatADatagram()
        assertEquals(
            LiveFrameParser.LiveViewFormat.FORMAT_A,
            LiveFrameParser.classifyDatagram(datagram, datagram.size),
        )
    }

    @Test
    fun `format B fragments are detected by packet count and index`() {
        val fragment = formatBFragment(7, 4, 2, ByteArray(100))
        assertEquals(
            LiveFrameParser.LiveViewFormat.FORMAT_B,
            LiveFrameParser.classifyDatagram(fragment, fragment.size),
        )
        // The detection is header-based, not size-based: a bigger fragment still
        // classifies as Format B while it stays under the 1500-byte MTU.
        val bigFragment = formatBFragment(7, 40, 0, ByteArray(1400))
        assertEquals(
            LiveFrameParser.LiveViewFormat.FORMAT_B,
            LiveFrameParser.classifyDatagram(bigFragment, bigFragment.size),
        )
    }

    @Test
    fun `unknown short datagrams are not mistaken for a live frame`() {
        assertEquals(LiveFrameParser.LiveViewFormat.UNKNOWN, LiveFrameParser.classifyDatagram(ByteArray(0)))
        assertEquals(LiveFrameParser.LiveViewFormat.UNKNOWN, LiveFrameParser.classifyDatagram(ByteArray(11)))
        assertEquals(LiveFrameParser.LiveViewFormat.UNKNOWN, LiveFrameParser.classifyDatagram(ByteArray(12)))
        // packetCount 0 / index >= count are not plausible Format B headers.
        assertEquals(
            LiveFrameParser.LiveViewFormat.UNKNOWN,
            LiveFrameParser.classifyDatagram(formatBFragment(1, 0, 0, ByteArray(10)), 22),
        )
        // packetCount 4 with packetIndex 4 is neither a plausible Format B
        // fragment nor a Format A datagram (frame header not >1500 bytes).
        assertEquals(
            LiveFrameParser.LiveViewFormat.UNKNOWN,
            LiveFrameParser.classifyDatagram(formatBFragment(1, 4, 4, ByteArray(10)), 22),
        )
    }

    // ---------------------------------------------------------------- format A

    @Test
    fun `APK formula reconstructs the verified json offset 236`() {
        val datagram = formatADatagram()
        assertEquals(236, LiveFrameParser.apkJsonOffset(datagram, datagram.size))
    }

    @Test
    fun `format A parses the verified layout into state json and jpeg`() {
        val json = """{"ExposureMode":"S","Fnumber":"1.7","ShutterSpeed":"1s"}""".toByteArray()
        val jpeg = fakeJpeg(128)
        val datagram = formatADatagram(json = json, jpeg = jpeg)
        val frame = LiveFrameParser.parseFormatADatagram(datagram, datagram.size)
        assertNotNull(frame)
        assertEquals(String(json), frame!!.paramsJson)
        assertArrayEquals(jpeg, frame.jpeg)
        assertTrue(frame.hasJpegHeader)
        assertTrue(frame.hasJpegFooter)
        assertEquals(0x90E00E3AL, frame.frameIndex)
    }

    @Test
    fun `format A falls back to scanning for the json when the header math is wrong`() {
        // Header with marker/payload-type bits that do NOT trigger the APK
        // extension branch (simple old-style header) but the JSON follows at 12,
        // which is exactly what the mock camera emits.
        val json = """{"ExposureMode":"M","BatteryLevel":"42"}""".toByteArray()
        val jpeg = fakeJpeg(32)
        val datagram = simpleLegacyDatagram(json, jpeg)
        assertEquals(12, LiveFrameParser.apkJsonOffset(datagram, datagram.size))
        val frame = LiveFrameParser.parseFormatADatagram(datagram, datagram.size)
        assertNotNull(frame)
        assertEquals(String(json), frame!!.paramsJson)
        assertArrayEquals(jpeg, frame.jpeg)
    }

    @Test
    fun `format A rejects truncated or non-frame datagrams`() {
        assertNull(LiveFrameParser.parseFormatADatagram(ByteArray(0)))
        assertNull(LiveFrameParser.parseFormatADatagram(ByteArray(11)))
        val full = formatADatagram()
        assertNull(LiveFrameParser.parseFormatADatagram(full, 100)) // truncated header/JSON
        assertNull(LiveFrameParser.parseFormatADatagram(full, full.size - full.size) ) // empty view
        // Header + table but no JSON/JPEG at all.
        val headerOnly = ByteArray(236).also {
            System.arraycopy(u32be(0x90E00E3AL), 0, it, 0, 4)
            it[0] = 0x90.toByte()
            it[1] = 0xE0.toByte()
        }
        assertNull(LiveFrameParser.parseFormatADatagram(headerOnly))
    }

    @Test
    fun `brace matching respects braces inside json strings`() {
        val json = """{"WB":"a}b{c","ExposureMode":"P"}""".toByteArray()
        val datagram = formatADatagram(json = json)
        val frame = LiveFrameParser.parseFormatADatagram(datagram, datagram.size)
        assertNotNull(frame)
        assertEquals(String(json), frame!!.paramsJson)
    }

    // ---------------------------------------------------------------- format B

    @Test
    fun `format B payload splits json block and jpeg at offset 2048`() {
        val json = """{"ExposureMode":"P","Fnumber":"4.0"}""".toByteArray()
        val jpeg = fakeJpeg(300)
        val payload = formatBPayload(json, jpeg)
        val frame = LiveFrameParser.parseFormatBPayload(payload)
        assertNotNull(frame)
        assertEquals(String(json), frame!!.paramsJson)
        assertArrayEquals(jpeg, frame.jpeg)
        assertTrue(frame.hasJpegFooter)
    }

    @Test
    fun `format B payload must end with a complete jpeg`() {
        val payload = formatBPayload()
        // Chop the EOI marker: assembly was incomplete/corrupt.
        val truncated = payload.copyOfRange(0, payload.size - 2)
        assertNull(LiveFrameParser.parseFormatBPayload(truncated))
    }

    @Test
    fun `multi packet assembler reassembles in-order fragments`() {
        val payload = formatBPayload(jpeg = fakeJpeg(900))
        val packetCount = 3
        val chunkSize = (payload.size + packetCount - 1) / packetCount
        val assembler = MultiPacketAssembler()
        var assembled: ByteArray? = null
        for (index in 0 until packetCount) {
            val from = index * chunkSize
            val to = minOf(payload.size, from + chunkSize)
            assembled = assembler.feed(formatBFragment(11, packetCount, index, payload.copyOfRange(from, to)))
        }
        assertNotNull(assembled)
        assertArrayEquals(payload, assembled)
        assertEquals(1, assembler.completedFrames)
        assertEquals(11L, assembler.lastCompletedFrameIndex)
    }

    @Test
    fun `multi packet assembler tolerates reordering`() {
        val payload = formatBPayload(jpeg = fakeJpeg(900))
        val packetCount = 3
        val chunkSize = (payload.size + packetCount - 1) / packetCount
        fun chunk(index: Int): ByteArray = payload.copyOfRange(
            index * chunkSize,
            minOf(payload.size, (index + 1) * chunkSize),
        )
        val assembler = MultiPacketAssembler()
        assertNull(assembler.feed(formatBFragment(3, packetCount, 2, chunk(2))))
        assertNull(assembler.feed(formatBFragment(3, packetCount, 0, chunk(0))))
        val assembled = assembler.feed(formatBFragment(3, packetCount, 1, chunk(1)))
        assertNotNull(assembled)
        assertArrayEquals(payload, assembled)
    }

    @Test
    fun `multi packet assembler ignores duplicates`() {
        val payload = formatBPayload(jpeg = fakeJpeg(300))
        val packetCount = 2
        val half = payload.size / 2
        val assembler = MultiPacketAssembler()
        assertNull(assembler.feed(formatBFragment(5, packetCount, 0, payload.copyOfRange(0, half))))
        assertNull(assembler.feed(formatBFragment(5, packetCount, 0, payload.copyOfRange(0, half))))
        val assembled = assembler.feed(formatBFragment(5, packetCount, 1, payload.copyOfRange(half, payload.size)))
        assertNotNull(assembled)
        assertEquals(1, assembler.completedFrames)
    }

    @Test
    fun `multi packet assembler drops an incomplete frame when the next frame completes`() {
        val payload = formatBPayload(jpeg = fakeJpeg(300))
        val half = payload.size / 2
        val assembler = MultiPacketAssembler()
        // Frame 20 loses packet 1.
        assertNull(assembler.feed(formatBFragment(20, 2, 0, payload.copyOfRange(0, half))))
        // Frame 21 arrives complete while frame 20 is still incomplete.
        assembler.feed(formatBFragment(21, 2, 2 - 2, payload.copyOfRange(0, half)))
        val assembled = assembler.feed(formatBFragment(21, 2, 1, payload.copyOfRange(half, payload.size)))
        assertNotNull(assembled)
        assertEquals(1, assembler.completedFrames)
        assertEquals(21L, assembler.lastCompletedFrameIndex)
        assertTrue("older incomplete frame must be counted as dropped", assembler.droppedFrames >= 1)
        // Stale packets from the completed frame are ignored.
        assertNull(assembler.feed(formatBFragment(21, 2, 0, payload.copyOfRange(0, half))))
    }

    @Test
    fun `multi packet assembler keeps a two-frame window`() {
        val payload = formatBPayload(jpeg = fakeJpeg(300))
        val half = payload.size / 2
        val assembler = MultiPacketAssembler()
        // Interleaved frames 1, 2, 3: the window holds at most two.
        assembler.feed(formatBFragment(1, 2, 0, payload.copyOfRange(0, half)))
        assembler.feed(formatBFragment(2, 2, 0, payload.copyOfRange(0, half)))
        assembler.feed(formatBFragment(3, 2, 0, payload.copyOfRange(0, half))) // evicts frame 1
        val assembled = assembler.feed(formatBFragment(2, 2, 1, payload.copyOfRange(half, payload.size)))
        assertNotNull(assembled)
        assertEquals(2L, assembler.lastCompletedFrameIndex)
        assertTrue(assembler.droppedFrames >= 1)
    }

    @Test
    fun `multi packet assembler sanity-bounds corrupt headers and oversized frames`() {
        val payload = formatBPayload(jpeg = fakeJpeg(64))
        val assembler = MultiPacketAssembler()
        assertNull(assembler.feed(ByteArray(0)))
        assertNull(assembler.feed(formatBFragment(1, 0, 0, payload)))
        assertNull(assembler.feed(formatBFragment(1, 5000, 0, payload)))
        assertNull(assembler.feed(formatBFragment(1, 2, 2, payload)))
        assertNull(assembler.feed(formatBFragment(-1, 2, 0, payload)))

        // An assembled frame above the 300 KiB safety cap is dropped.
        val oversized = ByteArray(LiveFrameParser.MAX_ASSEMBLED_FRAME_BYTES + 1)
        val overflowAssembler = MultiPacketAssembler()
        assertNull(overflowAssembler.feed(formatBFragment(9, 2, 0, oversized)))
        assertEquals(1, overflowAssembler.droppedFrames)
        // ... and its remaining packet cannot revive the frame.
        assertNull(overflowAssembler.feed(formatBFragment(9, 2, 1, ByteArray(10))))
    }

    @Test
    fun `assembler reset clears partial state`() {
        val payload = formatBPayload(jpeg = fakeJpeg(64))
        val half = payload.size / 2
        val assembler = MultiPacketAssembler()
        assembler.feed(formatBFragment(4, 2, 0, payload.copyOfRange(0, half)))
        assembler.reset()
        assertEquals(-1L, assembler.currentFrameIndex)
        // After reset only a fresh frame start can complete.
        assertEquals(0, assembler.completedFrames)
    }

    @Test
    fun `format B assembly integrates with payload parsing`() {
        val json = """{"ExposureMode":"C","BatteryLevel":"101"}""".toByteArray()
        val jpeg = fakeJpeg(400)
        val payload = padToBlock(json) + jpeg
        val packetCount = 2
        val half = payload.size / 2
        val assembler = MultiPacketAssembler()
        assembler.feed(formatBFragment(100, packetCount, 1, payload.copyOfRange(half, payload.size)))
        val assembled = assembler.feed(formatBFragment(100, packetCount, 0, payload.copyOfRange(0, half)))
        assertNotNull(assembled)
        val frame = LiveFrameParser.parseFormatBPayload(assembled!!)
        assertNotNull(frame)
        assertEquals(String(json), frame!!.paramsJson)
        assertArrayEquals(jpeg, frame.jpeg)
    }

    // ----------------------------------------------------------------- helpers

    @Test
    fun `jpeg marker scanning finds the first soi`() {
        val data = ByteArray(32).apply {
            this[10] = 0xFF.toByte()
            this[11] = 0xD8.toByte()
            this[12] = 0xFF.toByte()
        }
        assertEquals(10, LiveFrameParser.findJpegMarker(data, 0, data.size))
        assertEquals(-1, LiveFrameParser.findJpegMarker(data, 13, data.size))
    }

    @Test
    fun `u32be reader reads unsigned values`() {
        val bytes = ByteBuffer.allocate(4).putInt(0x90E00E3A.toInt()).array()
        assertEquals(0x90E00E3AL, LiveFrameParser.readU32BigEndian(bytes, 0))
        assertEquals(-1L, LiveFrameParser.readU32BigEndian(bytes, 2))
        assertFalse(LiveFrameParser.isJpegSoi(bytes, 0))
    }
}
