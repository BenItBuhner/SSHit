package app.berth.android.security

/**
 * What the notice shows of a blocked clipboard write, made safe to judge by eye. Control
 * characters and format characters cannot be shown as they are: a carriage return hides what came
 * before it, a bidi override reverses what follows, an escape sequence is invisible. Each becomes
 * a visible escape (`^M`, `^[`, `\u202E`) before the text is cut to [MAX_LINES] lines or
 * [MAX_CHARS] characters, and [countLine] says what was left out.
 */
data class ClipboardPreview(
    /** The text to show, already escaped and cut; `(empty)` for a write with nothing in it. */
    val shown: String,
    /** Whole lines after the cut, when the cut fell on a line break; the count line says so. */
    val hiddenLines: Int,
    /** Characters after the cut, when it fell inside a line; the count line says so instead. */
    val hiddenChars: Int,
    /** The whole write, in UTF-8 bytes. */
    val totalBytes: Int,
) {
    val truncated: Boolean get() = hiddenLines > 0 || hiddenChars > 0

    /** `+ 3 more lines · 1.2 KB`, or null when everything is on show. */
    fun countLine(size: (Long) -> String): String? = when {
        hiddenLines > 0 -> "+ $hiddenLines more ${if (hiddenLines == 1) "line" else "lines"} \u00B7 ${size(totalBytes.toLong())}"
        hiddenChars > 0 -> "+ $hiddenChars more ${if (hiddenChars == 1) "character" else "characters"} \u00B7 ${size(totalBytes.toLong())}"
        else -> null
    }

    companion object {
        const val MAX_LINES = 6
        const val MAX_CHARS = 400

        fun of(text: String): ClipboardPreview {
            val totalBytes = text.toByteArray(Charsets.UTF_8).size
            if (text.isEmpty()) return ClipboardPreview("(empty)", 0, 0, 0)
            val safe = escape(text)
            var cut = safe.length
            var breaks = 0
            for (i in safe.indices) {
                if (safe[i] == '\n' && ++breaks == MAX_LINES) {
                    cut = i
                    break
                }
            }
            cut = minOf(cut, MAX_CHARS)
            // Never split a surrogate pair: a dangling high half would be its own kind of unreadable.
            if (cut in 1 until safe.length && safe[cut - 1].isHighSurrogate()) cut--
            val rest = safe.substring(cut)
            return when {
                rest.isEmpty() -> ClipboardPreview(safe, 0, 0, totalBytes)
                rest[0] == '\n' -> ClipboardPreview(safe.substring(0, cut), rest.count { it == '\n' }, 0, totalBytes)
                else -> ClipboardPreview(safe.substring(0, cut), 0, rest.length, totalBytes)
            }
        }

        /**
         * C0 controls in caret notation (`^[` for escape, `^M` for carriage return, `^?` for
         * delete), and C1 controls and every format character (the bidi marks, embeddings,
         * overrides and isolates among them, plus the zero-width joiners and the byte-order mark)
         * as `\uXXXX`. Line feeds stay line breaks and tabs stay tabs.
         */
        fun escape(text: String): String {
            val out = StringBuilder(text.length)
            for (ch in text) {
                when {
                    ch == '\n' || ch == '\t' -> out.append(ch)
                    ch < ' ' -> out.append('^').append((ch.code + 64).toChar())
                    ch == '\u007F' -> out.append("^?")
                    ch in '\u0080'..'\u009F' || Character.getType(ch) == Character.FORMAT.toInt() -> out.append("\\u%04X".format(ch.code))
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }
    }
}
