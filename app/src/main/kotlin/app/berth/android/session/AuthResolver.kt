package app.berth.android.session

import app.berth.data.crypto.HardwareKeys
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
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
 * the server actually asks for it, and cancelled prompts simply skip the method.
 */
@Singleton
class AuthResolver @Inject constructor(
    private val identities: IdentityRepository,
    private val secrets: SecretStore,
    private val hardwareKeys: HardwareKeys,
    private val prompts: PromptCenter,
) {
    suspend fun resolve(host: Host): List<SshAuth> {
        val interactive = SshAuth.KeyboardInteractive { instruction, prompt, _ ->
            runBlocking { prompts.password(host, serverPrompt = prompt, instruction = instruction.ifBlank { null }) }
        }
        return when (val auth = host.auth) {
            is AuthMethod.Key -> {
                val identity = identities.get(auth.identityId) ?: throw IllegalStateException("The key for ${host.name} no longer exists")
                val provider = if (identity.isHardwareBacked) {
                    hardwareKeys.keyProvider(identity.keystoreAlias ?: HardwareKeys.aliasFor(identity.id))
                } else {
                    val pem = identities.privateKey(identity.id)?.toString(Charsets.UTF_8)
                        ?: throw IllegalStateException("Private key for ${identity.name} is missing")
                    if (SshKeys.isEncrypted(pem)) {
                        val passphrase = prompts.passphrase(host, identity.name) ?: throw IllegalStateException("Passphrase entry cancelled")
                        SshKeys.load(pem, passphrase = passphrase)
                    } else {
                        SshKeys.load(pem)
                    }
                }
                listOf(SshAuth.PublicKey(provider), interactive, askPassword(host))
            }
            is AuthMethod.Password -> {
                val saved = auth.secretId?.let { secrets.get(it) }?.toString(Charsets.UTF_8)?.toCharArray()
                val password = if (saved != null) SshAuth.Password { saved } else askPassword(host)
                listOf(password, interactive)
            }
            AuthMethod.AskEachTime -> listOf(askPassword(host), interactive)
        }
    }

    private fun askPassword(host: Host) = SshAuth.Password { runBlocking { prompts.password(host) } }

    companion object {
        fun passwordSecretId(hostId: String) = "host-password:$hostId"
    }
}
