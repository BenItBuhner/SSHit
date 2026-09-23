package app.berth.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Makes sure the full BouncyCastle provider is what sshj sees, and that sshj resolves JCA
 * primitives through the normal provider chain rather than pinning them to "BC".
 *
 * Android ships a stripped-down provider registered under the same "BC" name. sshj checks for a
 * provider called "BC" and, finding Android's, never registers the real one; several algorithms it
 * needs (Ed25519 signatures, bcrypt-pbkdf for OpenSSH keys, some ciphers) are then missing at
 * runtime. Swapping the provider is the documented workaround.
 *
 * The real provider always goes to the front of the chain. Conscrypt (Android's default provider,
 * also what Robolectric installs) hands out Ed25519 keys whose `getAlgorithm()` is the bare OID,
 * which sshj cannot classify, so key objects must come from BouncyCastle wherever that provider
 * is present.
 *
 * Leaving the provider unpinned matters for hardware-backed identities: an AndroidKeyStore
 * private key can only sign inside the Keystore provider, and `Signature.getInstance(alg)` without
 * a provider defers the choice to `initSign`, where the Keystore provider is the one that accepts
 * the key. Everything else still lands on BouncyCastle, which we insert at the front.
 *
 * The app installs it on a background thread at launch, so every path that has sshj or the JCA
 * build a key object (a connection, and [SshKeys] generating, loading or parsing a key) calls
 * [ensureProviders] first; a caller that arrives mid-install waits for it on the lock.
 */
object SshSecurity {
    @Volatile
    private var installed = false

    /**
     * Cheap and idempotent: re-checks the chain order on every call because test harnesses
     * (Robolectric) and some OEM builds re-insert Conscrypt at the front after startup.
     */
    fun ensureProviders() {
        synchronized(this) {
            val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            val bc = if (existing is BouncyCastleProvider) existing else BouncyCastleProvider()
            if (Security.getProviders().firstOrNull() !== bc) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(bc, 1)
            }
            if (!installed) {
                SecurityUtils.setRegisterBouncyCastle(false)
                SecurityUtils.setSecurityProvider(null)
                installed = true
            }
        }
    }
}
