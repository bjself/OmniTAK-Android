package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, finding M3).
 *
 * `isFromSelf` used to be computed from the remote-supplied sender uid
 * (`chatgrp uid0` / `link uid`). The self UID is broadcast in every PPLI,
 * so a mesh peer could send a message with a foreign event uid but
 * `uid0=<victim>`; it rendered right-aligned as the victim's own message,
 * incremented no unread count and raised no notification.
 *
 * Now: parsed messages are never marked as from self, and a frame whose
 * sender is our own uid is dropped (it is an echo or a spoof).
 */
class ChatXmlSelfSpoofTest {

    private fun geoChat(eventUid: String, uid0: String, sender: String = "ALPHA", text: String = "hi") = """
        <event version="2.0" uid="$eventUid" type="b-t-f" time="2026-09-14T00:00:00Z" start="2026-09-14T00:00:00Z" stale="2026-09-15T00:00:00Z" how="h-g-i-g-o">
          <point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>
          <detail>
            <__chat chatroom="All Chat Rooms" id="All Chat Rooms" senderCallsign="$sender">
              <chatgrp uid0="$uid0" uid1="All Chat Rooms" id="All Chat Rooms"/>
            </__chat>
            <remarks>$text</remarks>
          </detail>
        </event>
    """.trimIndent()

    @Test
    fun `spoofed sender uid equal to self is dropped`() {
        val xml = geoChat(eventUid = "GeoChat.ATTACKER.All Chat Rooms.1", uid0 = "ANDROID-VICTIM", text = "I said this")
        assertNull(ChatXml.parse(xml, selfUid = "ANDROID-VICTIM"))
    }

    @Test
    fun `echo of our own message is dropped`() {
        val xml = geoChat(eventUid = "GeoChat.ANDROID-ME.All Chat Rooms.42", uid0 = "ANDROID-ME")
        assertNull(ChatXml.parse(xml, selfUid = "ANDROID-ME"))
    }

    @Test
    fun `message from a peer is never marked as from self`() {
        val xml = geoChat(eventUid = "GeoChat.ANDROID-PEER.All Chat Rooms.7", uid0 = "ANDROID-PEER")
        val msg = ChatXml.parse(xml, selfUid = "ANDROID-ME")
        assertNotNull(msg)
        assertFalse(msg!!.isFromSelf)
        assertEquals("ANDROID-PEER", msg.senderUid)
    }

    @Test
    fun `without a self uid nothing is marked as from self either`() {
        val xml = geoChat(eventUid = "GeoChat.ANDROID-X.All Chat Rooms.7", uid0 = "ANDROID-X")
        val msg = ChatXml.parse(xml)
        assertNotNull(msg)
        assertFalse(msg!!.isFromSelf)
    }
}
