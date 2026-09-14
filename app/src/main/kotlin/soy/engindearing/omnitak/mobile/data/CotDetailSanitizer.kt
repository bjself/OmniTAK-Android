package soy.engindearing.omnitak.mobile.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Re-parses an untrusted CoT `<detail>` fragment and re-serializes only what
 * is well formed, escaping every attribute value and text node, and dropping
 * the elements that steer TAK Server routing (`<marti>`, which carries
 * `<dest callsign=...>`, and `<__serverdestination>`).
 *
 * Used for `Detail.xmlDetail` received from Meshtastic peers (TAKMessage on
 * portnum 72). That string is authored by whoever holds the channel key and
 * used to be spliced verbatim into the CoT XML the mesh-to-server relay
 * forwards (audit 2026-09-14, M5). Returns null when the fragment is not
 * well formed so the caller can fall back to rendering from parsed fields.
 */
object CotDetailSanitizer {

    /** Elements a mesh peer must not be able to inject into server-bound CoT. */
    private val DROP = setOf("marti", "__serverdestination")

    fun sanitize(fragment: String): String? {
        val trimmed = fragment.trim()
        if (trimmed.isEmpty()) return null
        // Accept either a bare fragment or one already wrapped in <detail>.
        val wrapped = if (trimmed.startsWith("<detail")) trimmed else "<detail>$trimmed</detail>"
        return runCatching { rebuild(wrapped) }.getOrNull()
    }

    private fun rebuild(xml: String): String? {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val out = StringBuilder()
        var depth = 0          // depth inside the outer <detail>
        var dropDepth = -1     // depth at which a dropped subtree started, -1 = not dropping
        var sawRoot = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name ?: return null
                    if (depth == 0) {
                        if (sawRoot || name != "detail") return null
                        sawRoot = true
                        out.append("<detail>")
                    } else if (dropDepth < 0) {
                        if (name in DROP) {
                            dropDepth = depth
                        } else {
                            out.append('<').append(name)
                            for (i in 0 until parser.attributeCount) {
                                out.append(' ').append(parser.getAttributeName(i))
                                    .append("=\"").append(CotXml.escape(parser.getAttributeValue(i))).append('"')
                            }
                            out.append('>')
                        }
                    }
                    depth++
                }
                XmlPullParser.TEXT -> {
                    if (depth > 1 && dropDepth < 0) out.append(CotXml.escape(parser.text))
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth < 0) return null
                    if (dropDepth >= 0) {
                        if (depth == dropDepth) dropDepth = -1
                    } else if (depth == 0) {
                        out.append("</detail>")
                    } else {
                        out.append("</").append(parser.name).append('>')
                    }
                }
            }
            // next() (not nextToken()) resolves entity references into TEXT
            // and skips comments / processing instructions / DOCDECL.
            event = parser.next()
        }
        if (depth != 0 || !sawRoot) return null
        // Collapse "<x></x>" into "<x/>" for compactness and CoT convention.
        return out.toString().replace(Regex("<([A-Za-z_][\\w.-]*)([^<>]*)></\\1>"), "<$1$2/>")
    }
}
