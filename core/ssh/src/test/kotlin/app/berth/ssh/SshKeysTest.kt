package app.berth.ssh

import app.berth.domain.model.KeyAlgorithm
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshKeysTest {
    init {
        SshSecurity.ensureProviders()
    }

    @Test
    fun `generated keys round trip through OpenSSH public and private formats`() {
        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_P256, KeyAlgorithm.ECDSA_P384, KeyAlgorithm.RSA_3072)) {
            val pair = SshKeys.generate(algorithm)
            assertEquals(algorithm.sshName, SshKeys.keyTypeName(pair.public), "wire name for $algorithm")
            assertEquals(algorithm, SshKeys.algorithmOf(pair.public))

            val publicLine = SshKeys.openSshPublic(pair.public, "berth test")
            assertTrue(publicLine.endsWith(" berth test"))
            val parsed = SshKeys.parseOpenSshPublic(publicLine)
            assertContentEquals(SshKeys.publicKeyBlob(pair.public), SshKeys.publicKeyBlob(parsed), "public blob for $algorithm")

            val privateText = SshKeys.openSshPrivate(pair, "berth test")
            assertTrue(privateText.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
            assertFalse(SshKeys.isEncrypted(privateText))
            val provider = SshKeys.load(privateText)
            assertContentEquals(SshKeys.publicKeyBlob(pair.public), SshKeys.publicKeyBlob(provider.public), "loaded public for $algorithm")
            // Private key material must survive the round trip too, not just the public half.
            assertTrue(samePrivateKey(pair.private, provider.private), "loaded private for $algorithm")
        }
    }

    /** Providers encode the same key differently (named vs explicit curves), so compare the scalars. */
    private fun samePrivateKey(a: PrivateKey, b: PrivateKey): Boolean = when {
        a is ECPrivateKey && b is ECPrivateKey -> a.s == b.s
        a is RSAPrivateKey && b is RSAPrivateKey -> a.modulus == b.modulus && a.privateExponent == b.privateExponent
        else -> a.encoded.contentEquals(b.encoded)
    }

    @Test
    fun `fingerprints use the ssh-keygen SHA256 form`() {
        val pair = SshKeys.generate(KeyAlgorithm.ED25519)
        val fp = SshKeys.fingerprintSha256(pair.public)
        assertTrue(fp.startsWith("SHA256:"))
        assertEquals(43, fp.removePrefix("SHA256:").length)
        assertFalse(fp.endsWith("="))
        assertEquals(fp.removePrefix("SHA256:").chunked(4).joinToString(" "), SshKeys.groupedFingerprint(fp))
    }

    @Test
    fun `encrypted key detection`() {
        assertTrue(SshKeys.isEncrypted("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n"))
        val plain = SshKeys.openSshPrivate(SshKeys.generate(KeyAlgorithm.ED25519))
        assertFalse(SshKeys.isEncrypted(plain))
    }

    @Test
    fun `OpenSSH itself accepts the private keys we write`() {
        val keygen = listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("ssh-keygen not available", keygen != null)

        for (algorithm in listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_P256, KeyAlgorithm.RSA_3072)) {
            val pair = SshKeys.generate(algorithm)
            val dir = Files.createTempDirectory("berth-keys").toFile()
            try {
                val keyFile = File(dir, "id")
                keyFile.writeText(SshKeys.openSshPrivate(pair, "berth"))
                Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"))

                val derived = run(keygen!!.path, "-y", "-f", keyFile.path)
                assertEquals(SshKeys.openSshPublic(pair.public, "berth"), derived.trim(), "ssh-keygen -y for $algorithm")

                val fingerprintLine = run(keygen.path, "-lf", keyFile.path)
                assertTrue(fingerprintLine.contains(SshKeys.fingerprintSha256(pair.public)), "ssh-keygen -lf for $algorithm: $fingerprintLine")
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `passphrase protected keys load with the passphrase and nowhere else`() {
        val pair = SshKeys.generate(KeyAlgorithm.ED25519)
        val pem = SshKeys.openSshPrivate(pair, "berth", "correct horse".toCharArray())
        assertTrue(SshKeys.isEncrypted(pem))
        assertTrue(pem.contains("BEGIN OPENSSH PRIVATE KEY"))

        val loaded = SshKeys.load(pem, passphrase = "correct horse".toCharArray())
        assertEquals(SshKeys.publicKeyBase64(pair.public), SshKeys.publicKeyBase64(loaded.public))
        assertTrue(samePrivateKey(pair.private, loaded.private))

        val wrong = runCatching { SshKeys.load(pem, passphrase = "wrong".toCharArray()).private }
        assertTrue(wrong.isFailure, "a wrong passphrase must not yield a key")

        val keygen = listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("ssh-keygen not available", keygen != null)
        val dir = Files.createTempDirectory("berth-keys").toFile()
        try {
            val keyFile = File(dir, "id")
            keyFile.writeText(pem)
            Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------"))
            val derived = run(keygen!!.path, "-y", "-P", "correct horse", "-f", keyFile.path)
            assertEquals(SshKeys.openSshPublic(pair.public, "berth"), derived.trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "timed out: ${command.joinToString(" ")}")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed: $output")
        return output
    }
}
