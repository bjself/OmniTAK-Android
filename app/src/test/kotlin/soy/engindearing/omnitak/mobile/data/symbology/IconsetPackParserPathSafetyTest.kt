package soy.engindearing.omnitak.mobile.data.symbology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, finding H1 — zip-slip).
 *
 * `iconset.xml` is attacker-controlled: icon packs are shared team artifacts
 * imported from the Settings file picker. The `uid` attribute becomes the
 * directory `<filesDir>/iconpacks/<uid>/` and each `filename` attribute is
 * joined under it by [IconPackImporter]; `IconPackRegistry.remove(uid)`
 * later `deleteRecursively()`s that same directory. Neither value may be
 * allowed to escape the pack directory.
 *
 * The parser is the pure-JVM choke point, so it must reject an unsafe uid
 * (whole pack refused) and drop unsafe icon entries (rest of the pack kept).
 */
class IconsetPackParserPathSafetyTest {

    // Built by concatenation, not trimIndent(): a multi-line template value
    // would defeat trimIndent and leave the XML declaration indented, which
    // the parser rejects (that is a fixture bug, not a parser behaviour).
    private fun xml(uid: String, vararg filenames: String): String {
        val icons = filenames.mapIndexed { i, f -> """  <icon name="Icon$i" filename="$f"/>""" }
            .joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<iconset uid=\"$uid\" name=\"Pack\" version=\"1\">\n" +
            icons + "\n" +
            "</iconset>\n"
    }

    // ── uid ────────────────────────────────────────────────────────────────

    @Test
    fun `uid with parent traversal rejects the whole pack`() {
        assertNull(IconsetPackParser.parse(xml("../..", "Ground/Ambulance.png")))
    }

    @Test
    fun `uid containing a forward slash rejects the whole pack`() {
        assertNull(IconsetPackParser.parse(xml("evil/../../files", "Ground/Ambulance.png")))
    }

    @Test
    fun `uid containing a backslash rejects the whole pack`() {
        assertNull(IconsetPackParser.parse(xml("evil\\..\\..", "Ground/Ambulance.png")))
    }

    @Test
    fun `uid that is a single dot rejects the whole pack`() {
        assertNull(IconsetPackParser.parse(xml(".", "Ground/Ambulance.png")))
    }

    @Test
    fun `uuid style uid is accepted`() {
        val parsed = IconsetPackParser.parse(xml("f47ac10b-58cc-4372-a567-0e02b2c3d479", "Ground/Ambulance.png"))
        assertNotNull(parsed)
        assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", parsed!!.uid)
    }

    // ── filename ───────────────────────────────────────────────────────────

    @Test
    fun `filename with parent traversal is dropped, safe siblings kept`() {
        val parsed = IconsetPackParser.parse(
            xml("safe-uid", "../../files/datastore/tak_servers.preferences_pb", "Ground/Ambulance.png"),
        )
        assertNotNull(parsed)
        assertEquals(listOf("Ground/Ambulance.png"), parsed!!.icons.map { it.filename })
    }

    @Test
    fun `absolute filename is dropped`() {
        val parsed = IconsetPackParser.parse(xml("safe-uid", "/data/data/x/files/evil.png", "Ok.png"))
        assertEquals(listOf("Ok.png"), parsed!!.icons.map { it.filename })
    }

    @Test
    fun `filename with backslash traversal is dropped`() {
        val parsed = IconsetPackParser.parse(xml("safe-uid", "Ground\\..\\..\\evil.png", "Ok.png"))
        assertEquals(listOf("Ok.png"), parsed!!.icons.map { it.filename })
    }

    @Test
    fun `filename with embedded parent segment is dropped`() {
        val parsed = IconsetPackParser.parse(xml("safe-uid", "Ground/../../evil.png", "Ok.png"))
        assertEquals(listOf("Ok.png"), parsed!!.icons.map { it.filename })
    }

    @Test
    fun `pack whose every icon is unsafe parses to an empty icon list`() {
        val parsed = IconsetPackParser.parse(xml("safe-uid", "../a.png", "/b.png"))
        assertNotNull(parsed)
        assertTrue(parsed!!.icons.isEmpty())
    }

    @Test
    fun `nested but safe filename is kept`() {
        val parsed = IconsetPackParser.parse(xml("safe-uid", "Ground/Vehicles/Ambulance.png"))
        assertEquals(listOf("Ground/Vehicles/Ambulance.png"), parsed!!.icons.map { it.filename })
    }

    // ── helper contract ────────────────────────────────────────────────────

    @Test
    fun `isSafeRelativePath contract`() {
        assertTrue(IconsetPackParser.isSafeRelativePath("Ambulance.png"))
        assertTrue(IconsetPackParser.isSafeRelativePath("Ground/Ambulance.png"))
        assertTrue(IconsetPackParser.isSafeRelativePath("a.b/c-d_e.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath(""))
        assertFalse(IconsetPackParser.isSafeRelativePath("   "))
        assertFalse(IconsetPackParser.isSafeRelativePath(".."))
        assertFalse(IconsetPackParser.isSafeRelativePath("../x.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x/../y.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x/./y.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("/x.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("\\x.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x\\..\\y.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x//y.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x/y.png/"))
        assertFalse(IconsetPackParser.isSafeRelativePath("x\u0000.png"))
        assertFalse(IconsetPackParser.isSafeRelativePath("C:/x.png"))
    }

    @Test
    fun `isSafeSegment contract`() {
        assertTrue(IconsetPackParser.isSafeSegment("f47ac10b-58cc-4372-a567-0e02b2c3d479"))
        assertTrue(IconsetPackParser.isSafeSegment("my.pack_v2"))
        assertFalse(IconsetPackParser.isSafeSegment(""))
        assertFalse(IconsetPackParser.isSafeSegment("."))
        assertFalse(IconsetPackParser.isSafeSegment(".."))
        assertFalse(IconsetPackParser.isSafeSegment("a/b"))
        assertFalse(IconsetPackParser.isSafeSegment("a\\b"))
        assertFalse(IconsetPackParser.isSafeSegment("a:b"))
        assertFalse(IconsetPackParser.isSafeSegment("a\u0000b"))
    }
}
