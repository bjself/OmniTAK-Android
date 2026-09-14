package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Security regression test (audit 2026-09-14, finding M11).
 *
 * The in-process tile server was created with `ServerSocket(0)`, which
 * binds every interface, while its URL template and the network security
 * config assume 127.0.0.1. Imported offline imagery was reachable from the
 * LAN by anyone who guessed the UUID and port. It must bind loopback only.
 *
 * Pure JVM: MBTilesServer uses java.net only; the fake db avoids SQLite.
 */
class MBTilesServerBindTest {

    private class FakeDb : RasterTileDb {
        override val minZoom = 0
        override val maxZoom = 5
        override val format = "png"
        override val bounds: DoubleArray? = null
        override fun tile(z: Int, x: Int, y: Int): ByteArray? =
            if (z == 1 && x == 2 && y == 3) byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) else null
        override fun close() {}
    }

    @Test
    fun `server binds loopback only and serves a tile there`() {
        MBTilesServer.register("bindtest", FakeDb())
        try {
            val addr = MBTilesServer.bindAddress
            assertNotNull("server did not start", addr)
            assertTrue("bound to ${addr!!.hostAddress}, expected loopback", addr.isLoopbackAddress)

            val url = URL("http://127.0.0.1:${MBTilesServer.port}/bindtest/1/2/3")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            assertEquals(200, conn.responseCode)
            assertEquals(4, conn.inputStream.readBytes().size)
            conn.disconnect()
        } finally {
            MBTilesServer.unregister("bindtest")
        }
    }
}
