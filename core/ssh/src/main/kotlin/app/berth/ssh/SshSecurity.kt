package app.berth.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Makes sure the full BouncyCastle provider is what sshj sees.
 *
 * Android ships a stripped-down provider registered under the same "BC" name. sshj checks for a
 * provider called "BC" and, finding Android's, never registers the real one; several algorithms it
 * needs (Ed25519 signatures, bcrypt-pbkdf for OpenSSH keys, some ciphers) are then missing at
 * runtime. Swapping the provider is the documented workaround and is harmless on a plain JVM,
 * where the check simply finds no provider and installs ours.
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
                Security.addProvider(BouncyCastleProvider())
            }
            installed = true
        }
    }
}
