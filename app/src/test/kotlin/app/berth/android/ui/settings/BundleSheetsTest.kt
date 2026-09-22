package app.berth.android.ui.settings

import app.berth.android.ui.keys.NewKeyPrefill
import app.berth.android.ui.prompts.formatDate
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.RecreateNotice
import app.berth.domain.model.SwatchColor
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
 * and the plural, the button names the keys the ticks replace and the keys they add beside a saved
 * one, and the disclosure's last clause holds under a ticked Replace.
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
    fun `the disclosure names what an import replaces and that nothing is removed, until a tick takes a saved key's place`() {
        assertEquals(
            "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed.",
            IMPORT_DISCLOSURE,
        )
        assertEquals(IMPORT_DISCLOSURE, importDisclosure(replacing = 0))
        // A ticked Replace removes a saved key, said on the row and the button: the last clause says so too (nit 20).
        assertEquals(
            "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed but the host key you ticked to replace.",
            importDisclosure(replacing = 1),
        )
        assertEquals(
            "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed but the 2 host keys you ticked to replace.",
            importDisclosure(replacing = 2),
        )
    }

    @Test
    fun `the hosts-only export counts the hosts and their saved passwords, and a named key's row says whether this phone has it`() {
        fun host(id: String, auth: AuthMethod) = Host(id = id, name = id, color = SwatchColor.COPPER, monogram = "HO", address = "$id.local", user = "ben", auth = auth, createdAt = 0)
        assertEquals("1 host", hostsExportContents(listOf(host("web", AuthMethod.Key("id-laptop")))))
        assertEquals(
            "a password asked each time is not a saved one",
            "3 hosts \u00B7 2 saved passwords",
            hostsExportContents(listOf(host("a", AuthMethod.Password("s-a")), host("b", AuthMethod.Password("s-b")), host("c", AuthMethod.Password(null)))),
        )
        assertEquals("2 hosts \u00B7 1 saved password", hostsExportContents(listOf(host("a", AuthMethod.Password("s-a")), host("b", AuthMethod.AskEachTime))))

        // The keys a bundle carries, and the hardware keys it names: never `0 keys and`.
        assertEquals("2 keys", keysLine(carried = 2, hardware = 0))
        assertEquals("1 key and 1 hardware key to make again", keysLine(carried = 1, hardware = 1))
        assertEquals("2 hardware keys to make again", keysLine(carried = 0, hardware = 2))

        // A key named and not carried: what it is, and so what its hosts do here.
        assertEquals("Ed25519, not in the file \u00B7 this phone has it, so prod-web logs in with it", namedKeyLine("laptop", "Ed25519", hereAs = "laptop", hosts = listOf("prod-web")))
        assertEquals(
            "Ed25519, not in the file \u00B7 this phone has it as laptop ed25519, so prod-web, staging log in with it",
            namedKeyLine("old laptop", "Ed25519", hereAs = "laptop ed25519", hosts = listOf("prod-web", "staging")),
        )
        assertEquals(
            "RSA 4096, not in the file \u00B7 this phone does not have it, so ci-runner asks each time until you pick a key",
            namedKeyLine("deploy bot", "RSA 4096", hereAs = null, hosts = listOf("ci-runner")),
        )
        assertEquals(
            "Ed25519, not in the file \u00B7 this phone does not have it, so a, b ask each time until you pick a key",
            namedKeyLine("k", "Ed25519", hereAs = null, hosts = listOf("a", "b")),
        )
        assertEquals("Ed25519, not in the file \u00B7 this phone does not have it", namedKeyLine("k", "Ed25519", hereAs = null, hosts = emptyList()))

        // The report's lines under the keys to make again and the keys left on the other phone speak to one key or to several.
        assertTrue(hardwareNote(1).startsWith("This key was hardware-backed on the phone that made the bundle"))
        assertTrue(hardwareNote(2).startsWith("These were hardware-backed on the phone that made the bundle"))
        assertTrue(leftBehindNote(1).startsWith("This key stayed on the phone that made the file. Bring it here"))
        assertTrue(leftBehindNote(2).startsWith("These keys stayed on the phone that made the file. Bring each here"))

        // Make a key on the report opens as the key it stands in for: hardware where the named one was, software where it was not.
        assertEquals(NewKeyPrefill("Phone key", KeyAlgorithm.ECDSA_P256, hardware = true), NewKeyPrefill(RecreateNotice("Phone key", KeyAlgorithm.ECDSA_P256, listOf("db"))))
        assertEquals(NewKeyPrefill("laptop", KeyAlgorithm.ED25519, hardware = false), NewKeyPrefill(RecreateNotice("laptop", KeyAlgorithm.ED25519, listOf("web"), hardware = false)))
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
