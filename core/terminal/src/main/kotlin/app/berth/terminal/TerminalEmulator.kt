package app.berth.terminal

import java.util.Base64

enum class MouseTracking { NONE, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }

/**
 * An xterm-compatible VT emulator: UTF-8 input, 16/256/truecolor SGR, alternate screen,
 * scroll regions, scrollback, DEC private modes used by modern TUIs, and the query responses
 * (DA, DSR, DECRQSS, OSC 10/11) that editors probe for.
 *
 * All public methods are safe to call from any thread; rendering code should hold [lock] while
 * reading lines so a concurrent [write] cannot tear a frame.
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    maxScrollback: Int = DEFAULT_SCROLLBACK,
    private val listener: TerminalListener,
) : VtHandler {
    val lock = Any()

    var cols: Int = cols.coerceAtLeast(MIN_COLS)
        private set
    var rows: Int = rows.coerceAtLeast(MIN_ROWS)
        private set

    private val decoder = Utf8Decoder()
    private val parser = VtParser(this)

    private val mainBuffer = ScreenBuffer(this.cols, this.rows, maxScrollback)
    private val altBuffer = ScreenBuffer(this.cols, this.rows, 0)
    private var buffer: ScreenBuffer = mainBuffer

    val isAlternateScreen: Boolean get() = buffer === altBuffer
    val scrollbackSize: Int get() = buffer.scrollbackSize

    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set
    private var pendingWrap = false

    private var fg = TermColor.COLOR_DEFAULT
    private var bg = TermColor.COLOR_DEFAULT
    private var attrs = 0

    var autoWrap = true
        private set
    var originMode = false
        private set
    var insertMode = false
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var lineFeedNewLine = false
        private set
    var reverseVideo = false
        private set
    var mouseTracking = MouseTracking.NONE
        private set
    var mouseSgrEncoding = false
        private set
    var focusEvents = false
        private set
    var cursorStyle = CursorStyle.DEFAULT
        private set
    var title = ""
        private set

    private var synchronizedOutput = false
    private var synchronizedSince = 0L
    private val titleStack = ArrayDeque<String>()

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var tabStops = BooleanArray(this.cols) { it % TAB_WIDTH == 0 }

    private var charsetG0 = Charset.ASCII
    private var charsetG1 = Charset.ASCII
    private var shiftedOut = false

    private var lastPrinted = 0

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    /** Palette as 0xRRGGBB, mutable through OSC 4 / 104. */
    val palette: IntArray = Palette.defaultPalette()

    /** Theme colors reported to the host through OSC 10/11 queries. */
    var defaultForegroundRgb: Int = Palette.BERTH_DARK_FOREGROUND
    var defaultBackgroundRgb: Int = Palette.BERTH_DARK_BACKGROUND

    /** Pixel geometry reported to XTWINOPS queries; set by the renderer. */
    var cellWidthPx: Int = 8
    var cellHeightPx: Int = 16

    private var dirty = false

    private enum class Charset { ASCII, DEC_GRAPHICS }

    private class SavedCursor {
        var x = 0
        var y = 0
        var fg = TermColor.COLOR_DEFAULT
        var bg = TermColor.COLOR_DEFAULT
        var attrs = 0
        var originMode = false
        var pendingWrap = false
        var g0 = Charset.ASCII
        var g1 = Charset.ASCII
        var shiftedOut = false
    }

    // ---------------------------------------------------------------------------------------------
    // Input

    /**
     * Feeds host output. Large chunks are parsed in slices of [WRITE_SLICE_BYTES] with [lock]
     * released between them, so a renderer waiting to copy a frame is never held for a whole
     * network read; the decoder and parser keep their state across the slices.
     */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var pos = offset
        val end = offset + length
        do {
            val n = minOf(WRITE_SLICE_BYTES, end - pos)
            synchronized(lock) {
                decoder.decode(bytes, pos, n) { parser.feed(it) }
            }
            pos += n
            flushChanges()
        } while (pos < end)
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun flushChanges() {
        val notify: Boolean
        synchronized(lock) {
            if (!dirty) return
            if (synchronizedOutput && System.nanoTime() - synchronizedSince < SYNC_TIMEOUT_NANOS) return
            dirty = false
            notify = true
        }
        if (notify) listener.onScreenChanged()
    }

    private fun markDirty() {
        dirty = true
    }

    /**
     * Encodes [text] for sending to the host as a paste: newlines become carriage returns and the
     * payload is wrapped in bracketed-paste markers when the application asked for them.
     */
    fun encodePaste(text: String): ByteArray = synchronized(lock) {
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        val payload = if (bracketedPaste) {
            // Strip anything that could terminate the bracket early.
            body.replace("\u001b[201~", "")
        } else {
            body
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bracketedPaste) BRACKET_START + bytes + BRACKET_END else bytes
    }

    fun encodeKey(key: TerminalKey, modifiers: Int = 0): ByteArray = synchronized(lock) {
        KeyEncoder.encode(key, modifiers, applicationCursorKeys, applicationKeypad)
    }

    fun encodeText(codePoint: Int, modifiers: Int = 0): ByteArray = KeyEncoder.encodeText(codePoint, modifiers)

    /**
     * Encodes a mouse event for the application's tracking mode, or returns null when the
     * application did not ask for that kind of event (so the caller can scroll locally instead).
     */
    fun encodeMouse(button: Int, col: Int, row: Int, modifiers: Int = 0, release: Boolean = false, motion: Boolean = false): ByteArray? = synchronized(lock) {
        val wheel = button == MouseButton.WHEEL_UP || button == MouseButton.WHEEL_DOWN
        val wanted = when (mouseTracking) {
            MouseTracking.NONE -> false
            MouseTracking.X10 -> !release && !motion && !wheel
            MouseTracking.NORMAL -> !motion
            MouseTracking.BUTTON_EVENT -> !motion || button != MouseButton.RELEASE
            MouseTracking.ANY_EVENT -> true
        }
        if (!wanted) return null
        if (wheel && release) return null
        MouseEncoder.encode(button, col.coerceIn(0, cols - 1), row.coerceIn(0, rows - 1), modifiers, release, motion, mouseSgrEncoding)
    }

    /** Applies a terminal theme's 16 ANSI colors and default foreground/background. */
    fun applyTheme(ansi: IntArray, foregroundRgb: Int, backgroundRgb: Int) {
        synchronized(lock) {
            for (i in 0 until minOf(16, ansi.size)) palette[i] = ansi[i] and 0xFFFFFF
            defaultForegroundRgb = foregroundRgb and 0xFFFFFF
            defaultBackgroundRgb = backgroundRgb and 0xFFFFFF
            markDirty()
        }
        flushChanges()
    }

    // ---------------------------------------------------------------------------------------------
    // View access (callers hold [lock] while iterating)

    fun viewLine(row: Int, scrollOffset: Int): TerminalLine = buffer.viewLine(row, scrollOffset)

    fun line(row: Int): TerminalLine = buffer.line(row)

    /** Text of the visible screen; used by tests. */
    fun screenText(): List<String> = synchronized(lock) { (0 until rows).map { buffer.line(it).toText() } }

    // ---------------------------------------------------------------------------------------------
    // Resize

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(MIN_COLS)
        val r = newRows.coerceAtLeast(MIN_ROWS)
        synchronized(lock) {
            if (c == cols && r == rows) return
            if (isAlternateScreen) {
                val saved = savedMain
                val (sx, sy) = mainBuffer.resizeReflow(c, r, saved.x, saved.y, TermColor.COLOR_DEFAULT)
                saved.x = sx
                saved.y = sy
                val shift = altBuffer.resizeNoReflow(c, r, cursorY, TermColor.COLOR_DEFAULT, keepInScrollback = false)
                cursorY = (cursorY - shift).coerceIn(0, r - 1)
                cursorX = cursorX.coerceIn(0, c - 1)
            } else {
                val (nx, ny) = mainBuffer.resizeReflow(c, r, cursorX, cursorY, TermColor.COLOR_DEFAULT)
                cursorX = nx
                cursorY = ny
                altBuffer.resizeNoReflow(c, r, 0, TermColor.COLOR_DEFAULT, keepInScrollback = false)
                savedAlt.x = savedAlt.x.coerceIn(0, c - 1)
                savedAlt.y = savedAlt.y.coerceIn(0, r - 1)
            }
            val oldStops = tabStops
            tabStops = BooleanArray(c) { i -> if (i < oldStops.size) oldStops[i] else i % TAB_WIDTH == 0 }
            cols = c
            rows = r
            scrollTop = 0
            scrollBottom = r - 1
            pendingWrap = false
            markDirty()
        }
        flushChanges()
    }

    // ---------------------------------------------------------------------------------------------
    // VtHandler

    override fun print(codePoint: Int) {
        var cp = codePoint
        val charset = if (shiftedOut) charsetG1 else charsetG0
        if (charset == Charset.DEC_GRAPHICS && cp in 0x60..0x7E) cp = DEC_GRAPHICS[cp - 0x60]

        val width = CharWidth.of(cp)
        if (width == 0) {
            attachCombining(cp)
            return
        }

        if (pendingWrap) {
            if (autoWrap) {
                buffer.line(cursorY).wrapped = true
                cursorX = 0
                lineFeed()
            }
            pendingWrap = false
        }

        if (width == 2 && cursorX == cols - 1) {
            if (!autoWrap) return
            val l = buffer.line(cursorY)
            l.clearCell(cursorX, bg)
            l.wrapped = true
            cursorX = 0
            lineFeed()
        }

        val line = buffer.line(cursorY)
        if (insertMode) {
            val shift = cols - cursorX - width
            if (shift > 0) line.moveCells(cursorX, cursorX + width, shift)
            line.clearRange(cursorX, cursorX + width, bg)
            repairWideAtEdge(line)
        }
        repairOverwrite(line, cursorX)
        if (width == 2) repairOverwrite(line, cursorX + 1)

        line.set(cursorX, cp, fg, bg, attrs or if (width == 2) Attr.WIDE else 0)
        if (width == 2) line.set(cursorX + 1, 0, fg, bg, attrs or Attr.WIDE_TAIL)
        lastPrinted = cp

        cursorX += width
        if (cursorX >= cols) {
            cursorX = cols - 1
            pendingWrap = autoWrap
        }
        markDirty()
    }

    private fun attachCombining(cp: Int) {
        if (!CharWidth.isCombining(cp) && cp !in 0x200C..0x200D && cp !in 0xFE00..0xFE0F) return
        val line = buffer.line(cursorY)
        var x = if (pendingWrap) cursorX else cursorX - 1
        if (x < 0) return
        if (line.attrs[x] and Attr.WIDE_TAIL != 0) x--
        if (x < 0 || line.chars[x] == 0) return
        line.addCombining(x, cp)
        markDirty()
    }

    /** Clears the orphaned half of a wide character when [x] is about to be overwritten. */
    private fun repairOverwrite(line: TerminalLine, x: Int) {
        if (x >= cols) return
        val a = line.attrs[x]
        if (a and Attr.WIDE != 0 && x + 1 < cols) line.clearCell(x + 1, line.bg[x + 1])
        if (a and Attr.WIDE_TAIL != 0 && x - 1 >= 0) line.clearCell(x - 1, line.bg[x - 1])
    }

    private fun repairWideAtEdge(line: TerminalLine) {
        if (cols > 0 && line.attrs[cols - 1] and Attr.WIDE != 0) line.clearCell(cols - 1, line.bg[cols - 1])
    }

    override fun execute(control: Int) {
        when (control) {
            0x07 -> listener.onBell()
            0x08 -> { if (cursorX > 0) cursorX--; pendingWrap = false; markDirty() }
            0x09 -> { tabForward(1); markDirty() }
            0x0A, 0x0B, 0x0C -> { lineFeed(); if (lineFeedNewLine) cursorX = 0; pendingWrap = false; markDirty() }
            0x0D -> { cursorX = 0; pendingWrap = false; markDirty() }
            0x0E -> shiftedOut = true
            0x0F -> shiftedOut = false
            else -> Unit
        }
    }

    override fun escDispatch(intermediates: String, final: Char) {
        if (intermediates.isEmpty()) {
            when (final) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> { lineFeed(); pendingWrap = false }
                'E' -> { cursorX = 0; lineFeed(); pendingWrap = false }
                'H' -> tabStops[cursorX] = true
                'M' -> { reverseIndex(); pendingWrap = false }
                'c' -> fullReset()
                '=' -> applicationKeypad = true
                '>' -> applicationKeypad = false
                'N', 'O' -> Unit // SS2/SS3 single shifts: G2/G3 are never designated here.
                '\\' -> Unit // ST, terminator of a string already dispatched.
                else -> Unit
            }
            markDirty()
            return
        }
        when (intermediates[0]) {
            '(' -> charsetG0 = charsetFor(final)
            ')' -> charsetG1 = charsetFor(final)
            '#' -> if (final == '8') screenAlignment()
            else -> Unit
        }
        markDirty()
    }

    private fun charsetFor(designator: Char): Charset = when (designator) {
        '0' -> Charset.DEC_GRAPHICS
        else -> Charset.ASCII
    }

    private fun screenAlignment() {
        for (y in 0 until rows) {
            val l = buffer.line(y)
            for (x in 0 until cols) l.set(x, 'E'.code, TermColor.COLOR_DEFAULT, TermColor.COLOR_DEFAULT, 0)
        }
        scrollTop = 0
        scrollBottom = rows - 1
        cursorX = 0
        cursorY = 0
        pendingWrap = false
    }

    override fun csiDispatch(params: CsiParams, intermediates: String, final: Char) {
        when (intermediates) {
            "" -> csiPlain(params, final)
            "?" -> csiPrivate(params, final)
            ">" -> csiGreater(params, final)
            "!" -> if (final == 'p') softReset()
            " " -> if (final == 'q') { cursorStyle = CursorStyle.fromDecscusr(params.zeroBased(0)) }
            "$" -> if (final == 'p') reportMode(params.zeroBased(0), private = false)
            "?$" -> if (final == 'p') reportMode(params.zeroBased(0), private = true)
            "=" -> Unit
            "\"" -> Unit // DECSCA, DECSCL: not implemented
            else -> Unit
        }
        markDirty()
    }

    private fun csiPlain(p: CsiParams, final: Char) {
        when (final) {
            '@' -> insertChars(p.oneBased(0))
            'A' -> moveCursor(0, -p.oneBased(0))
            'B' -> moveCursor(0, p.oneBased(0))
            'C' -> moveCursor(p.oneBased(0), 0)
            'D' -> moveCursor(-p.oneBased(0), 0)
            'E' -> { moveCursor(0, p.oneBased(0)); cursorX = 0 }
            'F' -> { moveCursor(0, -p.oneBased(0)); cursorX = 0 }
            'G', '`' -> setCursorColumn(p.oneBased(0) - 1)
            'H', 'f' -> setCursorPosition(p.oneBased(1) - 1, p.oneBased(0) - 1)
            'I' -> tabForward(p.oneBased(0))
            'J' -> eraseInDisplay(p.zeroBased(0))
            'K' -> eraseInLine(p.zeroBased(0))
            'L' -> insertLines(p.oneBased(0))
            'M' -> deleteLines(p.oneBased(0))
            'P' -> deleteChars(p.oneBased(0))
            'S' -> buffer.scrollUp(scrollTop, scrollBottom, p.oneBased(0), bg, keepInScrollback = false)
            'T' -> if (p.size <= 1) buffer.scrollDown(scrollTop, scrollBottom, p.oneBased(0), bg)
            'X' -> eraseChars(p.oneBased(0))
            'Z' -> tabBackward(p.oneBased(0))
            'a' -> moveCursor(p.oneBased(0), 0)
            'b' -> repeatLast(p.oneBased(0))
            'c' -> respond(PRIMARY_DA)
            'd' -> setCursorRow(p.oneBased(0) - 1)
            'e' -> moveCursor(0, p.oneBased(0))
            'g' -> when (p.zeroBased(0)) {
                0 -> tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h' -> setAnsiModes(p, true)
            'l' -> setAnsiModes(p, false)
            'm' -> applySgr(p)
            'n' -> deviceStatus(p.zeroBased(0), private = false)
            'r' -> setScrollRegion(p)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> windowOps(p)
            else -> Unit
        }
    }

    private fun csiPrivate(p: CsiParams, final: Char) {
        when (final) {
            'h' -> for (i in 0 until p.size.coerceAtLeast(1)) setDecMode(p.zeroBased(i), true)
            'l' -> for (i in 0 until p.size.coerceAtLeast(1)) setDecMode(p.zeroBased(i), false)
            'J' -> eraseInDisplay(p.zeroBased(0))
            'K' -> eraseInLine(p.zeroBased(0))
            'n' -> deviceStatus(p.zeroBased(0), private = true)
            else -> Unit
        }
    }

    private fun csiGreater(p: CsiParams, final: Char) {
        when (final) {
            'c' -> respond(SECONDARY_DA)
            'q' -> respond("\u001bP>|${TERMINAL_NAME}(${TERMINAL_VERSION})\u001b\\")
            'm' -> Unit // XTMODKEYS: modifyOtherKeys is not implemented; keys are sent in legacy form.
            'n', 'p', 't' -> Unit
            else -> Unit
        }
    }

    override fun oscDispatch(payload: String) {
        val sep = payload.indexOf(';')
        val code = (if (sep < 0) payload else payload.substring(0, sep)).toIntOrNull() ?: return
        val arg = if (sep < 0) "" else payload.substring(sep + 1)
        when (code) {
            0, 2 -> setTitle(arg)
            1 -> Unit
            4 -> oscPalette(arg)
            7 -> listener.onWorkingDirectoryChanged(arg)
            8 -> Unit // Hyperlinks: the URL is accepted; per-cell link ids are not stored yet.
            9 -> oscNotification9(arg)
            99 -> oscNotification99(arg)
            133 -> if (arg.isNotEmpty()) listener.onShellIntegration(arg[0], arg.substringAfter(';', ""))
            777 -> oscNotification777(arg)
            10 -> if (arg == "?") respond(colorReport(10, defaultForegroundRgb)) else parseColorSpec(arg)?.let { defaultForegroundRgb = it }
            11 -> if (arg == "?") respond(colorReport(11, defaultBackgroundRgb)) else parseColorSpec(arg)?.let { defaultBackgroundRgb = it }
            12 -> Unit // cursor color: rendered from theme
            52 -> oscClipboard(arg)
            104 -> {
                if (arg.isEmpty()) Palette.defaultPalette().copyInto(palette)
                else arg.split(';').mapNotNull { it.toIntOrNull() }.forEach { i -> if (i in 0..255) palette[i] = Palette.defaultColor(i) }
            }
            110 -> defaultForegroundRgb = Palette.BERTH_DARK_FOREGROUND
            111 -> defaultBackgroundRgb = Palette.BERTH_DARK_BACKGROUND
            else -> Unit
        }
        markDirty()
    }

    /** OSC 9 in its iTerm2 form: `9;message`. ConEmu progress reports (`9;4;...`) are ignored. */
    private fun oscNotification9(arg: String) {
        if (arg.startsWith("4;")) return
        if (arg.isNotBlank()) listener.onNotification("", arg)
    }

    /** OSC 99 (kitty): `99;i=1:d=0;body` with optional `p=title` / `p=body` parts. Only the simple form is kept. */
    private fun oscNotification99(arg: String) {
        val sep = arg.indexOf(';')
        if (sep < 0) return
        val meta = arg.substring(0, sep)
        val payload = arg.substring(sep + 1)
        val isTitle = meta.split(':').any { it == "p=title" }
        if (payload.isNotBlank()) {
            if (isTitle) listener.onNotification(payload, "") else listener.onNotification("", payload)
        }
    }

    /** OSC 777 (rxvt-unicode / VTE): `777;notify;title;body`. */
    private fun oscNotification777(arg: String) {
        val parts = arg.split(';', limit = 3)
        if (parts.size >= 2 && parts[0] == "notify") {
            listener.onNotification(parts[1], parts.getOrElse(2) { "" })
        }
    }

    override fun dcsDispatch(params: CsiParams, intermediates: String, final: Char, payload: String) {
        if (intermediates == "$" && final == 'q') {
            // DECRQSS: report the setting whose final characters are in the payload.
            val reply = when (payload) {
                " q" -> "${decscusrValue()} q"
                "r" -> "${scrollTop + 1};${scrollBottom + 1}r"
                "m" -> "${sgrString()}m"
                else -> null
            }
            respond(if (reply != null) "\u001bP1\$r$reply\u001b\\" else "\u001bP0\$r\u001b\\")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cursor movement

    private fun lineFeed() {
        if (cursorY == scrollBottom) {
            buffer.scrollUp(scrollTop, scrollBottom, 1, bg, keepInScrollback = !isAlternateScreen && scrollTop == 0)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
        markDirty()
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) {
            buffer.scrollDown(scrollTop, scrollBottom, 1, bg)
        } else if (cursorY > 0) {
            cursorY--
        }
        markDirty()
    }

    private fun moveCursor(dx: Int, dy: Int) {
        pendingWrap = false
        if (dy != 0) {
            // Vertical movement is confined to the scroll region when the cursor starts inside it.
            val top = if (cursorY >= scrollTop) scrollTop else 0
            val bottom = if (cursorY <= scrollBottom) scrollBottom else rows - 1
            cursorY = (cursorY + dy).coerceIn(top, bottom)
        }
        if (dx != 0) cursorX = (cursorX + dx).coerceIn(0, cols - 1)
    }

    private fun setCursorColumn(x: Int) {
        pendingWrap = false
        cursorX = x.coerceIn(0, cols - 1)
    }

    private fun setCursorRow(y: Int) {
        pendingWrap = false
        cursorY = if (originMode) (scrollTop + y).coerceIn(scrollTop, scrollBottom) else y.coerceIn(0, rows - 1)
    }

    private fun setCursorPosition(x: Int, y: Int) {
        pendingWrap = false
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = if (originMode) (scrollTop + y).coerceIn(scrollTop, scrollBottom) else y.coerceIn(0, rows - 1)
    }

    private fun tabForward(count: Int) {
        pendingWrap = false
        repeat(count) {
            if (cursorX >= cols - 1) return
            do { cursorX++ } while (cursorX < cols - 1 && !tabStops[cursorX])
        }
    }

    private fun tabBackward(count: Int) {
        pendingWrap = false
        repeat(count) {
            if (cursorX <= 0) return
            do { cursorX-- } while (cursorX > 0 && !tabStops[cursorX])
        }
    }

    private fun setScrollRegion(p: CsiParams) {
        val top = p.oneBased(0) - 1
        val bottom = p.oneBased(1, rows) - 1
        if (top < 0 || bottom >= rows || top >= bottom) {
            if (p.size == 0 || (p[0] <= 0 && p[1] <= 0)) {
                scrollTop = 0
                scrollBottom = rows - 1
                setCursorPosition(0, 0)
            }
            return
        }
        scrollTop = top
        scrollBottom = bottom
        setCursorPosition(0, 0)
    }

    private fun saveCursor() {
        val s = if (isAlternateScreen) savedAlt else savedMain
        s.x = cursorX
        s.y = cursorY
        s.fg = fg
        s.bg = bg
        s.attrs = attrs
        s.originMode = originMode
        s.pendingWrap = pendingWrap
        s.g0 = charsetG0
        s.g1 = charsetG1
        s.shiftedOut = shiftedOut
    }

    private fun restoreCursor() {
        val s = if (isAlternateScreen) savedAlt else savedMain
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        fg = s.fg
        bg = s.bg
        attrs = s.attrs
        originMode = s.originMode
        pendingWrap = s.pendingWrap
        charsetG0 = s.g0
        charsetG1 = s.g1
        shiftedOut = s.shiftedOut
    }

    // ---------------------------------------------------------------------------------------------
    // Editing

    private fun eraseInDisplay(mode: Int) {
        pendingWrap = false
        when (mode) {
            0 -> {
                buffer.line(cursorY).clearRange(cursorX, cols, bg)
                for (y in cursorY + 1 until rows) buffer.line(y).clear(bg)
            }
            1 -> {
                for (y in 0 until cursorY) buffer.line(y).clear(bg)
                buffer.line(cursorY).clearRange(0, cursorX + 1, bg)
            }
            2 -> buffer.clearAll(bg)
            3 -> buffer.clearScrollback()
        }
    }

    private fun eraseInLine(mode: Int) {
        pendingWrap = false
        val line = buffer.line(cursorY)
        when (mode) {
            0 -> line.clearRange(cursorX, cols, bg)
            1 -> line.clearRange(0, cursorX + 1, bg)
            2 -> line.clearRange(0, cols, bg)
        }
    }

    private fun insertLines(n: Int) {
        pendingWrap = false
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        buffer.scrollDown(cursorY, scrollBottom, n, bg)
    }

    private fun deleteLines(n: Int) {
        pendingWrap = false
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        buffer.scrollUp(cursorY, scrollBottom, n, bg, keepInScrollback = false)
    }

    private fun insertChars(n: Int) {
        pendingWrap = false
        val line = buffer.line(cursorY)
        val count = n.coerceAtMost(cols - cursorX)
        repairOverwrite(line, cursorX)
        val shift = cols - cursorX - count
        if (shift > 0) line.moveCells(cursorX, cursorX + count, shift)
        line.clearRange(cursorX, cursorX + count, bg)
        repairWideAtEdge(line)
    }

    private fun deleteChars(n: Int) {
        pendingWrap = false
        val line = buffer.line(cursorY)
        val count = n.coerceAtMost(cols - cursorX)
        repairOverwrite(line, cursorX)
        if (cursorX + count < cols) repairOverwrite(line, cursorX + count)
        val shift = cols - cursorX - count
        if (shift > 0) line.moveCells(cursorX + count, cursorX, shift)
        line.clearRange(cols - count, cols, bg)
    }

    private fun eraseChars(n: Int) {
        pendingWrap = false
        val line = buffer.line(cursorY)
        val end = (cursorX + n).coerceAtMost(cols)
        repairOverwrite(line, cursorX)
        if (end < cols) repairOverwrite(line, end - 1)
        line.clearRange(cursorX, end, bg)
    }

    private fun repeatLast(n: Int) {
        if (lastPrinted == 0) return
        val cp = lastPrinted
        repeat(n.coerceAtMost(cols * rows)) { print(cp) }
    }

    // ---------------------------------------------------------------------------------------------
    // Modes

    private fun setAnsiModes(p: CsiParams, enable: Boolean) {
        for (i in 0 until p.size.coerceAtLeast(1)) {
            when (p.zeroBased(i)) {
                4 -> insertMode = enable
                20 -> lineFeedNewLine = enable
            }
        }
    }

    private fun setDecMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = enable
            3 -> { // DECCOLM: column switching is not supported, but the side effects are.
                eraseInDisplay(2)
                setCursorPosition(0, 0)
            }
            5 -> reverseVideo = enable
            6 -> { originMode = enable; setCursorPosition(0, 0) }
            7 -> { autoWrap = enable; pendingWrap = false }
            9 -> mouseTracking = if (enable) MouseTracking.X10 else MouseTracking.NONE
            12 -> cursorStyle = cursorStyle.copy(blinking = enable)
            25 -> cursorVisible = enable
            47, 1047 -> {
                if (enable) switchToAlternate(clear = false) else switchToMain(clearAltFirst = mode == 1047)
            }
            1000 -> mouseTracking = if (enable) MouseTracking.NORMAL else MouseTracking.NONE
            1002 -> mouseTracking = if (enable) MouseTracking.BUTTON_EVENT else MouseTracking.NONE
            1003 -> mouseTracking = if (enable) MouseTracking.ANY_EVENT else MouseTracking.NONE
            1004 -> focusEvents = enable
            1005, 1015 -> Unit // UTF-8 / urxvt mouse encodings: SGR (1006) is the one we support.
            1006 -> mouseSgrEncoding = enable
            1048 -> if (enable) saveCursor() else restoreCursor()
            1049 -> {
                if (enable) {
                    saveCursor()
                    switchToAlternate(clear = true)
                } else {
                    switchToMain(clearAltFirst = false)
                    restoreCursor()
                }
            }
            2004 -> bracketedPaste = enable
            2026 -> {
                synchronizedOutput = enable
                if (enable) synchronizedSince = System.nanoTime()
            }
            else -> Unit
        }
    }

    private fun switchToAlternate(clear: Boolean) {
        if (isAlternateScreen) return
        buffer = altBuffer
        if (clear) altBuffer.clearAll(TermColor.COLOR_DEFAULT)
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    private fun switchToMain(clearAltFirst: Boolean) {
        if (!isAlternateScreen) return
        if (clearAltFirst) altBuffer.clearAll(TermColor.COLOR_DEFAULT)
        buffer = mainBuffer
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    private fun reportMode(mode: Int, private: Boolean) {
        val value = if (private) {
            when (mode) {
                1 -> flag(applicationCursorKeys)
                6 -> flag(originMode)
                7 -> flag(autoWrap)
                12 -> flag(cursorStyle.blinking)
                25 -> flag(cursorVisible)
                47, 1047, 1049 -> flag(isAlternateScreen)
                1000 -> flag(mouseTracking == MouseTracking.NORMAL)
                1002 -> flag(mouseTracking == MouseTracking.BUTTON_EVENT)
                1003 -> flag(mouseTracking == MouseTracking.ANY_EVENT)
                1004 -> flag(focusEvents)
                1006 -> flag(mouseSgrEncoding)
                2004 -> flag(bracketedPaste)
                2026 -> flag(synchronizedOutput)
                else -> 0
            }
        } else {
            when (mode) {
                4 -> flag(insertMode)
                20 -> flag(lineFeedNewLine)
                else -> 0
            }
        }
        respond(if (private) "\u001b[?$mode;$value\$y" else "\u001b[$mode;$value\$y")
    }

    private fun flag(set: Boolean) = if (set) 1 else 2

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autoWrap = true
        applicationCursorKeys = false
        applicationKeypad = false
        scrollTop = 0
        scrollBottom = rows - 1
        fg = TermColor.COLOR_DEFAULT
        bg = TermColor.COLOR_DEFAULT
        attrs = 0
        charsetG0 = Charset.ASCII
        charsetG1 = Charset.ASCII
        shiftedOut = false
        pendingWrap = false
        cursorStyle = CursorStyle.DEFAULT
        savedMain.let { it.x = 0; it.y = 0; it.fg = TermColor.COLOR_DEFAULT; it.bg = TermColor.COLOR_DEFAULT; it.attrs = 0 }
    }

    private fun fullReset() {
        softReset()
        bracketedPaste = false
        mouseTracking = MouseTracking.NONE
        mouseSgrEncoding = false
        focusEvents = false
        lineFeedNewLine = false
        reverseVideo = false
        synchronizedOutput = false
        tabStops = BooleanArray(cols) { it % TAB_WIDTH == 0 }
        if (isAlternateScreen) switchToMain(clearAltFirst = true)
        mainBuffer.clearAll(TermColor.COLOR_DEFAULT)
        altBuffer.clearAll(TermColor.COLOR_DEFAULT)
        Palette.defaultPalette().copyInto(palette)
        cursorX = 0
        cursorY = 0
        title = ""
        listener.onTitleChanged(title)
    }

    /** Resets the emulator as if it were freshly created; used when a session reconnects. */
    fun reset() {
        synchronized(lock) {
            parser.reset()
            decoder.reset()
            fullReset()
            markDirty()
        }
        flushChanges()
    }

    // ---------------------------------------------------------------------------------------------
    // SGR

    private fun applySgr(p: CsiParams) {
        if (p.size == 0) {
            resetSgr()
            return
        }
        var i = 0
        while (i < p.size) {
            val code = p.zeroBased(i)
            when (code) {
                0 -> resetSgr()
                1 -> attrs = attrs or Attr.BOLD
                2 -> attrs = attrs or Attr.DIM
                3 -> attrs = attrs or Attr.ITALIC
                4 -> {
                    // 4:0 clears, 4:1..5 are underline styles that all render as underline here.
                    val style = if (p.subCount(i) > 0) p.sub(i, 0) else 1
                    attrs = if (style == 0) attrs and Attr.UNDERLINE.inv() else attrs or Attr.UNDERLINE
                }
                5, 6 -> attrs = attrs or Attr.BLINK
                7 -> attrs = attrs or Attr.INVERSE
                8 -> attrs = attrs or Attr.INVISIBLE
                9 -> attrs = attrs or Attr.STRIKETHROUGH
                21 -> attrs = attrs or Attr.UNDERLINE
                22 -> attrs = attrs and (Attr.BOLD or Attr.DIM).inv()
                23 -> attrs = attrs and Attr.ITALIC.inv()
                24 -> attrs = attrs and Attr.UNDERLINE.inv()
                25 -> attrs = attrs and Attr.BLINK.inv()
                27 -> attrs = attrs and Attr.INVERSE.inv()
                28 -> attrs = attrs and Attr.INVISIBLE.inv()
                29 -> attrs = attrs and Attr.STRIKETHROUGH.inv()
                in 30..37 -> fg = TermColor.indexed(code - 30)
                38 -> i = extendedColor(p, i, isForeground = true)
                39 -> fg = TermColor.COLOR_DEFAULT
                in 40..47 -> bg = TermColor.indexed(code - 40)
                48 -> i = extendedColor(p, i, isForeground = false)
                49 -> bg = TermColor.COLOR_DEFAULT
                58 -> i = extendedColor(p, i, isForeground = null) // underline color: parsed, not stored
                59 -> Unit
                in 90..97 -> fg = TermColor.indexed(code - 90 + 8)
                in 100..107 -> bg = TermColor.indexed(code - 100 + 8)
                else -> Unit
            }
            i++
        }
    }

    private fun resetSgr() {
        fg = TermColor.COLOR_DEFAULT
        bg = TermColor.COLOR_DEFAULT
        attrs = 0
    }

    /**
     * Parses 38/48/58 extended colors in both the semicolon form (38;5;n / 38;2;r;g;b) and the
     * colon form (38:5:n / 38:2::r:g:b). Returns the index of the last parameter consumed.
     */
    private fun extendedColor(p: CsiParams, index: Int, isForeground: Boolean?): Int {
        val color: Int?
        var last = index
        if (p.subCount(index) > 0) {
            val kind = p.sub(index, 0)
            color = when (kind) {
                5 -> TermColor.indexed(p.sub(index, 1).coerceIn(0, 255))
                2 -> {
                    // ITU T.416 allows an empty color-space id: 38:2::r:g:b. Take the last three.
                    val n = p.subCount(index)
                    if (n >= 4) TermColor.rgb(p.sub(index, n - 3).coerceIn(0, 255), p.sub(index, n - 2).coerceIn(0, 255), p.sub(index, n - 1).coerceIn(0, 255)) else null
                }
                else -> null
            }
        } else {
            val kind = p.zeroBased(index + 1)
            when (kind) {
                5 -> {
                    color = TermColor.indexed(p.zeroBased(index + 2).coerceIn(0, 255))
                    last = index + 2
                }
                2 -> {
                    color = TermColor.rgb(p.zeroBased(index + 2).coerceIn(0, 255), p.zeroBased(index + 3).coerceIn(0, 255), p.zeroBased(index + 4).coerceIn(0, 255))
                    last = index + 4
                }
                else -> {
                    color = null
                    last = index + 1
                }
            }
        }
        if (color != null) {
            when (isForeground) {
                true -> fg = color
                false -> bg = color
                null -> Unit
            }
        }
        return last
    }

    private fun sgrString(): String {
        val parts = ArrayList<String>()
        parts.add("0")
        if (attrs and Attr.BOLD != 0) parts.add("1")
        if (attrs and Attr.DIM != 0) parts.add("2")
        if (attrs and Attr.ITALIC != 0) parts.add("3")
        if (attrs and Attr.UNDERLINE != 0) parts.add("4")
        if (attrs and Attr.BLINK != 0) parts.add("5")
        if (attrs and Attr.INVERSE != 0) parts.add("7")
        if (attrs and Attr.INVISIBLE != 0) parts.add("8")
        if (attrs and Attr.STRIKETHROUGH != 0) parts.add("9")
        colorSgr(fg, 38, 30, 90)?.let(parts::add)
        colorSgr(bg, 48, 40, 100)?.let(parts::add)
        return parts.joinToString(";")
    }

    private fun colorSgr(color: Int, extended: Int, base: Int, brightBase: Int): String? = when (TermColor.kind(color)) {
        TermColor.KIND_INDEXED -> {
            val idx = TermColor.index(color)
            when {
                idx < 8 -> (base + idx).toString()
                idx < 16 -> (brightBase + idx - 8).toString()
                else -> "$extended:5:$idx"
            }
        }
        TermColor.KIND_RGB -> {
            val v = TermColor.rgbValue(color)
            "$extended:2::${(v shr 16) and 0xFF}:${(v shr 8) and 0xFF}:${v and 0xFF}"
        }
        else -> null
    }

    private fun decscusrValue(): Int = when (cursorStyle.shape) {
        CursorShape.BLOCK -> if (cursorStyle.blinking) 1 else 2
        CursorShape.UNDERLINE -> if (cursorStyle.blinking) 3 else 4
        CursorShape.BAR -> if (cursorStyle.blinking) 5 else 6
    }

    // ---------------------------------------------------------------------------------------------
    // Reports

    private fun deviceStatus(kind: Int, private: Boolean) {
        when (kind) {
            5 -> respond("\u001b[0n")
            6 -> {
                val row = if (originMode) cursorY - scrollTop + 1 else cursorY + 1
                val col = cursorX + 1
                respond(if (private) "\u001b[?$row;$col;1R" else "\u001b[$row;${col}R")
            }
            15 -> if (private) respond("\u001b[?13n") // no printer
            25 -> if (private) respond("\u001b[?21n") // UDKs locked
            26 -> if (private) respond("\u001b[?27;1;0;0n") // keyboard: North American
            else -> Unit
        }
    }

    private fun windowOps(p: CsiParams) {
        when (p.zeroBased(0)) {
            11 -> respond("\u001b[1t")
            13 -> respond("\u001b[3;0;0t")
            14 -> respond("\u001b[4;${rows * cellHeightPx};${cols * cellWidthPx}t")
            16 -> respond("\u001b[6;$cellHeightPx;${cellWidthPx}t")
            18 -> respond("\u001b[8;$rows;${cols}t")
            19 -> respond("\u001b[9;$rows;${cols}t")
            20 -> respond("\u001b]L$title\u001b\\")
            21 -> respond("\u001b]l$title\u001b\\")
            22 -> { titleStack.addLast(title); if (titleStack.size > 16) titleStack.removeFirst() }
            23 -> titleStack.removeLastOrNull()?.let { setTitle(it) }
            else -> Unit
        }
    }

    private fun colorReport(code: Int, rgb: Int): String {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return "\u001b]$code;rgb:${hex4(r)}/${hex4(g)}/${hex4(b)}\u001b\\"
    }

    private fun hex4(v: Int): String = String.format("%02x%02x", v, v)

    private fun oscPalette(arg: String) {
        val parts = arg.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val idx = parts[i].toIntOrNull()
            val spec = parts[i + 1]
            if (idx != null && idx in 0..255) {
                if (spec == "?") {
                    respond("\u001b]4;$idx;rgb:${hex4((palette[idx] shr 16) and 0xFF)}/${hex4((palette[idx] shr 8) and 0xFF)}/${hex4(palette[idx] and 0xFF)}\u001b\\")
                } else {
                    parseColorSpec(spec)?.let { palette[idx] = it }
                }
            }
            i += 2
        }
    }

    private fun oscClipboard(arg: String) {
        val sep = arg.indexOf(';')
        if (sep < 0) return
        val data = arg.substring(sep + 1)
        if (data == "?") return // Reading the clipboard is never granted to the remote side.
        runCatching { String(Base64.getDecoder().decode(data), Charsets.UTF_8) }
            .getOrNull()
            ?.let(listener::onClipboardWrite)
    }

    private fun setTitle(value: String) {
        if (title == value) return
        title = value
        listener.onTitleChanged(value)
    }

    private fun respond(text: String) {
        listener.onResponse(text.toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 5000
        const val MIN_COLS = 2
        const val MIN_ROWS = 1

        /** The most input parsed under [lock] in one go; about a quarter of a millisecond of work. */
        const val WRITE_SLICE_BYTES = 8 * 1024
        private const val TAB_WIDTH = 8
        private const val TERMINAL_NAME = "berth"
        private const val TERMINAL_VERSION = "0.1"
        private const val SYNC_TIMEOUT_NANOS = 150_000_000L

        /** VT220 with ANSI color. */
        private const val PRIMARY_DA = "\u001b[?62;22c"

        /** Report as xterm patch 354; applications treat that as a modern xterm. */
        private const val SECONDARY_DA = "\u001b[>41;354;0c"

        private val BRACKET_START = "\u001b[200~".toByteArray(Charsets.US_ASCII)
        private val BRACKET_END = "\u001b[201~".toByteArray(Charsets.US_ASCII)

        /** DEC Special Graphics for 0x60..0x7E. */
        private val DEC_GRAPHICS = intArrayOf(
            0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1, 0x2424, 0x240B, 0x2518,
            0x2510, 0x250C, 0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524,
            0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        /** Parses `rgb:rr/gg/bb`, `rgb:rrrr/gggg/bbbb`, and `#rrggbb` color specifications. */
        fun parseColorSpec(spec: String): Int? {
            if (spec.startsWith("#") && spec.length == 7) return spec.substring(1).toIntOrNull(16)
            if (spec.startsWith("rgb:")) {
                val parts = spec.substring(4).split('/')
                if (parts.size != 3) return null
                val channels = parts.map { part ->
                    val v = part.toIntOrNull(16) ?: return null
                    when (part.length) {
                        1 -> v * 17
                        2 -> v
                        3 -> v shr 4
                        4 -> v shr 8
                        else -> return null
                    }
                }
                return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
            }
            return null
        }
    }
}
