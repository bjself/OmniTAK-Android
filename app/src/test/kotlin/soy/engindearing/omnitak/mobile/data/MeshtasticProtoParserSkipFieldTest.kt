package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, finding H2 — infinite loop).
 *
 * [MeshtasticProtoParser.skipField] promises to "always advance at least one
 * byte". For wire type 2 it computed `lenEnd + len.toInt()`; a varint whose
 * low 32 bits are negative (e.g. `FA FF FF FF 0F` = 0xFFFFFFFA, `.toInt()` =
 * -6) moved the cursor *backwards*, and every enclosing parse loop then
 * re-read the same tag forever. Any node on the mesh (or any LAN host on the
 * plaintext TCP path) could pin the frame collector at 100% CPU with six
 * bytes on portnum 3.
 */
class MeshtasticProtoParserSkipFieldTest {

    /** Field 15, wire type 2, length varint 0xFFFFFFFA (negative as Int). */
    private val negativeLengthPosition = byteArrayOf(
        0x7A,
        0xFA.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F,
    )

    @Test
    fun `skipField never returns an offset before the length varint end`() {
        // offset 1 is the start of the length varint; it ends at offset 6.
        val end = MeshtasticProtoParser.skipField(negativeLengthPosition, 1, 2)
        assertTrue("skipField moved backwards: $end", end >= 6)
    }

    @Test
    fun `skipField with huge positive length clamps to buffer end`() {
        // length varint 0x7FFFFFFF followed by 2 payload bytes
        val buf = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 0x41, 0x42)
        assertEquals(buf.size, MeshtasticProtoParser.skipField(buf, 0, 2))
    }

    @Test
    fun `skipField with length overflowing Long range clamps to buffer end`() {
        // 10-byte varint = ULong.MAX_VALUE
        val buf = ByteArray(10) { 0xFF.toByte() }.also { it[9] = 0x01 } + byteArrayOf(0x00)
        val end = MeshtasticProtoParser.skipField(buf, 0, 2)
        assertTrue(end in 10..buf.size)
    }

    @Test(timeout = 2_000)
    fun `parsePosition terminates on negative length unknown field`() {
        // Must return (null or a position), never spin.
        assertNull(MeshtasticProtoParser.parsePosition(negativeLengthPosition))
    }

    @Test(timeout = 2_000)
    fun `parseFromRadio terminates on negative length unknown field`() {
        // Unknown top-level field 15 with the poisoned length, then nothing else.
        MeshtasticProtoParser.parseFromRadio(negativeLengthPosition)
    }

    @Test(timeout = 2_000)
    fun `parseFromRadio terminates when poisoned field is nested in a MeshPacket Data payload`() {
        // FromRadio.packet(2) { MeshPacket.decoded(4) { Data { portnum(1)=3, payload(2)=<poison> } } }
        val poison = negativeLengthPosition
        val data = byteArrayOf(0x08, 0x03) + byteArrayOf(0x12, poison.size.toByte()) + poison
        val meshPacket = byteArrayOf(0x22, data.size.toByte()) + data
        val fromRadio = byteArrayOf(0x12, meshPacket.size.toByte()) + meshPacket
        val frame = MeshtasticProtoParser.parseFromRadio(fromRadio)
        if (frame is FromRadioFrame.Packet) {
            // The payload is handed to sub-parsers by the manager; make sure
            // the position parser also terminates on it.
            assertNull(MeshtasticProtoParser.parsePosition(frame.packet.payload))
        }
    }
}
