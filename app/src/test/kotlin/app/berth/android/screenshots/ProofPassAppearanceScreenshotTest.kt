package app.berth.android.screenshots

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshotDir
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.keys.summary
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthColors
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.berthColors
import app.berth.android.ui.theme.toColor
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.domain.model.AccentPreset
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The interface and key rows of the v1 proof pass, each a frame with the control that set it and
 * the screen it changed:
 *
 * - A72 and A74, Black and Light: the variant picked in Settings, and Settings and the Interface
 *   editor (its Stage mock included) drawn in it, the background read off the frame.
 * - A73, Material You: the chip picked, and the interface's accent the system's wallpaper colour
 *   in place of Copper, on the tokens and in the frame's pixels.
 * - A10, key generation: the New key sheet filled in, the Ed25519 key it made in the list, and
 *   that key's detail sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ProofPassAppearanceScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = screenshotDir
    private lateinit var graph: TestGraph

    /** The tokens the shell's theme handed its content on the last frame. */
    private var drawn: BerthColors? = null

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    /** Under the theme the shell draws with, as AppRoot mounts it, the tokens it hands down kept in [drawn]. */
    private fun shell(content: @Composable () -> Unit) {
        compose.setContent {
            val theme by graph.viewModel.shownInterfaceTheme.collectAsState()
            BerthTheme(theme) {
                val colors = Berth.colors
                SideEffect { drawn = colors }
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** Settings, whose Interface editor row opens the editor, as the shell's back stack has them. */
    private fun settingsThenEditor() {
        var editor by mutableStateOf(false)
        shell {
            if (editor) {
                AppearanceScreen(graph.viewModel, onBack = { editor = false })
            } else {
                SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}, onAppearance = { editor = true })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openEditor() {
        compose.onNodeWithText("Interface editor").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        compose.settle(600)
    }

    private fun awaitOnMain(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** A variant's segment; the Interface editor also has a preset of each variant's name. */
    private fun variant(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun frame(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    /**
     * The screen's own background: a column of pixels in the screen margin, left of every panel,
     * down the middle third, all one colour.
     */
    private fun background(): Color {
        val pixels = frame()
        val x = 4
        val colours = (pixels.height / 3 until pixels.height * 2 / 3 step 16).map { pixels[x, it] }.toSet()
        assertEquals("the margin is one colour: $colours", 1, colours.size)
        return colours.single()
    }

    private fun count(pixels: PixelMap, colour: Color): Int {
        val argb = colour.toArgb()
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].toArgb() == argb) n++
        return n
    }

    // ---- A72 and A74, Black and Light ----------------------------------------------------------------

    /**
     * True black (spec A72, C19): Black picked in Settings' Appearance row, and the page it was
     * picked on, then the Interface editor with its Stage mock, drawn on #000000 where Dark draws
     * graphite.
     */
    @Test
    fun `A72 Black picked in Settings draws Settings and the Interface editor on true black`() {
        settingsThenEditor()
        val dark = background()
        assertEquals("Dark's graphite before the pick", berthColors(InterfaceTheme.DEFAULT, dark = true).surface0.toArgb(), dark.toArgb())

        compose.onNode(variant("Black")).performScrollTo().performClick()
        awaitOnMain("the shell to draw in Black") { graph.viewModel.shownInterfaceTheme.value.variant == InterfaceVariant.TRUE_BLACK }
        compose.settle(600)
        compose.onNode(variant("Black")).assertIsSelected()
        compose.onNode(variant("Dark")).assertIsNotSelected()
        assertEquals(Color.Black.toArgb(), drawn!!.surface0.toArgb())
        assertEquals("Settings' background is black", Color.Black.toArgb(), background().toArgb())
        capture("A72-settings-black")

        openEditor()
        compose.onNode(variant("Black")).assertIsSelected()
        assertEquals("the editor's background is black", Color.Black.toArgb(), background().toArgb())
        capture("A72-interface-editor-black")
    }

    /**
     * The light interface (spec A74, C19): Light picked in Settings, and Settings then the
     * Interface editor drawn on the light set's surface, dark text over it.
     */
    @Test
    fun `A74 Light picked in Settings draws Settings and the Interface editor light`() {
        settingsThenEditor()
        val before = background()

        compose.onNode(variant("Light")).performScrollTo().performClick()
        awaitOnMain("the shell to draw in Light") { graph.viewModel.shownInterfaceTheme.value.variant == InterfaceVariant.LIGHT }
        compose.settle(600)
        compose.onNode(variant("Light")).assertIsSelected()
        val light = berthColors(graph.viewModel.shownInterfaceTheme.value, dark = false)
        assertTrue("the tokens are the light set's", !drawn!!.isDark && drawn!!.surface0 == light.surface0)
        val after = background()
        assertEquals("Settings' background is the light surface", light.surface0.toArgb(), after.toArgb())
        assertNotEquals(before.toArgb(), after.toArgb())
        capture("A74-settings-light")

        openEditor()
        compose.onNode(variant("Light")).assertIsSelected()
        assertEquals("the editor's background is the light surface", light.surface0.toArgb(), background().toArgb())
        capture("A74-interface-editor-light")
    }

    // ---- A73, Material You ------------------------------------------------------------------------

    /**
     * Dynamic colour (spec A73, C19): the Material You chip picked in Settings makes the system's
     * wallpaper accent the interface's in place of Copper, the tokens and the frame both; the
     * Interface editor shows the same chip picked and its Stage mock in that accent.
     */
    @Test
    fun `A73 Material You picked in Settings makes the system accent the interface's`() {
        settingsThenEditor()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val system = dynamicDarkColorScheme(context).primary
        val copper = InterfaceTheme.DEFAULT.accent.toColor()
        assertNotEquals("the system accent is not Copper", copper.toArgb(), system.toArgb())
        assertEquals(copper.toArgb(), drawn!!.accent.toArgb())

        compose.onNodeWithText("Material You").performScrollTo()
        compose.settle(400)
        val before = count(frame(), system)
        compose.onNodeWithText("Material You").performClick()
        awaitOnMain("the shell to take the system accent") { graph.viewModel.shownInterfaceTheme.value.materialYou }
        compose.settle(600)
        compose.onNodeWithText("Material You").assertIsSelected()
        compose.onNodeWithText(AccentPreset.COPPER.title).assertIsNotSelected()
        assertEquals("the interface's accent is the system's", system.toArgb(), drawn!!.accent.toArgb())
        val after = count(frame(), system)
        assertTrue("more of the frame is the system accent once picked: $before then $after", after > before)
        capture("A73-settings-material-you")

        openEditor()
        compose.onNodeWithText("Material You").assertIsSelected()
        assertTrue("the editor draws in the system accent", count(frame(), system) > 0)
        capture("A73-interface-editor-material-you")
    }

    // ---- A10, key generation -----------------------------------------------------------------------

    /**
     * Key generation (spec A10, C12): the Keys screen's New key opens the sheet on Ed25519; named
     * and commented, Generate makes the key, which the list shows by its fingerprint and whose
     * detail sheet has the whole of it. The public key parses back to the fingerprint the row shows.
     */
    @Test
    fun `A10 New key makes an Ed25519 key that the list and its sheet show`() {
        shell { KeysScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No keys yet.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New key").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Ed25519 is the default for modern servers.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ed25519").assertIsSelected()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("pixel-ed25519")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("ben@pixel")
        compose.settle(600)
        capture("A10-new-key-sheet")

        compose.onNodeWithText("Generate").performClick()
        awaitOnMain("the key to be saved") { graph.viewModel.identities.value.isNotEmpty() }
        awaitOnMain("the sheet to close") { compose.onAllNodes(hasText("Ed25519 is the default for modern servers.")).fetchSemanticsNodes().isEmpty() }
        val key = graph.viewModel.identities.value.single()
        assertEquals("pixel-ed25519", key.name)
        assertEquals(KeyAlgorithm.ED25519, key.algorithm)
        assertEquals(KeyProtection.NONE, key.protection)
        assertTrue(key.publicKeyOpenSsh, key.publicKeyOpenSsh.startsWith("ssh-ed25519 AAAA") && key.publicKeyOpenSsh.endsWith(" ben@pixel"))
        assertEquals("the public key is the fingerprint's", key.fingerprintSha256, SshKeys.fingerprintSha256(SshKeys.parseOpenSshPublic(key.publicKeyOpenSsh)))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("pixel-ed25519")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(key.summary(), substring = true)).assertExists()
        compose.settle(600)
        capture("A10-key-generated")

        compose.onNodeWithText("pixel-ed25519").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Close sheet")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(800)
        capture("A10-key-detail")
    }
}
