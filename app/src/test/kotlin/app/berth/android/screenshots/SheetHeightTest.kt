package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [assertSheetAtContentHeight] leaves a lazy list where it found it (#22 nit 12). A LazyColumn
 * publishes its position as index × 500 + offset, so undoing the check's scroll by the difference
 * of that value sent a list standing a pixel short of row 6 several rows back up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class SheetHeightTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `the check leaves a scrolled lazy list where it found it`() {
        lateinit var list: LazyListState
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                // 48 dp rows are 126 px at 420 dpi: the list stands 1 px short of row 6, so any scroll crosses a row.
                list = rememberLazyListState(initialFirstVisibleItemIndex = 5, initialFirstVisibleItemScrollOffset = 125)
                BerthSheet(onDismiss = {}) {
                    LazyColumn(state = list) {
                        items(40) { Text("row $it", Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val cut = compose.onAllNodes(hasText("row ", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
            .last { it.boundsInWindow.height > 0f && it.boundsInWindow.height < it.size.height - 1 }
        compose.assertSheetAtContentHeight(cut.config[SemanticsProperties.Text].first().text)
        assertEquals("the list's first row and its offset after the check", 5 to 125, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    }
}
