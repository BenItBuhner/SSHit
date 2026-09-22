package app.berth.android.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import app.berth.android.session.Prompt
import app.berth.android.session.PromptCenter
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeyAuthModel
import app.berth.data.crypto.KeystoreSigning
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.repository.IdentityRepository
import app.berth.ssh.AgentKey
import app.berth.ssh.SshAuth
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSigner
import app.berth.ssh.encodeEcdsaP256Signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a Keystore-backed identity into the `publickey` method for one connection, getting the
 * user past the key's authentication on the way (spec C20, Biometric gating). A key that needs
 * the user for every use is unlocked through the system prompt carrying the very `Signature` that
 * will sign; a key with a timed window is tried first and the prompt shown only when the window
 * has closed, then tried once more. A key Android has invalidated (new biometrics enrolled) is
 * named to the user, who may have it regenerated. The same key held by a forwarded agent
 * ([agentKey]) gets past the same authentication for each signature it makes.
 */
@Singleton
class KeyUnlocker @Inject constructor(
    private val keystore: KeystoreSigning,
    private val identities: IdentityRepository,
    private val authenticator: DeviceAuthenticator,
    private val prompts: PromptCenter,
    private val appLock: AppLockController,
) {
    /**
     * The `publickey` auth for [identity] against [host], with the user's authentication done.
     * Throws [IllegalStateException] with the plain reason when the user cancelled or the key
     * cannot sign, which the session shows as its failure state.
     */
    suspend fun authFor(host: Host, identity: Identity): SshAuth.PublicKey {
        val alias = identity.keystoreAlias ?: HardwareKeys.aliasFor(identity.id)
        val provider = keystore.keyProvider(alias)
        val signature = try {
            when (keystore.authModel(alias)) {
                KeyAuthModel.NONE -> keystore.beginSign(alias)
                KeyAuthModel.PER_USE -> unlockPerUse(host, identity, alias)
                KeyAuthModel.TIMED_WINDOW -> unlockTimedWindow(host, identity, alias)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated(host, identity, alias)
        }
        return SshAuth.PublicKey(provider, SshSigner { data ->
            signature.update(data)
            encodeEcdsaP256Signature(signature.sign())
        })
    }

    /**
     * [identity] as the agent forwarded to [host] holds it. Nothing is unlocked until a program on
     * the host asks for a signature, and then each signature gets past the key's authentication the
     * way a login does: a key that needs the user for every use prompts for every signature, one
     * with a timed window is tried first, one that needs nobody signs. A key Android has invalidated
     * refuses the request; the next connection offers to regenerate it.
     */
    fun agentKey(host: Host, identity: Identity): AgentKey {
        val alias = identity.keystoreAlias ?: HardwareKeys.aliasFor(identity.id)
        val public = keystore.keyProvider(alias).public
        return AgentKey(public, agentComment(identity), identity.name) { data, _ ->
            val signature = try {
                when (keystore.authModel(alias)) {
                    KeyAuthModel.NONE -> keystore.beginSign(alias)
                    KeyAuthModel.PER_USE -> unlockPerUse(host, identity, alias, forwarded = true)
                    KeyAuthModel.TIMED_WINDOW -> unlockTimedWindow(host, identity, alias, forwarded = true)
                }
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw IllegalStateException("${keyName(identity)} can no longer sign: this device's fingerprints or face changed since the key was made.", e)
            }
            signature.update(data)
            AgentKey.signatureBlob(SshKeys.keyTypeName(public), encodeEcdsaP256Signature(signature.sign()))
        }
    }

    private suspend fun unlockPerUse(host: Host, identity: Identity, alias: String, forwarded: Boolean = false): Signature {
        // Invalidation surfaces here, before any prompt; the prompt then authorises this very object.
        val fresh = keystore.beginSign(alias)
        return when (val outcome = prompt(host, identity, fresh, forwarded)) {
            is AuthOutcome.Succeeded -> outcome.signature ?: fresh
            AuthOutcome.Cancelled -> throw IllegalStateException(cancelledMessage(host, identity))
            is AuthOutcome.Failed -> throw IllegalStateException("${keyName(identity)} could not be unlocked: ${outcome.message}")
        }
    }

    private suspend fun unlockTimedWindow(host: Host, identity: Identity, alias: String, forwarded: Boolean = false): Signature {
        try {
            return keystore.beginSign(alias)
        } catch (_: UserNotAuthenticatedException) {
            // The window has closed; one prompt reopens it, then one more try.
        }
        when (val outcome = prompt(host, identity, signature = null, forwarded)) {
            is AuthOutcome.Succeeded -> Unit
            AuthOutcome.Cancelled -> throw IllegalStateException(cancelledMessage(host, identity))
            is AuthOutcome.Failed -> throw IllegalStateException("${keyName(identity)} could not be unlocked: ${outcome.message}")
        }
        return try {
            keystore.beginSign(alias)
        } catch (e: UserNotAuthenticatedException) {
            throw IllegalStateException("${keyName(identity)} stayed locked after the unlock. Try connecting again.", e)
        }
    }

    /** The system prompt under a sheet that says why it is up; the sheet's Cancel cancels the prompt. */
    private suspend fun prompt(host: Host, identity: Identity, signature: Signature?, forwarded: Boolean): AuthOutcome {
        appLock.awaitUnlocked()
        return prompts.unlockKey(host, identity.name, forwarded) { prompt -> authenticate(prompt, host, identity, signature) }
    }

    private suspend fun authenticate(prompt: Prompt.UnlockKey, host: Host, identity: Identity, signature: Signature?): AuthOutcome = coroutineScope {
        val title = if (prompt.forwarded) forwardedPromptTitle(host) else promptTitle(host)
        val auth = async { authenticator.authenticate(title, promptSubtitle(identity.name), signature) }
        val watcher = launch {
            prompt.cancelled.await()
            auth.cancel()
        }
        try {
            auth.await()
        } catch (e: CancellationException) {
            if (prompt.cancelled.isCompleted) AuthOutcome.Cancelled else throw e
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun invalidated(host: Host, identity: Identity, alias: String): Nothing {
        appLock.awaitUnlocked()
        val regenerate = prompts.keyInvalidated(host, identity)
        if (!regenerate) throw IllegalStateException("${keyName(identity)} can no longer sign: this device's fingerprints or face changed since the key was made.")
        val public = keystore.regenerate(alias, identity.protection)
        identities.update(
            identity.copy(
                publicKeyOpenSsh = SshKeys.openSshPublic(public, identity.comment),
                fingerprintSha256 = SshKeys.fingerprintSha256(public),
            ),
        )
        throw IllegalStateException("${keyName(identity)} has a new key pair. Add its public key to ${host.name} (Keys, Copy public key), then connect again.")
    }

    companion object {
        /**
         * A key's name is free text (`Pixel`, `work`, `this phone`), so a sentence never leads with it
         * bare: it is always quoted and framed as a key.
         */
        fun keyName(name: String): String = "The key \u201C$name\u201D"

        fun keyName(identity: Identity): String = keyName(identity.name)

        /** The sheet's and the system prompt's title: what the user is doing, not the key's name. */
        fun promptTitle(host: Host): String = "Sign in to ${host.name}"

        /** The title when a program on [host] asked the forwarded agent for the signature. */
        fun forwardedPromptTitle(host: Host): String = "Sign for ${host.name}"

        fun promptSubtitle(keyName: String): String = "Confirm to sign with the key \u201C$keyName\u201D"

        /** What `ssh-add -L` on the remote prints after the key: its own comment, or its name when it has none. */
        fun agentComment(identity: Identity): String = identity.comment.ifBlank { identity.name }

        fun cancelledMessage(host: Host, identity: Identity): String =
            "Cancelled before the key \u201C${identity.name}\u201D could sign, so Berth did not sign in to ${host.name}."
    }
}
