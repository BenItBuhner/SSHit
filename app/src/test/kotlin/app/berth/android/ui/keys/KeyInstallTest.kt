package app.berth.android.ui.keys

import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Install on host (spec C12) as the pure parts: the command typed into the shell, the quoting
 * that keeps a key line one word, and the reading of the shell's answer off a real emulator's
 * screen, including one that has scrolled.
 */
class KeyInstallTest {
    private val keyLine = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGRjb2xhZ2VvbmJlcnRoa2V5dGVzdGZha2Vwa3k0MjQy ben@pixel"

    @Test
    fun `a single-quoted word holds anything but a quote, which is spliced in`() {
        assertEquals("'plain'", KeyInstall.quote("plain"))
        assertEquals("'it'\\''s'", KeyInstall.quote("it's"))
        assertEquals("a dollar, a backtick and a backslash are literal inside single quotes", "'\$HOME `id` \\n'", KeyInstall.quote("\$HOME `id` \\n"))
        assertEquals("''", KeyInstall.quote(""))
    }

    @Test
    fun `the command appends the quoted line, sets the modes and answers one way or the other`() {
        val command = KeyInstall.command("  $keyLine \n")
        assertTrue(command.startsWith("mkdir -p ~/.ssh && printf '%s\\n' '$keyLine' >> ~/.ssh/authorized_keys"))
        assertTrue(command.contains("chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"))
        assertTrue(command.endsWith("&& printf 'berth: key %s\\n' installed || printf 'berth: key %s\\n' 'not installed'"))
        assertFalse("the typed line must never read as the answer when the shell echoes it", command.contains(KeyInstall.INSTALLED))
        assertFalse(command.contains(KeyInstall.NOT_INSTALLED))
        assertEquals("one line, so one Enter runs all of it", -1, command.indexOf('\n'))
        assertTrue("a comment with a quote stays one shell word", KeyInstall.command("ssh-ed25519 AAAA ben's laptop").contains("'ssh-ed25519 AAAA ben'\\''s laptop'"))
    }

    @Test
    fun `an answer is the one that appears more often than before, a row holding at most one`() {
        val before = KeyInstall.counts(listOf("\$ ", "berth: key installed", ""))
        assertEquals(mapOf(KeyInstall.Outcome.INSTALLED to 1, KeyInstall.Outcome.NOT_INSTALLED to 0), before)
        assertNull("the earlier install's answer, still on screen, is not this one's", KeyInstall.answer(listOf("\$ ", "berth: key installed", "\$ mkdir -p ~/.ssh && ..."), before))
        assertEquals(KeyInstall.Outcome.INSTALLED, KeyInstall.answer(listOf("\$ ", "berth: key installed", "\$ mkdir ...", "berth: key installed", "\$ "), before))
        assertEquals(KeyInstall.Outcome.NOT_INSTALLED, KeyInstall.answer(listOf("\$ ", "berth: key installed", "\$ mkdir ...", "bash: /home/ben/.ssh/authorized_keys: Read-only file system", "berth: key not installed"), before))
        assertEquals("not installed is not also counted as installed", mapOf(KeyInstall.Outcome.INSTALLED to 0, KeyInstall.Outcome.NOT_INSTALLED to 1), KeyInstall.counts(listOf("berth: key not installed")))
    }

    @Test
    fun `the answer is read off a screen that has scrolled, from where the screen's top was when the command was typed`() {
        val t = TerminalEmulator(40, 5, 3, TerminalListenerAdapter())
        // Enough output that the buffer has dropped lines, so absolute rows and buffer rows differ.
        for (i in 1..12) t.write("line $i\r\n")
        assertTrue("the fixture scrolled past its scrollback", t.linesDropped > 0)
        t.write("berth: key installed\r\n\$ ")
        val top = KeyInstall.screenTop(t)
        val before = KeyInstall.counts(KeyInstall.rowsFrom(t, top))
        assertEquals("the old answer on screen is counted before typing", 1, before[KeyInstall.Outcome.INSTALLED])
        t.write("mkdir -p ~/.ssh && printf '%s\\n' 'ssh-ed25519 AAAA' >> ~/.ssh/authorized_keys ...\r\n")
        assertNull("the echoed command is not an answer", KeyInstall.answer(KeyInstall.rowsFrom(t, top), before))
        // The command's echo and its answer push the screen on; the rows from the recorded top still hold both.
        t.write("berth: key installed\r\n\$ ")
        assertEquals(KeyInstall.Outcome.INSTALLED, KeyInstall.answer(KeyInstall.rowsFrom(t, top), before))
        val rows = KeyInstall.rowsFrom(t, top)
        assertEquals("the rows start at the recorded top, which scrolled into the scrollback", 2, rows.count { it.startsWith("berth: key installed") })
    }

    @Test
    fun `a top that scrolled out of the scrollback reads from the oldest row kept`() {
        val t = TerminalEmulator(40, 3, 2, TerminalListenerAdapter())
        val top = KeyInstall.screenTop(t)
        assertEquals(0L, top)
        for (i in 1..20) t.write("line $i\r\n")
        val rows = KeyInstall.rowsFrom(t, top)
        assertEquals("every row the buffer still has", t.bufferRows, rows.size)
        assertTrue(rows.first().startsWith("line"))
    }
}
