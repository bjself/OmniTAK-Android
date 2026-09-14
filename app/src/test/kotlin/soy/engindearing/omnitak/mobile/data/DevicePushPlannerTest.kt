package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Security regression tests (audit 2026-09-14, finding H4).
 *
 * "Push to device" used to send `set_channel { index=0, settings{name},
 * role=PRIMARY }` with NO psk field. Meshtastic's set_channel replaces the
 * whole Channel struct and a primary channel with an empty PSK means
 * encryption disabled, so one tap could silently strip encryption from the
 * operator's primary channel.
 *
 * Fix: the parser keeps the PSK from `get_channel_response`, and the push
 * planner either echoes that PSK back in set_channel or skips the channel
 * write entirely when the current PSK has not been read yet.
 */
class DevicePushPlannerTest {

    private val dest = 0x12345678u
    private val psk = ByteArray(16) { (it * 7 + 3).toByte() }
    private val config = MeshDeviceConfig(channelName = "Renamed")

    // ── parser keeps the PSK ──────────────────────────────────────────────

    @Test
    fun `get_channel_response carries psk uplink downlink`() {
        val parsed = AdminMessageParser.parse(
            adminWithChannel(index = 0, name = "OmniTAK", role = 1, psk = psk, uplink = true, downlink = false),
        ) as AdminResponse.Channel
        assertArrayEquals(psk, parsed.psk)
        assertTrue(parsed.uplinkEnabled)
        assertFalse(parsed.downlinkEnabled)
        assertEquals("OmniTAK", parsed.name)
    }

    @Test
    fun `channel response without psk has empty psk`() {
        val parsed = AdminMessageParser.parse(
            adminWithChannel(index = 0, name = "OmniTAK", role = 1, psk = null, uplink = false, downlink = false),
        ) as AdminResponse.Channel
        assertEquals(0, parsed.psk.size)
    }

    // ── planner ───────────────────────────────────────────────────────────

    @Test
    fun `plan without a known primary channel skips set_channel`() {
        val plan = DevicePushPlanner.plan(dest, config, knownPrimary = null)
        assertTrue(plan.channelSkipped)
        assertEquals(4, plan.frames.size)
        assertTrue(plan.frames.none { it.containsBytes(SET_CHANNEL_TAG) })
    }

    @Test
    fun `plan with a known primary channel echoes its psk into set_channel`() {
        val known = AdminResponse.Channel(index = 0, name = "OmniTAK", role = 1, psk = psk, uplinkEnabled = true)
        val plan = DevicePushPlanner.plan(dest, config, knownPrimary = known)
        assertFalse(plan.channelSkipped)
        assertEquals(5, plan.frames.size)
        val setChannel = plan.frames.single { it.containsBytes(SET_CHANNEL_TAG) }
        // ChannelSettings.psk = field 2 (tag 0x12) + len 16 + the 16 key bytes
        assertTrue("psk must be echoed", setChannel.containsBytes(byteArrayOf(0x12, 0x10) + psk))
        // ChannelSettings.name = field 3 (tag 0x1a) + the new name
        assertTrue("new name must be written", setChannel.containsBytes(byteArrayOf(0x1a, 0x07) + "Renamed".toByteArray()))
        // ChannelSettings.uplink_enabled = field 5 (tag 0x28) = 1, preserved
        assertTrue("uplink flag must be preserved", setChannel.containsBytes(byteArrayOf(0x28, 0x01)))
    }

    @Test
    fun `plan treats a known primary with empty psk as unknown and skips`() {
        // An empty PSK on the primary means "encryption off" today; writing it
        // back would be faithful, but a read that raced with a firmware reset is
        // indistinguishable from a real empty key. Refuse rather than risk it.
        val known = AdminResponse.Channel(index = 0, name = "OmniTAK", role = 1, psk = ByteArray(0))
        val plan = DevicePushPlanner.plan(dest, config, knownPrimary = known)
        assertTrue(plan.channelSkipped)
    }

    @Test
    fun `buildSetPrimaryChannel refuses to encode an empty psk`() {
        val known = AdminResponse.Channel(index = 0, name = "OmniTAK", role = 1, psk = ByteArray(0))
        assertEquals(null, AdminMessageSerializer.buildSetPrimaryChannel(dest, "X", known))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** AdminMessage.set_channel = field 33, wire 2 → tag bytes 8a 02. */
    private val SET_CHANNEL_TAG = byteArrayOf(0x8a.toByte(), 0x02)

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private fun adminWithChannel(
        index: Int,
        name: String,
        role: Int,
        psk: ByteArray?,
        uplink: Boolean,
        downlink: Boolean,
    ): ByteArray {
        val settings = ByteArrayOutputStream().apply {
            if (psk != null && psk.isNotEmpty()) {
                write(0x12); writeVarint(this, psk.size.toLong()); write(psk)          // field 2 bytes
            }
            if (name.isNotEmpty()) {
                write(0x1A); writeVarint(this, name.length.toLong()); write(name.toByteArray()) // field 3
            }
            if (uplink) { write(0x28); write(0x01) }     // field 5 bool
            if (downlink) { write(0x30); write(0x01) }   // field 6 bool
        }.toByteArray()
        val channel = ByteArrayOutputStream().apply {
            write(0x08); writeVarint(this, index.toLong())
            write(0x12); writeVarint(this, settings.size.toLong()); write(settings)
            write(0x18); writeVarint(this, role.toLong())
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x12); writeVarint(this, channel.size.toLong()); write(channel) // get_channel_response = 2
        }.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }
}
