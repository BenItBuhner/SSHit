package app.berth.domain.model

import kotlinx.serialization.Serializable

enum class KeyAlgorithm(val sshName: String, val displayName: String) {
    ED25519("ssh-ed25519", "Ed25519"),
    ECDSA_P256("ecdsa-sha2-nistp256", "ECDSA P-256"),
    ECDSA_P384("ecdsa-sha2-nistp384", "ECDSA P-384"),
    RSA_3072("ssh-rsa", "RSA 3072"),
    RSA_4096("ssh-rsa", "RSA 4096"),
}

/**
 * Where the private key lives. Software keys are encrypted at rest with the Keystore-wrapped
 * master key; hardware keys are generated inside Android Keystore (StrongBox when available) and
 * are only ever referenced by alias. Ed25519 cannot be hardware-backed on Android, so only
 * [KeyAlgorithm.ECDSA_P256] uses [ANDROID_KEYSTORE].
 */
enum class KeyStorage { SOFTWARE_ENCRYPTED, ANDROID_KEYSTORE }

enum class KeyProtection { NONE, BIOMETRIC, PASSPHRASE }

/**
 * A first-class identity. The private key material is never part of this model: the data layer
 * hands out a signer for [id] on demand, after any [protection] gate has been satisfied.
 */
@Serializable
data class Identity(
    val id: String,
    val name: String,
    val algorithm: KeyAlgorithm,
    val storage: KeyStorage,
    val protection: KeyProtection,
    /** Single-line `<type> <base64> <comment>` form. */
    val publicKeyOpenSsh: String,
    /** `SHA256:` prefixed, unpadded base64, as printed by `ssh-keygen -lf`. */
    val fingerprintSha256: String,
    val comment: String = "",
    /** Android Keystore alias for [KeyStorage.ANDROID_KEYSTORE] keys. */
    val keystoreAlias: String? = null,
    val createdAt: Long,
) {
    val isHardwareBacked: Boolean get() = storage == KeyStorage.ANDROID_KEYSTORE
}
