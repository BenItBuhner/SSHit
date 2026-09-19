package app.berth.ssh

import app.berth.domain.model.TunnelType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshConfigParserTest {
    private val sample = """
        # Work hosts
        Host *
            ServerAliveInterval 30
            IdentityFile ~/.ssh/id_ed25519
            Compression yes

        Host prod-web prod-web-alt
            HostName 10.0.0.12
            User deploy
            Port 2200
            IdentityFile ~/.ssh/work_ed25519
            ProxyJump bastion
            LocalForward 8080 localhost:80
            LocalForward 127.0.0.1:5432 db.internal:5432
            RemoteForward 9000 127.0.0.1:3000
            DynamicForward 1080
            ForwardAgent yes

        Host bastion
          HostName=bastion.example.com
          User = ben
          AddressFamily inet6
          RemoteCommand "tmux new -A -s berth"

        Host git*
            User git

        Host github.com
            HostName github.com

        Match host prod-*
            User nobody

        Host !*-alt prod-*
            Port 22
    """.trimIndent()

    @Test
    fun `concrete aliases become hosts with first-value-wins semantics`() {
        val result = SshConfigParser.parse(sample)
        assertEquals(listOf("prod-web", "prod-web-alt", "bastion", "github.com"), result.hosts.map { it.alias })

        val prod = result.hosts.first { it.alias == "prod-web" }
        assertEquals("10.0.0.12", prod.hostName)
        assertEquals("deploy", prod.user)
        assertEquals(2200, prod.port, "the Host block's Port comes before the trailing block")
        assertEquals(listOf("bastion"), prod.proxyJump)
        assertEquals(listOf("~/.ssh/id_ed25519", "~/.ssh/work_ed25519"), prod.identityFiles)
        assertEquals(true, prod.compression)
        assertEquals(30, prod.serverAliveInterval)
        assertEquals(true, prod.forwardAgent)
        assertNull(prod.remoteCommand)

        val bastion = result.hosts.first { it.alias == "bastion" }
        assertEquals("bastion.example.com", bastion.hostName)
        assertEquals("ben", bastion.user)
        assertEquals(22, bastion.port)
        assertEquals("inet6", bastion.addressFamily)
        assertEquals("tmux new -A -s berth", bastion.remoteCommand)
        assertEquals(listOf("~/.ssh/id_ed25519"), bastion.identityFiles)

        val github = result.hosts.first { it.alias == "github.com" }
        assertEquals("git", github.user, "wildcard blocks apply to matching concrete aliases")
    }

    @Test
    fun `forwards map onto tunnels`() {
        val prod = SshConfigParser.parse(sample).hosts.first { it.alias == "prod-web" }
        assertEquals(
            listOf(
                SshConfigForward(TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80),
                SshConfigForward(TunnelType.LOCAL, "127.0.0.1", 5432, "db.internal", 5432),
                SshConfigForward(TunnelType.REMOTE, "127.0.0.1", 9000, "127.0.0.1", 3000),
                SshConfigForward(TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0),
            ),
            prod.forwards,
        )
        assertEquals(SshConfigForward(TunnelType.LOCAL, "0.0.0.0", 8080, "::1", 80), SshConfigParser.parseForward(TunnelType.LOCAL, "*:8080 [::1]:80"))
        assertEquals(SshConfigForward(TunnelType.DYNAMIC, "0.0.0.0", 1080, "", 0), SshConfigParser.parseForward(TunnelType.DYNAMIC, "*/1080"))
        assertNull(SshConfigParser.parseForward(TunnelType.LOCAL, "8080"))
    }

    @Test
    fun `Match and Include are reported instead of guessed`() {
        val result = SshConfigParser.parse(sample + "\nInclude ~/.ssh/config.d/*\n")
        assertTrue(result.notes.any { it.startsWith("1 Match block was skipped") }, result.notes.toString())
        assertTrue(result.notes.any { it.startsWith("Include ~/.ssh/config.d/*") }, result.notes.toString())
        assertNull(result.hosts.firstOrNull { it.user == "nobody" })
    }

    @Test
    fun `negated patterns exclude and HostName expands the alias token`() {
        assertTrue(SshConfigParser.matches("prod-web", listOf("!*-alt", "prod-*")))
        assertTrue(!SshConfigParser.matches("prod-web-alt", listOf("!*-alt", "prod-*")))
        val hosts = SshConfigParser.parse("Host box\n  HostName %h.example.org\n").hosts
        assertEquals("box.example.org", hosts.single().hostName)
    }

    @Test
    fun `empty and comment-only input yields nothing`() {
        assertEquals(emptyList(), SshConfigParser.parse("# nothing here\n\n").hosts)
        assertEquals(emptyList(), SshConfigParser.parse("").hosts)
        val defaultsOnly = SshConfigParser.parse("Host *\n  User root\n")
        assertEquals(emptyList(), defaultsOnly.hosts)
    }
}
