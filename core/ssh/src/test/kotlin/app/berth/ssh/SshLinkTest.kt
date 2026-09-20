package app.berth.ssh

import app.berth.domain.model.TunnelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshLinkTest {
    private fun parsed(text: String): SshLink {
        val result = SshLink.parse(text)
        assertIs<SshLink.Result.Parsed>(result, "expected $text to parse, got $result")
        return result.link
    }

    private fun malformed(text: String): String {
        val result = SshLink.parse(text)
        assertIs<SshLink.Result.Malformed>(result, "expected $text to be refused, got $result")
        return result.reason
    }

    @Test
    fun `the usual spellings of a host`() {
        parsed("ssh://ben@10.0.2.2:2222").let {
            assertEquals(SshLink.Scheme.SSH, it.scheme)
            assertEquals("ben", it.user)
            assertEquals("10.0.2.2", it.host)
            assertEquals(2222, it.port)
            assertTrue(it.shell)
            assertFalse(it.tunnelsOnly)
            assertEquals("ben@10.0.2.2:2222", it.target)
        }
        parsed("ssh://deploy@host.internal/").let {
            assertEquals("host.internal", it.host)
            assertEquals(22, it.port)
            assertNull(it.path, "an ssh:// link's path is nothing to open")
        }
        parsed("SSH://Example.COM").let {
            assertNull(it.user, "no user in the link leaves the user to the host or the editor")
            assertEquals("Example.COM", it.host)
            assertEquals("Example.COM", it.target)
        }
        parsed("ben@[fe80::1]").let {
            assertEquals("fe80::1", it.host)
            assertEquals(22, it.port)
            assertEquals("ben@[fe80::1]", it.target)
        }
        parsed("ssh://[2001:db8::10]:2200").let {
            assertEquals("2001:db8::10", it.host)
            assertEquals(2200, it.port)
        }
        parsed("example.com:2200").let {
            assertEquals("example.com", it.host)
            assertEquals(2200, it.port)
        }
    }

    @Test
    fun `sftp links carry the folder and open files`() {
        parsed("sftp://ben@nas.local/home/ben/photos/").let {
            assertEquals(SshLink.Scheme.SFTP, it.scheme)
            assertEquals("/home/ben/photos", it.path)
            assertFalse(it.tunnelsOnly)
        }
        assertNull(parsed("sftp://ben@nas.local/").path, "the root slash alone means the home folder")
        assertNull(parsed("sftp://ben@nas.local").path)
        assertEquals("/srv/my files", parsed("sftp://nas.local/srv/my%20files").path)
    }

    @Test
    fun `the fingerprint parameter and the fragment come through`() {
        parsed("ssh://ben;fingerprint=SHA256:abc%2Fdef+ghi=@host.example.org#prod%20web").let {
            assertEquals("ben", it.user)
            assertEquals("SHA256:abc/def+ghi=", it.fingerprint, "a plus in the fingerprint is a plus, not a space")
            assertEquals("prod web", it.name)
        }
        assertNull(parsed("ssh://ben@host#").name)
    }

    @Test
    fun `forwards in the query are the openssh flags and make the link tunnels only`() {
        val link = parsed("ssh://ben@bastion?L=8080:localhost:80&R=9000:127.0.0.1:3000&D=1080&L=*:5433:db.internal:5432&dynamic=[::1]:1081")
        assertEquals(
            listOf(
                SshConfigForward(TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80),
                SshConfigForward(TunnelType.REMOTE, "127.0.0.1", 9000, "127.0.0.1", 3000),
                SshConfigForward(TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0),
                SshConfigForward(TunnelType.LOCAL, "0.0.0.0", 5433, "db.internal", 5432),
                SshConfigForward(TunnelType.DYNAMIC, "::1", 1081, "", 0),
            ),
            link.forwards,
        )
        assertFalse(link.shell)
        assertTrue(link.tunnelsOnly)

        // ssh -L would read the empty bind as every interface; from a link that has to be written out.
        assertEquals(SshConfigForward(TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80), parsed("ssh://h?L=:8080:localhost:80").forwards.single(), "an empty bind is loopback, never every interface")
        assertEquals(SshConfigForward(TunnelType.REMOTE, "127.0.0.1", 9000, "localhost", 3000), parsed("ssh://h?R=:9000:localhost:3000").forwards.single())
        assertEquals(SshConfigForward(TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0), parsed("ssh://h?D=:1080").forwards.single())
        assertEquals(SshConfigForward(TunnelType.LOCAL, "0.0.0.0", 8080, "localhost", 80), parsed("ssh://h?L=*:8080:localhost:80").forwards.single(), "written out, every interface is asked for")
        assertEquals(SshConfigForward(TunnelType.LOCAL, "127.0.0.1", 8080, "fe80::1", 80), parsed("ssh://h?L=8080:[fe80::1]:80").forwards.single())
    }

    @Test
    fun `N alone asks for a login with no shell, and a plain link asks for one`() {
        parsed("ssh://ben@bastion?N").let {
            assertFalse(it.shell)
            assertTrue(it.tunnelsOnly)
            assertTrue(it.forwards.isEmpty())
        }
        assertTrue(parsed("ssh://ben@bastion").shell)
        assertFalse(parsed("sftp://ben@bastion?L=8080:localhost:80").tunnelsOnly, "an sftp link opens Files whatever forwards it carries")
        assertTrue(parsed("ssh://h?theme=dark&x").shell, "unknown query keys are left alone")
    }

    @Test
    fun `a plain link is one Quick connect can open as an unsaved host`() {
        assertTrue(parsed("ssh://ben@bastion:2200").plain)
        assertTrue(parsed("bastion").plain)
        assertTrue(parsed("ssh://ben;fingerprint=SHA256:abc@bastion/").plain, "the fingerprint is read by nothing yet, so it is no reason to save a host")
        assertTrue(parsed("ssh://h?theme=dark").plain, "unknown query keys ask for nothing")
        assertFalse(parsed("ssh://ben@bastion?L=8080:localhost:80").plain, "forwards need a saved host")
        assertFalse(parsed("ssh://ben@bastion?N").plain, "no shell is a Tunnels tab, which needs a saved host")
        assertFalse(parsed("sftp://ben@bastion").plain, "Files need a saved host")
        assertFalse(parsed("ssh://ben@bastion#Relay").plain, "a name is a saved host's")
    }

    @Test
    fun `what is wrong is named and nothing throws`() {
        assertEquals("The link is empty.", malformed("   "))
        assertEquals("Only ssh:// and sftp:// links open here, not http://.", malformed("http://example.com"))
        // The reasons name the part, not the link: Quick connect's field shows the same ones for what is typed there.
        assertEquals("No host is named.", malformed("ssh://"))
        assertEquals("No host is named.", malformed("ssh://ben@"))
        assertEquals("No host is named.", malformed("ssh://ben@:22"))
        assertEquals("The user before @ is empty.", malformed("ssh://@host"))
        assertEquals("The port after : is empty.", malformed("host:"))
        assertEquals("\u201C99999\u201D isn't a port from 1 to 65535.", malformed("host:99999"))
        assertEquals("\u201Cabc\u201D isn't a port from 1 to 65535.", malformed("ssh://host:abc"))
        assertEquals("An IPv6 address needs brackets: [address]:port.", malformed("ssh://fe80::1"))
        assertEquals("The IPv6 address is missing its closing bracket.", malformed("ssh://[fe80::1"))
        assertEquals("What is in the brackets isn't an IPv6 address.", malformed("ssh://[nope]"))
        assertEquals("Only a :port can follow the IPv6 address.", malformed("ssh://[::1]x"))
        assertEquals("The host part has a space in it.", malformed("a b"))
        assertEquals("No host is named.", malformed("ssh://ben@user@"), "the last @ splits user from host")
        assertEquals("a@b", parsed("ssh://a@b@host").user, "so an @ in the user survives")
        assertEquals("\u201Cbad_host!\u201D isn't a host name or address.", malformed("bad_host!"))
        assertEquals("The forward \u201Cabc\u201D isn't [bind:]port:host:hostport.", malformed("ssh://h?L=abc"))
        assertEquals("The forward \u201C8080:localhost\u201D isn't [bind:]port:host:hostport.", malformed("ssh://h?L=8080:localhost"))
        assertEquals("The remote forward \u201C9000::3000\u201D isn't [bind:]port:host:hostport.", malformed("ssh://h?R=9000::3000"))
        assertEquals("The dynamic forward \u201C1080:x:y\u201D isn't [bind:]port.", malformed("ssh://h?D=1080:x:y"))
        assertEquals("The dynamic forward \u201C\u201D isn't [bind:]port.", malformed("ssh://h?D"))
    }

    @Test
    fun `a bare spelling and the ssh scheme read the same`() {
        val bare = parsed("ben@host.example.org:2200")
        val schemed = parsed("ssh://ben@host.example.org:2200")
        assertEquals(bare, schemed)
    }
}
