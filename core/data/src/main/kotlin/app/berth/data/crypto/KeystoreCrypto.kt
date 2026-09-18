package app.berth.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets at rest with an AES-256-GCM key that lives in Android Keystore. The key never
 * leaves the Keystore; the database only ever holds `version || iv || ciphertext`.
 *
 * Software identities and saved passwords go through here. Hardware identities do not: their
 * private keys are Keystore objects and have nothing to encrypt.
 */
interface SecretCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class KeystoreCrypto(private val alias: String = DEFAULT_ALIAS) : SecretCrypto {
    private val lock = Any()

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plain)
        return byteArrayOf(VERSION) + iv + ciphertext
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES && blob[0] == VERSION) { "unknown secret format" }
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = blob.copyOfRange(1 + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "berth.master.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
    }
}
