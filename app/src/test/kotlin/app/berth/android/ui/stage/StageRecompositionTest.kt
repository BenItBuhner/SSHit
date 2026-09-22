package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import app.berth.terminal.CellPos
import app.berth.terminal.SelectionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What re-runs on the Stage when something changes under it, counted from the Compose runtime's own
 * trace hooks: every composable body that executes, rather than skips, reports its name. Output, a
 * scroll through history and a selection's drag reach the canvas's draw alone; a title is the strip's
 * tab's to show; an edit to another host is the body's to look at and not the terminal's; a tab switch
 * composes the incoming terminal once and leaves both emulators as they stood. The body, the canvas and
 * the Deck are the expensive parts of the Stage, so those are the counts held here.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class StageRecompositionTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()
    private val runs = Runs()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
        Composer.setTracer(runs)
    }

    @After
    fun tearDown() {
        Composer.setTracer(null)
        sessions.forEach { it.close() }
        graph.close()
    }

    @Test
    fun `output re-runs no composable on the Stage`() {
        val live = stageHomelab()
        repeat(CHUNKS) { i ->
            write(live, "\u001b[01;34mdir-$i\u001b[0m  file-$i.txt  \u001b[32mscript-$i.sh\u001b[0m\r\n")
            compose.waitForIdle()
        }
        report("output x$CHUNKS")
        assertEquals("composables run", 0, runs.total())
    }

    @Test
    fun `a title the shell sets re-runs its own tab in the strip and nothing else of the strip`() {
        stageHomelab()
        val strip = graph.sessions.get("s-homelab")!!
        repeat(TITLES) { i ->
            write(strip, "\u001b]2;homelab: step $i\u0007")
            compose.waitForIdle()
        }
        assertEquals("the record took every title", "homelab: step ${TITLES - 1}", strip.record.value.title)
        report("strip title x$TITLES")
        assertEquals("TabItem", TITLES, runs[TAB_ITEM])
        assertEquals("TabStrip", 0, runs[TAB_STRIP])
        assertEquals("TabHeader", 0, runs[TAB_HEADER])
        assertEquals("StageScreen", 0, runs[STAGE_SCREEN])
    }

    @Test
    fun `a scroll through history re-runs nothing of the body`() {
        val tools = StageTools()
        val live = stageHomelab(tools = tools, before = { s -> repeat(HISTORY) { i -> write(s, "line $i\r\n") } })
        repeat(SCROLLS) {
            tools.viewport.scrollOffset += 1
            compose.waitForIdle()
        }
        assertEquals(SCROLLS, tools.viewport.scrollOffset)
        assertTrue(live.emulator.scrollbackSize >= SCROLLS)
        report("scroll x$SCROLLS")
        assertEquals("StageBody", 0, runs[STAGE_BODY])
        assertEquals("TerminalCanvas", 0, runs[TERMINAL_CANVAS])
        assertEquals("Deck", 0, runs[DECK])
    }

    @Test
    fun `a selection dragged across the screen re-runs its bar and nothing of the body`() {
        val tools = StageTools()
        val live = stageHomelab(tools = tools)
        synchronized(live.emulator.lock) { tools.selection.start(live.emulator, CellPos(0, 0), SelectionMode.CELL) }
        compose.waitForIdle()
        runs.reset()
        repeat(DRAGS) { i ->
            synchronized(live.emulator.lock) { tools.selection.extendTo(live.emulator, CellPos(i / 10, 1 + i % 10 * 3)) }
            compose.waitForIdle()
        }
        report("selection drag x$DRAGS")
        assertEquals("StageBody", 0, runs[STAGE_BODY])
        assertEquals("TerminalCanvas", 0, runs[TERMINAL_CANVAS])
        assertEquals("Deck", 0, runs[DECK])
    }

    @Test
    fun `a tab switch composes the incoming terminal once and leaves both emulators as they were`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        val a = StageFixture.liveHomelab().also { sessions += it }
        val b = StageFixture.liveQuick().also { sessions += it }
        var tab by mutableStateOf(a)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().isNotEmpty() }
        tab = b
        compose.waitForIdle()
        tab = a
        compose.waitForIdle()
        val emulators = a.emulator to b.emulator
        val grids = (a.emulator.cols to a.emulator.rows) to (b.emulator.cols to b.emulator.rows)
        runs.reset()
        repeat(SWITCHES) {
            tab = if (tab === a) b else a
            compose.waitForIdle()
        }
        report("switch x$SWITCHES")
        assertEquals("one canvas composed a switch", SWITCHES, runs[TERMINAL_CANVAS])
        assertSame(emulators.first, a.emulator)
        assertSame(emulators.second, b.emulator)
        assertEquals("the grids stood: no resize, no reflow", grids, (a.emulator.cols to a.emulator.rows) to (b.emulator.cols to b.emulator.rows))
    }

    /**
     * Homelab live on stage beside the fixture's strip. Its host carries its own size, as any host
     * pinched once does (a pinch writes the host's size, review #15). [before] runs on the session
     * before the Stage composes.
     */
    private fun stageHomelab(tools: StageTools? = null, before: (TerminalSession) -> Unit = {}): TerminalSession {
        StageFixture.seed(graph)
        runBlocking {
            val homelab = graph.hosts.get("homelab")!!
            graph.hosts.upsert(homelab.copy(appearance = AppearanceOverride(fontSizeSp = 14)))
        }
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab().also { sessions += it }
        before(live)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    if (tools != null) {
                        StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
                    } else {
                        StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { graph.viewModel.hosts.value.any { it.id == "homelab" && it.appearance.fontSizeSp == 14 } }
        compose.waitForIdle()
        runs.reset()
        return live
    }

    /** Bytes as the read loop hands them over: on a thread of its own, into the emulator. */
    private fun write(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val t = Thread { session.emulator.write(bytes) }
        t.start()
        t.join()
    }

    private fun report(what: String) {
        val watched = listOf(STAGE_SCREEN, STAGE_BODY, TERMINAL_CANVAS, DECK, TAB_HEADER, TAB_STRIP, TAB_ITEM, SCROLLED_PILL)
        println("RECOMPOSE $what: " + watched.joinToString(" ") { "${it.substringAfterLast('.')}=${runs[it]}" } + " all=${runs.total()}")
    }

    /** Every composable body that ran, by name; the runtime calls it on the composing thread. */
    private class Runs : CompositionTracer {
        private val byName = ConcurrentHashMap<String, AtomicInteger>()

        override fun isTraceInProgress(): Boolean = true

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            byName.getOrPut(info.substringBefore(" (")) { AtomicInteger() }.incrementAndGet()
        }

        override fun traceEventEnd() = Unit

        operator fun get(name: String): Int = byName[name]?.get() ?: 0

        fun total(): Int = byName.values.sumOf { it.get() }

        fun reset() = byName.clear()
    }

    private companion object {
        const val TITLES = 10
        const val CHUNKS = 20
        const val HISTORY = 120
        const val SCROLLS = 20
        const val DRAGS = 20
        const val SWITCHES = 10

        const val STAGE_SCREEN = "app.berth.android.ui.stage.StageScreen"
        const val STAGE_BODY = "app.berth.android.ui.stage.StageBody"
        const val TERMINAL_CANVAS = "app.berth.android.ui.terminal.TerminalCanvas"
        const val DECK = "app.berth.android.ui.stage.Deck"
        const val TAB_HEADER = "app.berth.android.ui.tabs.TabHeader"
        const val TAB_STRIP = "app.berth.android.ui.tabs.TabStrip"
        const val TAB_ITEM = "app.berth.android.ui.tabs.TabItem"
        const val SCROLLED_PILL = "app.berth.android.ui.stage.ScrolledPill"
    }
}
