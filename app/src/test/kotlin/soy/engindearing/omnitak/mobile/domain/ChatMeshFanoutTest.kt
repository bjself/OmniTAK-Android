package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security regression tests (audit 2026-09-14, finding M4). */
class ChatMeshFanoutTest {

    @Test
    fun `group chat fans out to the mesh when connected and enabled`() {
        assertTrue(ChatMeshFanout.shouldFanOut(convoIsGroup = true, meshConnected = true, broadcastOverMesh = true))
    }

    @Test
    fun `a private server DM never fans out to the mesh`() {
        assertFalse(ChatMeshFanout.shouldFanOut(convoIsGroup = false, meshConnected = true, broadcastOverMesh = true))
    }

    @Test
    fun `nothing fans out when the mesh is disconnected or the preference is off`() {
        assertFalse(ChatMeshFanout.shouldFanOut(convoIsGroup = true, meshConnected = false, broadcastOverMesh = true))
        assertFalse(ChatMeshFanout.shouldFanOut(convoIsGroup = true, meshConnected = true, broadcastOverMesh = false))
    }
}
