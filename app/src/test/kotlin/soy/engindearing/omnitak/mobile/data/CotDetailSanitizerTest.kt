package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, finding M5).
 *
 * A Meshtastic TAKMessage may carry `Detail.xmlDetail`, a free-form XML
 * fragment written by the sending node. AtakPluginParser spliced it
 * verbatim into the rebuilt CoT `rawXml`, and the mesh-to-server relay
 * prefers `rawXml`, so any mesh node could inject arbitrary `<detail>`
 * children (server routing hints, unbalanced tags, ...) into what OmniTAK
 * sends to the TAK server.
 *
 * [CotDetailSanitizer] re-parses the fragment and re-serializes only
 * well-formed elements, escaping text and attributes, and drops the
 * server-routing elements a mesh peer has no business setting.
 *
 * Runs on the real kxml2 pull parser (test dependency), same as production.
 */
class CotDetailSanitizerTest {

    @Test
    fun `well formed fragment round trips with escaped attributes`() {
        val out = CotDetailSanitizer.sanitize(
            """<contact callsign="A &amp; B"/><__group name="Cyan" role="Team Member"/>""",
        )
        assertNotNull(out)
        assertTrue(out!!.startsWith("<detail>") && out.endsWith("</detail>"))
        assertTrue(out.contains("""<contact callsign="A &amp; B"/>"""))
        assertTrue(out.contains("""<__group name="Cyan" role="Team Member"/>"""))
    }

    @Test
    fun `fragment already wrapped in detail is accepted once`() {
        val out = CotDetailSanitizer.sanitize("""<detail><contact callsign="A"/></detail>""")
        assertEquals("""<detail><contact callsign="A"/></detail>""", out)
    }

    @Test
    fun `text content is re-escaped`() {
        val out = CotDetailSanitizer.sanitize("<remarks>a &lt; b &amp; c</remarks>")
        assertEquals("<detail><remarks>a &lt; b &amp; c</remarks></detail>", out)
    }

    @Test
    fun `quotes in attributes are escaped`() {
        val out = CotDetailSanitizer.sanitize("""<contact callsign="say &quot;hi&quot;"/>""")
        assertEquals("""<detail><contact callsign="say &quot;hi&quot;"/></detail>""", out)
    }

    @Test
    fun `server routing elements are dropped but siblings kept`() {
        val out = CotDetailSanitizer.sanitize(
            """<contact callsign="A"/><marti><dest callsign="VICTIM"/></marti><__serverdestination destinations="1.2.3.4:8089:tcp"/><remarks>ok</remarks>""",
        )
        assertNotNull(out)
        assertFalse(out!!.contains("marti"))
        assertFalse(out.contains("dest"))
        assertFalse(out.contains("__serverdestination"))
        assertTrue(out.contains("""<contact callsign="A"/>"""))
        assertTrue(out.contains("<remarks>ok</remarks>"))
    }

    @Test
    fun `nested content inside a dropped element is dropped too`() {
        val out = CotDetailSanitizer.sanitize("""<marti><dest callsign="X"/><contact callsign="INNER"/></marti><contact callsign="OUTER"/>""")
        assertEquals("""<detail><contact callsign="OUTER"/></detail>""", out)
    }

    @Test
    fun `unbalanced fragment returns null`() {
        assertNull(CotDetailSanitizer.sanitize("""<contact callsign="A"><remarks>x"""))
    }

    @Test
    fun `fragment that tries to close detail early returns null`() {
        assertNull(CotDetailSanitizer.sanitize("""<contact/></detail><event uid="X"><detail>"""))
    }

    @Test
    fun `blank fragment returns null`() {
        assertNull(CotDetailSanitizer.sanitize("   "))
    }

    @Test
    fun `comments and processing instructions are dropped`() {
        val out = CotDetailSanitizer.sanitize("""<!-- hi --><?pi x?><contact callsign="A"/>""")
        assertEquals("""<detail><contact callsign="A"/></detail>""", out)
    }

    @Test
    fun `nested legitimate elements are preserved`() {
        val out = CotDetailSanitizer.sanitize(
            """<__chat chatroom="All Chat Rooms" senderCallsign="A"><chatgrp uid0="U" uid1="All Chat Rooms"/></__chat>""",
        )
        assertEquals(
            """<detail><__chat chatroom="All Chat Rooms" senderCallsign="A"><chatgrp uid0="U" uid1="All Chat Rooms"/></__chat></detail>""",
            out,
        )
    }
}
