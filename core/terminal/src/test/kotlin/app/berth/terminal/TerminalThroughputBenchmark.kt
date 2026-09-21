package app.berth.terminal

import org.junit.Assume.assumeTrue
import java.util.Random
import kotlin.test.Test

/**
 * Throughput of [TerminalEmulator.write] over the kinds of output a session produces, as megabytes
 * of host bytes a second. Opt-in: set BERTH_BENCH=1 (the module's test task forwards it) and a
 * plain `./gradlew test` skips it. Every workload is a fixed, seeded payload fed through a fresh
 * 80 x 24 emulator in 16 KB reads, the size of a network read; a pass over every workload and then
 * two more of its own to warm the JIT, then the median of seven timed passes, so a run before a
 * change compares with one after it on the same machine.
 *
 *   BERTH_BENCH=1 ./gradlew :core:terminal:test --tests 'app.berth.terminal.TerminalThroughputBenchmark' --rerun
 */
class TerminalThroughputBenchmark {
    private class Workload(val name: String, val payload: ByteArray, val scrollback: Int = 10_000, val prefill: ByteArray? = null)

    @Test
    fun `write throughput by workload`() {
        assumeTrue("set BERTH_BENCH=1 to run the throughput benchmark", System.getenv("BERTH_BENCH") == "1")
        val workloads = listOf(
            Workload("plain-text", plainText(LINES)),
            Workload("colored-ls", coloredListing(LINES)),
            Workload("scrollback-churn", plainText(LINES), scrollback = 10_000, prefill = plainText(10_100)),
            Workload("full-screen-redraw", fullScreenRedraw(frames = 2_000)),
            Workload("wide-and-combining", wideAndCombining(LINES)),
            Workload("osc8-links", hyperlinks(LINES)),
        )
        // One pass over everything first, so the JIT has seen every path before the first workload is timed.
        for (w in workloads) runOnce(w)
        println(String.format("%-20s %12s %12s %10s %10s", "workload", "bytes", "median MB/s", "min", "max"))
        for (w in workloads) {
            val passes = DoubleArray(TIMED) { 0.0 }
            repeat(WARMUP) { runOnce(w) }
            for (i in 0 until TIMED) passes[i] = runOnce(w)
            passes.sort()
            val median = passes[TIMED / 2]
            println(String.format("%-20s %,12d %12.1f %10.1f %10.1f", w.name, w.payload.size, median, passes.first(), passes.last()))
            println(String.format("BENCH %s bytes=%d median_mbps=%.2f min_mbps=%.2f max_mbps=%.2f", w.name, w.payload.size, median, passes.first(), passes.last()))
        }
    }

    /** Feeds one workload through a fresh emulator and returns the throughput in MB/s. */
    private fun runOnce(w: Workload): Double {
        val term = TerminalEmulator(COLS, ROWS, w.scrollback, TerminalListenerAdapter())
        w.prefill?.let { feed(term, it) }
        val started = System.nanoTime()
        feed(term, w.payload)
        val seconds = (System.nanoTime() - started) / 1e9
        return w.payload.size / seconds / 1e6
    }

    private fun feed(term: TerminalEmulator, bytes: ByteArray) {
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(READ, bytes.size - pos)
            term.write(bytes, pos, n)
            pos += n
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Payloads. Each is deterministic (seeded) so every pass, before and after a change, sees the same bytes.

    private fun plainText(lines: Int): ByteArray {
        val random = Random(1)
        val sb = StringBuilder(lines * (COLS + 1))
        repeat(lines) {
            for (x in 0 until COLS - 1) sb.append(PLAIN[random.nextInt(PLAIN.length)])
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** `ls --color` style: bold and 16-color runs, 256-color and truecolor runs, resets between entries. */
    private fun coloredListing(lines: Int): ByteArray {
        val random = Random(2)
        val sb = StringBuilder()
        repeat(lines) {
            repeat(6) { entry ->
                when (entry % 3) {
                    0 -> sb.append("\u001b[01;3").append(random.nextInt(8)).append('m')
                    1 -> sb.append("\u001b[38;5;").append(random.nextInt(256)).append('m')
                    else -> sb.append("\u001b[38;2;").append(random.nextInt(256)).append(';').append(random.nextInt(256)).append(';').append(random.nextInt(256)).append('m')
                }
                repeat(10) { sb.append(PLAIN[random.nextInt(PLAIN.length)]) }
                sb.append("\u001b[0m  ")
            }
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** A TUI repainting every row of the screen: hide cursor, home, per-row address, clear and write, show cursor. */
    private fun fullScreenRedraw(frames: Int): ByteArray {
        val random = Random(3)
        val sb = StringBuilder()
        repeat(frames) {
            sb.append("\u001b[?25l\u001b[H")
            for (row in 1..ROWS) {
                sb.append("\u001b[").append(row).append(";1H\u001b[K")
                sb.append(if (row % 2 == 0) "\u001b[7m" else "\u001b[0;1m")
                repeat(COLS - 1) { sb.append(PLAIN[random.nextInt(PLAIN.length)]) }
                sb.append("\u001b[0m")
            }
            sb.append("\u001b[").append(1 + random.nextInt(ROWS)).append(';').append(1 + random.nextInt(COLS)).append("H\u001b[?25h")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** CJK, emoji and combining marks: three and four byte UTF-8 with double-width cells. */
    private fun wideAndCombining(lines: Int): ByteArray {
        val random = Random(4)
        val sb = StringBuilder()
        repeat(lines) {
            var width = 0
            while (width < COLS - 2) {
                when (random.nextInt(4)) {
                    0 -> { sb.appendCodePoint(0x4E00 + random.nextInt(0x1000)); width += 2 }
                    1 -> { sb.appendCodePoint(0x1F600 + random.nextInt(0x40)); width += 2 }
                    2 -> { sb.append('e').appendCodePoint(0x0301); width += 1 }
                    else -> { sb.append(PLAIN[random.nextInt(PLAIN.length)]); width += 1 }
                }
            }
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** `ls --hyperlink` style: OSC 8 open with a distinct URL, the entry name, OSC 8 close; several per line. */
    private fun hyperlinks(lines: Int): ByteArray {
        val random = Random(5)
        val sb = StringBuilder()
        var n = 0
        repeat(lines) {
            repeat(4) {
                sb.append("\u001b]8;;file:///home/demo/projects/berth/file-").append(n++).append(".kt\u001b\\")
                repeat(12) { sb.append(PLAIN[random.nextInt(PLAIN.length)]) }
                sb.append("\u001b]8;;\u001b\\  ")
            }
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val COLS = 80
        const val ROWS = 24
        const val LINES = 20_000
        const val READ = 16 * 1024
        const val WARMUP = 2
        const val TIMED = 7
        const val PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ./-_="
    }
}
