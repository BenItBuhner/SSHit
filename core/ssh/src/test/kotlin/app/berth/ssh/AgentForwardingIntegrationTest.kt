package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent forwarding end to end against the two local sshds (see [SshIntegrationTest] for the
 * variables): Berth logs in to the first with the test key and forwards its agent, and the
 * programs a user would run there, `ssh-add`, `ssh` to the second sshd and `ssh-keygen -Y sign`,
 * talk to it through the `SSH_AUTH_SOCK` the server makes. The user's answer comes from a scripted
 * [AgentApprover]; the app's sheet drives the same answer in its own live test.
 */
class AgentForwardingIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val keyFile = System.getenv("SSH_TEST_KEY_FILE").orEmpty()
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = ArrayList<SshConnection>()

    @Before
    fun requireServer() {
        assumeTrue("set SSH_TEST_HOST/PORT/USER/PASSWORD to run", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        assumeTrue("set SSH_TEST_KEY_FILE to a key authorised for the test user", keyFile.isNotEmpty())
        SshSecurity.ensureProviders()
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        scope.cancel()
    }

    /** Answers every request with [allow] and keeps what it was asked. */
    private class Approver(@Volatile var allow: Boolean = true) : AgentApprover {
        val asked = CopyOnWriteArrayList<AgentSignRequest>()
        override suspend fun approve(request: AgentSignRequest): Boolean {
            asked += request
            return allow
        }
    }

    private val keyText by lazy { File(keyFile).readText() }
    private val provider by lazy { SshKeys.load(keyText) }
    private val publicLine by lazy { SshKeys.openSshPublic(provider.public) }

    private fun keyAuth() = SshAuth.PublicKey(provider, agentKey = AgentKey.software(provider, "berth-test"))

    private fun connect(auth: List<SshAuth>): SshConnection = runBlocking {
        SshConnection(SshEndpoint(host = host, port = port, user = user, auth = auth, keepaliveSeconds = 5), AcceptAllHostKeys)
            .also { opened += it }
            .apply { connect() }
    }

    /**
     * A shell on the first sshd with its echo and prompt off, read into one buffer; [run] types a
     * command and waits for the status line it prints after it, so each call returns what that
     * command wrote and its exit status.
     */
    private inner class RemoteShell(val channel: ShellChannel) {
        private val out = StringBuffer()
        private var next = 0

        init {
            scope.launch { runCatching { channel.output().collect { out.append(it.toString(Charsets.UTF_8)) } } }
            run("stty -echo; bind 'set enable-bracketed-paste off' 2>/dev/null; PS1=''; PS2=''; export PS1 PS2")
        }

        fun run(command: String, timeoutMillis: Long = 30_000): Pair<String, Int> {
            val id = next++
            val from = out.length
            // One line, so nothing is left for the command to read as input; the marker is split as typed,
            // so only its output matches.
            channel.write("$command; echo \"__berth\"\"_done_$id:\$?\"\n")
            val marker = Regex("__berth_done_$id:(\\d+)")
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val text = out.substring(from)
                marker.find(text)?.let { return plain(text.substring(0, it.range.first)) to it.groupValues[1].toInt() }
                Thread.sleep(25)
            }
            throw AssertionError("no status from `$command` in $timeoutMillis ms; the shell said: ${out.substring(from)}")
        }

        /** Without carriage returns and the terminal modes readline sets around each line. */
        private fun plain(text: String) = text.replace(Regex("\u001b\\[[?0-9;]*[A-Za-z]"), "").replace("\r", "")
    }

    private fun shell(connection: SshConnection, agent: SshAgent?): RemoteShell =
        RemoteShell(runBlocking { connection.openShell(120, 40, agent = agent) })

    private val sshToSecond: String
        get() {
            assumeTrue("set SSH_TEST_JUMP_PORT to a second sshd to run", jumpPort != null)
            return "ssh -n -p $jumpPort -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes " +
                "-o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o IdentityFile=none -o LogLevel=ERROR $user@$host"
        }

    @Test
    fun `with forwarding on, ssh-add on the first host lists the key that logged in, and only it`() {
        val approver = Approver()
        val connection = connect(listOf(keyAuth()))
        assertIs<SshAuth.PublicKey>(connection.authenticatedWith)
        val sh = shell(connection, SshAgent(connection.agentKey, approver))
        assertTrue(sh.channel.agentForwarded, "the server agreed to forward")

        val (sock, _) = sh.run("printf '[%s]\\n' \"\$SSH_AUTH_SOCK\"")
        assertNotEquals("[]", sock.trim(), "the server made an SSH_AUTH_SOCK")
        val (listed, status) = sh.run("ssh-add -L")
        assertEquals(0, status, listed)
        val lines = listed.trim().lines()
        assertEquals(1, lines.size, listed)
        assertEquals(publicLine.split(' ').take(2), lines.single().split(' ').take(2))
        assertTrue(lines.single().endsWith("berth-test"), "the comment rides along: $listed")
        assertTrue(approver.asked.isEmpty(), "listing needs no approval")
    }

    @Test
    fun `ssh from the first host to the second authenticates through the forwarded agent`() {
        val approver = Approver()
        val connection = connect(listOf(keyAuth()))
        val sh = shell(connection, SshAgent(connection.agentKey, approver))
        val (said, status) = sh.run("$sshToSecond 'echo hop-ok from \$(hostname) on \$SSH_CONNECTION'")
        assertEquals(0, status, said)
        assertTrue(said.contains("hop-ok"), said)
        assertTrue(said.contains(" $jumpPort"), "the second sshd answered: $said")

        val request = approver.asked.single()
        assertEquals(connection.agentKey, request.key)
        val login = assertIs<AgentSignPurpose.Login>(request.purpose)
        assertEquals(user, login.user)
        assertEquals("ssh-connection", login.service)
        // OpenSSH 8.9 on binds the request to the destination's host key, which the sheet shows.
        assertNotNull(login.serverFingerprint)
    }

    @Test
    fun `with forwarding off the first host has no SSH_AUTH_SOCK`() {
        val connection = connect(listOf(keyAuth()))
        val sh = shell(connection, agent = null)
        assertTrue(!sh.channel.agentForwarded)
        assertEquals("[]", sh.run("printf '[%s]\\n' \"\$SSH_AUTH_SOCK\"").first.trim())
        val (said, status) = sh.run("ssh-add -L")
        assertNotEquals(0, status)
        assertTrue(said.contains("Could not open a connection to your authentication agent"), said)
    }

    @Test
    fun `a signature with the held key verifies, and one asked of a key the agent does not hold is refused unasked`() {
        val approver = Approver()
        val connection = connect(listOf(keyAuth()))
        val sh = shell(connection, SshAgent(connection.agentKey, approver))
        sh.run("d=\$(mktemp -d) && cd \"\$d\" && printf 'commit\\n' > msg && printf '%s\\n' '$publicLine' > held.pub")

        val (signed, signedStatus) = sh.run("ssh-keygen -Y sign -f held.pub -n git msg")
        assertEquals(0, signedStatus, signed)
        val (checked, checkedStatus) = sh.run("ssh-keygen -Y check-novalidate -n git -s msg.sig < msg")
        assertEquals(0, checkedStatus, checked)
        assertTrue(checked.contains("Good \"git\" signature"), checked)
        assertEquals("git", assertIs<AgentSignPurpose.SshSig>(approver.asked.single().purpose).namespace)

        sh.run("ssh-keygen -q -t ed25519 -N '' -f other && rm other")
        val (refused, refusedStatus) = sh.run("ssh-keygen -Y sign -f other.pub -n git msg")
        assertNotEquals(0, refusedStatus, refused)
        assertEquals(1, approver.asked.size, "the user is never asked about a key the agent does not hold")
        sh.run("cd / && rm -rf \"\$d\"")
    }

    @Test
    fun `adding and removing keys through the forwarded agent is refused`() {
        val approver = Approver()
        val connection = connect(listOf(keyAuth()))
        val sh = shell(connection, SshAgent(connection.agentKey, approver))
        sh.run("d=\$(mktemp -d) && cd \"\$d\" && ssh-keygen -q -t ed25519 -N '' -f extra && printf '%s\\n' '$publicLine' > held.pub")
        val (added, addStatus) = sh.run("ssh-add extra")
        assertNotEquals(0, addStatus, added)
        val (removed, removeStatus) = sh.run("ssh-add -d held.pub")
        assertNotEquals(0, removeStatus, removed)
        val (cleared, clearStatus) = sh.run("ssh-add -D")
        assertNotEquals(0, clearStatus, cleared)
        val (listed, _) = sh.run("ssh-add -L")
        assertEquals(1, listed.trim().lines().size, "still the one key: $listed")
        assertTrue(approver.asked.isEmpty())
        sh.run("cd / && rm -rf \"\$d\"")
    }

    @Test
    fun `a deny answers the remote with an agent failure, and its ssh falls through cleanly`() {
        val approver = Approver(allow = false)
        val connection = connect(listOf(keyAuth()))
        val sh = shell(connection, SshAgent(connection.agentKey, approver))
        val (said, status) = sh.run(sshToSecond + " 'echo should-not-run'")
        assertEquals(255, status, said)
        assertTrue(said.contains("Permission denied"), said)
        assertTrue(!said.contains("should-not-run"), said)
        assertEquals(1, approver.asked.size)

        // The shell and the agent are both still there; the next request, allowed, goes through.
        approver.allow = true
        val (again, againStatus) = sh.run("$sshToSecond 'echo hop-ok'")
        assertEquals(0, againStatus, again)
        assertTrue(again.contains("hop-ok"), again)
        assertEquals(2, approver.asked.size)
    }

    @Test
    fun `a login a key did not make leaves the forwarded agent empty`() {
        // A key the server refuses is tried first; the password is what lets the user in.
        val stranger = SshKeys.load(SshKeys.openSshPrivate(SshKeys.generate(KeyAlgorithm.ED25519)))
        val connection = connect(
            listOf(SshAuth.PublicKey(stranger, agentKey = AgentKey.software(stranger, "stranger")), SshAuth.Password { password.toCharArray() }),
        )
        assertIs<SshAuth.Password>(connection.authenticatedWith)
        assertNull(connection.agentKey)
        val sh = shell(connection, SshAgent(connection.agentKey, Approver()))
        assertTrue(sh.channel.agentForwarded)
        val (said, status) = sh.run("ssh-add -L")
        assertEquals(1, status, said)
        assertTrue(said.contains("The agent has no identities."), said)
    }
}
