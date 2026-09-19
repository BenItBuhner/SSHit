package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandHistoryTest {
    private class Recorder : TerminalListenerAdapter() {
        val commands = ArrayList<String>()
        val marks = ArrayList<Char>()
        override fun onCommandEntered(command: String) { commands += command }
        override fun onShellIntegration(mark: Char, param: String) { marks += mark }
    }

    private fun term(cols: Int = 40, rows: Int = 6, scrollback: Int = 100): Pair<TerminalEmulator, Recorder> {
        val r = Recorder()
        return TerminalEmulator(cols, rows, scrollback, r) to r
    }

    private fun mark(m: Char, param: String = "") = "\u001b]133;$m${if (param.isEmpty()) "" else ";$param"}\u0007"

    // ---- the store ------------------------------------------------------------------------------

    @Test
    fun `records in order, skips blanks and a repeat of the latest entry`() {
        val h = CommandHistory()
        assertTrue(h.record("ls", 1))
        assertFalse(h.record("  ", 2))
        assertFalse(h.record("ls", 3))
        assertTrue(h.record("pwd", 4))
        assertTrue(h.record("ls", 5))
        assertEquals(listOf("ls", "pwd", "ls"), h.snapshot().map { it.text })
        assertEquals(listOf(1L, 4L, 5L), h.snapshot().map { it.at })
    }

    @Test
    fun `keeps the newest entries up to the cap`() {
        val h = CommandHistory(cap = 3)
        for (i in 1..5) h.record("cmd$i", i.toLong())
        assertEquals(listOf("cmd3", "cmd4", "cmd5"), h.snapshot().map { it.text })
        h.load((1..10).map { CommandEntry("old$it", it.toLong()) })
        assertEquals(listOf("old8", "old9", "old10"), h.snapshot().map { it.text })
    }

    @Test
    fun `entries can be removed one at a time or all at once`() {
        val h = CommandHistory()
        h.record("a", 1)
        h.record("b", 2)
        assertTrue(h.remove(CommandEntry("a", 1)))
        assertEquals(listOf("b"), h.snapshot().map { it.text })
        h.clear()
        assertEquals(0, h.size)
    }

    // ---- OSC 133 marks --------------------------------------------------------------------------

    @Test
    fun `the command between the B and C marks is read off the screen`() {
        val (t, r) = term()
        t.write(mark('A') + "user@host:~$ " + mark('B'))
        t.write("git status")
        t.write("\r\n" + mark('C'))
        assertEquals(listOf("git status"), r.commands)
        assertEquals(listOf('A', 'B', 'C'), r.marks)
        t.write("On branch main\r\n" + mark('D', "0"))
        assertEquals(1, r.commands.size)
    }

    @Test
    fun `a command wrapped over two rows is rejoined and trailing blanks go`() {
        val (t, r) = term(cols = 20)
        t.write(mark('A') + "$ " + mark('B') + "echo one two three four   ")
        t.write("\r\n" + mark('C'))
        assertEquals(listOf("echo one two three four"), r.commands)
    }

    @Test
    fun `an empty line at the prompt records nothing`() {
        val (t, r) = term()
        t.write(mark('A') + "$ " + mark('B') + "\r\n" + mark('C'))
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `a shell that sends the command line with the C mark is believed over the screen`() {
        val (t, r) = term()
        t.write(mark('A') + "$ " + mark('B') + "ls -la  # typed")
        t.write("\r\n" + mark('C', "cmdline_url=ls%20-la%20%7C%20wc"))
        assertEquals(listOf("ls -la | wc"), r.commands)
        t.write(mark('A') + "$ " + mark('B') + "x\r\n" + mark('C', "cmdline=pwd"))
        assertEquals(listOf("ls -la | wc", "pwd"), r.commands)
    }

    @Test
    fun `the command survives the prompt scrolling into history before C arrives`() {
        val (t, r) = term(cols = 40, rows = 3)
        t.write("old\r\nold\r\n" + mark('A') + "$ " + mark('B') + "make all")
        // Enter echoes a newline that scrolls the prompt row into history before the shell marks C.
        t.write("\r\n\r\n\r\n" + mark('C'))
        assertEquals(listOf("make all"), r.commands)
    }

    @Test
    fun `a password answered inside a command is not a command`() {
        val (t, r) = term()
        t.write(mark('A') + "$ " + mark('B') + "sudo true\r\n" + mark('C'))
        t.write("[sudo] password: \r\n" + mark('D', "0"))
        t.write(mark('A') + "$ " + mark('B'))
        assertEquals(listOf("sudo true"), r.commands)
    }

    // ---- the fallback for shells without marks ------------------------------------------------------

    @Test
    fun `typed text is recorded on Enter when the screen shows it`() {
        val line = TypedLine()
        line.typed("git statsu")
        line.backspace()
        line.backspace()
        line.typed("us")
        assertEquals("git status", line.commit("user@host:~$ git status"))
        assertNull(line.current())
    }

    @Test
    fun `text the screen never echoed is not recorded`() {
        val line = TypedLine()
        line.typed("hunter2")
        assertNull(line.commit("Password: "))
        assertNull(line.current())
    }

    @Test
    fun `completion, history recall and pastes hand the line to the shell`() {
        val tab = TypedLine()
        tab.typed("git sta")
        tab.unreliable()
        tab.typed("tus")
        assertNull(tab.commit("$ git status"))
        assertTrue(tab.reliable)

        val chord = TypedLine()
        chord.typed("ls\u0001")
        assertFalse(chord.reliable)
        assertNull(chord.commit("$ ls"))
    }

    @Test
    fun `an abandoned line starts over`() {
        val line = TypedLine()
        line.typed("rm -rf /")
        line.reset()
        line.typed("ls")
        assertEquals("ls", line.commit("$ ls"))
    }
}
