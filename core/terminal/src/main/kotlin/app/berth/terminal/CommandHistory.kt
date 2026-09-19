package app.berth.terminal

/** One command a session ran, and when. */
data class CommandEntry(val text: String, val at: Long)

/**
 * The commands of one session, oldest first, capped at [cap] entries (spec C16: 2,000). A command
 * equal to the one before it is not recorded twice in a row, the way `HISTCONTROL=ignoredups`
 * keeps a shell's own history readable.
 */
class CommandHistory(private val cap: Int = DEFAULT_CAP) {
    private val entries = ArrayList<CommandEntry>()

    val size: Int get() = entries.size

    /** Records [text] at [at]; false when it was blank or repeats the latest entry. */
    fun record(text: String, at: Long): Boolean {
        val command = text.trim()
        if (command.isEmpty() || entries.lastOrNull()?.text == command) return false
        entries.add(CommandEntry(command, at))
        while (entries.size > cap) entries.removeAt(0)
        return true
    }

    fun remove(entry: CommandEntry): Boolean = entries.remove(entry)

    fun clear() = entries.clear()

    /** Loads persisted entries, oldest first, in place of whatever is held; keeps the newest [cap]. */
    fun load(saved: List<CommandEntry>) {
        entries.clear()
        entries.addAll(saved.takeLast(cap))
    }

    fun snapshot(): List<CommandEntry> = ArrayList(entries)

    companion object {
        const val DEFAULT_CAP = 2_000
    }
}

/**
 * What has been typed at the prompt since the last Enter, for shells without OSC 133 marks. The
 * line is trusted while it was built from plain characters and Backspace. A Tab hands part of it to
 * the shell's completion: what was typed before the Tab is kept as an anchor and, on Enter, the
 * command is read off the screen from that anchor to the end of the line, checked against whatever
 * was typed after it. An arrow, a control chord or a multi-line paste hands the whole line to the
 * shell (history recall, editing) and nothing is recorded until the next Enter. The caller resolves
 * the committed line against the screen once the echo has landed, which keeps passwords out.
 */
class TypedLine {
    private val sb = StringBuilder()

    /** Length of the typed text when the first Tab went to the shell; -1 while none has. */
    private var anchorLen = -1

    /** Whether the text typed after the anchor is the end of the command as typed; a second Tab completes past it. */
    private var tailKnown = true

    /** False once the shell has been asked to edit the line in a way this cannot follow. */
    var reliable: Boolean = true
        private set

    fun typed(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\r'.code || cp == '\n'.code -> Unit
                cp < 0x20 || cp == 0x7F -> reliable = false
                else -> sb.appendCodePoint(cp)
            }
        }
    }

    fun backspace() {
        if (sb.isEmpty()) return
        // Deleting into what completion wrote is deleting text this never saw.
        if (anchorLen >= 0 && sb.length <= anchorLen) {
            reliable = false
            return
        }
        val last = sb.codePointBefore(sb.length)
        sb.setLength(sb.length - Character.charCount(last))
    }

    /** Tab: the shell completes the line from here; what was typed so far anchors the read-back on Enter. */
    fun completed() {
        when {
            anchorLen < 0 && sb.isBlank() -> reliable = false
            anchorLen < 0 -> anchorLen = sb.length
            else -> tailKnown = false
        }
    }

    /** The shell now owns the line's contents. */
    fun unreliable() {
        reliable = false
    }

    /** Ctrl+C or Ctrl+U: the line is abandoned and a fresh one begins. */
    fun reset() {
        sb.setLength(0)
        anchorLen = -1
        tailKnown = true
        reliable = true
    }

    /** The line as typed, or null when it is blank or no longer trusted. */
    fun current(): String? = if (reliable) sb.toString().trim().takeIf { it.isNotEmpty() } else null

    /**
     * Enter: what to look for on the screen once the echo has landed, or null when nothing trusted
     * was typed. Clears the line either way.
     */
    fun commit(): Pending? {
        val pending = if (reliable && sb.isNotBlank()) Pending(sb.toString(), anchorLen, tailKnown) else null
        reset()
        return pending
    }

    /** [commit] resolved at once against the logical line the cursor is on, for a caller that knows the echo has landed. */
    fun commit(cursorLine: String): String? = commit()?.resolve(cursorLine)

    /**
     * A line Enter committed, to be resolved against the logical line it was typed on: the typed
     * text when the line shows it; after a Tab, the line from the typed prefix to its end, which
     * must still end with what was typed after the Tab.
     */
    class Pending internal constructor(private val typed: String, private val anchorLen: Int, private val tailKnown: Boolean) {
        fun resolve(line: String): String? {
            if (anchorLen < 0) {
                val exact = typed.trim()
                return exact.takeIf { it.isNotEmpty() && line.contains(it) }
            }
            val prefix = typed.substring(0, anchorLen).trimStart()
            if (prefix.isEmpty()) return null
            val at = line.lastIndexOf(prefix)
            if (at < 0) return null
            val command = line.substring(at).trim()
            val tail = typed.substring(anchorLen).trim()
            if (tailKnown && tail.isNotEmpty() && !command.endsWith(tail)) return null
            return command.takeIf { it.isNotEmpty() }
        }
    }
}
