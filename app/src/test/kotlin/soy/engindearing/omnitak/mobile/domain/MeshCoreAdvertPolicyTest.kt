package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Security regression tests (audit 2026-09-14, finding M6).
 *
 * MeshCoreManager.sendCoTOverMesh treated every non-chat CoT as the local
 * node's own position and issued SET_ADVERT_LATLON + SEND_SELF_ADVERT. The
 * mesh-to-server relay calls the same method for every relayable
 * server-origin event, so with the gateway on and MeshCore selected the
 * operator's MeshCore node advertised other contacts' (or hostile markers')
 * coordinates as its own position. Only the operator's own PLI may update
 * the advert.
 */
class MeshCoreAdvertPolicyTest {

    private fun event(uid: String, type: String = "a-f-G-U-C") =
        CoTEvent(uid = uid, type = type, lat = 1.0, lon = 2.0)

    @Test
    fun `own PLI updates the advert`() {
        assertTrue(MeshCoreManager.isSelfAdvertCandidate(event("ANDROID-ME"), selfUid = "ANDROID-ME"))
    }

    @Test
    fun `another contact's position does not update the advert`() {
        assertFalse(MeshCoreManager.isSelfAdvertCandidate(event("ANDROID-PEER"), selfUid = "ANDROID-ME"))
    }

    @Test
    fun `a hostile marker does not update the advert`() {
        assertFalse(MeshCoreManager.isSelfAdvertCandidate(event("hostile-1", "a-h-G"), selfUid = "ANDROID-ME"))
    }

    @Test
    fun `chat never updates the advert`() {
        assertFalse(MeshCoreManager.isSelfAdvertCandidate(event("ANDROID-ME", "b-t-f"), selfUid = "ANDROID-ME"))
    }

    @Test
    fun `unknown self uid means nothing is advertised`() {
        assertFalse(MeshCoreManager.isSelfAdvertCandidate(event("ANDROID-ME"), selfUid = null))
        assertFalse(MeshCoreManager.isSelfAdvertCandidate(event("ANDROID-ME"), selfUid = ""))
    }
}
