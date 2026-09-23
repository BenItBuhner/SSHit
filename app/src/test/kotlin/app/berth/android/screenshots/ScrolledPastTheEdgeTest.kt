package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The audit's exemption for a row scrolled partly out of the window holds what a list that
 * scrolls up and down cuts at the window's edge, and nothing a sideways scroller merely stands
 * against it. A control with no name stands in for the label a cut row loses: on its way out of
 * the foot of a list it is exempt, and so it is in a sideways strip inside that list, cut there by
 * the list; in a sideways strip against the window's top, which the edge does not cut, it is a
 * finding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ScrolledPastTheEdgeTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @get:Rule(order = 2)
    val frames = TemporaryFolder()

    @Test
    fun `a sideways strip's control against the window's top is audited`() {
        show {
            LazyRow(Modifier.fillMaxWidth().height(40.dp)) {
                item { Nameless(Modifier.width(96.dp).height(40.dp)) }
                items(20) { Labelled("tab $it", Modifier.width(96.dp).height(40.dp)) }
            }
        }
        val failure = assertThrows(Throwable::class.java) { compose.captureAudited(frames.newFile("strip.png")) }
        assertTrue(failure.message, failure.message.orEmpty().contains("SpeakableTextPresentCheck"))
    }

    @Test
    fun `a list's row cut at the window's foot is exempt`() {
        show {
            LazyColumn(Modifier.fillMaxSize()) {
                rows(1..16)
                item { Nameless(Modifier.fillMaxWidth().height(56.dp)) }
                rows(17..40)
            }
        }
        compose.captureAudited(frames.newFile("list.png"))
    }

    @Test
    fun `a sideways strip in a list, cut at the window's foot by the list, is exempt`() {
        show {
            LazyColumn(Modifier.fillMaxSize()) {
                rows(1..16)
                item {
                    LazyRow(Modifier.fillMaxWidth()) {
                        items(20) { Nameless(Modifier.width(96.dp).height(56.dp)) }
                    }
                }
                rows(17..40)
            }
        }
        compose.captureAudited(frames.newFile("list-strip.png"))
    }

    private fun LazyListScope.rows(numbers: IntRange) {
        items(numbers.count()) { Labelled("row ${numbers.first + it}", Modifier.fillMaxWidth().height(56.dp)) }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize().background(Berth.colors.surface1)) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Labelled(label: String, modifier: Modifier) {
        Box(modifier.clickable {}, contentAlignment = Alignment.Center) {
            Text(label, style = BerthType.bodyMedium, color = Berth.colors.text1)
        }
    }

    @Composable
    private fun Nameless(modifier: Modifier) {
        Box(modifier.clickable {})
    }
}
