package com.yim1remote.app.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the runtime write-type negotiation decision.
 *
 * This is the single most Android-specific pitfall of the whole module: the
 * camera declares only `write` (not `write_no_response`), yet its firmware is
 * friendlier to no-response writes and answers the acknowledged form with
 * application error `0x80` on some paths. The decision logic is pure so it can be
 * tested here without `android.jar`; [BleGattSession] only maps the chosen
 * [BleWriteKind] onto the framework constants.
 */
class BleWriteNegotiationTest {

    private val writeOnly = BleWriteNegotiation.PROPERTY_WRITE
    private val noResponseOnly = BleWriteNegotiation.PROPERTY_WRITE_NO_RESPONSE
    private val bothWritable = writeOnly or noResponseOnly

    @Test
    fun `prefers no response when the camera advertises both flavours`() {
        assertEquals(
            listOf(BleWriteKind.NO_RESPONSE, BleWriteKind.WITH_RESPONSE),
            BleWriteNegotiation.plan(bothWritable),
        )
    }

    @Test
    fun `uses with response when only write is advertised`() {
        assertEquals(
            listOf(BleWriteKind.WITH_RESPONSE, BleWriteKind.NO_RESPONSE),
            BleWriteNegotiation.plan(writeOnly),
        )
    }

    @Test
    fun `uses no response when only write_no_response is advertised`() {
        assertEquals(
            listOf(BleWriteKind.NO_RESPONSE, BleWriteKind.WITH_RESPONSE),
            BleWriteNegotiation.plan(noResponseOnly),
        )
    }

    @Test
    fun `falls back to with response when neither write property is advertised`() {
        // e.g. a notify-only bitmap; still try an acknowledged write, then no-response.
        assertEquals(
            listOf(BleWriteKind.WITH_RESPONSE, BleWriteKind.NO_RESPONSE),
            BleWriteNegotiation.plan(0x10), // PROPERTY_NOTIFY
        )
    }

    @Test
    fun `learned flavour wins but the other flavour stays available for a fallback`() {
        // A previous no-response write worked even though only `write` is advertised.
        assertEquals(
            listOf(BleWriteKind.NO_RESPONSE, BleWriteKind.WITH_RESPONSE),
            BleWriteNegotiation.plan(writeOnly, learned = BleWriteKind.NO_RESPONSE),
        )
        // ... and the reverse: with-response learned on a write-only characteristic.
        assertEquals(
            listOf(BleWriteKind.WITH_RESPONSE, BleWriteKind.NO_RESPONSE),
            BleWriteNegotiation.plan(noResponseOnly, learned = BleWriteKind.WITH_RESPONSE),
        )
    }

    @Test
    fun `learned flavour is always tried first regardless of advertisement`() {
        val plan = BleWriteNegotiation.plan(bothWritable, learned = BleWriteKind.WITH_RESPONSE)
        assertEquals(BleWriteKind.WITH_RESPONSE, plan.first())
        assertEquals(2, plan.size)
        assertEquals(BleWriteKind.NO_RESPONSE, plan[1])
    }

    @Test
    fun `advertised kind reads the property bitmap`() {
        assertEquals(BleWriteKind.NO_RESPONSE, BleWriteNegotiation.advertisedKind(bothWritable))
        assertEquals(BleWriteKind.WITH_RESPONSE, BleWriteNegotiation.advertisedKind(writeOnly))
        assertEquals(BleWriteKind.NO_RESPONSE, BleWriteNegotiation.advertisedKind(noResponseOnly))
        assertNull(BleWriteNegotiation.advertisedKind(0x00))
        assertNull(BleWriteNegotiation.advertisedKind(0x02)) // read-only property
    }

    @Test
    fun `property constants match the android framework values`() {
        // BluetoothGattCharacteristic.PROPERTY_WRITE / PROPERTY_WRITE_NO_RESPONSE.
        assertEquals(0x08, BleWriteNegotiation.PROPERTY_WRITE)
        assertEquals(0x04, BleWriteNegotiation.PROPERTY_WRITE_NO_RESPONSE)
    }

    @Test
    fun `labels are the chinese diagnostics strings`() {
        assertEquals("无响应写入", BleWriteKind.NO_RESPONSE.label)
        assertEquals("带响应写入", BleWriteKind.WITH_RESPONSE.label)
    }
}
