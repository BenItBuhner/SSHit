package app.berth.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.TerminalFont
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The font-size step behind the pinch and the keyboard chord (review #15): on a host with a size of
 * its own the step writes that size, so the terminal under the fingers is the one that changes and
 * every other tab keeps the app's; on any other host, or no host, it writes the app's. The
 * two-finger double-tap's reset (spec D1) takes a host's own size away, so the tab follows the app
 * again, and with none to take away returns the app's size to its default. Both stay inside the
 * font's bounds, and steps fired in a burst, as a pinch fires them, each read the last one's result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FontSizeOverrideTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        StageFixture.seed(graph)
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun hostSize(id: String): Int? = graph.hosts.items.value.first { it.id == id }.appearance.fontSizeSp
    private val appSize: Int get() = runBlocking { graph.settings.terminalFont.first().sizeSp }

    private fun setHostSize(id: String, size: Int?) = runBlocking {
        val host = graph.hosts.items.value.first { it.id == id }
        graph.hosts.upsert(host.copy(appearance = host.appearance.copy(fontSizeSp = size)))
    }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what: host ${hostSize("homelab")}, app $appSize")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a step on a host with its own size writes that size and leaves the app's alone`() {
        setHostSize("homelab", 16)
        graph.viewModel.stepFontSize("homelab", 1)
        await("the host's size stepped") { hostSize("homelab") == 17 }
        assertEquals(TerminalFont().sizeSp, appSize)
        graph.viewModel.stepFontSize("homelab", -3)
        await("the host's size stepped down") { hostSize("homelab") == 14 }
        assertEquals(TerminalFont().sizeSp, appSize)
        assertNull("another host keeps following the app", hostSize("pi-hole"))
    }

    @Test
    fun `a step on a host without a size of its own, or with no host, writes the app's size`() {
        graph.viewModel.stepFontSize("pi-hole", 1)
        await("the app's size stepped") { appSize == TerminalFont().sizeSp + 1 }
        assertNull(hostSize("pi-hole"))
        graph.viewModel.stepFontSize(null, 1)
        await("the app's size stepped again") { appSize == TerminalFont().sizeSp + 2 }
        graph.viewModel.stepFontSize("no-such-host", -1)
        await("an unknown host is no host") { appSize == TerminalFont().sizeSp + 1 }
    }

    @Test
    fun `steps stop at the font's bounds`() {
        setHostSize("homelab", TerminalFont.MAX_SIZE_SP - 1)
        graph.viewModel.stepFontSize("homelab", 1)
        await("at the top") { hostSize("homelab") == TerminalFont.MAX_SIZE_SP }
        graph.viewModel.stepFontSize("homelab", 1)
        Thread.sleep(100)
        assertEquals(TerminalFont.MAX_SIZE_SP, hostSize("homelab"))
        runBlocking { graph.settings.setTerminalFont(TerminalFont(sizeSp = TerminalFont.MIN_SIZE_SP)) }
        graph.viewModel.stepFontSize(null, -1)
        Thread.sleep(100)
        assertEquals(TerminalFont.MIN_SIZE_SP, appSize)
    }

    @Test
    fun `a burst of steps lands every one, as a pinch fires them`() {
        setHostSize("homelab", 12)
        repeat(5) { graph.viewModel.stepFontSize("homelab", 1) }
        await("five steps") { hostSize("homelab") == 17 }
        repeat(4) { graph.viewModel.stepFontSize(null, 1) }
        await("four on the app") { appSize == TerminalFont().sizeSp + 4 }
        assertEquals(17, hostSize("homelab"))
    }

    @Test
    fun `the reset takes a host's own size away, and with none returns the app's to its default`() {
        setHostSize("homelab", 18)
        runBlocking { graph.settings.setTerminalFont(TerminalFont(sizeSp = 16)) }
        graph.viewModel.resetFontSize("homelab")
        await("the host follows the app again") { hostSize("homelab") == null }
        assertEquals("the app's size is not what the host's reset touches", 16, appSize)
        graph.viewModel.resetFontSize("homelab")
        await("the app's size returns to its default") { appSize == TerminalFont().sizeSp }
        runBlocking { graph.settings.setTerminalFont(TerminalFont(sizeSp = 20)) }
        graph.viewModel.resetFontSize(null)
        await("no host at all: the app's size") { appSize == TerminalFont().sizeSp }
    }

    @Test
    fun `the reset keeps the rest of the host's appearance`() {
        runBlocking {
            val host = graph.hosts.items.value.first { it.id == "homelab" }
            graph.hosts.upsert(host.copy(appearance = AppearanceOverride(terminalThemeId = "solarized", fontSizeSp = 15)))
        }
        graph.viewModel.resetFontSize("homelab")
        await("size gone") { hostSize("homelab") == null }
        assertEquals("solarized", graph.hosts.items.value.first { it.id == "homelab" }.appearance.terminalThemeId)
    }
}
