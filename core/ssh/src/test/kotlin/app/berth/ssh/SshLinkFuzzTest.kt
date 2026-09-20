package app.berth.ssh

import app.berth.domain.model.TunnelType
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SshLink.parse] as the exported surface it is: any app can hand Berth a link through the `VIEW`
 * intent filter, so the parser is run over a corpus built from URI atoms (the schemes, delimiters,
 * the forward keys, percent escapes, a bidi override, NUL, lone surrogates, long runs) and over a
 * table of hand-written probes, and asked to hold what the app relies on: it never throws; a parsed
 * link is one the editor could have made (a host in one of the three forms, ports in range, a user
 * with nothing invisible in it, a name cut and clean, at most [SshLink.MAX_FORWARDS] forwards each
 * with its ports and addresses in order); and a reason is one clean line. The seed is fixed, so a
 * failure reproduces from its message alone.
 */
class SshLinkFuzzTest {
    private val atoms = listOf(
        "ssh://", "sftp://", "SSH://", "http://", "://", "ssh:", "user", "root", "@", ":", "22", "0", "65536", "-1", "+22",
        "[", "]", "::1", "fe80::1%25eth0", "1.2.3.4", "/", "//", "?", "&", "=", "#", "L", "R", "D", "N", "local", "Dynamic",
        "L=8080:host:80", "L=:8080:host:80", "D=1080", "D=:1080", "R=[::1]:2222:localhost:22", "L=[::1:80", "L=]]]:80",
        ";fingerprint=", ";", ",", "SHA256:", "MD5:", "%", "%2", "%zz", "%00", "%0a", "%20", "%40", "%3a", "%2f", "%25", "+", "\"",
        " ", "\t", "\n", "\r", "\u202e", "\u0000", "é", "ж", "\uD83D\uDE00", "..", ".", "-", "_", "*", "~", "a".repeat(300),
        "1".repeat(20), "host", "example.com", "evil.example", ";type=dir", "fingerprint=a,type=d",
    )

    private val probes = listOf(
        "ssh://ops%40prod-db.example.com" + "%20".repeat(24) + "@evil.example",
        "ssh://a@b@host", "ssh://%1b%5b31mroot@host", "ssh://root%0a@host", "ssh://host#%e2%80%aeeman%20tsoh",
        "ssh://host#" + "%20".repeat(3) + "a".repeat(50), "ssh://host#" + "n".repeat(1500),
        "ssh://user;fingerprint=%1b%5b2J%1b%5bH@host", "ssh://user;fingerprint=" + "x".repeat(1500) + "@host",
        "ssh://user;FINGERPRINT=SHA256%3Aabc@host", "ssh://user;fingerprint=SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@host",
        "ssh://user;fingerprint=SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+/@host", "ssh://user;fingerprint@host",
        "ssh://;fingerprint=x@host", "ssh://user;fingerprint=a,fingerprint=b@host", "ssh://user;fingerprint=a;fingerprint=b@host",
        "ssh://host?L=99999:host:80", "ssh://host?L=0:host:80", "ssh://host?L=-5:host:80", "ssh://host?L=8080:host:99999",
        "ssh://host?L=8080:host:0", "ssh://host?D=1080", "ssh://host?D=:1080", "ssh://host?D=*:1080", "ssh://host?D=0.0.0.0:1080",
        "ssh://host?L=8080:h:80&L=8080:h:80&L=8080:h:80", "ssh://host?" + (1..200).joinToString("&") { "L=${it + 1000}:h:80" },
        "ssh://host?N=0", "ssh://host?n", "ssh://host?l=8080:h:80", "ssh://host?LOCAL=8080:h:80", "ssh://host?L=8080%20:h:80",
        "ssh://host?L=8080:h%20x:80", "ssh://host?L=8080:h:80%20extra", "ssh://host?L=[[[[:80", "ssh://host?L=8080:[::1]:80",
        "ssh://host?L=8080:[::1:80", "ssh://host?L=8080:[host:80]:-1", "ssh://host?L=80%2280:h:80", "ssh://host?D=80%2280",
        "ssh://host?L=%01:8080:h:80", "ssh://host?R=8080:h:80", "sftp://host/%00%0a/x", "sftp://host/..%2f..%2fetc",
        "sftp://host//srv//", "sftp://host/srv/;type=dir", "ssh://[fe80::1%25eth0]", "ssh://[fe80::1%eth0]", "ssh://[1.2.3.4]",
        "ssh://[:::::]:22", "ssh://host:+22", "ssh://host:022", "ssh://host:0x16", "ssh:host", "ssh:/host", "ssh:///",
        "ssh://host?#frag", "ssh://host/?x=1", "ssh://a_b-c.example_", "ssh://пример.рф", "ssh://xn--e1afmkfd.xn--p1ai",
        "ssh://-host", "ssh://host.", "ssh://host%00", "ssh://ho%20st", "ssh://user@host:22/path?L=1:h:2#name",
        "SFTP://User@Host:2222/Srv/Data/#Prod", "  ssh://host  ", "ssh://host\u0000", "ssh://" + "h".repeat(3000),
        "ssh://" + "!".repeat(3000), "ssh://h?" + "L=8080:h:80&".repeat(300),
    )

    @Test
    fun `parse never throws, a parsed link is one the editor could have made, and a reason is one clean line`() {
        val rnd = Random(20260920)
        var parsed = 0
        var malformed = 0
        repeat(CORPUS) {
            val text = buildString {
                repeat(rnd.nextInt(14)) { append(atoms[rnd.nextInt(atoms.size)]) }
                if (rnd.nextInt(4) == 0) repeat(rnd.nextInt(8)) { append(rnd.nextInt(0x80).toChar()) }
                if (rnd.nextInt(16) == 0) repeat(rnd.nextInt(4)) { append(rnd.nextInt(0x10000).toChar()) }
            }
            if (check(text)) parsed++ else malformed++
        }
        for (probe in probes) check(probe)
        // The corpus has to reach both outcomes in numbers for the invariants to have been tried.
        assertTrue(parsed > 1_000 && malformed > 1_000, "the corpus parsed $parsed and refused $malformed")
    }

    /** Runs [text] through the parser and the invariants; true when it parsed. */
    private fun check(text: String): Boolean {
        val shown = text.take(160).replace("\n", "\\n").replace("\u0000", "\\0").replace("\u202e", "\\u202e")
        val result = try {
            SshLink.parse(text)
        } catch (e: Throwable) {
            throw AssertionError("parse threw on ${text.length} characters: $shown", e)
        }
        when (result) {
            is SshLink.Result.Malformed -> {
                val reason = result.reason
                assertTrue(reason.isNotBlank(), "an empty reason for: $shown")
                assertTrue(reason.none { it.isInvisible() }, "a reason with an invisible character in it, for: $shown")
                assertTrue(reason.length <= REASON_MAX, "a reason of ${reason.length} characters for: $shown")
                assertTrue(reason.endsWith("."), "a reason that is not a sentence, for: $shown")
                return false
            }
            is SshLink.Result.Parsed -> {
                val l = result.link
                assertTrue(text.length <= SshLink.MAX_LENGTH, "a link over the bound parsed: ${text.length} characters")
                assertTrue(l.host.isNotEmpty() && (HOST_NAME.matches(l.host) || IPV6.matches(l.host)), "host “${l.host.take(80)}” from: $shown")
                assertTrue(l.port in 1..65535, "port ${l.port} from: $shown")
                l.user?.let { u ->
                    assertTrue(u.isNotEmpty(), "an empty user from: $shown")
                    assertTrue(u.none { it.isWhitespace() || it.isInvisible() }, "user “${u.take(80)}” from: $shown")
                }
                l.name?.let { n ->
                    assertTrue(n.isNotEmpty() && n.length <= SshLink.MAX_NAME_LENGTH, "a name of ${n.length} characters from: $shown")
                    assertTrue(n.none { it.isInvisible() }, "name “${n.take(80)}” from: $shown")
                    assertEquals(n, n.trim(), "a name with space around it from: $shown")
                }
                l.fingerprint?.let { assertTrue(it.isNotEmpty(), "an empty fingerprint from: $shown") }
                assertTrue(l.forwards.size <= SshLink.MAX_FORWARDS, "${l.forwards.size} forwards from: $shown")
                for (f in l.forwards) {
                    assertTrue(f.bindPort in 1..65535, "bind port ${f.bindPort} from: $shown")
                    assertTrue(HOST_NAME.matches(f.bindAddress) || IPV6.matches(f.bindAddress), "bind address “${f.bindAddress}” from: $shown")
                    if (f.type == TunnelType.DYNAMIC) {
                        assertEquals("" to 0, f.destinationHost to f.destinationPort, "a dynamic forward with a destination from: $shown")
                    } else {
                        assertTrue(f.destinationPort in 1..65535, "destination port ${f.destinationPort} from: $shown")
                        assertTrue(HOST_NAME.matches(f.destinationHost) || IPV6.matches(f.destinationHost), "destination “${f.destinationHost}” from: $shown")
                    }
                }
                if (l.scheme == SshLink.Scheme.SSH) assertNull(l.path, "an ssh:// link with a path from: $shown")
                l.path?.let { p -> assertTrue(p.startsWith("/") && !p.endsWith("/"), "path “$p” from: $shown") }
                assertEquals(l.scheme == SshLink.Scheme.SSH && !l.shell, l.tunnelsOnly)
                assertTrue(l.forwards.isEmpty() || !l.shell, "a shell beside forwards from: $shown")
                assertNotNull(l.target)
                return true
            }
        }
    }

    private fun Char.isInvisible(): Boolean =
        isISOControl() || category == CharCategory.FORMAT || category == CharCategory.LINE_SEPARATOR || category == CharCategory.PARAGRAPH_SEPARATOR

    private companion object {
        /** Strings in the corpus; under two seconds. */
        const val CORPUS = 300_000

        /** The longest fixed text of a reason, with the most a quoted part may be and its ellipsis. */
        const val REASON_MAX = 100 + SshLink.QUOTED_MAX

        // The host forms as the parser states them, written out here so the test says what a host is on its own.
        val HOST_NAME = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?$""")
        val IPV6 = Regex("""^[0-9A-Fa-f:.]+(?:%[A-Za-z0-9._-]+)?$""")
    }
}
