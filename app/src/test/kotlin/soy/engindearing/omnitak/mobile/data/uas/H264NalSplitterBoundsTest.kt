package soy.engindearing.omnitak.mobile.data.uas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, finding M9).
 *
 * RawH264UdpPlayer accepts datagrams from any host on 0.0.0.0:port. After
 * one start code, a stream that never sends another start code grew the
 * splitter's buffer without bound (and copied it on every push), so any LAN
 * host could OOM the app while video was enabled.
 */
class H264NalSplitterBoundsTest {
    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    @Test
    fun `payload without a closing start code is capped`() {
        val splitter = H264NalSplitter()
        splitter.push(b(0x00, 0x00, 0x01, 0xAA))
        val chunk = ByteArray(64 * 1024) { 0xAA.toByte() }
        val pushes = (H264NalSplitter.MAX_NAL_BYTES / chunk.size) + 4
        repeat(pushes) { splitter.push(chunk) }
        assertTrue(
            "buffer grew to ${splitter.bufferedBytes}",
            splitter.bufferedBytes <= H264NalSplitter.MAX_NAL_BYTES + chunk.size,
        )
        assertTrue(splitter.droppedOversize >= 1)
    }

    @Test
    fun `splitter recovers after an oversize drop`() {
        val splitter = H264NalSplitter()
        splitter.push(b(0x00, 0x00, 0x01))
        val chunk = ByteArray(256 * 1024) { 0x55 }
        repeat((H264NalSplitter.MAX_NAL_BYTES / chunk.size) + 2) { splitter.push(chunk) }
        // A fresh, well-formed NAL closed by a start code still comes out.
        val out = splitter.push(b(0x00, 0x00, 0x01, 0xAA, 0xBB, 0x00, 0x00, 0x01))
        assertEquals(1, out.size)
        assertArrayEquals(b(0xAA, 0xBB), out[0])
    }

    @Test
    fun `garbage before the first start code does not accumulate`() {
        val splitter = H264NalSplitter()
        repeat(64) { splitter.push(ByteArray(1024) { 0x77 }) }
        assertTrue(splitter.bufferedBytes <= 3)
    }
}
