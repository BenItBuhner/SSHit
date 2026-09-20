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
        assertTrue(parsed("ssh://ben;fingerprint=SHA256:abc@bastion/").plain, "a fingerprint goes with the login, not the host, so it is no reason to save one")
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

    @Test
    fun `a link is bounded in length, in forwards and in its name, and a reason quotes only so much`() {
        // A VIEW intent's data can be hundreds of kilobytes; what parses rides into the back stack, so the bound is at the door.
        val longHost = "h".repeat(SshLink.MAX_LENGTH - "ssh://".length)
        assertEquals(longHost, parsed("ssh://$longHost").host, "a link of exactly the bound is read")
        assertEquals("The link is too long.", malformed("ssh://${longHost}h"))
        assertEquals("The link is too long.", malformed(" ".repeat(SshLink.MAX_LENGTH + 1)), "before it is trimmed, since the trim itself is work on the whole of it")

        val sixteen = (1..SshLink.MAX_FORWARDS).joinToString("&") { "L=${8000 + it}:h:80" }
        assertEquals(SshLink.MAX_FORWARDS, parsed("ssh://h?$sixteen").forwards.size)
        assertEquals("The link asks for more than 16 forwards.", malformed("ssh://h?$sixteen&D=1080"))
        assertEquals(1, parsed("ssh://h?L=8080:h:80&L=8080:h:80&N").forwards.distinct().size, "the same forward twice is the pending rows' business, not the parser's")

        assertEquals("n".repeat(SshLink.MAX_NAME_LENGTH), parsed("ssh://h#" + "n".repeat(500)).name, "a name is cut, not refused: it is decorative")
        assertEquals("prod", parsed("ssh://h#" + "%20".repeat(SshLink.MAX_NAME_LENGTH) + "prod").name, "trimmed before it is cut, so the spaces do not eat the name")

        // What a reason quotes is cut with an ellipsis, so a notice is one line whatever the link was.
        val reason = malformed("ssh://" + "!".repeat(300))
        assertEquals("\u201C${"!".repeat(SshLink.QUOTED_MAX)}\u2026\u201D isn't a host name or address.", reason)
        assertEquals("\u201C${"9".repeat(SshLink.QUOTED_MAX)}\u2026\u201D isn't a port from 1 to 65535.", malformed("ssh://h:" + "9".repeat(200)))
        assertEquals("Only ssh:// and sftp:// links open here, not ${"x".repeat(SshLink.QUOTED_MAX)}\u2026://.", malformed("x".repeat(100) + "://h"))
    }

    @Test
    fun `characters that cannot be seen are refused in a user, dropped from a name and kept out of a reason`() {
        // Percent-encoding is the one way they get in; no server has such a user, and a newline in one is a second line in a one-line row.
        for (user in listOf("root%0a", "%1b%5b31mroot", "ro%20ot", "ops%e2%80%aeprod", "a%c2%a0b", "%e2%80%8bx", "x%00")) {
            assertEquals("The user has a character that cannot be in a user name.", malformed("ssh://$user@host"), user)
        }
        assertEquals("a@b", parsed("ssh://a%40b@host").user, "@ stays legal, since user@REALM logins are real")
        assertEquals("jos\u00e9", parsed("ssh://jos%c3%a9@host").user, "letters outside ASCII are letters")

        // A bidi override in a name would reverse the tab title around it; a name is decorative, so it loses the character and keeps the rest.
        assertEquals("eman tsoh", parsed("ssh://host#%e2%80%aeeman%20tsoh").name)
        assertEquals("onetwo", parsed("ssh://host#one%0atwo").name, "a newline is dropped, not turned into a space")
        assertNull(parsed("ssh://host#%e2%80%ae%00").name, "a name that was nothing but such characters is no name")

        // A reason shows in the notice bar, so the part it quotes is cleaned the same way.
        assertEquals("\u201Cbad!host\u201D isn't a host name or address.", malformed("ssh://bad!host\u202e"), "the override is in the host, refused with it, and not quoted")
        assertEquals("\u201Cbad!host%e2%80%ae\u201D isn't a host name or address.", malformed("ssh://bad!host%e2%80%ae"), "a host is never percent-decoded (the zone id of an IPv6 address is written %25), so encoded it is text")
    }

    @Test
    fun `the fingerprint parameter is read as the draft writes it, and a slash in one is named`() {
        // Standard base64 has / in about half of all SHA-256 fingerprints; unencoded it ends the authority.
        assertEquals("The fingerprint has a / in it; write %2F in its place.", malformed("ssh://ben;fingerprint=SHA256:abc+/def@host.example.org"))
        assertEquals("The fingerprint has a / in it; write %2F in its place.", malformed("sftp://ben;fingerprint=SHA256:a/b@host/srv"))
        assertEquals("SHA256:abc+/def", parsed("ssh://ben;fingerprint=SHA256:abc+%2Fdef@host.example.org").fingerprint)
        assertEquals("ssh-ed25519-c1-b1-30-29", parsed("ssh://ben;fingerprint=ssh-ed25519-c1-b1-30-29@host").fingerprint, "the draft's own dashed form needs no encoding")
        // The draft's c-param list: the first after `;`, the rest after `,`; `;` between them is taken too.
        parsed("ssh://ben;fingerprint=abc,type=d@host").let {
            assertEquals("abc", it.fingerprint)
            assertEquals("ben", it.user)
        }
        assertEquals("abc", parsed("ssh://ben;type=d;fingerprint=abc@host").fingerprint)
        assertEquals("abc", parsed("ssh://ben;FINGERPRINT=abc@host").fingerprint)
        assertEquals("The link names the fingerprint twice.", malformed("ssh://ben;fingerprint=a,fingerprint=b@host"))
        assertEquals("The link names the fingerprint twice.", malformed("ssh://ben;fingerprint=a;fingerprint=a@host"), "even the same one, since the draft says one")
        assertNull(parsed("ssh://ben;fingerprint=@host").fingerprint, "an empty one is none")
        assertNull(parsed("ssh://ben;fingerprint@host").fingerprint)
        assertEquals("ben", parsed("ssh://ben;fingerprint@host").user)
        // The draft's sftp form ends the path in ;type=dir or ;type=file.
        assertEquals("/srv/data", parsed("sftp://host/srv/data/;type=dir").path)
        assertEquals("/srv/data", parsed("sftp://host/srv/data;type=d").path)
        assertNull(parsed("sftp://host/;type=dir").path, "the home folder either way")
    }
}
