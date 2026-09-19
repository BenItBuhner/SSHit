package app.berth.android.ui.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.berth.android.security.BerthClipboard

/**
 * Routes every copy made through Compose's clipboard locals through [BerthClipboard], so the
 * screens' Copy buttons need no change and their copies are the ones the auto-clear timer tracks.
 * Reads still go to the platform.
 */
@Composable
fun BerthClipboardLocals(clipboard: BerthClipboard, content: @Composable () -> Unit) {
    @Suppress("DEPRECATION")
    val platformManager = LocalClipboardManager.current
    val platform = LocalClipboard.current
    val manager = remember(platformManager, clipboard) { RoutedClipboardManager(platformManager, clipboard) }
    val routed = remember(platform, clipboard) { RoutedClipboard(platform, clipboard) }
    @Suppress("DEPRECATION")
    CompositionLocalProvider(LocalClipboardManager provides manager, LocalClipboard provides routed, content = content)
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class RoutedClipboardManager(private val platform: ClipboardManager, private val clipboard: BerthClipboard) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) = clipboard.copy(annotatedString.text)
    override fun getText(): AnnotatedString? = platform.getText()
    override fun hasText(): Boolean = platform.hasText()
    override fun getClip(): ClipEntry? = platform.getClip()
    override fun setClip(clipEntry: ClipEntry?) {
        if (clipEntry == null) platform.setClip(null) else clipboard.copy(clipEntry.clipData)
    }
    override val nativeClipboard: android.content.ClipboardManager get() = platform.nativeClipboard
}

private class RoutedClipboard(private val platform: Clipboard, private val clipboard: BerthClipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry == null) platform.setClipEntry(null) else clipboard.copy(clipEntry.clipData)
    }
    override val nativeClipboard: android.content.ClipboardManager get() = platform.nativeClipboard
}
