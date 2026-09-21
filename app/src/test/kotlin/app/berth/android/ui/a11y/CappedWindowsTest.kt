package app.berth.android.ui.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The interface's font cap (A11) reaches every window the app opens. A sheet, a dialog and a menu
 * each open a window of their own, whose Compose view provides the density afresh from its
 * Context, uncapped, so the cap the theme sets on the Activity's window stops at that window's
 * edge: `BerthSheet` (as a bottom sheet and as a dialog) and `BerthMenu` apply it again inside,
 * and those two files are where the app's window-opening calls live. The sources are read rather
 * than each window opened, since a raw `DropdownMenu` added later on any screen is what this holds
 * against: the review of the library slice found fourteen, each setting its rows at the system's
 * 2× where the screen around it stood at 1.3×.
 */
class CappedWindowsTest {
    private val sources = File(System.getProperty("user.dir"), "src/main/kotlin")

    /** The composables that open a window: M3's sheet and menus, Compose's dialogs and popup. */
    private val windows = Regex("""\b(ModalBottomSheet|DropdownMenu|ExposedDropdownMenu|Dialog|BasicAlertDialog|AlertDialog|Popup)\(""")

    /** Where the cap is re-applied: the app's sheet (and its dialog form) and its menu. */
    private val capped = listOf(
        "app/berth/android/ui/components/BerthSheet.kt",
        "app/berth/android/ui/components/Components.kt",
    )

    @Test
    fun `every window the app opens is a BerthSheet or a BerthMenu`() {
        assertTrue("the app's sources are under ${sources.path}", sources.isDirectory)
        val calls = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(sources).path.replace(File.separatorChar, '/')
                file.readLines().asSequence().withIndex()
                    .filter { (_, line) -> !line.isComment() && windows.containsMatchIn(line) }
                    .map { (i, _) -> "$path:${i + 1}" }
            }
            .toList()
        val raw = calls.filter { call -> capped.none { call.startsWith("$it:") } }
        assertTrue(
            "windows opened outside BerthSheet and BerthMenu, each at the system's font scale where the screen is at the interface's cap: $raw",
            raw.isEmpty(),
        )
        // The sheet, its dialog form and the menu: three calls, so a rename cannot hide one from the check.
        assertEquals("the capped windows themselves: $calls", 3, calls.size)
    }

    private fun String.isComment(): Boolean = trimStart().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
}
