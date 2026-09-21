package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vectors are `ssh-keygen -lvf` output (OpenSSH 9.6) for three keys of three types, so the art,
 * the border captions and the `-lf` line are compared with what a server prints, not with itself.
 */
class RandomartTest {
    init {
        SshSecurity.ensureProviders()
    }

    private val ed25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEvvFaN19TkA+KZ9dmGTs8aSHQJL/LfddBsOAkRS+xZz ubuntu@cursor"
    private val ecdsa = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBuM0W3Lx7HF5yXxhe64tSw17KkazzIa1SSHbmsFutgsKThOXVPP3+UFjc7Ms52yrbHuxOSH4KscfXi749S/5NE= ubuntu@cursor"
    private val rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQC22GYg41mIIIJ0UhOtHiutrFDM/cRsh180UdEKRAH1jPuSSYK/SJ9L4wUJNBKjTJSNSts4O/6almvIDb10vrd5ElAJ6PobAza5i99RwZACrM8QebYMwqpi4q6pK+R5qQ9/AXBy8qvcrXlkQCfRlH7MSkhkIKsCwCMlLz+phV9Q3SKUUOhW/sztFfWZ9cBu543kuGPCpW5hRP3DYv9GIkm5+d06xm6T9KvR7TRq1MyW0TP+iqL20GdGmXYBto3U1HMhRp5GVrc0FY35Hccr+cN4QlNS8YKCGmEpln54lBi6uXVFUR89E3oCUPHfuySpmeg/NpiJwHwpHSZ5I3PwXyAje3ezA2K5gtUYMpI0GSDJXP0+y3TEu9VEZGCvTjhHXs7vKGfdmn3GfaH46J/YUVM21mGoRDEz9/8lYSl5rGukthN95qjQ4R5PeZ4GS5F5f03adxyvm5yJbw37Otcj2SZDNfi0GO+zPRItUQnzjmdM8IJ1IwVwAcxEmBjZwBxAwh8= vec@rsa"

    @Test
    fun `ed25519 art matches ssh-keygen`() {
        val expected = """
            +--[ED25519 256]--+
            |       ...o +=*.O|
            |       .+. .o= B=|
            |       o.*.o+ =.+|
            |        *+o*.o .+|
            |       .S.+.o+o o|
            |      +     ..+. |
            |     . oE    .   |
            |     . ..        |
            |      +o         |
            +----[SHA256]-----+
        """.trimIndent()
        assertEquals(expected, Randomart.of(SshKeys.parseOpenSshPublic(ed25519)))
    }

    @Test
    fun `ecdsa art matches ssh-keygen, its shorter caption centred the same way`() {
        val expected = """
            +---[ECDSA 256]---+
            |                 |
            |              .  |
            |           . o . |
            |          . + = .|
            |        S  + o +.|
            |      .o.o* o .. |
            |     =ooo+oE o.  |
            |    ooB+Oo.++..  |
            |     =+OB**...   |
            +----[SHA256]-----+
        """.trimIndent()
        assertEquals(expected, Randomart.of(SshKeys.parseOpenSshPublic(ecdsa)))
    }

    @Test
    fun `rsa art matches ssh-keygen, odd padding falling to the right`() {
        val expected = """
            +---[RSA 3072]----+
            |     E....=.     |
            |     .  .o+O     |
            |      . o+*+=  . |
            |       o oo*+ o +|
            |        S . .+++=|
            |         . ..=+=*|
            |           .o=*.=|
            |            +o.+.|
            |             . ..|
            +----[SHA256]-----+
        """.trimIndent()
        assertEquals(expected, Randomart.of(SshKeys.parseOpenSshPublic(rsa)))
    }

    @Test
    fun `art from the base64 blob is the same art`() {
        val key = SshKeys.parseOpenSshPublic(ed25519)
        assertEquals(Randomart.of(key), Randomart.of(SshKeys.publicKeyBase64(key)))
    }

    @Test
    fun `every art is eleven lines of nineteen columns`() {
        for (algorithm in KeyAlgorithm.entries) {
            val lines = Randomart.lines(SshKeys.generate(algorithm).public)
            assertEquals(Randomart.HEIGHT + 2, lines.size, "$algorithm")
            assertTrue(lines.all { it.length == Randomart.WIDTH + 2 }, "$algorithm: ${lines.map { it.length }}")
            val board = lines.drop(1).dropLast(1).joinToString("")
            assertEquals(1, board.count { it == 'E' }, "$algorithm marks the end once")
            // The start is S unless the bishop ended where it began: then E is written over it, as ssh-keygen writes it.
            val centre = lines[1 + Randomart.HEIGHT / 2][1 + Randomart.WIDTH / 2]
            assertTrue(centre == 'S' || centre == 'E', "$algorithm: the centre is '$centre'")
            assertEquals(if (centre == 'E') 0 else 1, board.count { it == 'S' }, "$algorithm marks the start once, unless the walk ended on it")
        }
    }

    /**
     * A walk that comes home: `0x0F` is two steps down-right then two back, so the bishop ends on the
     * start and the end mark takes the square, as `ssh-keygen` writes it (the start is set first, the
     * end after). One key in a few dozen does this, which is why the art is not asked for both marks.
     */
    @Test
    fun `the end is written over the start when the bishop comes home`() {
        val lines = Randomart.lines(byteArrayOf(0x0F), "[T]", "[F]")
        val board = lines.drop(1).dropLast(1).joinToString("")
        assertEquals('E', lines[1 + Randomart.HEIGHT / 2][1 + Randomart.WIDTH / 2])
        assertEquals(0, board.count { it == 'S' })
        assertEquals(1, board.count { it == 'E' })
        // The squares the walk crossed on its way out and back: each stepped on twice.
        assertEquals('o', lines[1 + Randomart.HEIGHT / 2 + 1][1 + Randomart.WIDTH / 2 + 1])
        assertEquals('.', lines[1 + Randomart.HEIGHT / 2 + 2][1 + Randomart.WIDTH / 2 + 2])
    }

    @Test
    fun `keygen line reads as ssh-keygen -lf prints it`() {
        assertEquals("256 SHA256:3VSVbH/UpVda/yIkYbg8HOTP9URGEpHmb4zAsDr2ulw ubuntu@cursor (ED25519)", SshKeys.keygenLine(SshKeys.parseOpenSshPublic(ed25519), "ubuntu@cursor"))
        assertEquals("256 SHA256:tq3w7xubOIZmjPlM7SZ+E1ct/DHht+rBMsTOk4q70Nc ubuntu@cursor (ECDSA)", SshKeys.keygenLine(SshKeys.parseOpenSshPublic(ecdsa), "ubuntu@cursor"))
        assertEquals("3072 SHA256:XNOSNNvmBJPOvQHA99dvS3j1t1QOa6bJdxPKkSlIpBA vec@rsa (RSA)", SshKeys.keygenLine(SshKeys.parseOpenSshPublic(rsa), "vec@rsa"))
        assertEquals("3072 SHA256:XNOSNNvmBJPOvQHA99dvS3j1t1QOa6bJdxPKkSlIpBA no comment (RSA)", SshKeys.keygenLine(SshKeys.parseOpenSshPublic(rsa)))
    }

    @Test
    fun `type names and sizes follow ssh-keygen`() {
        assertEquals("ED25519", SshKeys.keygenType("ssh-ed25519"))
        assertEquals("ECDSA", SshKeys.keygenType("ecdsa-sha2-nistp384"))
        assertEquals("RSA", SshKeys.keygenType("ssh-rsa"))
        assertEquals("DSA", SshKeys.keygenType("ssh-dss"))
        assertEquals("ED25519-SK", SshKeys.keygenType("sk-ssh-ed25519@openssh.com"))
        assertEquals("ECDSA-SK", SshKeys.keygenType("sk-ecdsa-sha2-nistp256@openssh.com"))
        assertEquals("ED25519-CERT", SshKeys.keygenType("ssh-ed25519-cert-v01@openssh.com"))
        assertEquals(384, SshKeys.bits(SshKeys.generate(KeyAlgorithm.ECDSA_P384).public))
        assertEquals(4096, SshKeys.bits(SshKeys.generate(KeyAlgorithm.RSA_4096).public))
    }

    @Test
    fun `the server command names the host key file for the type`() {
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", SshKeys.serverFingerprintCommand("ssh-ed25519"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub", SshKeys.serverFingerprintCommand("ecdsa-sha2-nistp256"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub", SshKeys.serverFingerprintCommand("ssh-rsa"))
    }
}
