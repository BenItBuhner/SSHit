package app.berth.ssh

import app.berth.terminal.Attr
import app.berth.terminal.TermColor
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Drives the real transport and the real emulator against a live sshd and renders the screen to
 * PNG frames: the same bytes the Android Canvas renderer draws, painted with java.awt instead.
 * Used when no device or emulator can run. Opt-in through `BERTH_DEMO_OUT` (a directory) plus the
 * `SSH_TEST_*` variables; `BERTH_DEMO_FONT` may point at a TTF, otherwise a system monospace is used.
 */
class TerminalDemoHarness {
    private val out = System.getenv("BERTH_DEMO_OUT").orEmpty()
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val fontPath = System.getenv("BERTH_DEMO_FONT").orEmpty()

    // Roughly what a 6.1" phone shows in portrait at 13sp JetBrains Mono with the Deck open.
    private val cols = 52
    private val rows = 38
    private var frame = 0

    @Test
    fun `record ls, htop and vim through the emulator`() = runBlocking {
        assumeTrue("BERTH_DEMO_OUT not set", out.isNotBlank() && host.isNotBlank())
        val dir = File(out).apply { mkdirs() }
        val recDir = File(dir, "rec").apply { mkdirs() }
        val renderer = FrameRenderer(fontPath)

        var shellRef: ShellChannel? = null
        // Query responses (DA, DSR, OSC 10/11) go straight back to the host, as TerminalSession does.
        val listener = object : TerminalListenerAdapter() {
            override fun onResponse(data: ByteArray) { shellRef?.write(data) }
        }
        val emulator = TerminalEmulator(cols, rows, maxScrollback = 2000, listener = listener)
        val endpoint = SshEndpoint(host, port, user, listOf(SshAuth.Password { password.toCharArray() }), connectTimeoutMillis = 10_000)
        val connection = SshConnection(endpoint, AcceptAllHostKeys)
        connection.connect()
        val shell = connection.openShell(cols, rows, "xterm-256color", mapOf("COLORTERM" to "truecolor", "TERM_PROGRAM" to "berth"), null)
        shellRef = shell
        val pump: Job = launch(Dispatchers.IO) {
            shell.output().collect { bytes -> synchronized(emulator.lock) { emulator.write(bytes) } }
        }
        // Continuous capture at ~8 fps for the recording; named key frames are written separately.
        var recFrame = 0
        val recorder: Job = launch(Dispatchers.IO) {
            while (true) {
                val image = synchronized(emulator.lock) { renderer.render(emulator) }
                ImageIO.write(image, "png", File(recDir, "%04d.png".format(recFrame++)))
                delay(125)
            }
        }
        fun snap(name: String) {
            val image = synchronized(emulator.lock) { renderer.render(emulator) }
            ImageIO.write(image, "png", File(dir, "%02d-%s.png".format(frame++, name)))
        }
        suspend fun settle(ms: Long = 700) = delay(ms)
        // Typed a few characters at a time so the recording reads like a person at the Deck.
        suspend fun type(text: String, perChunkMs: Long = 35) {
            var i = 0
            while (i < text.length) {
                val end = minOf(text.length, i + 3)
                shell.write(text.substring(i, end))
                i = end
                if (perChunkMs > 0) delay(perChunkMs)
            }
        }
        suspend fun typeFast(text: String) = type(text, 0)

        withTimeout(120_000) {
            settle(1500)
            snap("connected")

            typeFast("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear\n")
            settle()
            type("ls --color=always -la /\n")
            settle(1200)
            snap("ls-color")
            type("ls --color=always /etc | head -40\n")
            settle(1200)
            snap("ls-color-columns")

            type("clear\n")
            settle(300)
            typeFast("for i in 0 1 2 3 4 5 6 7; do printf '\\e[4%sm    \\e[0m' \$i; done; echo; for i in 0 1 2 3 4 5 6 7; do printf '\\e[10%sm    \\e[0m' \$i; done; echo; for r in 0 51 102 153 204 255; do for g in 0 51 102 153 204 255; do printf '\\e[48;2;%s;%s;120m ' \$r \$g; done; done; echo; printf '\\e[1mbold\\e[0m \\e[3mitalic\\e[0m \\e[4munderline\\e[0m \\e[9mstrike\\e[0m \\e[7minverse\\e[0m \\e[2mdim\\e[0m\\n'\n")
            settle(1200)
            snap("sgr-attributes")

            type("clear && htop\n")
            settle(3500)
            snap("htop")
            settle(1500)
            snap("htop-2")
            shell.write("q")
            settle(800)

            typeFast("clear && printf 'import os\\n\\n\\ndef greet(name: str) -> str:\\n    \"\"\"Say hello.\"\"\"\\n    return f\"hello, {name}\"\\n\\n\\nif __name__ == \"__main__\":\\n    print(greet(os.environ.get(\"USER\", \"berth\")))\\n' > /tmp/berth-demo.py\n")
            settle(400)
            type("vim -c 'syntax on' -c 'set number' -c 'colorscheme desert' /tmp/berth-demo.py\n")
            settle(2500)
            snap("vim")
            shell.write("jjjjjA")
            settle(300)
            type("  # typed from Berth", 60)
            settle(400)
            shell.write("\u001b")
            settle(400)
            snap("vim-insert")
            type(":q!\n")
            settle(800)
            snap("back-to-shell")
            type("exit\n")
            settle(500)
        }
        recorder.cancel()
        pump.cancel()
        shell.close()
        connection.close()

        val frames = dir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assertTrue(frames.size >= 6, "expected demo frames in $dir")
        assertTrue(recFrame > 40, "expected a recording sequence in $recDir")
        File(dir, "frames.txt").writeText(frames.joinToString("\n") { it.name })
    }
}

/** Paints a [TerminalEmulator] screen cell by cell, mirroring the Android renderer's rules. */
class FrameRenderer(fontPath: String) {
    private val scale = 2
    private val regular: Font
    private val bold: Font
    private val italic: Font
    private val boldItalic: Font
    private val cellW: Int
    private val cellH: Int
    private val ascent: Int
    private val pad = 12 * scale

    init {
        val base = if (fontPath.isNotBlank() && File(fontPath).exists()) {
            Font.createFont(Font.TRUETYPE_FONT, File(fontPath))
        } else {
            Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        val size = 13f * scale
        regular = base.deriveFont(Font.PLAIN, size)
        val boldFile = File(fontPath.replace("_regular", "_bold"))
        bold = if (fontPath.isNotBlank() && boldFile.exists()) Font.createFont(Font.TRUETYPE_FONT, boldFile).deriveFont(size) else base.deriveFont(Font.BOLD, size)
        val italicFile = File(fontPath.replace("_regular", "_italic"))
        italic = if (fontPath.isNotBlank() && italicFile.exists()) Font.createFont(Font.TRUETYPE_FONT, italicFile).deriveFont(size) else base.deriveFont(Font.ITALIC, size)
        val biFile = File(fontPath.replace("_regular", "_bold_italic"))
        boldItalic = if (fontPath.isNotBlank() && biFile.exists()) Font.createFont(Font.TRUETYPE_FONT, biFile).deriveFont(size) else base.deriveFont(Font.BOLD or Font.ITALIC, size)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        probe.font = regular
        val fm = probe.fontMetrics
        cellW = fm.charWidth('M')
        cellH = Math.round(size * 1.2f)
        ascent = (cellH - (fm.ascent + fm.descent)) / 2 + fm.ascent
        probe.dispose()
    }

    fun render(em: TerminalEmulator): BufferedImage {
        val bg = em.defaultBackgroundRgb
        val fg = em.defaultForegroundRgb
        val width = em.cols * cellW + pad * 2
        val height = em.rows * cellH + pad * 2
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color(bg)
        g.fillRect(0, 0, width, height)
        for (y in 0 until em.rows) {
            val line = em.viewLine(y, 0)
            val top = pad + y * cellH
            for (x in 0 until minOf(em.cols, line.cols)) {
                val attrs = line.attrs[x]
                if (attrs and Attr.WIDE_TAIL != 0) continue
                val inverse = attrs and Attr.INVERSE != 0
                var cellFg = resolve(line.fg[x], true, attrs, em.palette, fg, bg)
                var cellBg = resolve(line.bg[x], false, attrs, em.palette, fg, bg)
                if (inverse) { val t = cellFg; cellFg = cellBg; cellBg = t }
                if (attrs and Attr.DIM != 0) cellFg = mix(cellFg, cellBg, 0.4f)
                val left = pad + x * cellW
                val w = if (attrs and Attr.WIDE != 0) cellW * 2 else cellW
                if (cellBg != bg) {
                    g.color = Color(cellBg)
                    g.fillRect(left, top, w, cellH)
                }
                val cp = line.chars[x]
                if (cp != 0 && cp != 0x20 && attrs and Attr.INVISIBLE == 0) {
                    g.font = when {
                        attrs and Attr.BOLD != 0 && attrs and Attr.ITALIC != 0 -> boldItalic
                        attrs and Attr.BOLD != 0 -> bold
                        attrs and Attr.ITALIC != 0 -> italic
                        else -> regular
                    }
                    g.color = Color(cellFg)
                    g.drawString(line.cellText(x), left, top + ascent)
                }
                if (attrs and Attr.UNDERLINE != 0) {
                    g.color = Color(cellFg)
                    g.fillRect(left, top + ascent + 2 * scale, w, scale)
                }
                if (attrs and Attr.STRIKETHROUGH != 0) {
                    g.color = Color(cellFg)
                    g.fillRect(left, top + cellH * 55 / 100, w, scale)
                }
            }
        }
        if (em.cursorVisible && em.cursorY in 0 until em.rows) {
            val left = pad + em.cursorX * cellW
            val top = pad + em.cursorY * cellH
            g.color = Color(0xE0A458)
            g.fillRect(left, top, cellW, cellH)
            val line = em.line(em.cursorY)
            if (em.cursorX < line.cols && line.chars[em.cursorX] != 0) {
                g.color = Color(0x1A1408)
                g.font = regular
                g.drawString(line.cellText(em.cursorX), left, top + ascent)
            }
        }
        g.dispose()
        return image
    }

    private fun resolve(color: Int, isFg: Boolean, attrs: Int, palette: IntArray, fg: Int, bg: Int): Int = when (TermColor.kind(color)) {
        TermColor.KIND_INDEXED -> {
            var i = TermColor.index(color)
            if (isFg && attrs and Attr.BOLD != 0 && i < 8) i += 8
            palette[i]
        }
        TermColor.KIND_RGB -> TermColor.rgbValue(color)
        else -> if (isFg) fg else bg
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
