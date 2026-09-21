package app.berth.android.ui.keys

import app.berth.android.session.TerminalSession
import app.berth.domain.model.TmuxMode
import app.berth.terminal.Mod
import app.berth.terminal.TerminalEmulator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Install on host (spec C12): the command that appends a public key line to a server's
 * `~/.ssh/authorized_keys`, typed into a live session's shell the way a snippet is run, and how
 * its answer is read back off the screen. Nothing here reaches into the transport: the command
 * goes through [TerminalSession.sendText], and the answer is what the shell printed.
 *
 * The command is typed only where a shell is reading. A tab on the alternate screen is in a
 * program, `vim`, `less`, `htop`, and a program would take three hundred characters and an Enter as
 * keystrokes of its own; the install refuses that tab and the sheet offers it instead, so the
 * person can quit what is running or paste the command themselves. A tmux host is on the alternate
 * screen for as long as tmux is attached, so the flag says nothing about its pane; that host is
 * typed into as it stands. A half-typed prompt line is cleared first (Ctrl+U) rather than joined
 * to the command.
 */
object KeyInstall {
    /** What the shell prints when every step of [command] succeeded. */
    const val INSTALLED = "berth: key installed"

    /** What it prints when a step failed (a read-only home, a full disk, a missing `chmod`). */
    const val NOT_INSTALLED = "berth: key not installed"

    /** How long the sheet waits for either answer before saying the shell gave none. */
    const val ANSWER_TIMEOUT_MS = 20_000L

    enum class Outcome { INSTALLED, NOT_INSTALLED }

    /** What came of typing the command, or why it was not typed. */
    sealed interface Result {
        /** The command was typed; [outcome] is the shell's answer, or null when it gave neither in time. */
        data class Answered(val outcome: Outcome?) : Result

        /** Nothing was typed: the tab is in a program, not at a shell ([runningProgram]). */
        data object ProgramRunning : Result
    }

    /**
     * The part of a session the install types into and reads from, so the typing can be checked
     * against a fake; [shellOf] is the one over a [TerminalSession].
     */
    interface Shell {
        val emulator: TerminalEmulator
        val screenVersion: StateFlow<Long>
        val tmux: TmuxMode
        fun sendText(text: String, modifiers: Int = 0)
    }

    fun shellOf(session: TerminalSession): Shell = object : Shell {
        override val emulator: TerminalEmulator get() = session.emulator
        override val screenVersion: StateFlow<Long> get() = session.screenVersion
        override val tmux: TmuxMode get() = session.host.persistence.tmux
        override fun sendText(text: String, modifiers: Int) = session.sendText(text, modifiers)
    }

    /**
     * Whether a program, not a shell, would read what is typed: the alternate screen is up and no
     * tmux is attached to explain it. tmux itself holds the alternate screen while attached, so
     * for a host with tmux on the flag says nothing about the pane's own state.
     */
    fun runningProgram(isAlternateScreen: Boolean, tmux: TmuxMode): Boolean = isAlternateScreen && tmux == TmuxMode.OFF

    fun runningProgram(shell: Shell): Boolean = runningProgram(synchronized(shell.emulator.lock) { shell.emulator.isAlternateScreen }, shell.tmux)

    fun runningProgram(session: TerminalSession): Boolean = runningProgram(shellOf(session))

    /**
     * The spec's command, with the key line single-quoted for the shell (a `'` in the comment
     * becomes `'\''`), followed by the answer: `printf` puts the words together only when it runs,
     * so the typed line itself (`'berth: key %s\n' installed`) never reads as the answer on screen.
     */
    fun command(publicKeyLine: String): String =
        "mkdir -p ~/.ssh && printf '%s\\n' ${quote(publicKeyLine.trim())} >> ~/.ssh/authorized_keys" +
            " && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys" +
            " && printf 'berth: key %s\\n' installed || printf 'berth: key %s\\n' 'not installed'"

    /** [text] as one single-quoted shell word; the only character a single-quoted word cannot hold is `'`, spliced in as `'\''`. */
    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** Each answer's count among [rows]; a row holds at most one, so a row that says `not installed` is not also counted as installed. */
    fun counts(rows: List<String>): Map<Outcome, Int> = mapOf(
        Outcome.INSTALLED to rows.count { it.contains(INSTALLED) },
        Outcome.NOT_INSTALLED to rows.count { it.contains(NOT_INSTALLED) },
    )

    /**
     * The answer that is on screen more often than it was [before] the command was typed, or null
     * while neither is. Counting rather than looking for the words at all keeps an earlier install's
     * answer, still on the screen, from being read as this one's.
     */
    fun answer(rows: List<String>, before: Map<Outcome, Int>): Outcome? {
        val now = counts(rows)
        return Outcome.entries.firstOrNull { (now[it] ?: 0) > (before[it] ?: 0) }
    }

    /**
     * The rows of [emulator] from the absolute row [fromAbsolute] on (a buffer row plus what the
     * buffer has dropped, so the same text keeps its number as output scrolls), as plain text. The
     * emulator's lock is taken here; the reader thread keeps writing while the shell answers.
     */
    fun rowsFrom(emulator: TerminalEmulator, fromAbsolute: Long): List<String> = synchronized(emulator.lock) {
        val first = (fromAbsolute - emulator.linesDropped).coerceIn(0L, emulator.bufferRows.toLong()).toInt()
        (first until emulator.bufferRows).map { emulator.grid.line(it).toText() }
    }

    /** The absolute row at the top of the screen now: what is on screen and everything typed or printed after it starts here. */
    fun screenTop(emulator: TerminalEmulator): Long = synchronized(emulator.lock) {
        (emulator.bufferRows - emulator.rows).toLong() + emulator.linesDropped
    }

    /**
     * Types [command] into [shell] with its Enter, as a snippet is run, and waits for the answer:
     * what the screen shows from its current top down is read again on every change until one of
     * the two answers is there more often than it was, or [timeoutMs] pass with neither (the shell
     * busy, a prompt waiting), which is [Result.Answered] of null. Ctrl+U goes first, so a line
     * half-typed at the prompt is discarded rather than run with the command appended (`echo hell`
     * plus the command would install the key). A shell that is [runningProgram] gets nothing, and
     * the result says so.
     */
    suspend fun run(shell: Shell, command: String, timeoutMs: Long = ANSWER_TIMEOUT_MS): Result {
        if (runningProgram(shell)) return Result.ProgramRunning
        val top = screenTop(shell.emulator)
        val before = counts(rowsFrom(shell.emulator, top))
        shell.sendText("u", Mod.CTRL)
        shell.sendText(command + "\n")
        val outcome = withTimeoutOrNull(timeoutMs) {
            shell.screenVersion.mapNotNull { answer(rowsFrom(shell.emulator, top), before) }.first()
        }
        return Result.Answered(outcome)
    }

    suspend fun run(session: TerminalSession, command: String, timeoutMs: Long = ANSWER_TIMEOUT_MS): Result = run(shellOf(session), command, timeoutMs)
}
