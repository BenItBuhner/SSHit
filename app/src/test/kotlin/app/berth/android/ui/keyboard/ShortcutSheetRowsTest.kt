package app.berth.android.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshotDir
import app.berth.android.screenshots.captureAudited
import app.berth.android.screenshots.textLayout
import app.berth.android.ui.a11y.MAX_INTERFACE_FONT_SCALE
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.InterfaceTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.ceil

/**
 * C22's table keeps a row to a line on a 411 dp phone: at 1× the action and its keys each on one
 * line, and at the interface's 1.3× cap on no more than the lines a one-line text is given there
 * ([app.berth.android.ui.components.linesAtFontScale], spec A11), since the row is no wider at the
 * cap; under either chord prefix and wherever Ctrl+T and Ctrl+W go, since each changes what a
 * row's keys say. At either scale an action whose line fits in what the keys leave it keeps that
 * line. Whole at the cap is [captureAudited]'s and the screenshot tests' cut checks.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ShortcutSheetRowsTest(private val systemFontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "system font scale {0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(2f))
    }

    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = screenshotDir
    private val suffix = if (systemFontScale > 1f) "-font-scale-2x" else ""

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        outDir.mkdirs()
        RuntimeEnvironment.setFontScale(systemFontScale)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private val linesForOne = ceil(minOf(systemFontScale, MAX_INTERFACE_FONT_SCALE)).toInt()

    @Test
    fun `every row of the shortcut sheet is one line`() {
        var table by mutableStateOf(ChordTable(HardwareKeyboardSettings()))
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { ShortcutSheet(table, onDismiss = {}, onRemap = { _, _ -> }) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Keyboard shortcuts").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val wrapped = mutableListOf<String>()
        val crowded = mutableListOf<String>()
        for (prefix in ChordPrefix.entries) {
            for (ctrlTabKeysReachTerminal in listOf(false, true)) {
                table = ChordTable(HardwareKeyboardSettings().withChordPrefix(prefix), ctrlTabKeysReachTerminal)
                compose.waitForIdle()
                val setting = "$prefix, Ctrl+T and Ctrl+W ${if (ctrlTabKeysReachTerminal) "the shell's" else "the strip's"}"
                wrappedTexts(setting).takeIf { it.isNotEmpty() }?.let { wrapped += "$setting: $it" }
                crowdedActions().takeIf { it.isNotEmpty() }?.let { crowded += "$setting: $it" }
            }
        }
        assertTrue("texts on more than $linesForOne line(s) at ${systemFontScale}x:\n${wrapped.joinToString("\n")}", wrapped.isEmpty())
        assertTrue("actions wrapped at ${systemFontScale}x though their line fits in what the keys leave them:\n${crowded.joinToString("\n")}", crowded.isEmpty())

        table = ChordTable(HardwareKeyboardSettings())
        compose.waitForIdle()
        compose.captureAudited(File(outDir, "shortcut-sheet-rows$suffix.png"))
        captureAt("Split works on a wide screen", "shortcut-sheet-rows-panes")
        captureAt("Unbound combinations always reach the terminal", "shortcut-sheet-rows-terminal")
        table = ChordTable(HardwareKeyboardSettings().withChordPrefix(ChordPrefix.LEADER), true)
        captureAt("Transpose, delete word", "shortcut-sheet-rows-terminal-leader-readline")
    }

    private fun captureAt(text: String, name: String) {
        compose.onNodeWithText(text, substring = true).performScrollTo()
        compose.waitForIdle()
        compose.captureAudited(File(outDir, "$name$suffix.png"))
    }

    private fun wrappedTexts(setting: String): List<String> {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(shortcutRow), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.textLayout() }
        assertTrue("the sheet shows its rows' actions and keys under $setting: ${texts.size}", texts.size >= 40)
        return texts.filter { it.lineCount > linesForOne }.map { "${it.layoutInput.text} (${it.lineCount} lines)" }
    }

    /**
     * The actions on more than one line though their own line fits in the three fifths of the row
     * the keys leave them at the least ([KEYS_SHARE]): the keys are measured to leave such an
     * action its line, so what wraps it is a pixel's rounding ("Modified arrows" at the cap).
     */
    private fun crowdedActions(): List<String> {
        val (padding, gap) = with(compose.density) { ROW_PADDING.toPx() to COLUMN_GAP.toPx() }
        return compose.onAllNodes(shortcutRow, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { row ->
            val said = row.config[SemanticsProperties.ContentDescription].first()
            val action = row.texts().firstOrNull { said.startsWith("${it.layoutInput.text}, ") } ?: return@mapNotNull null
            val room = (row.size.width - 2 * padding) * (1 - KEYS_SHARE) - gap
            val line = action.multiParagraph.intrinsics.maxIntrinsicWidth
            "${action.layoutInput.text} (${action.lineCount} lines, its line ${line.toInt()} px of $room px)".takeIf { action.lineCount > 1 && line <= room }
        }
    }

    private fun SemanticsNode.texts(): List<TextLayoutResult> = listOfNotNull(textLayout()) + children.flatMap { it.texts() }

    private val shortcutRow = SemanticsMatcher("a row of the table, its texts merged under the action and keys it reads") {
        it.config.isMergingSemanticsOfDescendants && SemanticsProperties.ContentDescription in it.config
    } and hasAnyAncestor(isDialog())
}
