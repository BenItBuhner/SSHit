package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key generation, OpenSSH serialization and fingerprinting. Private keys are handled as OpenSSH
 * `openssh-key-v1` text; at-rest encryption is the data layer's job (Keystore-wrapped), and an
 * optional passphrase adds the same bcrypt/aes256-ctr protection `ssh-keygen -p` would, so the
 * format stays importable by `ssh-keygen` and every other client.
 */
object SshKeys {
    private val random = SecureRandom()

    fun generate(algorithm: KeyAlgorithm): KeyPair = when (algorithm) {
        KeyAlgorithm.ED25519 -> {
            val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
            val pair = gen.generateKeyPair()
            val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
            val pub = (pair.public as Ed25519PublicKeyParameters).encoded
            KeyPair(Ed25519KeyFactory.getPublicKey(pub), Ed25519KeyFactory.getPrivateKey(priv))
        }
        KeyAlgorithm.ECDSA_P256 -> ecPair("secp256r1")
        KeyAlgorithm.ECDSA_P384 -> ecPair("secp384r1")
        KeyAlgorithm.RSA_3072 -> rsaPair(3072)
        KeyAlgorithm.RSA_4096 -> rsaPair(4096)
    }

    private fun ecPair(curve: String): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve), random) }.generateKeyPair()

    private fun rsaPair(bits: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits, random) }.generateKeyPair()

    /** The SSH wire name of a key, e.g. `ssh-ed25519`. */
    fun keyTypeName(key: PublicKey): String = KeyType.fromKey(key).toString()

    fun algorithmOf(key: PublicKey): KeyAlgorithm? = when (KeyType.fromKey(key)) {
        KeyType.ED25519 -> KeyAlgorithm.ED25519
        KeyType.ECDSA256 -> KeyAlgorithm.ECDSA_P256
        KeyType.ECDSA384 -> KeyAlgorithm.ECDSA_P384
        KeyType.RSA -> if ((key as RSAPublicKey).modulus.bitLength() > 3072) KeyAlgorithm.RSA_4096 else KeyAlgorithm.RSA_3072
        else -> null
    }

    /** Wire-format public key blob (`string type, ...`). */
    fun publicKeyBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    fun publicKeyBase64(key: PublicKey): String = Base64.getEncoder().encodeToString(publicKeyBlob(key))

    /** `SHA256:` fingerprint, unpadded base64, as `ssh-keygen -lf` prints. */
    fun fingerprintSha256(key: PublicKey): String = fingerprintSha256(publicKeyBlob(key))

    fun fingerprintSha256(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Groups a fingerprint's base64 body in fours for display. */
    fun groupedFingerprint(fingerprint: String): String =
        fingerprint.removePrefix("SHA256:").chunked(4).joinToString(" ")

    /** `<type> <base64> [comment]` as found in `authorized_keys`. */
    fun openSshPublic(key: PublicKey, comment: String = ""): String {
        val line = "${keyTypeName(key)} ${publicKeyBase64(key)}"
        return if (comment.isBlank()) line else "$line ${comment.trim()}"
    }

    /** Parses one `authorized_keys`/`known_hosts`-style public key line. */
    fun parseOpenSshPublic(line: String): PublicKey {
        val parts = line.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "not an OpenSSH public key" }
        val blob = Base64.getDecoder().decode(parts[1])
        return Buffer.PlainBuffer(blob).readPublicKey()
    }

    fun parsePublicKeyBlob(base64: String): PublicKey = Buffer.PlainBuffer(Base64.getDecoder().decode(base64)).readPublicKey()

    /**
     * Serializes a key pair as an `openssh-key-v1` private key file. With a [passphrase] the private
     * section is protected the way `ssh-keygen` does it: bcrypt KDF (16 rounds) and aes256-ctr.
     */
    fun openSshPrivate(pair: KeyPair, comment: String = "", passphrase: CharArray? = null): String {
        val encrypted = passphrase != null && passphrase.isNotEmpty()
        val pub = pair.public
        val pubBlob = publicKeyBlob(pub)
        val check = random.nextInt()
        val private = Buffer.PlainBuffer()
            .putUInt32FromInt(check)
            .putUInt32FromInt(check)
            .putString(keyTypeName(pub))
        putPrivateSection(private, pair)
        private.putString(comment)
        val blockSize = if (encrypted) 16 else 8
        var pad = 1
        while (private.compactData.size % blockSize != 0) private.putRawBytes(byteArrayOf(pad++.toByte()))

        val file = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII))
            .putRawBytes(byteArrayOf(0))
        val section: ByteArray
        if (encrypted) {
            val salt = ByteArray(16).also(random::nextBytes)
            val rounds = 16
            val keyAndIv = ByteArray(48)
            val passBytes = String(passphrase!!).toByteArray(Charsets.UTF_8)
            try {
                BCrypt().pbkdf(passBytes, salt, rounds, keyAndIv)
            } finally {
                passBytes.fill(0)
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES"), IvParameterSpec(keyAndIv.copyOfRange(32, 48)))
            section = cipher.doFinal(private.compactData)
            keyAndIv.fill(0)
            file.putString("aes256-ctr")
                .putString("bcrypt")
                .putString(Buffer.PlainBuffer().putString(salt).putUInt32(rounds.toLong()).compactData)
        } else {
            section = private.compactData
            file.putString("none").putString("none").putString(ByteArray(0))
        }
        file.putUInt32(1)
            .putString(pubBlob)
            .putString(section)

        val body = Base64.getEncoder().encodeToString(file.compactData).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private fun putPrivateSection(buf: Buffer.PlainBuffer, pair: KeyPair) {
        val pub = pair.public
        val priv: PrivateKey = pair.private
        when (KeyType.fromKey(pub)) {
            KeyType.ED25519 -> {
                val pubBytes = Buffer.PlainBuffer(publicKeyBlob(pub)).apply { readString() }.readStringAsBytes()
                val seed = ed25519Seed(priv)
                buf.putString(pubBytes)
                buf.putString(seed + pubBytes)
            }
            KeyType.ECDSA256, KeyType.ECDSA384, KeyType.ECDSA521 -> {
                val blob = Buffer.PlainBuffer(publicKeyBlob(pub))
                blob.readString()
                val curve = blob.readString()
                val point = blob.readStringAsBytes()
                buf.putString(curve)
                buf.putString(point)
                buf.putMPInt((priv as ECPrivateKey).s)
            }
            KeyType.RSA -> {
                val k = priv as RSAPrivateCrtKey
                val iqmp = k.primeQ.modInverse(k.primeP)
                buf.putMPInt(k.modulus)
                buf.putMPInt(k.publicExponent)
                buf.putMPInt(k.privateExponent)
                buf.putMPInt(iqmp)
                buf.putMPInt(k.primeP)
                buf.putMPInt(k.primeQ)
            }
            else -> throw IllegalArgumentException("unsupported key type ${KeyType.fromKey(pub)}")
        }
    }

    /** Extracts the 32-byte seed from an Ed25519 private key in PKCS#8 encoding. */
    private fun ed25519Seed(priv: PrivateKey): ByteArray {
        val encoded = priv.encoded
        // PKCS#8 Ed25519: the seed is the final 32 bytes, wrapped as OCTET STRING { OCTET STRING seed }.
        require(encoded.size >= 32) { "unexpected Ed25519 private key encoding" }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /**
     * Loads a private key in OpenSSH, PKCS#8 or legacy PEM format. [passphrase] is only consulted
     * when the file is encrypted.
     */
    fun load(privateKeyText: String, publicKeyText: String? = null, passphrase: CharArray? = null): KeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(privateKeyText, publicKeyText != null)
        val provider = SshFactories.fileKeyProvider(format)
        val finder: PasswordFinder? = passphrase?.let { PasswordUtils.createOneOff(it) }
        provider.init(privateKeyText, publicKeyText, finder ?: NoPassword)
        return provider
    }

    private object NoPassword : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray? = null
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    /** True when the private key text is encrypted and needs a passphrase to load. */
    fun isEncrypted(privateKeyText: String): Boolean {
        if (privateKeyText.contains("ENCRYPTED")) return true
        if (!privateKeyText.contains("BEGIN OPENSSH PRIVATE KEY")) return false
        val body = privateKeyText.lines().filter { !it.startsWith("-----") }.joinToString("")
        return runCatching {
            val buf = Buffer.PlainBuffer(Base64.getDecoder().decode(body))
            buf.readRawBytes(ByteArray(15)) // "openssh-key-v1\0"
            buf.readString() != "none"
        }.getOrDefault(false)
    }
}
