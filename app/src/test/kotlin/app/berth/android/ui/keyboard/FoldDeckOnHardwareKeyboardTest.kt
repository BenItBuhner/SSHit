package app.berth.android.ui.keyboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Deck's fold on a hardware keyboard (spec C4): attaching one folds the Deck to its strip,
 * removing it stands the Deck again, each once; between the two the user's own choice holds, and
 * a Stage composed without a keyboard leaves the Deck as it was saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FoldDeckOnHardwareKeyboardTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var attached by mutableStateOf(false)
    private var deckVisible = true
    private val changes = ArrayList<Boolean>()
    private val restoration = StateRestorationTester(compose)

    private fun mount(attachedAtFirst: Boolean) {
        attached = attachedAtFirst
        restoration.setContent { FoldDeckOnHardwareKeyboard(attached) { deckVisible = it; changes += it } }
        compose.waitForIdle()
    }

    @Test
    fun `a keyboard attached at the first composition folds the Deck, and one attached later folds it then`() {
        mount(attachedAtFirst = true)
        assertEquals(listOf(false), changes)
        assertEquals(false, deckVisible)
    }

    @Test
    fun `no keyboard at the first composition leaves the Deck as saved, attaching folds it, removing stands it, once each`() {
        mount(attachedAtFirst = false)
        assertEquals("nothing changed without a keyboard", emptyList<Boolean>(), changes)
        attached = true
        compose.waitForIdle()
        assertEquals(listOf(false), changes)
        attached = false
        compose.waitForIdle()
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `a Deck the user opened under the keyboard stays open across a rotation with the keyboard still attached`() {
        mount(attachedAtFirst = true)
        assertEquals(listOf(false), changes)
        // The strip's tap, Ctrl+Shift+E or the overflow: the Stage's own state, which the fold never rewrites.
        deckVisible = true
        // The configuration change: the Stage is composed again from its saved state, the keyboard still there.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("the fold ran once, on attach, not again on the restore", listOf(false), changes)
        assertEquals(true, deckVisible)
    }
}
