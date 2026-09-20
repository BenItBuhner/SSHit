package app.berth.terminal

/** What a paste holds, so the Stage can decide between sending it and showing it first. */
data class PasteAnalysis(
    val text: String,
    /** Lines as a person counts them: a trailing line break does not add an empty one. */
    val lines: Int,
    /** Line breaks in the text; without bracketed paste each one runs whatever precedes it. */
    val lineBreaks: Int,
    /** Characters as code points. */
    val chars: Int,
    /** How many control characters other than tab and line breaks the text carries: escapes, NUL, DEL, C1. */
    val controlChars: Int,
    /** The caret names of the first distinct control characters, in order of appearance (`^[`, `^C`), for the warning. */
    val controlNames: List<String>,
) {
    val hasControlChars: Boolean get() = controlChars > 0
    val isMultiLine: Boolean get() = lines > 1 || lineBreaks > 0
    val isLong: Boolean get() = chars > PasteClassifier.PREVIEW_CHARS

    /** Whether the paste should be shown before it is sent. */
    val needsPreview: Boolean get() = isMultiLine || isLong || hasControlChars
}

/**
 * Sorts clipboard text into what pastes straight through and what deserves a look first (spec
 * C18): one plain line under the threshold goes as it is; more than one line, more than
 * [PREVIEW_CHARS] characters or any control character shows a preview.
 */
object PasteClassifier {
    const val PREVIEW_CHARS = 200

    fun analyze(text: String): PasteAnalysis {
        var breaks = 0
        var control = 0
        val names = ArrayList<String>(NAMED_CONTROLS)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp == '\r'.code -> {
                    breaks++
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                cp == '\n'.code -> breaks++
                isControl(cp) -> {
                    control++
                    val name = caretName(cp)
                    if (names.size < NAMED_CONTROLS && name !in names) names.add(name)
                }
            }
            i += Character.charCount(cp)
        }
        val endsWithBreak = text.endsWith("\n") || text.endsWith("\r")
        val lines = if (text.isEmpty()) 0 else breaks + if (endsWithBreak) 0 else 1
        return PasteAnalysis(text, lines.coerceAtLeast(if (text.isEmpty()) 0 else 1), breaks, text.codePointCount(0, text.length), control, names)
    }

    /** Whether [codePoint] is a control character the terminal would act on rather than print; tab and line breaks are not. */
    fun isControl(codePoint: Int): Boolean =
        (codePoint < 0x20 && codePoint != '\t'.code && codePoint != '\n'.code && codePoint != '\r'.code) || codePoint == 0x7F || codePoint in 0x80..0x9F

    /** The caret name of a control character: `^[` for escape, `^C`, `^?` for DEL; C1 controls as `\x9B`, which have no caret form. */
    fun caretName(codePoint: Int): String = when {
        codePoint < 0x20 -> "^" + (codePoint + 0x40).toChar()
        codePoint == 0x7F -> "^?"
        else -> "\\x%02X".format(codePoint)
    }

    /** How many distinct control characters the warning names before it says "and more". */
    const val NAMED_CONTROLS = 2

    /** The text as one line: each line trimmed and joined by single spaces, so nothing runs when it lands at a prompt. */
    fun asOneLine(text: String): String =
        text.split("\r\n", "\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** The text without the control characters [isControl] flags; tabs and line breaks stay. */
    fun stripControl(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isControl(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
