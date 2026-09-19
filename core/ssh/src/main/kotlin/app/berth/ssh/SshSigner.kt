package app.berth.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthPublickey
import java.security.Signature

/**
 * Signs the userauth challenge for a key sshj cannot drive itself. An Android Keystore key that
 * needs the user for every use is unlocked through a prompt that hands back one authorised
 * `java.security.Signature`; sshj's own `Signature` would never be that object.
 */
fun interface SshSigner {
    /** The SSH wire signature (`ecdsa-sha2-nistp256` blob) over [data]; a throw aborts the method. */
    fun sign(data: ByteArray): ByteArray
}

/** The wire encoding sshj applies to a P-256 DER signature; here for signers that hold a raw JCA [Signature]. */
fun encodeEcdsaP256Signature(der: ByteArray): ByteArray =
    SignatureECDSA("SHA256withECDSA", KeyType.ECDSA256.toString()).encode(der)

/**
 * `publickey` with the proof computed by [signer]. Everything up to the proof (the query, the
 * `PK_OK` round trip) is sshj's; only the signature over `session id || request` is replaced.
 * Only P-256 keys come this way, which is all Android Keystore offers for SSH.
 */
class SignerAuthPublickey(keyProvider: KeyProvider, private val signer: SshSigner) : AuthPublickey(keyProvider) {
    override fun putSig(reqBuf: SSHPacket): SSHPacket {
        val data = Buffer.PlainBuffer().putString(params.transport.sessionID).putBuffer(reqBuf).compactData
        val blob = try {
            signer.sign(data)
        } catch (e: UserAuthException) {
            throw e
        } catch (e: Exception) {
            throw UserAuthException("Signing with the key failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        reqBuf.putSignature(KeyType.ECDSA256.toString(), blob)
        return reqBuf
    }
}
