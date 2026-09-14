package soy.engindearing.omnitak.mobile.data.uas

import java.net.InetAddress

/**
 * Who [MavlinkConnection] is willing to listen to. MAVLink 2 signing is not
 * in use, so this is the only thing standing between a LAN host and the
 * ability to impersonate the vehicle or become the destination of every
 * command, mission upload and Follow-Me stream (audit 2026-09-14, H5).
 *
 * Two independent gates, both pure so they are unit tested:
 *  - UDP datagrams must come from the configured peer address. The source
 *    port may still be learned from that address (SITL and mavlink-router
 *    answer from an ephemeral port), but never from anyone else.
 *  - Messages are applied only from the vehicle sysid locked by the first
 *    non-GCS HEARTBEAT after connect. Applies to TCP too.
 */
object MavlinkPeerPolicy {

    fun acceptDatagramFrom(pinned: InetAddress?, from: InetAddress): Boolean =
        pinned != null && pinned == from

    /** New destination port: learned from [fromPort] only when [from] is the pinned peer. */
    fun learnPort(pinned: InetAddress?, from: InetAddress, fromPort: Int, current: Int): Int =
        if (acceptDatagramFrom(pinned, from)) fromPort else current

    class SysIdLock(private val gcsSystemId: Int = 255) {
        @Volatile var lockedSystemId: Int? = null
            private set

        /**
         * True when a message from [originSystemId] should be applied. Locks
         * on the first HEARTBEAT that is not from a ground station; before
         * that, everything is dropped.
         */
        fun accept(originSystemId: Int, isHeartbeat: Boolean): Boolean {
            val locked = lockedSystemId
            if (locked != null) return originSystemId == locked
            if (!isHeartbeat || originSystemId == gcsSystemId) return false
            lockedSystemId = originSystemId
            return true
        }

        fun reset() { lockedSystemId = null }
    }
}
