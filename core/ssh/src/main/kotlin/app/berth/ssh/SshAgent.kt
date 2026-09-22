package app.berth.ssh

import com.hierynomus.sshj.signature.SignatureEdDSA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.signature.SignatureRSA
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the SSH signature blob (`string algorithm, string signature`) over the data of one agent
 * sign request; [flags] are the request's, which for an RSA key choose the hash
 * ([SshAgent.RSA_SHA2_256], [SshAgent.RSA_SHA2_512]). Suspends for as long as the key needs the
 * user (a biometric prompt); a throw refuses the request, and cancellation withdraws it.
 */
fun interface AgentSigner {
    suspend fun sign(data: ByteArray, flags: Int): ByteArray
}

/**
 * The one key a forwarded agent holds: the key that logged in to the host whose agent this is
 * (never the rest of the library), as its public half, the comment `ssh-add -L` prints, and
 * the way it signs. The private half stays behind [signer]; it never crosses the wire.
 */
class AgentKey(val publicKey: PublicKey, val comment: String, private val signer: AgentSigner) {
    val blob: ByteArray = SshKeys.publicKeyBlob(publicKey)
    val keyType: String = SshKeys.keyTypeName(publicKey)

    suspend fun sign(data: ByteArray, flags: Int): ByteArray = signer.sign(data, flags)

    companion object {
        /**
         * A key whose private half is in this process ([KeyProvider] as loaded for the login): signed
         * with sshj's own signatures, the ones its `publickey` method uses, so an RSA key answers
         * `rsa-sha2-256` and `rsa-sha2-512` requests with those and a flagless one with `ssh-rsa`.
         */
        fun software(provider: KeyProvider, comment: String): AgentKey {
            val public = provider.public
            return AgentKey(public, comment) { data, flags ->
                val signature = signatureFor(KeyType.fromKey(public), flags)
                signature.initSign(provider.private)
                signature.update(data)
                signatureBlob(signature.signatureName, signature.encode(signature.sign()))
            }
        }

        /** `string algorithm, string signature`, the form a sign response carries and userauth expects. */
        fun signatureBlob(algorithm: String, signature: ByteArray): ByteArray =
            Buffer.PlainBuffer().putString(algorithm).putBytes(signature).compactData

        private fun signatureFor(type: KeyType, flags: Int): Signature = when (type) {
            KeyType.RSA -> when {
                flags and SshAgent.RSA_SHA2_512 != 0 -> SignatureRSA.FactoryRSASHA512().create()
                flags and SshAgent.RSA_SHA2_256 != 0 -> SignatureRSA.FactoryRSASHA256().create()
                else -> SignatureRSA.FactorySSHRSA().create()
            }
            KeyType.ECDSA256 -> SignatureECDSA.Factory256().create()
            KeyType.ECDSA384 -> SignatureECDSA.Factory384().create()
            KeyType.ECDSA521 -> SignatureECDSA.Factory521().create()
            KeyType.ED25519 -> SignatureEdDSA.Factory().create()
            else -> throw IllegalArgumentException("The agent cannot sign with a $type key")
        }
    }
}

/**
 * What a sign request is for, read from the data the remote wants signed, so the sheet that asks
 * the user can say it. Every string here came from the remote and is shown only after cleaning.
 */
sealed interface AgentSignPurpose {
    /**
     * A `publickey` login as [user] from the remote to a further server. [serverHostKey] is that
     * server's host key blob when the client bound the request to it (OpenSSH's
     * `publickey-hostbound-v00@openssh.com`, 8.9 on), which is the only word the agent gets on
     * where the login goes; the name the user typed at the remote is never sent.
     */
    class Login(val user: String, val service: String, val serverHostKey: ByteArray?) : AgentSignPurpose {
        val serverKeyType: String? get() = serverHostKey?.let(::blobKeyType)
        val serverFingerprint: String? get() = serverHostKey?.let(::blobFingerprint)
    }

    /** An `ssh-keygen -Y sign` signature (git's commit signing is one) in [namespace]. */
    class SshSig(val namespace: String) : AgentSignPurpose

    /** Data in no form Berth reads, [size] bytes of it. */
    class Unknown(val size: Int) : AgentSignPurpose

    companion object {
        private val SSHSIG_MAGIC = "SSHSIG".toByteArray(Charsets.US_ASCII)
        private const val USERAUTH_REQUEST = 50

        /**
         * Reads [data] as a userauth request made with the key [keyBlob], or an SSHSIG, or neither.
         * A userauth request for some other key than the one asked to sign it is not read as a login:
         * the sheet would name a login the signature does not make.
         */
        fun of(data: ByteArray, keyBlob: ByteArray): AgentSignPurpose =
            sshSig(data) ?: login(data, keyBlob) ?: Unknown(data.size)

        private fun sshSig(data: ByteArray): SshSig? {
            if (data.size < SSHSIG_MAGIC.size || !data.copyOf(SSHSIG_MAGIC.size).contentEquals(SSHSIG_MAGIC)) return null
            return runCatching {
                val r = AgentReader(data, SSHSIG_MAGIC.size)
                val namespace = r.utf8()
                r.string()
                r.utf8()
                r.string()
                if (r.remaining != 0) null else SshSig(namespace)
            }.getOrNull()
        }

        private fun login(data: ByteArray, keyBlob: ByteArray): Login? = runCatching {
            val r = AgentReader(data)
            if (r.string().isEmpty() || r.byte() != USERAUTH_REQUEST) return null
            val user = r.utf8()
            val service = r.utf8()
            val hostbound = when (r.utf8()) {
                "publickey" -> false
                "publickey-hostbound-v00@openssh.com" -> true
                else -> return null
            }
            if (!r.bool()) return null
            r.utf8()
            if (!r.string().contentEquals(keyBlob)) return null
            val hostKey = if (hostbound) r.string() else null
            if (r.remaining != 0) null else Login(user, service, hostKey)
        }.getOrNull()

        private fun blobKeyType(blob: ByteArray): String? = runCatching { AgentReader(blob).utf8() }.getOrNull()

        private fun blobFingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }
}

/** One sign request for the held [key], as the user is asked about it. */
class AgentSignRequest(val key: AgentKey, val purpose: AgentSignPurpose, val flags: Int)

/**
 * Decides a sign request for the held key: true signs, false answers the remote with a failure.
 * Suspends while the user is asked; cancellation (the agent closed) refuses.
 */
fun interface AgentApprover {
    suspend fun approve(request: AgentSignRequest): Boolean
}

/**
 * The in-app agent a forwarded `SSH_AUTH_SOCK` on a remote talks to (the OpenSSH agent protocol,
 * draft-miller-ssh-agent). It answers two things: the list of identities, which is [key] or
 * nothing, and a sign request for that key, which [approver] decides before [AgentKey.sign]
 * makes the signature. Everything else a client may ask of an agent, adding or removing keys,
 * locking, smartcards, extensions (OpenSSH's `session-bind@openssh.com` among them), and the old
 * SSH1 messages, is answered with a failure, as is a sign request for any key but [key], without
 * asking anyone.
 *
 * One agent serves every agent channel of one connection ([serve], one call per channel, each on
 * its own thread); [close] refuses what is in flight and ends them all.
 */
class SshAgent(val key: AgentKey?, private val approver: AgentApprover) : Closeable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val open = AtomicInteger(0)

    val isClosed: Boolean get() = !job.isActive

    /** The reply to one agent message (without its length); [message] starts with its type byte. */
    suspend fun handle(message: ByteArray): ByteArray {
        if (message.isEmpty()) return FAILURE_REPLY
        return when (message[0].toInt() and 0xff) {
            REQUEST_IDENTITIES -> identities()
            SIGN_REQUEST -> sign(message)
            else -> FAILURE_REPLY
        }
    }

    private fun identities(): ByteArray = message(IDENTITIES_ANSWER) {
        val held = listOfNotNull(key)
        writeInt(held.size)
        for (k in held) {
            string(k.blob)
            string(k.comment.toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun sign(message: ByteArray): ByteArray {
        val held = key ?: return FAILURE_REPLY
        val (blob, data, flags) = try {
            val r = AgentReader(message, 1)
            Triple(r.string(), r.string(), r.uint32().toInt())
        } catch (_: AgentReader.Malformed) {
            return FAILURE_REPLY
        }
        if (!blob.contentEquals(held.blob)) return FAILURE_REPLY
        val request = AgentSignRequest(held, AgentSignPurpose.of(data, held.blob), flags)
        val signature = try {
            if (!approver.approve(request)) return FAILURE_REPLY
            held.sign(data, flags)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FAILURE_REPLY
        }
        return message(SIGN_RESPONSE) { string(signature) }
    }

    /** Takes a place for one more channel; false once [MAX_CHANNELS] are open or the agent is closed. */
    fun tryOpenChannel(): Boolean {
        if (isClosed) return false
        if (open.incrementAndGet() > MAX_CHANNELS) {
            open.decrementAndGet()
            return false
        }
        return true
    }

    fun channelClosed() {
        open.decrementAndGet()
    }

    /**
     * Answers the messages on one agent channel until the remote closes it, sends a frame no agent
     * message fits ([MAX_MESSAGE]), or the agent is [close]d. Blocks the calling thread; each
     * message is handled on the agent's scope, so a close withdraws a request waiting on the user.
     */
    fun serve(input: InputStream, output: OutputStream) {
        val frames = DataInputStream(input)
        while (!isClosed) {
            val length = try {
                frames.readInt()
            } catch (_: EOFException) {
                return
            }
            if (length <= 0 || length > MAX_MESSAGE) return
            val message = ByteArray(length)
            try {
                frames.readFully(message)
            } catch (_: EOFException) {
                return
            }
            val reply = runBlocking {
                val work = scope.async { handle(message) }
                try {
                    work.await()
                } catch (_: CancellationException) {
                    null
                }
            } ?: return
            output.write(frame(reply))
            output.flush()
        }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        /** The session channel request that asks the server for an `SSH_AUTH_SOCK` backed by this agent. */
        const val FORWARDING_REQUEST = "auth-agent-req@openssh.com"

        /** The channel type the server opens for each connection to that socket. */
        const val CHANNEL_TYPE = "auth-agent@openssh.com"

        const val FAILURE = 5
        const val SUCCESS = 6
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val ADD_IDENTITY = 17
        const val REMOVE_IDENTITY = 18
        const val REMOVE_ALL_IDENTITIES = 19
        const val ADD_SMARTCARD_KEY = 20
        const val REMOVE_SMARTCARD_KEY = 21
        const val LOCK = 22
        const val UNLOCK = 23
        const val ADD_ID_CONSTRAINED = 25
        const val ADD_SMARTCARD_KEY_CONSTRAINED = 26
        const val EXTENSION = 27

        const val RSA_SHA2_256 = 2
        const val RSA_SHA2_512 = 4

        /** OpenSSH's own ceiling on an agent message; a longer length is a broken or hostile peer. */
        const val MAX_MESSAGE = 256 * 1024

        /** Agent channels one connection may hold open at once; one more is refused at the open. */
        const val MAX_CHANNELS = 8

        private val FAILURE_REPLY = byteArrayOf(FAILURE.toByte())

        fun frame(message: ByteArray): ByteArray = ByteArrayOutputStream(message.size + 4).also { out ->
            DataOutputStream(out).apply {
                writeInt(message.size)
                write(message)
            }
        }.toByteArray()

        private fun message(type: Int, body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeByte(type)
                body()
            }
        }.toByteArray()

        private fun DataOutputStream.string(bytes: ByteArray) {
            writeInt(bytes.size)
            write(bytes)
        }
    }
}

/**
 * The agent protocol's wire types over a byte array: sshj's own buffer caps a string at 32 KiB,
 * below what an agent message may carry, so the agent reads its messages itself.
 */
internal class AgentReader(private val bytes: ByteArray, private var pos: Int = 0) {
    class Malformed : Exception("malformed agent message")

    val remaining: Int get() = bytes.size - pos

    fun byte(): Int {
        if (remaining < 1) throw Malformed()
        return bytes[pos++].toInt() and 0xff
    }

    fun uint32(): Long {
        if (remaining < 4) throw Malformed()
        var v = 0L
        repeat(4) { v = (v shl 8) or (bytes[pos++].toLong() and 0xff) }
        return v
    }

    fun bool(): Boolean = byte() != 0

    fun string(): ByteArray {
        val length = uint32()
        if (length > remaining) throw Malformed()
        return bytes.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
    }

    fun utf8(): String = string().toString(Charsets.UTF_8)
}
