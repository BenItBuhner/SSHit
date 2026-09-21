package app.berth.android.ui.settings

import app.berth.android.ui.prompts.formatDate
import app.berth.domain.model.KnownHostKey
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
 * whole to measure it; the rows for what an import does not take as carried count in the singular
 * and the plural, and the button names the keys the ticks replace and the keys they add beside a
 * saved one.
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
    fun `the known hosts count row says how many of its keys this phone trusts already, and the button how many keys the ticks replace`() {
        // The count row is the keys that need no decision: new ones, and the ones trusted already, which the import leaves alone.
        assertEquals("db.internal:2200", knownHostsCaption(listOf("db.internal:2200"), existing = 0))
        assertEquals("203.0.113.10 \u00B7 trusted already", knownHostsCaption(listOf("203.0.113.10"), existing = 1))
        assertEquals("a, b \u00B7 all trusted already", knownHostsCaption(listOf("a", "b"), existing = 2))
        assertEquals("a, b, c \u00B7 1 trusted already", knownHostsCaption(listOf("a", "b", "c"), existing = 1))
        // The conflicts are rows of their own; the button counts the ticked ones, as the known_hosts import's does:
        // the ticks that take a saved key's place, and the ticks that add a key of another type beside it (nit 19).
        assertEquals("Import", importBundleLabel(0))
        assertEquals("Import, replace 1 key", importBundleLabel(1))
        assertEquals("Import, replace 2 keys", importBundleLabel(2))
        assertEquals("Import, add 1 key", importBundleLabel(0, adding = 1))
        assertEquals("Import, add 2 keys", importBundleLabel(0, adding = 2))
        assertEquals("Import, replace 1 key, add 1", importBundleLabel(1, adding = 1))
        assertEquals("Import, replace 2 keys, add 3", importBundleLabel(2, adding = 3))
    }

    @Test
    fun `the row for the tunnels that come in switched off counts one and many, a title of one line and a caption that says why`() {
        assertEquals("1 tunnel comes in switched off", tunnelsHeldOffLine(1))
        assertEquals("3 tunnels come in switched off", tunnelsHeldOffLine(3))
        assertEquals("*:9090 \u2192 localhost:9090 \u00B7 it listens on every interface; it stays off until you turn it on", tunnelsHeldOffCaption(listOf("*:9090 \u2192 localhost:9090")))
        assertEquals("a, b \u00B7 they listen on every interface; they stay off until you turn them on", tunnelsHeldOffCaption(listOf("a", "b")))
        // A caption names six and counts the rest, as every row of the panel does.
        assertEquals("1, 2, 3, 4, 5, 6 and 2 more", namesLine((1..8).map { it.toString() }))
        assertEquals("1, 2", namesLine(listOf("1", "2")))
        // The third switch says what new terminals would open in and what they open in now.
        assertEquals("New terminals open in Mine instead of Berth Dark", defaultTerminalThemeCaption("Mine", "Berth Dark"))
    }

    @Test
    fun `the disclosure names what an import replaces and that nothing is removed`() {
        assertEquals(
            "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed.",
            IMPORT_DISCLOSURE,
        )
    }

    @Test
    fun `the line under a key of a type this phone holds none of names the saved key it is added beside, and that it stays`() {
        val saved = KnownHostKey("kh-1", "203.0.113.10", 22, "ssh-ed25519", "AAAAsaved", "SHA256:L6ErsL0LWje5zGabcdefghijklmnopqrstuvwxyz0123", firstSeenAt = 0L, lastSeenAt = 0L)
        assertEquals(
            "Ticked, it is added beside the saved ED25519 key SHA256:L6Er sL0L Wje5 zGab\u2026 (trusted ${formatDate(0L)}), which stays.",
            addedBesideSavedKeyLine(saved),
        )
    }
}
