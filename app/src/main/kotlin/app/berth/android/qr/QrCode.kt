package app.berth.android.qr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A QR code (ISO/IEC 18004) of some bytes in byte mode at the smallest version that holds them:
 * [size] modules a side, [get] true where a module is dark. Written here rather than pulled in,
 * since the one thing the app encodes is a public key line (spec C12, Show QR), and a library for
 * that is more surface than this. The layout follows the standard's order: function patterns,
 * data and error correction interleaved by block, the mask that scores lowest of the eight, and
 * the format and version words each with their BCH remainder.
 */
class QrCode private constructor(
    val version: Int,
    val ecc: Ecc,
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {
    /** Modules a side: 21 at version 1, four more each version. */
    val size: Int get() = version * 4 + 17

    /** Whether the module at column [x], row [y] is dark; false outside the code, as the quiet zone is. */
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    /** Error correction level, with the two bits the format word carries for it. */
    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        /** [text] as UTF-8 at [ecc], or null when it does not fit version 40 at that level. */
        fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): QrCode? = encode(text.toByteArray(Charsets.UTF_8), ecc)

        /** [text] at the strongest level of [preferred] and the ones below it that fits, LOW last; null only past version 40 at LOW. */
        fun encodeFitting(text: String, preferred: Ecc = Ecc.MEDIUM): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val order = listOf(Ecc.HIGH, Ecc.QUARTILE, Ecc.MEDIUM, Ecc.LOW)
            for (level in order.drop(order.indexOf(preferred))) encode(data, level)?.let { return it }
            return null
        }

        fun encode(data: ByteArray, ecc: Ecc): QrCode? = encode(data, ecc, null)

        /** [forcedMask] fixes the mask instead of scoring the eight; for tests that want each one seen. */
        internal fun encode(data: ByteArray, ecc: Ecc, forcedMask: Int?): QrCode? {
            var version = MIN_VERSION
            while (true) {
                if (4 + countBits(version) + data.size * 8 <= dataCodewords(version, ecc) * 8) break
                if (version >= MAX_VERSION) return null
                version++
            }
            val capacity = dataCodewords(version, ecc) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            bits.append(0, min(4, capacity - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacity) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return Builder(version, ecc).build(interleave(bits.toBytes(), version, ecc), forcedMask)
        }

        /** Bits of the character count in byte mode: 8 through version 9, 16 after. */
        internal fun countBits(version: Int): Int = if (version <= 9) 8 else 16

        /** Codewords the version has room for in all, function patterns aside. */
        internal fun rawCodewords(version: Int): Int {
            var modules = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                modules -= (25 * align - 10) * align - 55
                if (version >= 7) modules -= 36
            }
            return modules / 8
        }

        /** Codewords left for data at [version] and [ecc]. */
        internal fun dataCodewords(version: Int, ecc: Ecc): Int =
            rawCodewords(version) - ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * ECC_BLOCKS[ecc.ordinal][version]

        /** Centres of the alignment patterns along one axis; none at version 1. */
        internal fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val size = version * 4 + 17
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        /** The 15-bit format word for [ecc] and [mask]: five data bits, their BCH remainder, the standard's xor mask. */
        internal fun formatBits(ecc: Ecc, mask: Int): Int {
            val data = ecc.formatBits shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return (data shl 10 or rem) xor 0x5412
        }

        /** The 18-bit version word, present from version 7. */
        internal fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            return version shl 12 or rem
        }

        /** Whether mask [mask] inverts the module at ([x], [y]). */
        internal fun masked(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
            else -> throw IllegalArgumentException("mask $mask")
        }

        /** Data codewords split into the version's blocks, each with its error correction, the blocks interleaved. */
        internal fun interleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val blocks = ECC_BLOCKS[ecc.ordinal][version]
            val eccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
            val raw = rawCodewords(version)
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = ReedSolomon.divisor(eccLen)
            val rows = ArrayList<ByteArray>(blocks)
            var k = 0
            for (i in 0 until blocks) {
                val dataLen = shortLen - eccLen + (if (i < shortBlocks) 0 else 1)
                val block = data.copyOfRange(k, k + dataLen)
                k += dataLen
                val remainder = ReedSolomon.remainder(block, divisor)
                val row = block.copyOf(shortLen + 1)
                remainder.copyInto(row, row.size - eccLen)
                rows += row
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0 until shortLen + 1) {
                for (j in 0 until blocks) {
                    // The short blocks have no codeword at the long blocks' last data position.
                    if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = rows[j][i]
                }
            }
            return out
        }

        // Rows by level in Ecc's order (LOW, MEDIUM, QUARTILE, HIGH); index 0 is unused padding.
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val ECC_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }

    /** Lays one code out: function patterns first, then the codewords, then the mask that scores lowest. */
    private class Builder(val version: Int, val ecc: Ecc) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun build(codewords: ByteArray, forcedMask: Int?): QrCode {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var best = forcedMask ?: 0
            var bestPenalty = Int.MAX_VALUE
            if (forcedMask == null) {
                for (mask in 0..7) {
                    applyMask(mask)
                    drawFormatBits(mask)
                    val penalty = penalty()
                    if (penalty < bestPenalty) {
                        best = mask
                        bestPenalty = penalty
                    }
                    applyMask(mask)
                }
            }
            applyMask(best)
            drawFormatBits(best)
            return QrCode(version, ecc, best, modules)
        }

        private fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val align = alignmentPositions(version)
            val n = align.size
            for (i in 0 until n) {
                for (j in 0 until n) {
                    // The three corners the finders take have no alignment pattern.
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                    drawAlignment(align[i], align[j])
                }
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = max(abs(dx), abs(dy))
                        setFunction(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) setFunction(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)
        }

        private fun drawFormatBits(mask: Int) {
            val bits = formatBits(ecc, mask)
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) setFunction(8, i, bit(i))
            setFunction(8, 7, bit(6))
            setFunction(8, 8, bit(7))
            setFunction(7, 8, bit(8))
            for (i in 9..14) setFunction(14 - i, 8, bit(i))
            for (i in 0..7) setFunction(size - 1 - i, 8, bit(i))
            for (i in 8..14) setFunction(8, size - 15 + i, bit(i))
            setFunction(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, bit)
                setFunction(b, a, bit)
            }
        }

        /** The codewords in the standard's zigzag: two columns at a time from the right, up then down, over the function patterns. */
        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = (right + 1) and 2 == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) if (!isFunction[y][x] && masked(mask, x, y)) modules[y][x] = !modules[y][x]
        }

        /** The standard's four penalties: runs of one colour, 2×2 blocks, finder-like runs, and imbalance of dark and light. */
        private fun penalty(): Int {
            var result = 0
            for (y in 0 until size) {
                var runColor = false
                var runX = 0
                val history = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runX++
                        if (runX == 5) result += N1 else if (runX > 5) result++
                    } else {
                        addHistory(runX, history)
                        if (!runColor) result += countFinderPatterns(history) * N3
                        runColor = modules[y][x]
                        runX = 1
                    }
                }
                result += terminateAndCount(runColor, runX, history) * N3
            }
            for (x in 0 until size) {
                var runColor = false
                var runY = 0
                val history = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runY++
                        if (runY == 5) result += N1 else if (runY > 5) result++
                    } else {
                        addHistory(runY, history)
                        if (!runColor) result += countFinderPatterns(history) * N3
                        runColor = modules[y][x]
                        runY = 1
                    }
                }
                result += terminateAndCount(runColor, runY, history) * N3
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += N2
                }
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * N4
            return result
        }

        private fun countFinderPatterns(history: IntArray): Int {
            val n = history[1]
            val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
            return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) + (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
        }

        private fun terminateAndCount(runColor: Boolean, runLength: Int, history: IntArray): Int {
            var length = runLength
            if (runColor) {
                addHistory(length, history)
                length = 0
            }
            length += size
            addHistory(length, history)
            return countFinderPatterns(history)
        }

        private fun addHistory(runLength: Int, history: IntArray) {
            val run = if (history[0] == 0) runLength + size else runLength
            System.arraycopy(history, 0, history, 1, history.size - 1)
            history[0] = run
        }

        private companion object {
            const val N1 = 3
            const val N2 = 3
            const val N3 = 40
            const val N4 = 10
        }
    }

    /** Bits appended most significant first. */
    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1 != 0
        }

        fun toBytes(): ByteArray {
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
            return out
        }
    }

    /** Reed–Solomon over GF(2⁸) with the QR polynomial 0x11D, as the standard's error correction codewords. */
    internal object ReedSolomon {
        fun divisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = (multiply(result[j].toInt() and 0xFF, root) xor (if (j + 1 < degree) result[j + 1].toInt() and 0xFF else 0)).toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        fun remainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }
    }
}
