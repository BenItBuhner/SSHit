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
 * line is only trusted while it was built from plain characters and Backspace; a Tab, an arrow, a
 * control chord or a paste hands editing to the shell (completion, history recall) and the local
 * copy no longer says what will run, so the line is dropped until the next Enter. The caller also
 * checks that the line was echoed before recording it, which keeps passwords out.
 */
class TypedLine {
    private val sb = StringBuilder()

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
        val last = sb.codePointBefore(sb.length)
        sb.setLength(sb.length - Character.charCount(last))
    }

    /** The shell now owns the line's contents. */
    fun unreliable() {
        reliable = false
    }

    /** Ctrl+C or Ctrl+U: the line is abandoned and a fresh one begins. */
    fun reset() {
        sb.setLength(0)
        reliable = true
    }

    /** The line as typed, or null when it is blank or no longer trusted. */
    fun current(): String? = if (reliable) sb.toString().trim().takeIf { it.isNotEmpty() } else null

    /**
     * The command to record on Enter, given the logical line the cursor is on: the typed text
     * when the screen shows it (so it was echoed), otherwise nothing. Clears the line either way.
     */
    fun commit(cursorLine: String): String? {
        val typed = current()
        reset()
        return typed?.takeIf { cursorLine.contains(it) }
    }
}
