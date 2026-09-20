package app.berth.android.ui

import app.berth.ssh.SshLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quick connect's spec (spec C11) is read by the link parser, so the field and an `ssh://` link
 * agree on every form; what Quick connect adds is `root` for a user left out, since an unsaved
 * host has no user of its own to fall back on.
 */
class QuickConnectTest {
    @Test
    fun `accepts the usual spellings`() {
        assertEquals(Triple("ben", "10.0.2.2", 2222), AppViewModel.parseQuickConnect("ben@10.0.2.2:2222"))
        assertEquals(Triple("root", "example.com", 22), AppViewModel.parseQuickConnect("example.com"))
        assertEquals(Triple("root", "example.com", 2200), AppViewModel.parseQuickConnect("example.com:2200"))
        assertEquals(Triple("deploy", "host.internal", 22), AppViewModel.parseQuickConnect("ssh://deploy@host.internal/"))
        assertEquals(Triple("ben", "fe80::1", 22), AppViewModel.parseQuickConnect("ben@[fe80::1]"))
        assertEquals(Triple("root", "fe80::1", 2200), AppViewModel.parseQuickConnect("[fe80::1]:2200"))
        assertEquals(Triple("ben", "bastion", 22), AppViewModel.parseQuickConnect("  ben@bastion  "))
        assertEquals("a fingerprint is no reason to refuse the address", Triple("ben", "bastion", 22), AppViewModel.parseQuickConnect("ssh://ben;fingerprint=SHA256:abc@bastion"))
    }

    @Test
    fun `rejects nonsense`() {
        assertNull(AppViewModel.parseQuickConnect(""))
        assertNull(AppViewModel.parseQuickConnect("host:99999"))
        assertNull(AppViewModel.parseQuickConnect("a b"))
        assertNull(AppViewModel.parseQuickConnect("user@"))
        assertNull(AppViewModel.parseQuickConnect("ben@[fe80::1"))
        assertNull(AppViewModel.parseQuickConnect("http://example.com"))
    }

    /** What only a saved host can hold is not an unsaved login: the field refuses it rather than dropping it on the floor. */
    @Test
    fun `refuses what asks for more than a shell`() {
        assertNull("forwards", AppViewModel.parseQuickConnect("ssh://ben@bastion?L=8080:localhost:80"))
        assertNull("no shell", AppViewModel.parseQuickConnect("ssh://ben@bastion?N"))
        assertNull("a folder", AppViewModel.parseQuickConnect("sftp://ben@bastion/var/log"))
        assertNull("a name", AppViewModel.parseQuickConnect("ssh://ben@bastion#Relay"))
    }

    /** The field and a link are one parser: every spelling the field takes, the link parser reads to the same host. */
    @Test
    fun `agrees with the link parser on every spelling`() {
        for (spec in listOf("ben@10.0.2.2:2222", "example.com", "example.com:2200", "ssh://deploy@host.internal/", "ben@[fe80::1]", "[::1]:2200", "SSH://Host")) {
            val link = (SshLink.parse(spec) as SshLink.Result.Parsed).link
            assertEquals(spec, Triple(link.user ?: AppViewModel.QUICK_CONNECT_USER, link.host, link.port), AppViewModel.parseQuickConnect(spec))
        }
    }
}
