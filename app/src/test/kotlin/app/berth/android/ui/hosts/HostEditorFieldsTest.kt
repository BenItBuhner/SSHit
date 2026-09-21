package app.berth.android.ui.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor's Tags and Environment fields (spec C10) and the host's lists they stand for, both ways. */
class HostEditorFieldsTest {
    @Test
    fun `tags split on commas and keep each once in the order written`() {
        assertEquals(listOf("prod", "homelab", "web"), HostEditorFields.parseTags("prod, homelab,web"))
        assertEquals(listOf("prod"), HostEditorFields.parseTags("prod, , Prod,PROD"))
        assertEquals(emptyList<String>(), HostEditorFields.parseTags(""))
        assertEquals(emptyList<String>(), HostEditorFields.parseTags(" , "))
    }

    @Test
    fun `tags round-trip through the field's text`() {
        val tags = listOf("prod", "eu-west", "k8s")
        assertEquals("prod, eu-west, k8s", HostEditorFields.tagsText(tags))
        assertEquals(tags, HostEditorFields.parseTags(HostEditorFields.tagsText(tags)))
        assertEquals("", HostEditorFields.tagsText(emptyList()))
    }

    @Test
    fun `environment takes one NAME=value per line`() {
        assertEquals(
            mapOf("LANG" to "C.UTF-8", "EDITOR" to "vim", "_x1" to "a=b=c"),
            HostEditorFields.parseEnvironment("LANG=C.UTF-8\nEDITOR = vim\n_x1=a=b=c"),
        )
    }

    @Test
    fun `environment skips blank lines and comments, and the last of a repeated name wins`() {
        assertEquals(mapOf("A" to "2", "B" to ""), HostEditorFields.parseEnvironment("\n# from the old host\nA=1\n\nA=2\nB=\n"))
        assertEquals(emptyMap<String, String>(), HostEditorFields.parseEnvironment(""))
        assertEquals(emptyMap<String, String>(), HostEditorFields.parseEnvironment("   \n# only a note"))
    }

    @Test
    fun `environment refuses what a shell would`() {
        assertNull("no equals", HostEditorFields.parseEnvironment("LANG"))
        assertNull("empty name", HostEditorFields.parseEnvironment("=value"))
        assertNull("digit first", HostEditorFields.parseEnvironment("1ABC=x"))
        assertNull("space in the name", HostEditorFields.parseEnvironment("MY VAR=x"))
        assertNull("dash in the name", HostEditorFields.parseEnvironment("MY-VAR=x"))
        assertNull("one bad line spoils the field", HostEditorFields.parseEnvironment("LANG=C\nbroken\nTERM=xterm"))
    }

    @Test
    fun `the environment's problem names the first bad line by its number, blank lines and notes counted`() {
        assertNull(HostEditorFields.environmentProblem("LANG=C\n\n# a note\nTERM=xterm"))
        val bad = HostEditorFields.environmentProblem("LANG=C.UTF-8\n1BAD=x")!!
        assertEquals(2, bad.number)
        assertEquals("Line 2: 1BAD is not a name a shell takes.", bad.message)
        assertEquals("Line 2: 1BAD is not a name a shell takes. " + HostEditorFields.ENVIRONMENT_HELP, bad.helper)
        assertEquals("Line 4: broken is not NAME=value.", HostEditorFields.environmentProblem("\n# from the old host\nA=1\nbroken\nB=2")!!.message)
        assertEquals("the name, not the whole line, when the name is the fault", "Line 1: MY VAR is not a name a shell takes.", HostEditorFields.environmentProblem("MY VAR=x")!!.message)
        assertEquals("an empty name is not NAME=value", "Line 1: =value is not NAME=value.", HostEditorFields.environmentProblem("=value")!!.message)
        assertEquals("the first bad line, not the last", 2, HostEditorFields.environmentProblem("A=1\nbroken\n2BAD=x")!!.number)
        assertEquals(
            "a long line is cut to its head",
            "Line 1: a line that goes on and\u2026 is not NAME=value.",
            HostEditorFields.environmentProblem("a line that goes on and on without an equals sign anywhere")!!.message,
        )
    }

    @Test
    fun `environment round-trips through the field's text`() {
        val env = linkedMapOf("LANG" to "C.UTF-8", "TZ" to "Europe/Berlin", "EMPTY" to "")
        assertEquals("LANG=C.UTF-8\nTZ=Europe/Berlin\nEMPTY=", HostEditorFields.environmentText(env))
        assertEquals(env, HostEditorFields.parseEnvironment(HostEditorFields.environmentText(env)))
    }
}
