package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.android.ui.theme.MonoFontFeatures
import app.berth.android.ui.theme.PlexSans
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Deck key's hand-set symbol label and its corner hint are set in Mono with the font's
 * ligatures and contextual alternates off ([MonoFontFeatures]), the way every mono span of the
 * interface has them: a key that sends `->` or `--` shows the characters, not the arrow or the long
 * dash JetBrains Mono would fuse them into. A word (`Esc`) stays in Plex with the font's defaults.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DeckKeyLabelTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private fun mount(vararg keys: DeckKey) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                val latch = ModifierLatch()
                val haptics = LocalHapticFeedback.current
                Row {
                    for (key in keys) {
                        DeckKeyView(key, latch, enabled = true, haptics = haptics, modifier = Modifier.width(64.dp).height(44.dp), onAction = {}, onHold = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The style [text] was laid out with, read from its own node under the key that merges it. */
    private fun styleOf(text: String): TextStyle {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        assertTrue("'$text' has no text layout to read", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        return results.single().layoutInput.style
    }

    @Test
    fun `a symbol label is mono with the ligatures off`() {
        mount(DeckKey(tap = DeckAction.Text("->")))
        val style = styleOf("->")
        assertSame(JetBrainsMono, style.fontFamily)
        assertEquals("the label is drawn with the font's ligatures on", MonoFontFeatures, style.fontFeatureSettings)
    }

    @Test
    fun `a symbol hint in the corner is too`() {
        mount(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC), up = DeckAction.Text("--")))
        val hint = styleOf("--")
        assertSame(JetBrainsMono, hint.fontFamily)
        assertEquals(MonoFontFeatures, hint.fontFeatureSettings)
    }

    @Test
    fun `a word stays in Plex with the font's defaults`() {
        mount(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)))
        val style = styleOf("Esc")
        assertSame(PlexSans, style.fontFamily)
        assertNull(style.fontFeatureSettings)
    }
}
