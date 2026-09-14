package soy.engindearing.omnitak.mobile.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Security regression tests (audit 2026-09-14, findings H3 / M1 / M12).
 *
 * - M1: a deep link or QR used to enroll with trust-all TLS unless the link
 *   explicitly opted out. The trust-all path sends Basic-auth credentials
 *   over an unverified channel and pins whatever CA the peer returns, so it
 *   must be an explicit, default-off opt-in — same as the manual Quick
 *   Connect switch and the contract documented on TakTls.configureUntrusted.
 * - H3: the user must be shown what a link will do before a server is added
 *   and auto-connected. [ServerImportPreview] is the pure summary the
 *   confirmation dialog renders, including the warnings.
 * - M12: log lines must never carry the enrollment token / password.
 *
 * Uri-based cases are guarded with `assumeTrue` because `android.net.Uri`
 * is a stub on the plain JVM (same convention as DeepLinkEnrollTest).
 */
class DeepLinkImportTrustTest {

    private fun cfg(
        useTLS: Boolean = true,
        username: String? = null,
        password: String? = null,
        trustSelfSigned: Boolean = false,
        port: Int = 8089,
    ) = ImportedServerConfig(
        name = "Ops",
        host = "tak.example.com",
        port = port,
        useTLS = useTLS,
        username = username,
        password = password,
        trustSelfSigned = trustSelfSigned,
    )

    // ── M1: trust default ─────────────────────────────────────────────────

    @Test
    fun `ImportedServerConfig trustSelfSigned defaults to false`() {
        val c = ImportedServerConfig(
            name = "n", host = "h", port = 8089, useTLS = true, username = null, password = null,
        )
        assertFalse(c.trustSelfSigned)
    }

    @Test
    fun `trust flag is opt-in only`() {
        assertFalse(DeepLinkImport.trustSelfSignedFrom(null))
        assertFalse(DeepLinkImport.trustSelfSignedFrom(""))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("ca"))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("system"))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("false"))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("0"))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("no"))
        assertFalse(DeepLinkImport.trustSelfSignedFrom("garbage"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("true"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("TRUE"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("1"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("yes"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("selfsigned"))
        assertTrue(DeepLinkImport.trustSelfSignedFrom("self-signed"))
    }

    // ── H3: confirmation preview ──────────────────────────────────────────

    @Test
    fun `preview of a plain enroll link says it will enroll and shows endpoint`() {
        val p = ServerImportPreview.from(cfg(username = "alpha", password = "secret"))
        assertTrue(p.willEnroll)
        assertEquals("tak.example.com:8089", p.endpoint)
        assertEquals("alpha", p.username)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `preview warns when TLS is off`() {
        val p = ServerImportPreview.from(cfg(useTLS = false, port = 8087))
        assertFalse(p.willEnroll)
        assertTrue(p.warnings.any { it.contains("unencrypted", ignoreCase = true) })
    }

    @Test
    fun `preview warns when link asks to skip certificate validation`() {
        val p = ServerImportPreview.from(cfg(username = "a", password = "b", trustSelfSigned = true))
        assertTrue(p.warnings.any { it.contains("certificate", ignoreCase = true) })
    }

    @Test
    fun `preview always states that the device will connect and share position`() {
        val p = ServerImportPreview.from(cfg())
        assertTrue(p.consequence.contains("position", ignoreCase = true))
    }

    // ── M12: log redaction ────────────────────────────────────────────────

    @Test
    fun `log line never contains the password or token`() {
        val p = ServerImportPreview.from(cfg(username = "alpha", password = "s3cr3t-token"))
        assertFalse(p.logLine.contains("s3cr3t-token"))
        assertTrue(p.logLine.contains("tak.example.com"))
    }

    // ── Uri-based (skipped when Uri is stubbed) ───────────────────────────

    private fun parsed(s: String): Uri? =
        runCatching { Uri.parse(s) }.getOrNull()?.takeIf { it.scheme != null }

    @Test
    fun parseEnrollLink_defaultsToStrictTrust() {
        val uri = parsed("tak://com.atakmap.app/enroll?host=tak.example.com%3A8089&username=u&token=t")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertFalse(DeepLinkImport.parseEnrollLink(uri!!)!!.trustSelfSigned)
    }

    @Test
    fun parseEnrollLink_trustTrueOptsIn() {
        val uri = parsed("tak://com.atakmap.app/enroll?host=tak.example.com%3A8089&username=u&token=t&trust=true")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertTrue(DeepLinkImport.parseEnrollLink(uri!!)!!.trustSelfSigned)
    }

    @Test
    fun parseServerConfig_defaultsToStrictTrust() {
        val uri = parsed("atak://com.atakmap.app/connect?host=tak.example.com&port=8089&username=u&password=p")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertFalse(DeepLinkImport.parseServerConfig(uri!!)!!.trustSelfSigned)
    }
}
