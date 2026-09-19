package app.berth.android.session

import app.berth.android.security.KeyUnlocker
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.SecretStore
import app.berth.ssh.SshAuth
import app.berth.ssh.SshKeys
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a host's configured [AuthMethod] into the ordered list of transport auth methods.
 * Secrets are looked up lazily inside the callbacks so nothing sensitive sits in memory before
 * the server actually asks for it, and cancelled prompts simply skip the method. A Keystore key
 * is unlocked up front through [KeyUnlocker], so the system prompt appears before the connection
 * is attempted rather than from inside the transport's handshake.
 */
@Singleton
class AuthResolver @Inject constructor(
    private val identities: IdentityRepository,
    private val secrets: SecretStore,
    private val keys: KeyUnlocker,
    private val prompts: PromptCenter,
) {
    suspend fun resolve(host: Host): List<SshAuth> {
        val interactive = SshAuth.KeyboardInteractive { instruction, prompt, _ ->
            runBlocking { prompts.password(host, serverPrompt = prompt, instruction = instruction.ifBlank { null }) }
        }
        return when (val auth = host.auth) {
            is AuthMethod.Key -> {
                val identity = identities.get(auth.identityId) ?: throw IllegalStateException("The key for ${host.name} no longer exists")
                val key = if (identity.isHardwareBacked) keys.authFor(host, identity) else SshAuth.PublicKey(softwareKey(host, identity))
                listOf(key, interactive, askPassword(host))
            }
            is AuthMethod.Password -> {
                val saved = auth.secretId?.let { secrets.get(it) }?.toString(Charsets.UTF_8)?.toCharArray()
                val password = if (saved != null) SshAuth.Password { saved } else askPassword(host)
                listOf(password, interactive)
            }
            AuthMethod.AskEachTime -> listOf(askPassword(host), interactive)
        }
    }

    private suspend fun softwareKey(host: Host, identity: Identity) = run {
        val pem = identities.privateKey(identity.id)?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Private key for ${identity.name} is missing")
        if (SshKeys.isEncrypted(pem)) {
            val passphrase = prompts.passphrase(host, identity.name) ?: throw IllegalStateException("Passphrase entry cancelled")
            SshKeys.load(pem, passphrase = passphrase)
        } else {
            SshKeys.load(pem)
        }
    }

    private fun askPassword(host: Host) = SshAuth.Password { runBlocking { prompts.password(host) } }

    companion object {
        fun passwordSecretId(hostId: String) = "host-password:$hostId"
    }
}
