package app.berth.data.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.berth.domain.model.KeyProtection
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Identities whose private key is generated inside Android Keystore and can never be exported.
 * Only ECDSA P-256 is available in hardware on Android; Ed25519 stays a software key.
 */
class HardwareKeys(private val context: Context) {
    val strongBoxAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Generates a P-256 signing key under [alias], in StrongBox when the device has one. */
    fun generate(alias: String, protection: KeyProtection): PublicKey {
        val pair = try {
            generator(alias, protection, strongBox = strongBoxAvailable).generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generator(alias, protection, strongBox = false).generateKeyPair()
        }
        return pair.public
    }

    private fun generator(alias: String, protection: KeyProtection, strongBox: Boolean): KeyPairGenerator {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
        if (protection == KeyProtection.BIOMETRIC) {
            // sshj builds its own Signature objects, so per-use CryptoObject prompts are not an
            // option; a short validity window after an unlock is the workable model.
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_WINDOW_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
            }
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(builder.build()) }
    }

    fun publicKey(alias: String): PublicKey? = store().getCertificate(alias)?.publicKey

    fun exists(alias: String): Boolean = store().containsAlias(alias)

    fun delete(alias: String) {
        val store = store()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    /** Whether the key ended up in secure hardware (TEE or StrongBox), per the Keystore's own report. */
    fun isInsideSecureHardware(alias: String): Boolean {
        val key = store().getKey(alias, null) as? PrivateKey ?: return false
        val factory = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            info.isInsideSecureHardware
        }
    }

    /** An sshj key provider that signs with the Keystore key; the private half is an opaque handle. */
    fun keyProvider(alias: String): KeyProvider {
        val store = store()
        val private = store.getKey(alias, null) as? PrivateKey ?: throw IllegalStateException("no hardware key for $alias")
        val public = store.getCertificate(alias)?.publicKey ?: throw IllegalStateException("no certificate for $alias")
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = private
            override fun getPublic(): PublicKey = public
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    private fun store(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AUTH_WINDOW_SECONDS = 60

        fun aliasFor(identityId: String): String = "berth.identity.$identityId"
    }
}
