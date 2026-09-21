package app.berth.android.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The import sheet's reading of a picked file, and its lines: the picker is `*∕*`, so the pick may
 * be anything on the phone and the read stops at the bundle limit instead of holding the pick
 * whole to measure it; the two rows for what an import does not take as carried count in the
 * singular and the plural.
 */
class BundleSheetsTest {
    @Test
    fun `a file within the limit is read whole, to the last byte the limit allows`() {
        val exact = ByteArray(1000) { it.toByte() }
        assertArrayEquals(exact, readAtMost(ByteArrayInputStream(exact), 1000))
        assertArrayEquals(exact, readAtMost(ByteArrayInputStream(exact), 1001))
        assertArrayEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 1000))
    }

    @Test
    fun `a file one byte over the limit is refused, and a stream with no end is refused after the limit and not a buffer more`() {
        assertNull(readAtMost(ByteArrayInputStream(ByteArray(1001)), 1000))

        // A 3 GB video from the same picker: the read gives up as soon as the stream proves to hold more than the limit.
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int {
                served++
                return 0x41
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0x41, off, off + len)
                served += len
                return len
            }
        }
        val limit = 32 shl 20
        assertNull(readAtMost(endless, limit))
        assertTrue("read ${endless.served} bytes for a limit of $limit", endless.served <= limit + (64 shl 10))
    }

    @Test
    fun `the rows for what stays as this phone has it count one and many`() {
        assertEquals("1 known host differs from yours and stays yours", knownHostsKeptLine(1))
        assertEquals("2 known hosts differ from yours and stay yours", knownHostsKeptLine(2))
        assertEquals("1 tunnel listens on every interface; it stays off until you turn it on", tunnelsHeldOffLine(1))
        assertEquals("3 tunnels listen on every interface; they stay off until you turn them on", tunnelsHeldOffLine(3))
    }

    @Test
    fun `the disclosure names what an import replaces and that nothing is removed`() {
        assertEquals(
            "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed.",
            IMPORT_DISCLOSURE,
        )
    }
}
