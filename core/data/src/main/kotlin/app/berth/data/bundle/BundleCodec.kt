package app.berth.data.bundle

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.berth` container (spec C20, Data): a document sealed under a passphrase.
 *
 * ```
 * offset  bytes  field
 *  0       4     magic  "BRTH"
 *  4       1     container version, 1
 *  5       1     kdf, 1 = Argon2id (version 0x13)
 *  6       4     Argon2 memory in KiB, big-endian
 * 10       4     Argon2 iterations, big-endian
 * 14       1     Argon2 parallelism
 * 15       1     salt length, 16
 * 16      16     salt
 * 32      12     AES-GCM nonce
 * 44       n     AES-256-GCM ciphertext, the 16-byte tag last
 * ```
 *
 * The key is Argon2id over the passphrase's UTF-8 bytes and the salt, 32 bytes, with the cost in
 * the header so it can rise in a later build and old files still open. The whole header is the
 * GCM's associated data: a version or a cost parameter changed after the fact fails the tag the
 * same as a changed byte of ciphertext or a wrong passphrase. The three are indistinguishable by
 * design (there is nothing else to check the passphrase against), so all three raise
 * [BundleException.Sealed]. A header this build cannot read raises before any work is done.
 */
class BundleCodec(private val random: SecureRandom = SecureRandom()) {

    /** [payload] sealed under [passphrase] at [cost]. The passphrase is the caller's to clear. */
    fun seal(payload: ByteArray, passphrase: CharArray, cost: BundleKdf = BundleKdf.DEFAULT): ByteArray {
        cost.requireSane()
        require(passphrase.isNotEmpty()) { "a bundle needs a passphrase" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(cost, salt, nonce)
        val key = deriveKey(passphrase, salt, cost)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(payload)
        } finally {
            key.fill(0)
        }
    }

    /**
     * The payload [blob] seals, or [BundleException]: [BundleException.NotABundle] for a file that
     * is not one, [BundleException.Unsupported] for a container or cost this build will not open,
     * [BundleException.Sealed] for a wrong passphrase or a changed file.
     */
    fun open(blob: ByteArray, passphrase: CharArray): ByteArray {
        val header = readHeader(blob)
        val key = deriveKey(passphrase, header.salt, header.cost)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, header.nonce))
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            return try {
                cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
            } catch (e: AEADBadTagException) {
                throw BundleException.Sealed()
            }
        } finally {
            key.fill(0)
        }
    }

    /** The header alone, so a picker can say what a file is before asking for its passphrase. */
    fun inspect(blob: ByteArray): BundleKdf = readHeader(blob).cost

    private class Header(val cost: BundleKdf, val salt: ByteArray, val nonce: ByteArray)

    private fun readHeader(blob: ByteArray): Header {
        if (blob.size < MAGIC.size || !MAGIC.contentEquals(blob.copyOfRange(0, MAGIC.size))) throw BundleException.NotABundle()
        // Starts as a bundle and ends before its tag: cut short since it was written.
        if (blob.size < HEADER_BYTES + TAG_BITS / 8) throw BundleException.Sealed()
        val buffer = ByteBuffer.wrap(blob, MAGIC.size, HEADER_BYTES - MAGIC.size)
        val version = buffer.get().toInt() and 0xFF
        if (version != VERSION) throw BundleException.Unsupported("container version $version")
        val kdf = buffer.get().toInt() and 0xFF
        if (kdf != KDF_ARGON2ID) throw BundleException.Unsupported("key derivation $kdf")
        val cost = BundleKdf(memoryKiB = buffer.getInt(), iterations = buffer.getInt(), parallelism = buffer.get().toInt() and 0xFF)
        // The header is only authenticated by the tag, the tag needs the key and the key needs the
        // KDF run at the header's cost: so the cost is the one field taken on trust, and it is held
        // to what a phone can run ([BundleKdf.isSane]) before a byte of Argon2 memory is asked for.
        if (!cost.isSane) throw BundleException.Unsupported("key derivation cost ${cost.memoryKiB} KiB × ${cost.iterations} × ${cost.parallelism}")
        val saltLength = buffer.get().toInt() and 0xFF
        if (saltLength != SALT_BYTES) throw BundleException.Unsupported("salt of $saltLength bytes")
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
        return Header(cost, salt, nonce)
    }

    private fun header(cost: BundleKdf, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(KDF_ARGON2ID.toByte())
            .putInt(cost.memoryKiB)
            .putInt(cost.iterations)
            .put(cost.parallelism.toByte())
            .put(SALT_BYTES.toByte())
            .put(salt)
            .put(nonce)
            .array()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, cost: BundleKdf): ByteArray {
        val bytes = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(passphrase)).let { buffer ->
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(cost.memoryKiB)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .withSalt(salt)
                .build()
            val key = ByteArray(KEY_BYTES)
            try {
                Argon2BytesGenerator().apply { init(params) }.generateBytes(bytes, key)
            } catch (e: OutOfMemoryError) {
                // Within the ceiling and still more than this process has left: the generator and
                // its blocks are garbage by now, so the answer is a line on the sheet, not a crash.
                throw BundleException.TooCostly()
            }
            return key
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        val MAGIC: ByteArray = "BRTH".toByteArray(StandardCharsets.US_ASCII)
        const val VERSION = 1
        const val KDF_ARGON2ID = 1
        const val HEADER_BYTES = 44
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Argon2id's cost. [DEFAULT] is RFC 9106's recommendation for when 2 GiB is not on the table:
 * 64 MiB, three passes, four lanes; a phone spends a second or two on it, a laptop guessing
 * passphrases spends the same on each guess.
 */
data class BundleKdf(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    /**
     * Within what a phone can be asked to run, since a reader runs the header's cost before it can
     * check the header: at most [MAX_MEMORY_KIB] (256 MiB, four times [DEFAULT]'s, room for a
     * stronger writer later and still under any phone's heap), at most [MAX_WORK_KIB_PASSES] of
     * memory × passes (1 GiB·pass, so 256 MiB runs four passes at most and 64 MiB sixteen), at most
     * 64 passes and 16 lanes, and Argon2's own floors.
     */
    val isSane: Boolean
        get() = memoryKiB in (8 * parallelism)..MAX_MEMORY_KIB &&
            iterations in 1..64 &&
            parallelism in 1..16 &&
            memoryKiB.toLong() * iterations <= MAX_WORK_KIB_PASSES

    fun requireSane() = require(isSane) { "key derivation cost out of range: $this" }

    companion object {
        val DEFAULT = BundleKdf(memoryKiB = 64 * 1024, iterations = 3, parallelism = 4)

        /** The most memory a reader will allocate for a header: 256 MiB. */
        const val MAX_MEMORY_KIB = 256 * 1024

        /** The most work a reader will do for a header, in KiB × passes: 1 GiB·pass. */
        const val MAX_WORK_KIB_PASSES = 1L shl 20
    }
}

/** Why a bundle did not open, in the words the sheet shows. */
sealed class BundleException(message: String) : RuntimeException(message) {
    class NotABundle : BundleException("This is not a .berth file.")
    class Unsupported(detail: String) : BundleException("This Berth can't open this bundle ($detail).")
    class Sealed : BundleException("That passphrase didn't open the bundle, or the file was changed since it was exported.")

    /** The header's cost is within the ceiling and this process still could not allocate it. */
    class TooCostly : BundleException("This file asks for more memory than this phone has.")
}
