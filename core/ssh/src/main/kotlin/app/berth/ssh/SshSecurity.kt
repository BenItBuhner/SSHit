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
 */
object SshSecurity {
    @Volatile
    private var installed = false

    fun ensureProviders() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (existing != null && existing !is BouncyCastleProvider) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } else if (existing == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } else if (Security.getProviders().firstOrNull() !== existing) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(existing, 1)
            }
            SecurityUtils.setRegisterBouncyCastle(false)
            SecurityUtils.setSecurityProvider(null)
            installed = true
        }
    }
}
