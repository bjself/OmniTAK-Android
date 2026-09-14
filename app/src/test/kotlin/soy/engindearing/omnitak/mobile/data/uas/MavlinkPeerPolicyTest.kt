package soy.engindearing.omnitak.mobile.data.uas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Security regression tests (audit 2026-09-14, finding H5).
 *
 * The UDP MAVLink link bound an ephemeral port on all interfaces and
 * re-targeted `udpAddress:udpPort` to whatever host last sent a datagram;
 * the first HEARTBEAT from anyone set the vehicle sysid. MAVLink signing is
 * not used, so any LAN host that learned the port (the GCS heartbeats at
 * 1 Hz) could inject telemetry that OmniTAK federated to the TAK server as
 * the drone, and could redirect every outgoing command, mission upload and
 * Follow-Me stream (carrying the operator's GPS) to itself.
 *
 * Policy: datagrams are accepted only from the configured peer address
 * (port may still be learned from that address, SITL-style); messages are
 * accepted only from the vehicle sysid locked by the first non-GCS
 * HEARTBEAT.
 */
class MavlinkPeerPolicyTest {

    private val drone = InetAddress.getByName("10.0.2.2")
    private val other = InetAddress.getByName("10.0.2.99")

    // ── UDP peer pinning ──────────────────────────────────────────────────

    @Test
    fun `datagram from the configured peer is accepted`() {
        assertTrue(MavlinkPeerPolicy.acceptDatagramFrom(pinned = drone, from = drone))
    }

    @Test
    fun `datagram from any other address is dropped`() {
        assertFalse(MavlinkPeerPolicy.acceptDatagramFrom(pinned = drone, from = other))
    }

    @Test
    fun `port is learned only from the pinned peer`() {
        assertEquals(14551, MavlinkPeerPolicy.learnPort(pinned = drone, from = drone, fromPort = 14551, current = 14550))
        assertEquals(14550, MavlinkPeerPolicy.learnPort(pinned = drone, from = other, fromPort = 9999, current = 14550))
    }

    // ── sysid lock ────────────────────────────────────────────────────────

    @Test
    fun `nothing is accepted before a vehicle heartbeat locks the sysid`() {
        val lock = MavlinkPeerPolicy.SysIdLock()
        assertFalse(lock.accept(originSystemId = 1, isHeartbeat = false))
        assertNull(lock.lockedSystemId)
    }

    @Test
    fun `first vehicle heartbeat locks and later messages from that sysid pass`() {
        val lock = MavlinkPeerPolicy.SysIdLock()
        assertTrue(lock.accept(originSystemId = 1, isHeartbeat = true))
        assertEquals(1, lock.lockedSystemId)
        assertTrue(lock.accept(originSystemId = 1, isHeartbeat = false))
    }

    @Test
    fun `messages from another sysid are rejected even if they are heartbeats`() {
        val lock = MavlinkPeerPolicy.SysIdLock()
        lock.accept(originSystemId = 1, isHeartbeat = true)
        assertFalse(lock.accept(originSystemId = 2, isHeartbeat = true))
        assertFalse(lock.accept(originSystemId = 2, isHeartbeat = false))
        assertEquals(1, lock.lockedSystemId)
    }

    @Test
    fun `a GCS heartbeat never locks the sysid`() {
        val lock = MavlinkPeerPolicy.SysIdLock(gcsSystemId = 255)
        assertFalse(lock.accept(originSystemId = 255, isHeartbeat = true))
        assertNull(lock.lockedSystemId)
    }

    @Test
    fun `reset clears the lock so a new connect can relock`() {
        val lock = MavlinkPeerPolicy.SysIdLock()
        lock.accept(originSystemId = 1, isHeartbeat = true)
        lock.reset()
        assertNull(lock.lockedSystemId)
        assertTrue(lock.accept(originSystemId = 7, isHeartbeat = true))
        assertEquals(7, lock.lockedSystemId)
    }
}
