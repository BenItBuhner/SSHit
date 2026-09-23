package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import com.hierynomus.sshj.signature.SignatureEdDSA
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-app agent on its own, message by message: what it answers, what it refuses without asking,
 * that its signatures verify for each key type Berth makes, and how it reads what it is asked to sign.
 */
class SshAgentTest {
    @Before
    fun providers() = SshSecurity.ensureProviders()

    private val ed25519: KeyPair by lazy { SshKeys.generate(KeyAlgorithm.ED25519) }

    private fun softwareKey(pair: KeyPair, comment: String = "berth@phone") =
        AgentKey.software(SshKeys.load(SshKeys.openSshPrivate(pair, comment)), comment)

    /** Records what it was asked and answers [allow]. */
    private class Approver(private val allow: Boolean = true) : AgentApprover {
        val asked = ArrayList<AgentSignRequest>()
        override suspend fun approve(request: AgentSignRequest): Boolean {
            asked += request
            return allow
        }
    }

    private fun agent(key: AgentKey?, approver: AgentApprover = Approver()) = SshAgent(key, approver)

    private fun ask(agent: SshAgent, message: ByteArray): ByteArray = runBlocking { agent.handle(message) }

    private fun message(type: Int, body: DataOutputStream.() -> Unit = {}): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply {
            writeByte(type)
            body()
        }
    }.toByteArray()

    private fun DataOutputStream.string(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.string(text: String) = string(text.toByteArray(Charsets.UTF_8))

    private fun signRequest(blob: ByteArray, data: ByteArray, flags: Int = 0) = message(SshAgent.SIGN_REQUEST) {
        string(blob)
        string(data)
        writeInt(flags)
    }

    private fun isFailure(reply: ByteArray) = reply.size == 1 && reply[0].toInt() == SshAgent.FAILURE

    /** The signature blob out of a sign response. */
    private fun signatureOf(reply: ByteArray): ByteArray {
        assertEquals(SshAgent.SIGN_RESPONSE, reply[0].toInt(), "a sign response")
        val r = AgentReader(reply, 1)
        return r.string().also { assertEquals(0, r.remaining) }
    }

    private fun verifies(verifier: Signature, pair: KeyPair, data: ByteArray, blob: ByteArray): Boolean {
        verifier.initVerify(pair.public)
        verifier.update(data)
        return verifier.verify(blob)
    }

    private fun algorithmOf(blob: ByteArray) = AgentReader(blob).utf8()

    @Test
    fun `an agent with no key lists no identities`() {
        val reply = ask(agent(null), message(SshAgent.REQUEST_IDENTITIES))
        assertEquals(SshAgent.IDENTITIES_ANSWER, reply[0].toInt())
        val r = AgentReader(reply, 1)
        assertEquals(0L, r.uint32())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `the one held key is listed with its blob and comment, and nothing else`() {
        val key = softwareKey(ed25519, "ben@pixel")
        val reply = ask(agent(key), message(SshAgent.REQUEST_IDENTITIES))
        assertEquals(SshAgent.IDENTITIES_ANSWER, reply[0].toInt())
        val r = AgentReader(reply, 1)
        assertEquals(1L, r.uint32())
        assertContentEquals(SshKeys.publicKeyBlob(ed25519.public), r.string())
        assertEquals("ben@pixel", r.utf8())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `an approved request is signed, and the signature verifies against the key`() {
        val approver = Approver()
        val key = softwareKey(ed25519)
        val data = "challenge".toByteArray()
        val blob = signatureOf(ask(agent(key, approver), signRequest(key.blob, data)))
        assertEquals("ssh-ed25519", algorithmOf(blob))
        assertTrue(verifies(SignatureEdDSA.Factory().create(), ed25519, data, blob))
        val asked = approver.asked.single()
        assertEquals(key, asked.key)
        assertIs<AgentSignPurpose.Unknown>(asked.purpose)
    }

    @Test
    fun `ECDSA keys sign with their curve's hash`() {
        for ((algorithm, factory) in listOf(KeyAlgorithm.ECDSA_P256 to SignatureECDSA.Factory256(), KeyAlgorithm.ECDSA_P384 to SignatureECDSA.Factory384())) {
            val pair = SshKeys.generate(algorithm)
            val key = softwareKey(pair)
            val data = "challenge for $algorithm".toByteArray()
            val blob = signatureOf(ask(agent(key), signRequest(key.blob, data)))
            assertEquals(algorithm.sshName, algorithmOf(blob))
            assertTrue(verifies(factory.create(), pair, data, blob), "$algorithm signature verifies")
        }
    }

    @Test
    fun `an RSA key signs with the hash the request's flags ask for`() {
        val pair = SshKeys.generate(KeyAlgorithm.RSA_3072)
        val key = softwareKey(pair)
        val data = "challenge".toByteArray()
        val cases = listOf(
            SshAgent.RSA_SHA2_512 to ("rsa-sha2-512" to SignatureRSA.FactoryRSASHA512()),
            SshAgent.RSA_SHA2_256 to ("rsa-sha2-256" to SignatureRSA.FactoryRSASHA256()),
            0 to ("ssh-rsa" to SignatureRSA.FactorySSHRSA()),
        )
        for ((flags, expected) in cases) {
            val (name, factory) = expected
            val blob = signatureOf(ask(agent(key), signRequest(key.blob, data, flags)))
            assertEquals(name, algorithmOf(blob), "flags $flags")
            assertTrue(verifies(factory.create(), pair, data, blob), "$name verifies")
        }
    }

    @Test
    fun `a request for a key the agent does not hold is refused without asking`() {
        val approver = Approver()
        val other = SshKeys.publicKeyBlob(SshKeys.generate(KeyAlgorithm.ED25519).public)
        assertTrue(isFailure(ask(agent(softwareKey(ed25519), approver), signRequest(other, "x".toByteArray()))))
        assertTrue(isFailure(ask(agent(null, approver), signRequest(other, "x".toByteArray()))))
        assertTrue(approver.asked.isEmpty())
    }

    @Test
    fun `a denied request is a failure, and the key never signs`() {
        var signed = false
        val key = AgentKey(ed25519.public, "phone") { _, _ -> signed = true; ByteArray(0) }
        val approver = Approver(allow = false)
        assertTrue(isFailure(ask(agent(key, approver), signRequest(key.blob, "x".toByteArray()))))
        assertEquals(1, approver.asked.size)
        assertFalse(signed)
    }

    @Test
    fun `a key that cannot sign (a cancelled biometric prompt) answers a failure`() {
        val key = AgentKey(ed25519.public, "phone") { _, _ -> throw IllegalStateException("cancelled") }
        assertTrue(isFailure(ask(agent(key), signRequest(key.blob, "x".toByteArray()))))
    }

    @Test
    fun `adding, removing, locking, smartcards, extensions and SSH1 messages are all refused`() {
        val approver = Approver()
        val a = agent(softwareKey(ed25519), approver)
        val refused = listOf(
            message(SshAgent.ADD_IDENTITY) { string("ssh-ed25519") },
            message(SshAgent.REMOVE_IDENTITY) { string(SshKeys.publicKeyBlob(ed25519.public)) },
            message(SshAgent.REMOVE_ALL_IDENTITIES),
            message(SshAgent.ADD_SMARTCARD_KEY) { string("pkcs11"); string("pin") },
            message(SshAgent.REMOVE_SMARTCARD_KEY) { string("pkcs11"); string("pin") },
            message(SshAgent.LOCK) { string("hunter2") },
            message(SshAgent.UNLOCK) { string("hunter2") },
            message(SshAgent.ADD_ID_CONSTRAINED) { string("ssh-ed25519") },
            message(SshAgent.ADD_SMARTCARD_KEY_CONSTRAINED) { string("pkcs11"); string("pin") },
            message(SshAgent.EXTENSION) { string("session-bind@openssh.com"); string(SshKeys.publicKeyBlob(ed25519.public)) },
            message(SshAgent.EXTENSION) { string("query") },
            message(1),
            message(9),
            message(200),
            ByteArray(0),
        )
        for (m in refused) assertTrue(isFailure(ask(a, m)), "type ${m.firstOrNull()} refused")
        assertTrue(approver.asked.isEmpty())
        // Still holding its one key after all of it.
        assertEquals(1L, AgentReader(ask(a, message(SshAgent.REQUEST_IDENTITIES)), 1).uint32())
    }

    @Test
    fun `a malformed sign request is refused`() {
        val key = softwareKey(ed25519)
        val a = agent(key)
        assertTrue(isFailure(ask(a, message(SshAgent.SIGN_REQUEST))))
        assertTrue(isFailure(ask(a, message(SshAgent.SIGN_REQUEST) { string(key.blob) })))
        assertTrue(isFailure(ask(a, message(SshAgent.SIGN_REQUEST) { writeInt(1_000_000); write(key.blob) })))
    }

    // ---- what is being signed ---------------------------------------------------------------------

    private fun userauth(keyBlob: ByteArray, user: String = "git", method: String = "publickey", hostKey: ByteArray? = null) =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                string(ByteArray(32) { it.toByte() })
                writeByte(50)
                string(user)
                string("ssh-connection")
                string(method)
                writeByte(1)
                string("ssh-ed25519")
                string(keyBlob)
                if (hostKey != null) string(hostKey)
            }
        }.toByteArray()

    @Test
    fun `a userauth request made with the held key reads as a login`() {
        val key = softwareKey(ed25519)
        val purpose = assertIs<AgentSignPurpose.Login>(AgentSignPurpose.of(userauth(key.blob, user = "demo"), key.blob))
        assertEquals("demo", purpose.user)
        assertEquals("ssh-connection", purpose.service)
        assertNull(purpose.serverHostKey)
        assertNull(purpose.serverFingerprint)
    }

    @Test
    fun `a host-bound userauth request names the destination's host key`() {
        val key = softwareKey(ed25519)
        val serverKey = SshKeys.generate(KeyAlgorithm.ED25519).public
        val data = userauth(key.blob, method = "publickey-hostbound-v00@openssh.com", hostKey = SshKeys.publicKeyBlob(serverKey))
        val purpose = assertIs<AgentSignPurpose.Login>(AgentSignPurpose.of(data, key.blob))
        assertEquals("ssh-ed25519", purpose.serverKeyType)
        assertEquals(SshKeys.fingerprintSha256(serverKey), purpose.serverFingerprint)
    }

    @Test
    fun `a userauth request for some other key, or with trailing bytes, is not read as a login`() {
        val key = softwareKey(ed25519)
        val other = SshKeys.publicKeyBlob(SshKeys.generate(KeyAlgorithm.ED25519).public)
        assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of(userauth(other), key.blob))
        assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of(userauth(key.blob) + byteArrayOf(0), key.blob))
        assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of(userauth(key.blob, method = "password"), key.blob))
    }

    @Test
    fun `an SSHSIG blob reads as a signature in its namespace`() {
        val key = softwareKey(ed25519)
        val data = "SSHSIG".toByteArray() + ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                string("git")
                string(ByteArray(0))
                string("sha512")
                string(ByteArray(64))
            }
        }.toByteArray()
        assertEquals("git", assertIs<AgentSignPurpose.SshSig>(AgentSignPurpose.of(data, key.blob)).namespace)
    }

    @Test
    fun `anything else is data of a size`() {
        val key = softwareKey(ed25519)
        assertEquals(5, assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of("hello".toByteArray(), key.blob)).size)
        assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of("SSHSIG".toByteArray(), key.blob))
        assertIs<AgentSignPurpose.Unknown>(AgentSignPurpose.of(ByteArray(0), key.blob))
    }

    @Test
    fun `the approver is told what the request is for`() {
        val approver = Approver()
        val key = softwareKey(ed25519)
        signatureOf(ask(agent(key, approver), signRequest(key.blob, userauth(key.blob, user = "demo"))))
        assertEquals("demo", assertIs<AgentSignPurpose.Login>(approver.asked.single().purpose).user)
    }

    // ---- the channel -----------------------------------------------------------------------------

    /** An agent channel stand-in: what the remote writes, and what the agent answers. */
    private class Pipe {
        val toAgent = PipedOutputStream()
        val agentIn = PipedInputStream(toAgent, 1 shl 20)
        val agentOut = PipedOutputStream()
        val fromAgent = DataInputStream(PipedInputStream(agentOut, 1 shl 20))

        fun send(message: ByteArray) {
            toAgent.write(SshAgent.frame(message))
            toAgent.flush()
        }

        fun reply(): ByteArray = ByteArray(fromAgent.readInt()).also { fromAgent.readFully(it) }
    }

    @Test
    fun `a channel carries framed messages in turn until the remote closes it`() {
        val key = softwareKey(ed25519)
        val a = agent(key)
        val pipe = Pipe()
        val served = thread { a.serve(pipe.agentIn, pipe.agentOut) }
        pipe.send(message(SshAgent.REQUEST_IDENTITIES))
        assertEquals(SshAgent.IDENTITIES_ANSWER, pipe.reply()[0].toInt())
        pipe.send(message(SshAgent.LOCK) { string("x") })
        assertTrue(isFailure(pipe.reply()))
        pipe.send(signRequest(key.blob, "x".toByteArray()))
        assertEquals(SshAgent.SIGN_RESPONSE, pipe.reply()[0].toInt())
        pipe.toAgent.close()
        served.join(5_000)
        assertFalse(served.isAlive, "serve returns at the channel's end")
    }

    @Test
    fun `a frame longer than any agent message ends the channel`() {
        val a = agent(softwareKey(ed25519))
        val pipe = Pipe()
        val served = thread { a.serve(pipe.agentIn, pipe.agentOut) }
        DataOutputStream(pipe.toAgent).apply {
            writeInt(SshAgent.MAX_MESSAGE + 1)
            flush()
        }
        served.join(5_000)
        assertFalse(served.isAlive)
    }

    @Test
    fun `closing the agent withdraws a request waiting on the user and ends its channel`() {
        val waiting = CompletableDeferred<Unit>()
        val withdrawn = CompletableDeferred<Unit>()
        val approver = AgentApprover {
            waiting.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withdrawn.complete(Unit)
            }
        }
        val key = softwareKey(ed25519)
        val a = agent(key, approver)
        val pipe = Pipe()
        val served = thread { a.serve(pipe.agentIn, pipe.agentOut) }
        pipe.send(signRequest(key.blob, "x".toByteArray()))
        runBlocking { withTimeout(5_000) { waiting.await() } }
        a.close()
        runBlocking { withTimeout(5_000) { withdrawn.await() } }
        served.join(5_000)
        assertFalse(served.isAlive)
        assertTrue(a.isClosed)
        assertFalse(a.tryOpenChannel(), "a closed agent takes no more channels")
    }

    @Test
    fun `the remote closing its channel withdraws a request waiting on the user and signs nothing`() {
        val waiting = CompletableDeferred<Unit>()
        val withdrawn = CompletableDeferred<Unit>()
        val approver = AgentApprover {
            waiting.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withdrawn.complete(Unit)
            }
        }
        val soft = softwareKey(ed25519)
        val signed = AtomicInteger()
        val key = AgentKey(soft.publicKey, soft.comment) { data, flags ->
            signed.incrementAndGet()
            soft.sign(data, flags)
        }
        val a = agent(key, approver)
        val pipe = Pipe()
        val served = thread { a.serve(pipe.agentIn, pipe.agentOut) }
        pipe.send(signRequest(key.blob, "x".toByteArray()))
        runBlocking { withTimeout(5_000) { waiting.await() } }
        pipe.toAgent.close()
        runBlocking { withTimeout(5_000) { withdrawn.await() } }
        served.join(5_000)
        assertFalse(served.isAlive, "serve returns at the channel's end")
        assertEquals(0, signed.get(), "nothing was signed")
        assertEquals(0, pipe.fromAgent.available(), "nothing was answered")
        assertFalse(a.isClosed, "the agent still serves the connection's other channels")

        val other = Pipe()
        val servedOther = thread { a.serve(other.agentIn, other.agentOut) }
        other.send(message(SshAgent.REQUEST_IDENTITIES))
        assertEquals(SshAgent.IDENTITIES_ANSWER, other.reply()[0].toInt())
        other.toAgent.close()
        servedOther.join(5_000)
        assertFalse(servedOther.isAlive)
    }

    @Test
    fun `a frame sent while a request waits is answered after it, in turn`() {
        val release = CompletableDeferred<Unit>()
        val approver = AgentApprover {
            release.await()
            true
        }
        val key = softwareKey(ed25519)
        val a = agent(key, approver)
        val pipe = Pipe()
        val served = thread { a.serve(pipe.agentIn, pipe.agentOut) }
        pipe.send(signRequest(key.blob, "x".toByteArray()))
        pipe.send(message(SshAgent.REQUEST_IDENTITIES))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (pipe.agentIn.available() > 0) {
            assertTrue(System.nanoTime() < deadline, "the agent reads the second frame while the first waits")
            Thread.sleep(5)
        }
        release.complete(Unit)
        assertEquals(SshAgent.SIGN_RESPONSE, pipe.reply()[0].toInt())
        assertEquals(SshAgent.IDENTITIES_ANSWER, pipe.reply()[0].toInt())
        pipe.toAgent.close()
        served.join(5_000)
        assertFalse(served.isAlive)
    }

    @Test
    fun `an agent takes a bounded number of channels at once`() {
        val a = agent(null)
        repeat(SshAgent.MAX_CHANNELS) { assertTrue(a.tryOpenChannel()) }
        assertFalse(a.tryOpenChannel())
        a.channelClosed()
        assertTrue(a.tryOpenChannel())
    }

    @Test
    fun `a sign response carries the algorithm and signature as userauth does`() {
        val blob = AgentKey.signatureBlob("ssh-ed25519", byteArrayOf(1, 2, 3))
        val expected = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(byteArrayOf(1, 2, 3)).compactData
        assertContentEquals(expected, blob)
    }
}
