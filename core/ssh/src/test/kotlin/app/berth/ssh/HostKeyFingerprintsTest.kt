package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostKeyFingerprintsTest {
    init {
        SshSecurity.ensureProviders()
    }

    private val key = SshKeys.generate(KeyAlgorithm.ED25519).public
    private val other = SshKeys.generate(KeyAlgorithm.ECDSA_P256).public
    private val sha256 = SshKeys.fingerprintSha256(key)
    private val sha256Body = sha256.removePrefix("SHA256:")
    private val md5 = HostKeyFingerprints.md5(key)
    private val md5Body = md5.removePrefix("MD5:")
    private val sha256Hex = java.security.MessageDigest.getInstance("SHA-256").digest(SshKeys.publicKeyBlob(key)).joinToString(":") { "%02x".format(it) }

    @Test
    fun `the forms ssh-keygen prints match the key they are of`() {
        val forms = listOf(
            sha256,
            sha256Body,
            "sha256:$sha256Body",
            "SHA256:$sha256Body=",
            " SHA256:$sha256Body ",
            "SHA256:" + sha256Body.replace('+', '-').replace('/', '_'),
            md5,
            md5Body,
            "md5:$md5Body",
            "MD5:" + md5Body.uppercase(),
            md5Body.uppercase(),
            sha256Hex,
            "SHA256:$sha256Hex",
        )
        for (form in forms) assertEquals(FingerprintCheck.MATCH, HostKeyFingerprints.check(form, key), form)
    }

    @Test
    fun `the ssh URI draft's host-key-alg-fingerprint form matches, with dashes and the algorithm before the pairs`() {
        val dashedMd5 = md5Body.replace(':', '-')
        val dashedSha256 = sha256Hex.replace(':', '-')
        val forms = listOf(
            dashedMd5,
            "ssh-ed25519-$dashedMd5",
            "SSH-ED25519-${dashedMd5.uppercase()}",
            "ssh-rsa-$dashedMd5",
            "ecdsa-sha2-nistp256-$dashedMd5",
            "sk-ssh-ed25519@openssh.com-$dashedMd5",
            "md5-$dashedMd5",
            "MD5:$dashedMd5",
            dashedSha256,
            "ssh-ed25519-$dashedSha256",
            "rsa-sha2-512-$dashedSha256",
        )
        for (form in forms) assertEquals(FingerprintCheck.MATCH, HostKeyFingerprints.check(form, key), form)
        assertEquals(md5, HostKeyFingerprints.normalize("ssh-ed25519-$dashedMd5"))
        assertEquals(sha256, HostKeyFingerprints.normalize("ssh-ed25519-$dashedSha256"))
        // The algorithm is not compared: a link that names the wrong one still names this key's fingerprint.
        assertEquals(FingerprintCheck.MATCH, HostKeyFingerprints.check("ssh-dss-$dashedMd5", key))
    }

    @Test
    fun `another key's fingerprint is a mismatch in either hash`() {
        assertEquals(FingerprintCheck.MISMATCH, HostKeyFingerprints.check(SshKeys.fingerprintSha256(other), key))
        assertEquals(FingerprintCheck.MISMATCH, HostKeyFingerprints.check(HostKeyFingerprints.md5(other), key))
        assertEquals(FingerprintCheck.MISMATCH, HostKeyFingerprints.check(HostKeyFingerprints.md5(other).removePrefix("MD5:"), key))
        assertEquals(FingerprintCheck.MISMATCH, HostKeyFingerprints.check("ssh-ed25519-" + HostKeyFingerprints.md5(other).removePrefix("MD5:").replace(':', '-'), key))
    }

    @Test
    fun `what is not a fingerprint is unreadable rather than a mismatch`() {
        val notFingerprints = listOf(
            "",
            "   ",
            "SHA256:",
            "SHA256:${sha256Body.dropLast(1)}",
            "SHA256:${sha256Body}A",
            "SHA256:${sha256Body.dropLast(1)}*",
            "SHA1:${sha256Body}",
            md5Body.dropLast(3),
            "$md5Body:aa",
            "ssh-ed25519-" + md5Body.replace(':', '-').dropLast(3),
            "ssh-ed25519-" + sha256Hex.replace(':', '-') + "-aa",
            "ssh-ed25519:" + md5Body.replace(':', '-'),
            "MD5:${md5Body.replaceRange(0, 2, "zz")}",
            "MD5:" + sha256Hex,
            "trust me",
            "\u001B[2J\u001B[H",
            sha256Body.substring(0, 20) + " " + sha256Body.substring(20) + "x",
        )
        for (text in notFingerprints) {
            assertEquals(FingerprintCheck.UNREADABLE, HostKeyFingerprints.check(text, key), "'$text'")
            assertNull(HostKeyFingerprints.normalize(text), "'$text'")
        }
    }

    @Test
    fun `normalize writes the form Berth shows`() {
        assertEquals(sha256, HostKeyFingerprints.normalize("sha256:$sha256Body="))
        assertEquals(sha256, HostKeyFingerprints.normalize(sha256Body.replace('+', '-').replace('/', '_')))
        assertEquals(md5, HostKeyFingerprints.normalize(md5Body.uppercase()))
        assertEquals(md5, HostKeyFingerprints.normalize("MD5: ${md5Body.uppercase()}"))
    }

    @Test
    fun `md5 is the digest of the key blob as ssh-keygen -E md5 prints it`() {
        val expected = java.security.MessageDigest.getInstance("MD5").digest(SshKeys.publicKeyBlob(key)).joinToString(":") { "%02x".format(it) }
        assertEquals("MD5:$expected", md5)
        assertEquals(47, md5Body.length)
        // And SHA256 is what the app already shows for the same key, so a link may carry that form verbatim.
        assertEquals(43, sha256Body.length)
        assertEquals(sha256Body, Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(SshKeys.publicKeyBlob(key))))
    }
}
