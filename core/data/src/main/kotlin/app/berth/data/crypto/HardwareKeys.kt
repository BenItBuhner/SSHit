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
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** How a Keystore key lets itself be used for signing. */
enum class KeyAuthModel {
    /** No user presence needed. */
    NONE,

    /**
     * Every signature needs its own authentication: the `Signature` is authorised through a
     * `BiometricPrompt.CryptoObject` and only that object may sign. Android 11 and later.
     */
    PER_USE,

    /**
     * A successful authentication opens a window in which any `Signature` may sign; outside it,
     * `initSign` throws `UserNotAuthenticatedException`. Keys from earlier builds, and every
     * biometric key on Android 10, where a device-credential fallback rules out `CryptoObject`.
     */
    TIMED_WINDOW,
}

/** The Keystore operations key unlocking needs; tests supply fakes that throw the Keystore's own exceptions. */
interface KeystoreSigning {
    fun authModel(alias: String): KeyAuthModel

    /**
     * A `SHA256withECDSA` signature initialised for the key at [alias]. Throws the Keystore's
     * `UserNotAuthenticatedException` when the key is locked and `KeyPermanentlyInvalidatedException`
     * when new biometrics were enrolled since it was made.
     */
    fun beginSign(alias: String): Signature

    /** Replaces the key pair under [alias]; the old private key is gone and the new public key returned. */
    fun regenerate(alias: String, protection: KeyProtection): PublicKey

    /** The sshj view of the key at [alias]: its public half and type; the private half is an opaque handle. */
    fun keyProvider(alias: String): KeyProvider
}

/**
 * Identities whose private key is generated inside Android Keystore and can never be exported.
 * Only ECDSA P-256 is available in hardware on Android; Ed25519 stays a software key.
 */
class HardwareKeys(private val context: Context) : KeystoreSigning {
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

    override fun regenerate(alias: String, protection: KeyProtection): PublicKey {
        delete(alias)
        return generate(alias, protection)
    }

    private fun generator(alias: String, protection: KeyProtection, strongBox: Boolean): KeyPairGenerator {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
        if (protection == KeyProtection.BIOMETRIC) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Per use: the prompt that unlocks the key carries the Signature that then signs
                // (KeyAuthModel.PER_USE). Device credential is allowed as the fallback, which
                // Android 11 accepts together with a CryptoObject. New enrolments invalidate the key.
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                // Android 10 cannot pair a CryptoObject with the device-credential fallback, so the
                // key opens a short window after any unlock instead (KeyAuthModel.TIMED_WINDOW).
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

    private fun keyInfo(key: PrivateKey): KeyInfo =
        KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)

    /** Whether the key ended up in secure hardware (TEE or StrongBox), per the Keystore's own report. */
    fun isInsideSecureHardware(alias: String): Boolean {
        val key = store().getKey(alias, null) as? PrivateKey ?: return false
        val info = keyInfo(key)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            info.isInsideSecureHardware
        }
    }

    override fun authModel(alias: String): KeyAuthModel {
        val info = keyInfo(privateKey(alias))
        return when {
            !info.isUserAuthenticationRequired -> KeyAuthModel.NONE
            // -1 (or 0 on Android 11+) means every use needs its own authentication.
            info.userAuthenticationValidityDurationSeconds <= 0 -> KeyAuthModel.PER_USE
            else -> KeyAuthModel.TIMED_WINDOW
        }
    }

    /**
     * The provider is left unpinned on purpose (see `SshSecurity`): `initSign` is where the JCA
     * picks the Keystore provider for this key, and where the Keystore's authentication and
     * invalidation exceptions surface.
     */
    override fun beginSign(alias: String): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(alias)) }

    private fun privateKey(alias: String): PrivateKey =
        store().getKey(alias, null) as? PrivateKey ?: throw IllegalStateException("no hardware key for $alias")

    override fun keyProvider(alias: String): KeyProvider {
        val private = privateKey(alias)
        val public = store().getCertificate(alias)?.publicKey ?: throw IllegalStateException("no certificate for $alias")
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = private
            override fun getPublic(): PublicKey = public
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    private fun store(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        /** How long a [KeyAuthModel.TIMED_WINDOW] key stays usable after an unlock; the Keys screen quotes it. */
        const val AUTH_WINDOW_SECONDS = 60

        fun aliasFor(identityId: String): String = "berth.identity.$identityId"
    }
}
