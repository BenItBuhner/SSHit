package app.berth.android.screenshots

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.R
import app.berth.android.createBerthComposeRule
import app.berth.android.session.ManagedTab
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.security.BerthClipboardLocals
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.StageTools
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.terminal.LinkTap
import app.berth.android.ui.terminal.TerminalFonts
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalPaintsCache
import app.berth.android.ui.terminal.TypefaceCache
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalSettings
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The terminal surface of wave three (spec A60, C18, C20, D1) in Berth Dark on a Pixel-class
 * phone, each at 1× and with the system's font size at its largest, where interface text stops at
 * its 1.3× cap and the terminal keeps its own size: the Settings rows the wave adds (the font row
 * that opens a picker, the Nerd Font fallback, the cursor's shape and blink, the scrollback kept,
 * the drag-for-arrows switch), the font picker sheet with every family set in its own face and an
 * import landing through the document picker's result, the sheet a tap on an OSC 8 link opens in
 * its two postures, rectangular selection from the selection bar's overflow, and the Session
 * sheet's Predictive text row (C6) with the grip it lights and the keyboard attributes it changes.
 * Every capture is an accessibility audit, and the screens at the cap are held to no text cut and
 * no button pushed past the sheet's edge. The gestures are driven for real on the canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalSurfaceScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
        // The imports live under the app's files and the caches are static: neither belongs to the next test.
        TerminalFonts.importedDir(context).deleteRecursively()
        TypefaceCache.clear()
        TerminalPaintsCache.clear()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    /** The system's largest font size: interface text at the 1.3× cap, the terminal's cells at 1×. Set before the first composition. */
    private fun atTheCap(cap: Boolean): String {
        if (cap) RuntimeEnvironment.setFontScale(2f)
        return if (cap) "-font-scale-2x" else ""
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** Real time passes (for the frame worker and the faces loading off the main thread) while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeout: Long = 5_000, substring: Boolean = false) {
        try {
            compose.waitUntil(timeout) { shown(text, substring) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val texts = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } ?: emptyList() }
            throw AssertionError("'$text' never showed; the texts on screen were $texts", e)
        }
    }

    private fun waitForNoText(text: String, timeout: Long = 5_000) = compose.waitUntil(timeout) { !shown(text) }

    private fun clipboardText(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private val application: Application get() = context as Application

    /**
     * Every button in the sheet's window ends inside the window's width: three across at the cap
     * (Cancel, Open anyway, Copy) must still fit, since a button pushed past the edge is one that
     * cannot be pressed. The measured size is read from the unclipped position, not the bounds in
     * the root, which are clipped to it.
     */
    private fun assertSheetButtonsInside() {
        val width = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        val buttons = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        assertTrue("the sheet has buttons", buttons.isNotEmpty())
        for (button in buttons) {
            val label = button.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: button.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            val right = button.positionInRoot.x + button.size.width
            assertTrue("'$label' runs past the window's edge: ends at $right of $width", right <= width + 0.5f)
        }
    }

    // ---- the Stage and the canvas' geometry ------------------------------------------------------------------

    /** The Stage as the shell mounts it, inside the clipboard locals that make every Copy Berth's own, with the test's tools. */
    @Composable
    private fun Stage(tab: ManagedTab?, tools: StageTools) {
        BerthClipboardLocals(graph.clipboard) {
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
        }
    }

    /** The paints the canvas draws with, for the cell size in pixels: the terminal's size is its own unless the font follows the system, whatever the interface's scale. */
    private fun paints(): TerminalPaints {
        val res = context.resources
        val font = runBlocking { graph.settings.terminalFont.first() }
        return TerminalPaintsCache.get(context, font, res.displayMetrics.density, if (font.followSystemScale) res.configuration.fontScale else 1f)
    }

    /** The centre of the view cell at [row], [col] in the canvas node's coordinates. */
    private fun cellCenter(row: Int, col: Int): Offset {
        val p = paints()
        return Offset((col + 0.5f) * p.cellWidth, (row + 0.5f) * p.cellHeight)
    }

    /** The view row and column where [token] first shows on the screen. */
    private fun cellOf(session: TerminalSession, token: String): Pair<Int, Int> {
        val rows = session.emulator.screenText()
        val row = rows.indexOfFirst { it.contains(token) }
        assertTrue("'$token' is on screen in $rows", row >= 0)
        return row to rows[row].indexOf(token)
    }

    /** Waits for the canvas to size the grid to itself, so cells map to pixels. */
    private fun awaitGrid(session: TerminalSession) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        val p = paints()
        compose.waitUntil(5_000) {
            val size = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().size
            val cols = (size.width / p.cellWidth).toInt()
            val rows = (size.height / p.cellHeight).toInt()
            cols >= 2 && session.emulator.cols == cols && session.emulator.rows == rows
        }
        settle(300)
    }

    /** Holds a finger at [at] past the long-press timeout, so the word under it is selected; the finger stays down. */
    private fun longPress(at: Offset, selected: () -> Boolean) {
        compose.onNodeWithTag(TerminalTag).performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5_000, selected)
    }

    /** Homelab's tab reading Live, with its Deck and no shell, so output written to the emulator is what a program printed. */
    private fun stageLiveHomelab(): Pair<TerminalSession, StageTools> {
        StageFixture.seed(graph)
        val session = StageFixture.liveHomelab().also { sessions += it }
        val tools = StageTools()
        themed { Stage(session, tools) }
        awaitGrid(session)
        return session to tools
    }

    // ---- Settings › Terminal (spec C20) ---------------------------------------------------------------------------

    private fun settingsTerminalRows(cap: Boolean) {
        val suffix = atTheCap(cap)
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Scrollback").performScrollTo()
        compose.waitForIdle()
        // The rows with their defaults: the default family on the font row, the fallback on, a block
        // cursor that does not blink, and the spec's 10,000 lines of history.
        compose.onNodeWithText("Terminal font").assertExists()
        compose.onNodeWithText(TerminalFonts.DEFAULT).assertExists()
        compose.onNodeWithText("Nerd Font fallback").assertExists()
        compose.onNodeWithText("Cursor").assertIsDisplayed()
        compose.onNodeWithText("Block").assertIsSelected()
        compose.onNodeWithText("Blink").assertIsDisplayed()
        compose.onNodeWithText("10,000 lines").assertIsDisplayed()
        assertEquals(TerminalSettings.DEFAULT_SCROLLBACK, graph.viewModel.terminalSettings.value.scrollbackLines)
        settle(200)
        capture("settings-terminal-rows$suffix")
        compose.assertNoTextCut("the Settings screen's Terminal rows${if (cap) " at the interface's font cap" else ""}")

        // Underline: the setting follows, and stands until a program asks for its own cursor.
        compose.onNodeWithText("Underline").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.cursorShape == "underline" }
        compose.onNodeWithText("Underline").assertIsSelected()
        compose.onNodeWithText("Blink").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.cursorBlink }
        // Scrollback opens its choices; 20,000 is written to the terminal document.
        compose.onNodeWithText("Scrollback").performClick()
        waitForText("20,000 lines")
        compose.onNodeWithText("20,000 lines").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalSettings.value.scrollbackLines == 20_000 }
        waitForNoText("50,000 lines")
        compose.onNodeWithText("20,000 lines").assertIsDisplayed()

        // The drag-for-arrows switch among the gestures, off by default (spec D1); on, the document says so.
        compose.onNodeWithText("Drag for arrow keys").performScrollTo()
        compose.waitForIdle()
        assertFalse(graph.viewModel.terminalSettings.value.horizontalDragArrows)
        settle(200)
        capture("settings-gestures-drag-arrows$suffix")
        compose.onNodeWithText("Drag for arrow keys").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalSettings.value.horizontalDragArrows }
        assertEquals("the other terminal settings stand", 20_000, graph.viewModel.terminalSettings.value.scrollbackLines)
    }

    @Test
    fun `settings terminal rows`() = settingsTerminalRows(cap = false)

    @Test
    fun `settings terminal rows at the 1,3 cap`() = settingsTerminalRows(cap = true)

    // ---- the font picker (spec C20, Fonts) ------------------------------------------------------------------------

    /** A face the user imported before: Hack's bold under a Nerd Font's name, so the list has an import with one face, and the fallback a source. */
    private fun importedNerdFont() = runBlocking {
        val result = TerminalFonts.import(context, fileUri("nerd.ttf", R.font.hack_bold))
        assertTrue(result.toString(), result is TerminalFonts.ImportResult.Done)
        val family = TerminalFonts.imported(context).single()
        File(family.dir, "family.txt").writeText("Hack Nerd Font Mono")
        TypefaceCache.clear()
        TerminalPaintsCache.clear()
    }

    /** The OpenType feature setting on every sample line's span: the terminal's own `liga, calt` with ligatures on and `-liga, -calt` with them off, said outright either way. */
    private fun sampleFeatureSettings(): Set<String?> =
        compose.onAllNodes(hasText(TerminalFonts.SAMPLE, substring = true)).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .flatMap { text -> text.spanStyles.filter { range -> text.text.substring(range.start, range.end) == TerminalFonts.SAMPLE }.map { it.item.fontFeatureSettings } }
            .toSet()

    /** A font resource as a file the document picker could have handed back. */
    private fun fileUri(name: String, resource: Int): Uri {
        val file = File(context.cacheDir, name)
        context.resources.openRawResource(resource).use { file.writeBytes(it.readBytes()) }
        return Uri.fromFile(file)
    }

    private fun fontPicker(cap: Boolean) {
        val suffix = atTheCap(cap)
        StageFixture.seed(graph)
        importedNerdFont()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Terminal font").performScrollTo().performClick()
        waitForText("Each family set in its own face")
        // Bundled families first, the device's monospace, then the import, each under the sample line.
        for (name in listOf("JetBrains Mono", "IBM Plex Mono", "Fira Code", "Hack", "Source Code Pro", TerminalFonts.SYSTEM, "Hack Nerd Font Mono")) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().let { assertTrue("$name is listed", it.isNotEmpty()) }
        }
            assertEquals(7, compose.onAllNodes(hasText(TerminalFonts.SAMPLE, substring = true)).fetchSemanticsNodes().size)
        // Each bundled family names its licence: four under the OFL, Hack under the MIT.
        compose.onAllNodesWithText("Bundled \u00B7 OFL", substring = true).assertCountEquals(4)
        compose.onAllNodesWithText("Bundled \u00B7 MIT", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Imported \u00B7 one face, the rest made from it", substring = true).assertExists()
        compose.onNodeWithContentDescription("Remove Hack Nerd Font Mono").assertExists()
        // The faces load off the main thread; the samples are set in them once here.
        settle(700)
        capture("settings-font-picker$suffix")
        compose.assertNoTextCut("the font picker${if (cap) " at the interface's font cap" else ""}")

        // The samples show what the terminal would draw: with the Ligatures switch off, every sample's
        // `=>` and `->` are two glyphs each, the terminal's own feature setting on the span (review #16).
        assertEquals(setOf<String?>("liga, calt"), sampleFeatureSettings())
        graph.viewModel.setTerminalFont(graph.viewModel.terminalFont.value.copy(ligatures = false))
        compose.waitUntil(5_000) { sampleFeatureSettings() == setOf<String?>("-liga, -calt") }
        graph.viewModel.setTerminalFont(graph.viewModel.terminalFont.value.copy(ligatures = true))
        compose.waitUntil(5_000) { sampleFeatureSettings() == setOf<String?>("liga, calt") }

        // Fira Code chosen: the setting follows and the row behind the sheet reads it.
        compose.onNodeWithText("Fira Code").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.family == "Fira Code" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fira Code").fetchSemanticsNodes().size == 2 }

        // Import font file asks the document picker for a file of any type, since phones name a font's
        // type variously; the file it hands back is read by its own name table, here a proportional
        // face the interface uses, which lands as its own family in the list and is warned about. It is
        // not chosen: an import is a file arriving, a choice is a tap on a row (review #16), so the
        // terminal stays in Fira Code.
        compose.onNodeWithText("Import font file").performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("the document picker was asked", request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals("*/*", request.intent.type)
        shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(fileUri("picked.ttf", R.font.ibm_plex_sans_regular)))
        waitForText("Imported IBM Plex Sans, regular", timeout = 10_000)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("IBM Plex Sans").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("an import is not a choice", "Fira Code", graph.viewModel.terminalFont.value.family)
        compose.onNodeWithText("not monospaced, columns will drift", substring = true).assertExists()
        assertEquals(8, compose.onAllNodes(hasText(TerminalFonts.SAMPLE, substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Imported IBM Plex Sans, regular").performScrollTo()
        settle(700)
        capture("settings-font-picker-imported$suffix")
        // The longest note a row can carry, an import of one proportional face, still fits its lines.
        compose.assertNoTextCut("the font picker with an import${if (cap) " at the interface's font cap" else ""}")
        assertEquals("Fira Code", graph.viewModel.terminalFont.value.family)

        // Chosen by its row, as any family is; then removed while in use: the terminal goes back to the default family and the note says so.
        compose.onNodeWithText("IBM Plex Sans").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.family == "IBM Plex Sans" }
        compose.onNodeWithContentDescription("Remove IBM Plex Sans").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.family == TerminalFonts.DEFAULT }
        waitForText("Removed IBM Plex Sans")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("IBM Plex Sans").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("Hack Nerd Font Mono"), TerminalFonts.imported(context).map { it.name })

        // Done closes the sheet; the row reads the family in use.
        compose.onNodeWithText("Hack").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.family == "Hack" }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        waitForNoText("Each family set in its own face")
        compose.onNodeWithText("Hack").assertIsDisplayed()
    }

    @Test
    fun `font picker sheet`() = fontPicker(cap = false)

    @Test
    fun `font picker sheet at the 1,3 cap`() = fontPicker(cap = true)

    // ---- OSC 8 links (spec A60) ----------------------------------------------------------------------------------

    private fun link(url: String, text: String) = "\u001b]8;;$url\u001b\\$text\u001b]8;;\u001b\\"

    private fun linkTap(cap: Boolean) {
        val suffix = atTheCap(cap)
        val (session, tools) = stageLiveHomelab()
        val canvas = compose.onNodeWithTag(TerminalTag)
        val pr = "https://git.homelab.lan/berth/berth/pulls/12"
        val docs = "https://caddyserver.com/docs/"
        val dressed = "https://evil.example/login"
        // A command at the restored prompt, with three links in its output: one whose text is a label,
        // one that is its own address, and one dressed as another site's.
        session.emulator.write("\u001b[A\u001b[20Gcat NOTES.md\r\n")
        session.emulator.write("${link(pr, "PR #12")} is ready for review\r\n")
        session.emulator.write("docs at ${link(docs, docs)}\r\n")
        session.emulator.write("release notes: ${link(dressed, "https://github.com/berth/releases")}\r\n")
        session.emulator.write("ben@homelab:~/srv$ ")
        compose.waitUntil(5_000) { session.emulator.screenText().any { it.startsWith("release notes") } }
        settle(400)

        // Held, the link is underlined in the theme's links colour, before the finger has lifted.
        val (row, col) = cellOf(session, "PR #12")
        canvas.performTouchInput { down(cellCenter(row, col + 2)) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertNull(tools.pendingLink)
        capture("terminal-link-pressed$suffix")
        // Lifted, the tap opens the sheet: the address in full, the caption naming the text it wore and where it goes, Open filled.
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.pendingLink != null }
        assertEquals(LinkTap(pr, "PR #12"), tools.pendingLink)
        waitForText("Open link")
        compose.onNodeWithText("Shown as \u201CPR #12\u201D, goes to git.homelab.lan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Link address, $pr").assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onAllNodesWithText("Open anyway").assertCountEquals(0)
        settle(300)
        capture("terminal-link-open-sheet$suffix")
        compose.assertNoTextCut("the link sheet${if (cap) " at the interface's font cap" else ""}", within = isDialog())
        assertSheetButtonsInside()
        compose.onNodeWithText("Open").performClick()
        compose.waitUntil(5_000) { tools.pendingLink == null }
        val opened = shadowOf(application).nextStartedActivity
        assertNotNull("Open hands the address to the system", opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(pr, opened.dataString)
        waitForNoText("Open link")

        // The address itself as the text: the caption names the host alone. Copy puts it on the clipboard and says so.
        val (docsRow, docsCol) = cellOf(session, docs)
        canvas.performTouchInput { click(cellCenter(docsRow, docsCol + 5)) }
        waitForText("Goes to caddyserver.com")
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        assertEquals(docs, clipboardText())
        assertNull(tools.pendingLink)
        assertNull("Copy opens nothing", shadowOf(application).nextStartedActivity)
        compose.mainClock.advanceTimeBy(1_600)
        waitForNoText("Copied")

        // Dressed as another site's address: the sheet is a warning, its caption showing the text as it
        // was worn, Cancel carries the weight and Open anyway stands plain beside it.
        val (dressedRow, dressedCol) = cellOf(session, "https://github.com/berth/releases")
        canvas.performTouchInput { click(cellCenter(dressedRow, dressedCol + 5)) }
        waitForText("Shown as \u201Chttps://github.com/berth/releases\u201D, but goes to evil.example")
        compose.onNodeWithContentDescription("Link address, $dressed").assertIsDisplayed()
        compose.onNodeWithText("Open anyway").assertIsDisplayed()
        compose.onAllNodesWithText("Open").assertCountEquals(0)
        settle(300)
        capture("terminal-link-open-sheet-warning$suffix")
        compose.assertNoTextCut("the link warning sheet${if (cap) " at the interface's font cap" else ""}", within = isDialog())
        assertSheetButtonsInside()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingLink == null }
        assertNull("Cancel opens nothing", shadowOf(application).nextStartedActivity)
        // Open anyway is the user's call, and opens the address the link carries, not the one it wore.
        canvas.performTouchInput { click(cellCenter(dressedRow, dressedCol + 5)) }
        waitForText("Open anyway")
        compose.onNodeWithText("Open anyway").performClick()
        compose.waitUntil(5_000) { tools.pendingLink == null }
        assertEquals(dressed, shadowOf(application).nextStartedActivity?.dataString)

        // A tap beside a link is the tap it always was: nothing to look at.
        canvas.performTouchInput { click(cellCenter(row, col + 20)) }
        settle(200)
        assertNull(tools.pendingLink)
        assertFalse(shown("Open link"))
    }

    @Test
    fun `link tap opens the confirmation sheet`() = linkTap(cap = false)

    @Test
    fun `link tap opens the confirmation sheet at the 1,3 cap`() = linkTap(cap = true)

    // ---- rectangular selection (spec C18, review #9) ---------------------------------------------------------------

    private fun rectangularSelection(cap: Boolean) {
        val suffix = atTheCap(cap)
        val (session, tools) = stageLiveHomelab()
        val canvas = compose.onNodeWithTag(TerminalTag)
        val block = "caddy:2\ngitea/gitea\npostgres:16"

        // The fixture's table wraps at a phone's width, so the screen is cleared and the table printed
        // again narrowed to three columns, the way a user reads it on a phone: every row on one line,
        // so a block cut from it is the column it looks like.
        session.emulator.write("\u001b[A\u001b[20Gclear\r\n\u001b[H\u001b[2J")
        session.emulator.write("ben@homelab:~/srv$ docker compose ps --format 'table {{.Name}}\\t{{.Image}}\\t{{.Status}}'\r\n")
        session.emulator.write("NAME        IMAGE               STATUS\r\n")
        session.emulator.write("caddy       caddy:2             Up 3 days\r\n")
        session.emulator.write("gitea       gitea/gitea:1.22    Up 3 days\r\n")
        session.emulator.write("postgres    postgres:16         Up 3 days\r\n")
        session.emulator.write("ben@homelab:~/srv$ ")
        compose.waitUntil(5_000) { session.emulator.screenText().any { it.startsWith("postgres") } }
        settle(400)

        // A long-press on caddy:2 and a drag two rows down: the stream from that word to postgres:16, three lines.
        val (row, col) = cellOf(session, "caddy:2")
        longPress(cellCenter(row, col + 2)) { tools.selection.active }
        assertEquals("caddy:2", tools.selection.text(session.emulator))
        canvas.performTouchInput { moveTo(cellCenter(row + 2, col + 2)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.summary == "3 lines" }
        assertFalse(tools.selection.rectangular)
        assertTrue(tools.selection.text(session.emulator).lines()[1].startsWith("gitea       gitea/gitea:1.22"))

        // The bar's overflow: Select all and Rectangular, off; no Open link, since the selection is not one.
        compose.onNodeWithContentDescription("Selection options").performClick()
        waitForText(StageTools.RECTANGULAR)
        compose.onNodeWithText("Select all").assertIsDisplayed()
        compose.onAllNodesWithText("Open link").assertCountEquals(0)
        settle(200)
        capture("terminal-selection-overflow$suffix")

        // Rectangular: the same two corners read as the block between them, cut column-wise from every
        // row, the IMAGE column of the table, and the summary counts cells.
        compose.onNodeWithText(StageTools.RECTANGULAR).performClick()
        compose.waitUntil(5_000) { tools.selection.rectangular && tools.selection.summary == "3 \u00D7 11 cells" }
        assertEquals(block, tools.selection.text(session.emulator))
        compose.onNodeWithContentDescription("Selection, 3 \u00D7 11 cells").assertIsDisplayed()
        settle(200)
        capture("terminal-selection-rectangular$suffix")
        compose.assertNoTextCut("the selection bar over a block${if (cap) " at the interface's font cap" else ""}")
        // Opened again, the toggle reads on.
        compose.onNodeWithContentDescription("Selection options").performClick()
        waitForText(StageTools.RECTANGULAR)
        compose.onNodeWithText(StageTools.RECTANGULAR).assertIsSelected()
        settle(200)
        capture("terminal-selection-rectangular-overflow$suffix")
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntil(5_000) { tools.selection.range?.start?.row == 0 }
        // Select all is every row whole, whichever way the toggle stands.
        assertTrue(tools.selection.text(session.emulator).startsWith("ben@homelab:~/srv$ docker compose ps"))
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.waitUntil(5_000) { !tools.selection.active }
        assertTrue("the toggle outlives the selection", tools.selection.rectangular)

        // Copy of a block: one line per row.
        longPress(cellCenter(row, col + 2)) { tools.selection.active }
        canvas.performTouchInput { moveTo(cellCenter(row + 2, col + 2)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.summary == "3 \u00D7 11 cells" }
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        assertEquals(block, clipboardText())
        assertFalse(tools.selection.active)
        compose.mainClock.advanceTimeBy(1_600)
        waitForNoText("Copied")

        // The tab's next selection begins as a block: the word Up, then the column of Ups two rows down.
        val (upRow, upCol) = cellOf(session, "Up 3 days")
        longPress(cellCenter(upRow, upCol)) { tools.selection.active }
        assertEquals("Up", tools.selection.text(session.emulator))
        canvas.performTouchInput { moveTo(cellCenter(upRow + 2, upCol)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.summary == "3 \u00D7 2 cells" }
        assertEquals("Up\nUp\nUp", tools.selection.text(session.emulator))

        // Off again from the overflow: the same corners read as the stream, in place, and stay off.
        compose.onNodeWithContentDescription("Selection options").performClick()
        waitForText(StageTools.RECTANGULAR)
        compose.onNodeWithText(StageTools.RECTANGULAR).performClick()
        compose.waitUntil(5_000) { !tools.selection.rectangular && tools.selection.summary == "3 lines" }
        val stream = tools.selection.text(session.emulator).lines()
        assertEquals(3, stream.size)
        assertTrue(stream[0], stream[0].startsWith("Up 3 days"))
        assertEquals("gitea       gitea/gitea:1.22    Up 3 days", stream[1])
        assertTrue(stream[2], stream[2].endsWith("Up"))
    }

    @Test
    fun `rectangular selection from the bar's overflow`() = rectangularSelection(cap = false)

    @Test
    fun `rectangular selection from the bar's overflow at the 1,3 cap`() = rectangularSelection(cap = true)

    // ---- predictive text (spec C6, C4; review #20) --------------------------------------------------------------

    private val predictiveRow = hasText("Predictive text") and isToggleable()

    /**
     * The attributes the terminal on stage hands the keyboard, read the way the keyboard reads them
     * on a restart: through the Compose view's own input connection, with the canvas focused.
     */
    private fun keyboardAttributes(): EditorInfo {
        val canvas = compose.onNodeWithTag(TerminalTag)
        canvas.requestFocus()
        canvas.assertIsFocused()
        compose.waitForIdle()
        val view = (canvas.fetchSemanticsNode().root as ViewRootForTest).view
        val info = EditorInfo()
        assertNotNull("the focused terminal has a keyboard connection", view.onCreateInputConnection(info))
        return info
    }

    private fun predictiveText(cap: Boolean) {
        val suffix = atTheCap(cap)
        StageFixture.seed(graph)
        val session = StageFixture.liveHomelab().also { sessions += it }
        val tools = StageTools()
        var sheet by mutableStateOf(true)
        // The Stage under the Session sheet, as the shell mounts them.
        themed {
            Stage(session, tools)
            if (sheet) SessionSheet(graph.viewModel, session, onDismiss = { sheet = false }, onSwitch = {}, onEditHost = {}, onNewSession = {})
        }
        awaitGrid(session)
        waitForText("Predictive text")
        // Half height by default (C6), the row starts under the fold; the handle's Expand is the reader's drag-up.
        val expandable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).fetchSemanticsNodes()
        if (expandable.isNotEmpty()) compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).performSemanticsAction(SemanticsActions.Expand)
        settle(500)
        compose.onNode(predictiveRow).assertIsDisplayed().assertIsOff()
        // Off is the default for every tab: the keyboard is told no suggestions, and the flag is nobody's yet.
        assertTrue(graph.viewModel.predictiveTextTabIds.value.isEmpty())

        // On: the row reads on, the flag is this tab's, and the row's own text is whole at either scale.
        compose.onNode(predictiveRow).performClick()
        compose.waitUntil(5_000) { session.id in graph.viewModel.predictiveTextTabIds.value }
        compose.onNode(predictiveRow).assertIsOn()
        settle(300)
        capture("session-sheet-predictive-text$suffix")
        compose.assertNoTextCut("the Predictive text row${if (cap) " at the interface's font cap" else ""}", within = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

        // Under the sheet the grip has turned accent (C4), and the keyboard is told plain text it may suggest for.
        sheet = false
        waitForNoText("Predictive text")
        settle(300)
        capture("stage-predictive-text-grip$suffix")
        val on = keyboardAttributes()
        assertEquals("plain text, nothing withheld from the keyboard", InputType.TYPE_CLASS_TEXT, on.inputType)
        assertNotEquals("and still nothing learned", 0, on.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)

        // Off again from the flag alone (the row is one way to it): the next read is the privacy default.
        graph.viewModel.setPredictiveText(session.id, false)
        compose.waitForIdle()
        val off = keyboardAttributes()
        assertNotEquals(0, off.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, off.inputType and InputType.TYPE_MASK_VARIATION)
    }

    @Test
    fun `predictive text is the tab's row on the Session sheet, lights the grip and changes what the keyboard is told`() = predictiveText(cap = false)

    @Test
    fun `predictive text row and grip at the 1,3 cap`() = predictiveText(cap = true)
}
