package soy.engindearing.omnitak.mobile.domain

/**
 * Whether a chat message typed for a TAK-server conversation should also be
 * broadcast on the connected mesh radio as an all-users GeoChat.
 *
 * Only group rooms fan out. A 1:1 DM to a server contact used to be
 * re-broadcast to every node on the mesh channel whenever a radio was
 * connected and the default-on `broadcastOverMesh` preference was set
 * (audit 2026-09-14, M4). DMs to mesh contacts take the dedicated
 * MESH-DM- / MESHCORE-DM- path in ChatScreen and never reach this policy.
 */
object ChatMeshFanout {
    fun shouldFanOut(convoIsGroup: Boolean, meshConnected: Boolean, broadcastOverMesh: Boolean): Boolean =
        convoIsGroup && meshConnected && broadcastOverMesh
}
