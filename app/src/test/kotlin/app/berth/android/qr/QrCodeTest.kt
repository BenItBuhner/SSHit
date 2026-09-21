package app.berth.android.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The encoder against the standard's numbers and against a reader written here from the other
 * side: format and version words checked by their BCH remainders, the mask undone, the zigzag
 * walked, the blocks de-interleaved and their error correction checked as syndromes over a GF(2⁸)
 * built from log tables rather than the encoder's multiply, then the byte segment read out.
 */
class QrCodeTest {
    @Test
    fun `the tables agree with the standard`() {
        assertEquals(26, QrCode.rawCodewords(1))
        assertEquals(44, QrCode.rawCodewords(2))
        assertEquals(196, QrCode.rawCodewords(7))
        assertEquals(3706, QrCode.rawCodewords(40))
        assertEquals(19, QrCode.dataCodewords(1, QrCode.Ecc.LOW))
        assertEquals(16, QrCode.dataCodewords(1, QrCode.Ecc.MEDIUM))
        assertEquals(9, QrCode.dataCodewords(1, QrCode.Ecc.HIGH))
        assertEquals(2956, QrCode.dataCodewords(40, QrCode.Ecc.LOW))
        assertEquals(1276, QrCode.dataCodewords(40, QrCode.Ecc.HIGH))
        assertArrayEquals(intArrayOf(), QrCode.alignmentPositions(1))
        assertArrayEquals(intArrayOf(6, 18), QrCode.alignmentPositions(2))
        assertArrayEquals(intArrayOf(6, 22, 38), QrCode.alignmentPositions(7))
        assertArrayEquals(intArrayOf(6, 34, 60, 86, 112, 138), QrCode.alignmentPositions(32))
        assertArrayEquals(intArrayOf(6, 30, 58, 86, 114, 142, 170), QrCode.alignmentPositions(40))
        assertEquals(0x5412, QrCode.formatBits(QrCode.Ecc.MEDIUM, 0))
        assertEquals(0x77C4, QrCode.formatBits(QrCode.Ecc.LOW, 0))
        assertEquals(0x07C94, QrCode.versionBits(7))
        assertEquals(0x28C69, QrCode.versionBits(40))
    }

    @Test
    fun `a short text is a version 1 code that reads back`() {
        val code = QrCode.encode("HELLO WORLD", QrCode.Ecc.MEDIUM)!!
        assertEquals(1, code.version)
        assertEquals(21, code.size)
        assertEquals("HELLO WORLD", String(decode(code), Charsets.UTF_8))
    }

    @Test
    fun `an Ed25519 public key line reads back at every level`() {
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGb0mAn2p7ik+bDVlTzSn6Q4jY1H1+mYB/r7uS5J2sPX ben@pixel"
        for (level in QrCode.Ecc.entries) {
            val code = QrCode.encode(line, level)!!
            assertTrue("version ${code.version} at $level", code.version in 4..10)
            assertEquals(level, code.ecc)
            assertEquals(line, String(decode(code), Charsets.UTF_8))
        }
    }

    @Test
    fun `an RSA 4096 public key line reads back`() {
        val body = base64Of(Random(7).nextBytes(535))
        val line = "ssh-rsa $body ben@pixel"
        val code = QrCode.encode(line, QrCode.Ecc.MEDIUM)!!
        assertTrue(code.version >= 10)
        assertEquals(line, String(decode(code), Charsets.UTF_8))
    }

    @Test
    fun `every version reads back, the blocks of two lengths included`() {
        val random = Random(11)
        for (version in QrCode.MIN_VERSION..QrCode.MAX_VERSION) {
            for (level in listOf(QrCode.Ecc.LOW, QrCode.Ecc.HIGH)) {
                val capacity = QrCode.dataCodewords(version, level) - 1 - (if (version <= 9) 1 else 2)
                val below = if (version > 1) QrCode.dataCodewords(version - 1, level) - 1 - (if (version - 1 <= 9) 1 else 2) else -1
                if (capacity <= below) continue
                val data = random.nextBytes(capacity)
                val code = QrCode.encode(data, level)!!
                assertEquals("version for ${data.size} bytes at $level", version, code.version)
                assertArrayEquals("version $version at $level", data, decode(code))
            }
        }
    }

    @Test
    fun `each of the eight masks reads back`() {
        val text = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGb0mAn2p7ik+bDVlTzSn6Q4jY1H1+mYB/r7uS5J2sPX mask@check"
        for (mask in 0..7) {
            for (version in listOf(QrCode.Ecc.MEDIUM to text, QrCode.Ecc.LOW to text.repeat(8))) {
                val code = QrCode.encode(version.second.toByteArray(), version.first, mask)!!
                assertEquals(mask, code.mask)
                assertEquals("mask $mask at version ${code.version}", version.second, String(decode(code), Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `too long for version 40 is null, and encodeFitting steps the level down`() {
        assertNull(QrCode.encode(ByteArray(2954), QrCode.Ecc.LOW))
        assertEquals(40, QrCode.encode(ByteArray(2953), QrCode.Ecc.LOW)!!.version)
        val big = "x".repeat(1400)
        assertNull(QrCode.encode(big, QrCode.Ecc.HIGH))
        assertEquals(QrCode.Ecc.QUARTILE, QrCode.encodeFitting(big, QrCode.Ecc.HIGH)!!.ecc)
        assertEquals(QrCode.Ecc.MEDIUM, QrCode.encodeFitting("y".repeat(2000), QrCode.Ecc.HIGH)!!.ecc)
        assertEquals(QrCode.Ecc.LOW, QrCode.encodeFitting("z".repeat(2500), QrCode.Ecc.HIGH)!!.ecc)
        assertNull(QrCode.encodeFitting("w".repeat(3000)))
    }

    @Test
    fun `the finder patterns and quiet zone are where a reader looks`() {
        val code = QrCode.encode("berth", QrCode.Ecc.LOW)!!
        val n = code.size
        for ((cx, cy) in listOf(3 to 3, n - 4 to 3, 3 to n - 4)) {
            for (dy in -4..4) for (dx in -4..4) {
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until n || y !in 0 until n) continue
                // Dark centre and outer ring, a light ring between, a light separator around.
                assertEquals("finder at $cx,$cy offset $dx,$dy", ring != 2 && ring != 4, code[x, y])
            }
        }
        assertEquals(false, code[-1, 0])
        assertEquals(false, code[0, n])
        for (i in 8 until n - 8) assertEquals("timing $i", i % 2 == 0, code[6, i])
    }

    // ---- The reader ----------------------------------------------------------------------------------

    private fun decode(code: QrCode): ByteArray {
        val n = code.size
        val version = (n - 17) / 4
        assertEquals(code.version, version)

        // Format word, first copy, checked as a BCH(15,5) word after the standard's xor mask.
        var format = 0
        fun formatBit(i: Int, dark: Boolean) { if (dark) format = format or (1 shl i) }
        for (i in 0..5) formatBit(i, code[8, i])
        formatBit(6, code[8, 7])
        formatBit(7, code[8, 8])
        formatBit(8, code[7, 8])
        for (i in 9..14) formatBit(i, code[14 - i, 8])
        var second = 0
        for (i in 0..7) if (code[n - 1 - i, 8]) second = second or (1 shl i)
        for (i in 8..14) if (code[8, n - 15 + i]) second = second or (1 shl i)
        assertEquals("the two format copies agree", format, second)
        val unmasked = format xor 0x5412
        val formatData = unmasked ushr 10
        var rem = formatData
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        assertEquals("format BCH remainder", rem, unmasked and 0x3FF)
        val ecc = QrCode.Ecc.entries.first { it.formatBits == formatData ushr 3 }
        val mask = formatData and 7
        assertEquals(code.ecc, ecc)
        assertEquals(code.mask, mask)
        assertTrue(code[8, n - 8])

        if (version >= 7) {
            var word = 0
            var mirrored = 0
            for (i in 0 until 18) {
                if (code[n - 11 + i % 3, i / 3]) word = word or (1 shl i)
                if (code[i / 3, n - 11 + i % 3]) mirrored = mirrored or (1 shl i)
            }
            assertEquals(word, mirrored)
            var vrem = word ushr 12
            repeat(12) { vrem = (vrem shl 1) xor ((vrem ushr 11) * 0x1F25) }
            assertEquals("version BCH remainder", vrem, word and 0xFFF)
            assertEquals(version, word ushr 12)
        }

        val function = functionMap(version)
        val raw = ArrayList<Boolean>()
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until n) {
                for (j in 0..1) {
                    val x = right - j
                    val y = if ((right + 1) and 2 == 0) n - 1 - vert else vert
                    if (!function[y][x]) raw += code[x, y] xor maskBit(mask, y, x)
                }
            }
            right -= 2
        }
        val codewords = ByteArray(raw.size / 8)
        for (i in codewords.indices) {
            var b = 0
            for (k in 0 until 8) if (raw[i * 8 + k]) b = b or (0x80 ushr k)
            codewords[i] = b.toByte()
        }
        assertEquals(QrCode.rawCodewords(version), codewords.size)

        val blocks = ECC_BLOCKS[ecc.ordinal][version]
        val eccLen = ECC_PER_BLOCK[ecc.ordinal][version]
        val shortBlocks = blocks - codewords.size % blocks
        val shortLen = codewords.size / blocks
        val rows = Array(blocks) { ByteArray(shortLen + 1) }
        var k = 0
        for (i in 0 until shortLen + 1) for (j in 0 until blocks) if (i != shortLen - eccLen || j >= shortBlocks) rows[j][i] = codewords[k++]
        assertEquals(codewords.size, k)
        val data = ArrayList<Byte>()
        for (j in 0 until blocks) {
            val dataLen = shortLen - eccLen + (if (j < shortBlocks) 0 else 1)
            val row = rows[j]
            val block = row.copyOfRange(0, dataLen) + row.copyOfRange(shortLen + 1 - eccLen, shortLen + 1)
            for (i in 0 until eccLen) assertEquals("block $j syndrome $i", 0, GF.evaluate(block, GF.exp[i]))
            for (i in 0 until dataLen) data += row[i]
        }
        assertEquals(QrCode.dataCodewords(version, ecc), data.size)

        var bit = 0
        fun readBits(count: Int): Int {
            var v = 0
            repeat(count) {
                v = (v shl 1) or ((data[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        assertEquals("byte mode", 0b0100, readBits(4))
        val length = readBits(if (version <= 9) 8 else 16)
        return ByteArray(length) { readBits(8).toByte() }
    }

    /** The standard's eight mask conditions in its own terms, row [i] and column [j] (ISO 18004 table 10). */
    private fun maskBit(pattern: Int, i: Int, j: Int): Boolean = when (pattern) {
        0 -> (i + j) % 2 == 0
        1 -> i % 2 == 0
        2 -> j % 3 == 0
        3 -> (i + j) % 3 == 0
        4 -> (i / 2 + j / 3) % 2 == 0
        5 -> (i * j) % 2 + (i * j) % 3 == 0
        6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
        7 -> ((i + j) % 2 + (i * j) % 3) % 2 == 0
        else -> error("pattern $pattern")
    }

    /** The modules a reader knows are not data, drawn again from the standard rather than copied from the encoder. */
    private fun functionMap(version: Int): Array<BooleanArray> {
        val n = version * 4 + 17
        val map = Array(n) { BooleanArray(n) }
        fun mark(x: Int, y: Int) { if (x in 0 until n && y in 0 until n) map[y][x] = true }
        for (i in 0 until n) { mark(6, i); mark(i, 6) }
        for ((cx, cy) in listOf(3 to 3, n - 4 to 3, 3 to n - 4)) for (dy in -4..4) for (dx in -4..4) mark(cx + dx, cy + dy)
        for (i in 0..8) { mark(8, i); mark(i, 8) }
        for (i in 0..7) { mark(n - 1 - i, 8); mark(8, n - 1 - i) }
        if (version >= 2) {
            val count = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val centres = IntArray(count) { if (it == 0) 6 else n - 7 - (count - 1 - it) * step }
            for (a in centres.indices) for (b in centres.indices) {
                if ((a == 0 && b == 0) || (a == 0 && b == count - 1) || (a == count - 1 && b == 0)) continue
                for (dy in -2..2) for (dx in -2..2) mark(centres[a] + dx, centres[b] + dy)
            }
        }
        if (version >= 7) for (i in 0 until 18) { mark(n - 11 + i % 3, i / 3); mark(i / 3, n - 11 + i % 3) }
        return map
    }

    /** GF(2⁸) with 0x11D from log and antilog tables: the reader's own arithmetic. */
    private object GF {
        val exp = IntArray(512)
        val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        /** The polynomial with coefficients [poly] (highest first) at [x]. */
        fun evaluate(poly: ByteArray, x: Int): Int {
            var y = 0
            for (c in poly) y = mul(y, x) xor (c.toInt() and 0xFF)
            return y
        }
    }

    private fun base64Of(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private companion object {
        val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        val ECC_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }
}
