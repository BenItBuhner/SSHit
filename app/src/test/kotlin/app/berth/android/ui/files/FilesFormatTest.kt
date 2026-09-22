package app.berth.android.ui.files

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.android.ui.theme.MonoFontFeatures
import app.berth.domain.model.InterfaceTheme
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Caption under a file's name sets the mode in Mono with the font's ligatures and contextual
 * alternates off ([MonoFontFeatures]), the way every mono span of the interface does: `rw-r--r--`
 * is three dashes, drawn as three dashes, and the kind and time before it stay in the row's own
 * face with the font's defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class FilesFormatTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val now = 1_757_599_200_000L // 11 Sep 2025 14:00 UTC

    private fun caption(entry: SftpEntry): AnnotatedString {
        var caption: AnnotatedString? = null
        compose.setContent { BerthTheme(InterfaceTheme.DEFAULT) { caption = entryCaption(entry, now) } }
        compose.waitForIdle()
        return caption!!
    }

    @Test
    fun `the mode is one mono span with the ligatures off, and nothing else is`() {
        val entry = SftpEntry("notes.txt", "/home/demo/notes.txt", SftpFileType.REGULAR, size = 812, modifiedAt = now - 3_600_000, permissions = 0b110_100_100)
        val text = caption(entry)
        assertTrue(text.text, text.text.endsWith(" \u00B7 -rw-r--r--"))
        val mono = text.spanStyles.filter { it.item.fontFamily === JetBrainsMono }
        assertEquals("one mono span, the mode's: ${text.spanStyles}", 1, mono.size)
        val span = mono.single()
        assertEquals("-rw-r--r--", text.text.substring(span.start, span.end))
        assertEquals("the mode is drawn with the font's ligatures on", MonoFontFeatures, span.item.fontFeatureSettings)
        assertTrue("a span other than the mode's sets the font's features", text.spanStyles.none { it.item.fontFamily !== JetBrainsMono && it.item.fontFeatureSettings != null })
    }

    @Test
    fun `a dangling link's mode keeps the features off in its dimmer colour`() {
        val entry = SftpEntry("old", "/home/demo/old", SftpFileType.SYMLINK, size = 3, modifiedAt = now, permissions = 0b111_111_111)
        val span = caption(entry).spanStyles.single { it.item.fontFamily === JetBrainsMono }
        assertEquals(MonoFontFeatures, span.item.fontFeatureSettings)
    }
}
