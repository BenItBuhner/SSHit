package app.berth.android.session

import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.ssh.FingerprintCheck.MATCH
import app.berth.ssh.FingerprintCheck.MISMATCH
import app.berth.ssh.FingerprintCheck.UNREADABLE
import app.berth.ssh.HostKeyFingerprints
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkFingerprint.of] on its own, without an sshd: the link against the offered key, and on the
 * changed-key sheet against the saved key too, through the saved row's stored public key blob so an
 * MD5 link compares as well as a SHA-256 one; and what is said when the blob will not read back.
 */
class LinkFingerprintOfTest {
    init {
        SshSecurity.ensureProviders()
    }

    private val offered = SshKeys.generate(KeyAlgorithm.ED25519).public
    private val saved = SshKeys.generate(KeyAlgorithm.ED25519).public
    private val row = KnownHostKey("k", "h", 22, "ssh-ed25519", SshKeys.publicKeyBase64(saved), SshKeys.fingerprintSha256(saved), 0L, 0L)

    @Test
    fun `no link, or a blank one, is no comparison`() {
        assertNull(LinkFingerprint.of(null, offered, row))
        assertNull(LinkFingerprint.of("  ", offered, row))
    }

    @Test
    fun `the saved key's fingerprint, in either form, is the saved key's and not the offered key's`() {
        with(LinkFingerprint.of(SshKeys.fingerprintSha256(saved), offered, row)!!) {
            assertEquals(MISMATCH, check)
            assertEquals(MATCH, savedCheck)
            assertTrue(matchesSaved)
        }
        with(LinkFingerprint.of(HostKeyFingerprints.md5(saved).removePrefix("MD5:").uppercase(), offered, row)!!) {
            assertEquals(MISMATCH, check)
            assertEquals(MATCH, savedCheck)
        }
        with(LinkFingerprint.of(SshKeys.fingerprintSha256(offered), offered, row)!!) {
            assertEquals(MATCH, check)
            assertEquals(MISMATCH, savedCheck)
            assertFalse(matchesSaved)
        }
        val third = SshKeys.generate(KeyAlgorithm.ED25519).public
        with(LinkFingerprint.of(SshKeys.fingerprintSha256(third), offered, row)!!) {
            assertEquals(MISMATCH, check)
            assertEquals(MISMATCH, savedCheck)
        }
    }

    @Test
    fun `on first contact there is no saved key to compare with`() {
        val link = LinkFingerprint.of(SshKeys.fingerprintSha256(saved), offered)!!
        assertEquals(MISMATCH, link.check)
        assertNull(link.savedCheck)
        assertFalse(link.matchesSaved)
    }

    @Test
    fun `a saved row whose blob will not read back compares by its SHA-256 alone, so an MD5 link has nothing to compare with`() {
        val damaged = row.copy(publicKeyBase64 = "not base64!")
        assertEquals(MATCH, LinkFingerprint.of(SshKeys.fingerprintSha256(saved), offered, damaged)!!.savedCheck)
        assertEquals(MISMATCH, LinkFingerprint.of(SshKeys.fingerprintSha256(offered), offered, damaged)!!.savedCheck)
        assertNull(LinkFingerprint.of(HostKeyFingerprints.md5(saved), offered, damaged)!!.savedCheck)
    }

    @Test
    fun `a fingerprint Berth cannot read is unreadable against both keys`() {
        with(LinkFingerprint.of("nonsense\u202e", offered, row)!!) {
            assertEquals(UNREADABLE, check)
            assertEquals(UNREADABLE, savedCheck)
            assertEquals("nonsense", quoted)
        }
    }
}
