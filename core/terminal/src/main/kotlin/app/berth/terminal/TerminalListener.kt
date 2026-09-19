package app.berth.terminal

/** Callbacks from the emulator. All are invoked on the thread that called [TerminalEmulator.write]. */
interface TerminalListener {
    /** Visible content, cursor, or a mode that affects rendering changed. */
    fun onScreenChanged()

    fun onTitleChanged(title: String)

    fun onBell()

    /** Bytes the terminal must send back to the host (query responses). */
    fun onResponse(data: ByteArray)

    /** The host asked to place [text] on the clipboard via OSC 52. */
    fun onClipboardWrite(text: String)

    /** OSC 7: the shell reported its working directory as a `file://host/path` URL. */
    fun onWorkingDirectoryChanged(url: String)

    /** OSC 9 / 99 / 777: a desktop-style notification from the remote side. */
    fun onNotification(title: String, body: String)

    /**
     * OSC 133 shell integration. [mark] is 'A' (prompt start), 'B' (command start), 'C' (command
     * executed) or 'D' (command finished, [param] carries the exit code when present).
     */
    fun onShellIntegration(mark: Char, param: String)

    /**
     * A command the shell has started running, as reported by its OSC 133 marks (spec C16): the
     * text between the `B` and `C` marks, trimmed. Never called for a shell without integration.
     */
    fun onCommandEntered(command: String)
}

/** No-op base to subclass. */
open class TerminalListenerAdapter : TerminalListener {
    override fun onScreenChanged() = Unit
    override fun onTitleChanged(title: String) = Unit
    override fun onBell() = Unit
    override fun onResponse(data: ByteArray) = Unit
    override fun onClipboardWrite(text: String) = Unit
    override fun onWorkingDirectoryChanged(url: String) = Unit
    override fun onNotification(title: String, body: String) = Unit
    override fun onShellIntegration(mark: Char, param: String) = Unit
    override fun onCommandEntered(command: String) = Unit
}
