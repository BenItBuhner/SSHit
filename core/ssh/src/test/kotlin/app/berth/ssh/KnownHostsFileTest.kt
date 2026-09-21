package app.berth.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lines are `ssh-keyscan` output for the two test sshds and `ssh-keygen -H` over the same; the keys are real. */
class KnownHostsFileTest {
    init {
        SshSecurity.ensureProviders()
    }

    private val ed25519 = "AAAAC3NzaC1lZDI1NTE5AAAAIP2R11gOlpVtgtUojtKmyzfXdwVFQlmWgI5esCP8S2Po"
    private val ecdsa = "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBVtKHPDQtuqkvF94hHx0LkroJlgPdAnR2ojfYP5kEamcby3NzbNmrgE5KRZxgtZznrKmM3NdJXMmr+4ZCujv0A="
    private val hashedEd25519 = "|1|SglisSCFv+vprT37hIBnlEMPHVY=|xUvJ8nwGDINbvflhmvVrjf44wdk= ssh-ed25519 $ed25519"

    @Test
    fun `a keyscan file yields one entry per host and key`() {
        val parsed = KnownHostsFile.parse(
            """
            # 127.0.0.1:2222 SSH-2.0-OpenSSH_9.6p1
            [127.0.0.1]:2222 ssh-ed25519 $ed25519
            [127.0.0.1]:2223 ecdsa-sha2-nistp256 $ecdsa

            example.com,93.184.216.34 ssh-ed25519 $ed25519 a comment here
            """.trimIndent(),
        )
        assertTrue(parsed.skipped.isEmpty(), "${parsed.skipped}")
        assertEquals(listOf("127.0.0.1:2222", "127.0.0.1:2223", "example.com", "93.184.216.34"), parsed.entries.map { it.address })
        parsed.entries[0].let {
            assertEquals("127.0.0.1", it.host)
            assertEquals(2222, it.port)
            assertEquals("ssh-ed25519", it.keyType)
            assertEquals(ed25519, it.publicKeyBase64)
            assertEquals(SshKeys.fingerprintSha256(SshKeys.parsePublicKeyBlob(ed25519)), it.fingerprintSha256)
            assertFalse(it.hashed)
            assertEquals(2, it.line)
        }
        assertEquals("ecdsa-sha2-nistp256", parsed.entries[1].keyType)
        assertEquals(22, parsed.entries[2].port)
    }

    @Test
    fun `a hashed line is read for a saved host whose address hashes the same`() {
        val parsed = KnownHostsFile.parse(hashedEd25519, knownAddresses = listOf("10.0.0.5" to 22, "127.0.0.1" to 2222))
        assertEquals(1, parsed.entries.size)
        parsed.entries.single().let {
            assertEquals("127.0.0.1", it.host)
            assertEquals(2222, it.port)
            assertTrue(it.hashed)
        }
        assertEquals(0, parsed.hashedUnresolved)
    }

    @Test
    fun `a hashed line matching no saved host is counted, not guessed`() {
        val parsed = KnownHostsFile.parse(hashedEd25519, knownAddresses = listOf("127.0.0.1" to 22))
        assertTrue(parsed.isEmpty)
        assertEquals(1, parsed.hashedUnresolved)
        assertEquals(1, parsed.skipped.single().line)
    }

    @Test
    fun `markers, wildcards, negations, SSH-1 keys and junk are skipped with a reason each`() {
        val parsed = KnownHostsFile.parse(
            """
            @cert-authority *.example.com ssh-ed25519 $ed25519
            @revoked bad.example.com ssh-ed25519 $ed25519
            *.internal ssh-ed25519 $ed25519
            !host.example.com,host2.example.com ssh-ed25519 $ed25519
            legacy.example.com 1024 35 1234567890
            not a known hosts line at all
            broken.example.com ssh-ed25519 AAAAnotbase64!!
            """.trimIndent(),
        )
        assertEquals(listOf("host2.example.com"), parsed.entries.map { it.address }, "the negation alone is dropped from its line")
        val reasons = parsed.skipped.associate { it.line to it.reason }
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), reasons.keys)
        assertTrue(reasons.getValue(1).contains("certificate authority"))
        assertTrue(reasons.getValue(2).contains("revoked"))
        assertTrue(reasons.getValue(3).contains("wildcard"))
        assertTrue(reasons.getValue(4).contains("negated"))
        assertTrue(reasons.getValue(5).contains("SSH-1"))
        assertEquals("not a host key line", reasons.getValue(6))
        assertTrue(reasons.getValue(7).contains("decode"))
    }

    @Test
    fun `the same key for the same address is one entry however often it appears`() {
        val parsed = KnownHostsFile.parse(
            """
            [127.0.0.1]:2222 ssh-ed25519 $ed25519
            [127.0.0.1]:2222 ssh-ed25519 $ed25519
            [127.0.0.1]:2222 ecdsa-sha2-nistp256 $ecdsa
            """.trimIndent(),
        )
        assertEquals(2, parsed.entries.size)
    }

    @Test
    fun `an IPv6 address keeps its colons and its port`() {
        val parsed = KnownHostsFile.parse("[fe80::1%eth0]:2200,[2001:db8::10] ssh-ed25519 $ed25519")
        assertEquals(listOf("fe80::1%eth0" to 2200, "2001:db8::10" to 22), parsed.entries.map { it.host to it.port })
    }

    @Test
    fun `a port out of range is a skip, not an entry`() {
        val parsed = KnownHostsFile.parse("[host]:70000 ssh-ed25519 $ed25519")
        assertTrue(parsed.isEmpty)
        assertTrue(parsed.skipped.single().reason.contains("host and port"))
    }

    @Test
    fun `the OpenSSH pattern for an address is what its hash covers`() {
        assertEquals("example.com", KnownHostsFile.patternFor("example.com", 22))
        assertEquals("[example.com]:2222", KnownHostsFile.patternFor("example.com", 2222))
    }
}
