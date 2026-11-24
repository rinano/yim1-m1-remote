package com.yim1remote.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level parser tests against **real datagrams captured from the
 * team mock camera** (`tools/mock-camera/mock_camera.py`), which implements the
 * two wire formats byte-for-byte from `research/verified/liveview-udp.md`:
 *
 *  * `format_a_legacy.bin` — one whole-frame Format A datagram:
 *    `u32be frameIndex + u32be timestamp + u32be session constant 0x79CE4283`
 *    + 224-byte binary table + 2048-byte JSON region (at offset 236) + JPEG
 *    (at offset 2284). This is exactly the real 3.1-cn capture layout.
 *  * `format_b_packets.bin` — a complete firmware >= 3.2 small-packet frame
 *    stored as repeated `u32be length + datagram` records; reassembling them
 *    must reproduce `[2048-byte JSON][JPEG]`.
 *
 * Keeping the capture binaries as test resources means a regression in either
 * decoder is caught against actual bytes, not only hand-built fixtures.
 */
class LiveViewCaptureIntegrationTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream(name)
            ?: error("missing test resource $name")
        return stream.use { it.readBytes() }
    }

    @Test
    fun `parses a captured whole-frame Format A datagram`() {
        val datagram = resourceBytes("/liveview/format_a_legacy.bin")
        assertTrue("Format A datagram must be >1500 bytes", datagram.size > 1500)
        assertEquals(
            LiveFrameParser.LiveViewFormat.FORMAT_A,
            LiveFrameParser.classifyDatagram(datagram, datagram.size),
        )
        // The verified mock emits the real header: marker bits from the frame
        // index word put the JSON at the APK-computed offset.
        assertEquals(236, LiveFrameParser.apkJsonOffset(datagram, datagram.size))
        val frame = LiveFrameParser.parseFormatADatagram(datagram, datagram.size)
        assertNotNull("Format A datagram must parse", frame)
        frame!!
        val params = frame.paramsJson
        assertNotNull("Format A must carry the JSON state block", params)
        assertTrue("state JSON must be a JSON object", params!!.startsWith("{") && params.endsWith("}"))
        for (key in listOf("ExposureMode", "MeteringMode", "BatteryLevel", "ShutterSpeed")) {
            assertTrue("state JSON must contain $key", params.contains("\"$key\""))
        }
        assertTrue("JPEG must start with SOI", frame.hasJpegHeader)
        assertTrue("JPEG must end with EOI", frame.hasJpegFooter)
        assertTrue("JPEG must be a substantial preview", frame.jpeg.size > 10_000)
        // The camera state block is usable by the status parser as-is.
        val status = CameraProtocol.parseStatus(params)
        assertNotNull(status)
        assertEquals(true, status.connected)
    }

    @Test
    fun `reassembles a captured small-packet Format B frame`() {
        val captured = resourceBytes("/liveview/format_b_packets.bin")
        val assembler = MultiPacketAssembler()
        var assembled: ByteArray? = null
        var offset = 0
        var datagrams = 0
        while (offset + 4 <= captured.size) {
            val length = ((captured[offset].toInt() and 0xFF) shl 24) or
                ((captured[offset + 1].toInt() and 0xFF) shl 16) or
                ((captured[offset + 2].toInt() and 0xFF) shl 8) or
                (captured[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length <= 0 || offset + length > captured.size) break
            val datagram = captured.copyOfRange(offset, offset + length)
            offset += length
            datagrams++
            assertEquals(
                LiveFrameParser.LiveViewFormat.FORMAT_B,
                LiveFrameParser.classifyDatagram(datagram, datagram.size),
            )
            assembled = assembler.feed(datagram, datagram.size) ?: assembled
        }
        assertTrue("capture must contain several fragments", datagrams >= 2)
        assertEquals("exactly one frame must complete", 1, assembler.completedFrames)
        assertNotNull("fragment capture must reassemble a frame", assembled)
        val frame = LiveFrameParser.parseFormatBPayload(assembled!!)
        assertNotNull("reassembled payload must parse", frame)
        frame!!
        assertTrue(frame.paramsJson!!.startsWith("{"))
        for (key in listOf("ExposureMode", "ImageQuality", "BatteryLevel")) {
            assertTrue("state JSON must contain $key", frame.paramsJson!!.contains("\"$key\""))
        }
        assertTrue(frame.hasJpegHeader)
        assertTrue(frame.hasJpegFooter)
        assertTrue(frame.jpeg.size > 10_000)
    }
}
