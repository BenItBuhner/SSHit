package app.berth.data

import app.berth.data.bundle.BundleCodec
import app.berth.data.bundle.BundleException
import app.berth.data.bundle.BundleKdf
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `.berth` container on its own: what opens, what does not, and that nothing about the
 * payload shows through. A small Argon2 cost keeps each case quick; one case pays the default.
 */
class BundleCodecTest {
    private val codec = BundleCodec()
    private val quick = BundleKdf(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val payload = """{"format":1,"hosts":[{"name":"prod-web","address":"203.0.113.10"}]}""".toByteArray()

    @Test
    fun `a bundle opens with the passphrase it was sealed under`() {
        val blob = codec.seal(payload, "correct horse battery staple".toCharArray(), quick)
        assertContentEquals(payload, codec.open(blob, "correct horse battery staple".toCharArray()))
        assertEquals(quick, codec.inspect(blob))
    }

    @Test
    fun `the header reads back its magic, version, cost and lengths`() {
        val blob = codec.seal(payload, "pw".toCharArray(), quick)
        assertEquals("BRTH", blob.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val header = ByteBuffer.wrap(blob, 4, BundleCodec.HEADER_BYTES - 4)
        assertEquals(BundleCodec.VERSION, header.get().toInt())
        assertEquals(BundleCodec.KDF_ARGON2ID, header.get().toInt())
        assertEquals(quick.memoryKiB, header.getInt())
        assertEquals(quick.iterations, header.getInt())
        assertEquals(quick.parallelism, header.get().toInt())
        assertEquals(16, header.get().toInt())
        // Header, then the ciphertext the size of the payload, then the 16-byte tag.
        assertEquals(BundleCodec.HEADER_BYTES + payload.size + 16, blob.size)
    }

    @Test
    fun `a wrong passphrase does not open it, and says so without saying which`() {
        val blob = codec.seal(payload, "correct horse battery staple".toCharArray(), quick)
        val sealed = assertFailsWith<BundleException.Sealed> { codec.open(blob, "correct horse battery stable".toCharArray()) }
        assertTrue("passphrase" in sealed.message!! && "changed" in sealed.message!!)
        assertFailsWith<BundleException.Sealed> { codec.open(blob, "".toCharArray()) }
    }

    @Test
    fun `a changed byte anywhere fails the tag, whether ciphertext, tag or header`() {
        val blob = codec.seal(payload, "pw".toCharArray(), quick)
        fun flipped(at: Int) = blob.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }

        // Ciphertext: the first, a middle and the last byte before the tag.
        for (at in listOf(BundleCodec.HEADER_BYTES, BundleCodec.HEADER_BYTES + payload.size / 2, BundleCodec.HEADER_BYTES + payload.size - 1)) {
            assertFailsWith<BundleException.Sealed>("byte $at") { codec.open(flipped(at), "pw".toCharArray()) }
        }
        // The tag itself.
        assertFailsWith<BundleException.Sealed> { codec.open(flipped(blob.size - 1), "pw".toCharArray()) }
        // The salt and the nonce derive a different key or stream: the tag fails.
        assertFailsWith<BundleException.Sealed> { codec.open(flipped(16), "pw".toCharArray()) }
        assertFailsWith<BundleException.Sealed> { codec.open(flipped(32), "pw".toCharArray()) }
        // The cost is associated data too: a bundle whose memory was lowered after the fact does not open.
        val cheaper = blob.copyOf().also { ByteBuffer.wrap(it, 6, 4).putInt(512) }
        assertFailsWith<BundleException.Sealed> { codec.open(cheaper, "pw".toCharArray()) }
        // Cut short after the header: not a wrong passphrase, but the same answer, since it is the file that changed.
        assertFailsWith<BundleException.Sealed> { codec.open(blob.copyOf(blob.size - 5), "pw".toCharArray()) }
        assertFailsWith<BundleException.Sealed> { codec.open(blob.copyOf(BundleCodec.HEADER_BYTES + 3), "pw".toCharArray()) }
    }

    @Test
    fun `a file that is not a bundle is refused before any key is derived`() {
        assertFailsWith<BundleException.NotABundle> { codec.open(ByteArray(0), "pw".toCharArray()) }
        assertFailsWith<BundleException.NotABundle> { codec.open("BRT".toByteArray(), "pw".toCharArray()) }
        assertFailsWith<BundleException.NotABundle> { codec.open(payload, "pw".toCharArray()) }
        assertFailsWith<BundleException.NotABundle> { codec.open(ByteArray(200) { it.toByte() }, "pw".toCharArray()) }
    }

    @Test
    fun `a newer container, another kdf or a hostile cost is refused as unsupported, not as a wrong passphrase`() {
        val blob = codec.seal(payload, "pw".toCharArray(), quick)
        val newer = blob.copyOf().also { it[4] = 2 }
        assertTrue("version 2" in assertFailsWith<BundleException.Unsupported> { codec.open(newer, "pw".toCharArray()) }.message!!)
        val otherKdf = blob.copyOf().also { it[5] = 7 }
        assertFailsWith<BundleException.Unsupported> { codec.open(otherKdf, "pw".toCharArray()) }
        // 4 GiB of Argon2 memory asked for by the header: refused by the ceiling, never allocated.
        val greedy = blob.copyOf().also { ByteBuffer.wrap(it, 6, 4).putInt(4 * 1024 * 1024) }
        val started = System.nanoTime()
        assertFailsWith<BundleException.Unsupported> { codec.open(greedy, "pw".toCharArray()) }
        assertTrue((System.nanoTime() - started) < 1_000_000_000L, "a refused header costs nothing")
        val endless = blob.copyOf().also { ByteBuffer.wrap(it, 10, 4).putInt(1_000_000) }
        assertFailsWith<BundleException.Unsupported> { codec.open(endless, "pw".toCharArray()) }
        val oddSalt = blob.copyOf().also { it[15] = 8 }
        assertFailsWith<BundleException.Unsupported> { codec.open(oddSalt, "pw".toCharArray()) }
    }

    /**
     * The band the ceiling is for: a header within what Argon2 can express and beyond what a phone
     * can allocate. The reader's own cap refuses it as unsupported, never as a wrong passphrase,
     * before a block is asked for; a header at the cap is one it would run. The test JVM's heap is
     * Gradle's default 512 MB, so had the gigabyte been allocated this would have died of an
     * `OutOfMemoryError`, not failed an assertion.
     */
    @Test
    fun `a header asking for a gigabyte, or for more passes than the work cap allows, is refused before any of it is allocated`() {
        val blob = codec.seal(payload, "pw".toCharArray(), quick)
        fun withCost(memoryKiB: Int, iterations: Int): ByteArray = blob.copyOf().also {
            ByteBuffer.wrap(it, 6, 4).putInt(memoryKiB)
            ByteBuffer.wrap(it, 10, 4).putInt(iterations)
        }
        // 1 GiB, exactly what the old bound let through.
        val gigabyte = withCost(1 shl 20, 3)
        val started = System.nanoTime()
        assertTrue("1048576 KiB" in assertFailsWith<BundleException.Unsupported> { codec.open(gigabyte, "pw".toCharArray()) }.message!!)
        assertTrue((System.nanoTime() - started) < 1_000_000_000L, "a refused header costs nothing")
        // One KiB over the memory ceiling.
        assertFailsWith<BundleException.Unsupported> { codec.open(withCost(BundleKdf.MAX_MEMORY_KIB + 1, 1), "pw".toCharArray()) }
        // At the memory ceiling but over the work cap: 256 MiB × 5 passes.
        assertFailsWith<BundleException.Unsupported> { codec.open(withCost(BundleKdf.MAX_MEMORY_KIB, 5), "pw".toCharArray()) }
        // Under both caps the header is taken and it is the passphrase that decides: the tag fails on the changed header.
        assertFailsWith<BundleException.Sealed> { codec.open(withCost(2048, 2), "pw".toCharArray()) }

        // The predicate itself, at its edges.
        assertTrue(BundleKdf(BundleKdf.MAX_MEMORY_KIB, 4, 4).isSane, "256 MiB × 4 is 1 GiB·pass, the most a reader runs")
        assertFalse(BundleKdf(BundleKdf.MAX_MEMORY_KIB, 5, 4).isSane, "256 MiB × 5 is over the work cap")
        assertFalse(BundleKdf(BundleKdf.MAX_MEMORY_KIB + 1, 1, 1).isSane, "one KiB over the memory cap")
        assertTrue(BundleKdf(64 * 1024, 16, 4).isSane, "64 MiB runs sixteen passes")
        assertFalse(BundleKdf(64 * 1024, 17, 4).isSane, "and not seventeen")
        assertFalse(BundleKdf(1 shl 20, 1, 1).isSane, "a gigabyte is never run")
        assertEquals(256 * 1024, BundleKdf.MAX_MEMORY_KIB)
        assertEquals(1L shl 20, BundleKdf.MAX_WORK_KIB_PASSES)
    }

    @Test
    fun `sealing is randomised and the payload never shows through`() {
        val first = codec.seal(payload, "pw".toCharArray(), quick)
        val second = codec.seal(payload, "pw".toCharArray(), quick)
        assertFalse(first.contentEquals(second), "a fresh salt and nonce every time")
        for (blob in listOf(first, second)) {
            assertFalse(blob.containsSlice("prod-web".toByteArray()), "the host's name must not be readable in the file")
            assertFalse(blob.containsSlice("203.0.113.10".toByteArray()))
            assertFalse(blob.containsSlice("\"format\"".toByteArray()))
        }
    }

    @Test
    fun `the passphrase is UTF-8, so one typed with accents or an emoji opens on any phone`() {
        val passphrase = "Pässwörd \u2014 \uD83D\uDD12 \u5B89\u5168".toCharArray()
        val blob = codec.seal(payload, passphrase, quick)
        assertContentEquals(payload, codec.open(blob, passphrase.copyOf()))
        assertFailsWith<BundleException.Sealed> { codec.open(blob, "P\u00E4ssw\u00F6rd".toCharArray()) }
    }

    @Test
    fun `the cost is checked at both ends, and an empty passphrase is refused`() {
        assertFailsWith<IllegalArgumentException> { codec.seal(payload, "pw".toCharArray(), BundleKdf(memoryKiB = 8, iterations = 1, parallelism = 4)) }
        assertFailsWith<IllegalArgumentException> { codec.seal(payload, "pw".toCharArray(), BundleKdf(memoryKiB = 2 shl 20, iterations = 1, parallelism = 1)) }
        assertFailsWith<IllegalArgumentException> { codec.seal(payload, "pw".toCharArray(), BundleKdf(memoryKiB = 1024, iterations = 0, parallelism = 1)) }
        assertFailsWith<IllegalArgumentException> { codec.seal(payload, CharArray(0), quick) }
        assertTrue(BundleKdf.DEFAULT.isSane)
    }

    @Test
    fun `the default cost is RFC 9106's 64 MiB, and a seal and an open at it finish in the time a sheet can show`() {
        assertEquals(BundleKdf(memoryKiB = 65_536, iterations = 3, parallelism = 4), BundleKdf.DEFAULT)
        val started = System.nanoTime()
        val blob = codec.seal(payload, "correct horse battery staple".toCharArray())
        val sealedAt = System.nanoTime()
        assertContentEquals(payload, codec.open(blob, "correct horse battery staple".toCharArray()))
        val openedAt = System.nanoTime()
        val sealMs = (sealedAt - started) / 1_000_000
        val openMs = (openedAt - sealedAt) / 1_000_000
        println("bundle kdf: seal ${sealMs} ms, open ${openMs} ms at ${BundleKdf.DEFAULT}")
        // A generous bound for a loaded CI machine; the point is that it is seconds, not minutes.
        assertTrue(sealMs < 60_000 && openMs < 60_000, "seal $sealMs ms, open $openMs ms")
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}
