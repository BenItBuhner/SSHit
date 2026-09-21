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
import java.security.interfaces.DSAPublicKey
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
            SshSecurity.ensureProviders()
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

    /** The key's size in bits as `ssh-keygen -l` reports it: the modulus for RSA, the curve for ECDSA, 256 for Ed25519. */
    fun bits(key: PublicKey): Int = when (key) {
        is RSAPublicKey -> key.modulus.bitLength()
        is ECPublicKey -> key.params.curve.field.fieldSize
        is DSAPublicKey -> key.params.p.bitLength()
        else -> 256
    }

    /** `ssh-keygen`'s short type name: `ED25519`, `ECDSA`, `RSA`, `DSA`, with `-SK` and `-CERT` where the wire name says so. */
    fun keygenType(key: PublicKey): String = keygenType(keyTypeName(key))

    fun keygenType(wireName: String): String {
        val sk = wireName.startsWith("sk-")
        val cert = wireName.endsWith("-cert-v01@openssh.com")
        val base = wireName.removePrefix("sk-").removeSuffix("-cert-v01@openssh.com").removeSuffix("@openssh.com")
        val name = when {
            base == "ssh-ed25519" -> "ED25519"
            base == "ssh-rsa" -> "RSA"
            base == "ssh-dss" -> "DSA"
            base.startsWith("ecdsa-sha2-") -> "ECDSA"
            else -> base.uppercase()
        }
        return name + (if (sk) "-SK" else "") + (if (cert) "-CERT" else "")
    }

    /**
     * The line `ssh-keygen -lf` prints for a key: bits, `SHA256:` fingerprint, the comment (`no
     * comment` when there is none, as the tool writes; a known_hosts entry's host in its place)
     * and the type in parentheses.
     */
    fun keygenLine(key: PublicKey, comment: String = ""): String {
        val label = comment.trim().ifEmpty { "no comment" }
        return "${bits(key)} ${fingerprintSha256(key)} $label (${keygenType(key)})"
    }

    /**
     * The command a person runs on a server to print that server's fingerprint for the key type
     * at hand, for the trust sheet's "compare on the server" line: OpenSSH keeps its host keys at
     * `/etc/ssh/ssh_host_<type>_key.pub`, named `ed25519`, `ecdsa`, `rsa` or `dsa`.
     */
    fun serverFingerprintCommand(wireName: String): String {
        val file = when (keygenType(wireName).removeSuffix("-CERT").removeSuffix("-SK")) {
            "ECDSA" -> "ecdsa"
            "RSA" -> "rsa"
            "DSA" -> "dsa"
            else -> "ed25519"
        }
        return "ssh-keygen -lf /etc/ssh/ssh_host_${file}_key.pub"
    }

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
            val passBytes = String(passphrase).toByteArray(Charsets.UTF_8)
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
        SshSecurity.ensureProviders()
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
        if (privateKeyText.contains("PuTTY-User-Key-File")) return Regex("Encryption:\\s*(?!none)\\S").containsMatchIn(privateKeyText)
        if (!privateKeyText.contains("BEGIN OPENSSH PRIVATE KEY")) return false
        val body = privateKeyText.lines().filter { !it.startsWith("-----") }.joinToString("")
        return runCatching {
            val buf = Buffer.PlainBuffer(Base64.getDecoder().decode(body))
            buf.readRawBytes(ByteArray(15)) // "openssh-key-v1\0"
            buf.readString() != "none"
        }.getOrDefault(false)
    }

    /**
     * The public key an `openssh-key-v1` file carries in the clear, ahead of its private section
     * and whether or not that section is encrypted, so a stored key's public half and fingerprint
     * can be checked against its own bytes without its passphrase. Null when [privateKeyText] is
     * not such a file, or not a well-formed one.
     */
    fun publicKeyOfOpenSshPrivate(privateKeyText: String): PublicKey? {
        if (!privateKeyText.contains("BEGIN OPENSSH PRIVATE KEY")) return null
        val body = privateKeyText.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
        return runCatching {
            SshSecurity.ensureProviders()
            val buf = Buffer.PlainBuffer(Base64.getDecoder().decode(body))
            val magic = ByteArray(15).also { buf.readRawBytes(it) }
            require(magic.contentEquals("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))) { "not an openssh-key-v1 file" }
            buf.readString() // cipher name
            buf.readString() // kdf name
            buf.readBytes() // kdf options
            require(buf.readUInt32AsInt() == 1) { "one key a file" }
            Buffer.PlainBuffer(buf.readBytes()).readPublicKey()
        }.getOrNull()
    }

    /** True when [text] looks like a private key in any format [load] understands. */
    fun looksLikePrivateKey(text: String): Boolean =
        text.contains("PRIVATE KEY-----") || text.contains("PuTTY-User-Key-File")

    /** A private key read from user-supplied text, ready to be stored as an identity. */
    class ImportedKey(
        val pair: KeyPair,
        /** Null for key types Berth cannot generate itself (DSA, P-521); they still authenticate. */
        val algorithm: KeyAlgorithm?,
        /** Comment embedded in an OpenSSH v1 file, or empty. */
        val comment: String,
        /** `OpenSSH`, `PEM`, `PKCS#8` or `PuTTY`, for the import sheet. */
        val format: String,
    )

    sealed class ImportError(message: String) : Exception(message) {
        class NotAKey : ImportError("This isn't a private key. Expected an OpenSSH, PEM, PKCS#8 or PuTTY key file.")
        class PassphraseNeeded : ImportError("This key is protected by a passphrase.")
        class WrongPassphrase : ImportError("That passphrase didn't unlock the key.")
        class Unsupported(detail: String) : ImportError("Couldn't read this key: $detail")
    }

    /**
     * Reads a private key from text. Throws [ImportError.PassphraseNeeded] when the key is
     * encrypted and [passphrase] is null or empty, [ImportError.WrongPassphrase] when it does not
     * decrypt, and [ImportError.NotAKey] when the text is not a key at all.
     */
    fun importPrivate(text: String, passphrase: CharArray? = null): ImportedKey {
        val trimmed = text.trim()
        if (!looksLikePrivateKey(trimmed)) throw ImportError.NotAKey()
        val encrypted = isEncrypted(trimmed)
        if (encrypted && (passphrase == null || passphrase.isEmpty())) throw ImportError.PassphraseNeeded()
        val format = when {
            trimmed.startsWith("PuTTY-User-Key-File") -> "PuTTY"
            trimmed.contains("BEGIN OPENSSH PRIVATE KEY") -> "OpenSSH"
            trimmed.contains("BEGIN PRIVATE KEY") || trimmed.contains("BEGIN ENCRYPTED PRIVATE KEY") -> "PKCS#8"
            else -> "PEM"
        }
        // Read the comment first: sshj's one-off password finder blanks the passphrase once used.
        val comment = if (format == "OpenSSH") openSshComment(trimmed, passphrase) ?: "" else ""
        val provider = try {
            load(trimmed, passphrase = passphrase?.takeIf { it.isNotEmpty() }?.copyOf())
        } catch (e: Exception) {
            throw ImportError.Unsupported(e.message ?: e.javaClass.simpleName)
        }
        val pair = try {
            KeyPair(provider.public, provider.private)
        } catch (e: Exception) {
            if (encrypted) throw ImportError.WrongPassphrase()
            throw ImportError.Unsupported(e.message ?: e.javaClass.simpleName)
        }
        return ImportedKey(pair, algorithmOf(pair.public), comment, format)
    }

    /** The comment stored inside an `openssh-key-v1` file; null when it cannot be read. */
    internal fun openSshComment(text: String, passphrase: CharArray?): String? = runCatching {
        val body = text.lines().filter { !it.startsWith("-----") }.joinToString("")
        val file = Buffer.PlainBuffer(Base64.getDecoder().decode(body))
        file.readRawBytes(ByteArray(15))
        val cipherName = file.readString()
        val kdfName = file.readString()
        val kdfOptions = file.readStringAsBytes()
        val keyCount = file.readUInt32AsInt()
        repeat(keyCount) { file.readStringAsBytes() }
        var section = file.readStringAsBytes()
        if (cipherName != "none") {
            if (kdfName != "bcrypt" || passphrase == null || passphrase.isEmpty()) return null
            val (keyBits, mode) = when (cipherName) {
                "aes256-ctr" -> 32 to "CTR"
                "aes192-ctr" -> 24 to "CTR"
                "aes128-ctr" -> 16 to "CTR"
                "aes256-cbc" -> 32 to "CBC"
                "aes192-cbc" -> 24 to "CBC"
                "aes128-cbc" -> 16 to "CBC"
                else -> return null
            }
            val options = Buffer.PlainBuffer(kdfOptions)
            val salt = options.readStringAsBytes()
            val rounds = options.readUInt32AsInt()
            val keyAndIv = ByteArray(keyBits + 16)
            val passBytes = String(passphrase).toByteArray(Charsets.UTF_8)
            try {
                BCrypt().pbkdf(passBytes, salt, rounds, keyAndIv)
            } finally {
                passBytes.fill(0)
            }
            val cipher = Cipher.getInstance("AES/$mode/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyAndIv.copyOfRange(0, keyBits), "AES"), IvParameterSpec(keyAndIv.copyOfRange(keyBits, keyBits + 16)))
            section = cipher.doFinal(section)
            keyAndIv.fill(0)
        }
        val private = Buffer.PlainBuffer(section)
        if (private.readUInt32AsInt() != private.readUInt32AsInt()) return null
        val type = private.readString()
        when (type) {
            "ssh-ed25519" -> repeat(2) { private.readStringAsBytes() }
            "ssh-rsa" -> repeat(6) { private.readMPInt() }
            "ssh-dss" -> repeat(5) { private.readMPInt() }
            else -> if (type.startsWith("ecdsa-sha2-")) {
                private.readString()
                private.readStringAsBytes()
                private.readMPInt()
            } else {
                return null
            }
        }
        private.readString()
    }.getOrNull()
}
