package app.berth.android.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.linesAtFontScale
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType

/**
 * The spec's `Show visual fingerprint` (C13): a text button that gives way to OpenSSH's randomart
 * of the key, in Mono at the fingerprint rows' size so the two are read as one thing, and a way
 * back. The art is drawn as the eleven lines `ssh-keygen -lv` prints, so it can be held next to a
 * server's; a reader hears it as the picture it is, not sixty glyphs of punctuation.
 */
@Composable
fun VisualFingerprint(art: String, modifier: Modifier = Modifier, label: String? = null) {
    val c = Berth.colors
    var shown by rememberSaveable(art) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shown) {
            if (label != null) Text(label.uppercase(), style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                art,
                style = BerthType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = c.text1,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = "Visual fingerprint${label?.let { " of the $it key" } ?: ""}: the key's randomart, as ssh-keygen -lv draws it" },
            )
        }
        BerthButton(
            if (shown) "Hide visual fingerprint" else "Show visual fingerprint${label?.let { " of the $it key" } ?: ""}",
            onClick = { shown = !shown },
            kind = ButtonKind.TEXT,
        )
    }
}

/**
 * A line meant to be copied and run or read elsewhere (C13's `ssh-keygen -lf …` under COMPARE ON
 * THE SERVER, the key sheet's `ssh-keygen -lf` output): the caption over the text in Mono, with
 * the copy action at the trailing edge in a 48 dp target. The text wraps rather than cuts, since a
 * path or a fingerprint that loses its end is worth nothing; a caller whose text is a key line,
 * hundreds of characters of base64 whose middle says nothing, gives [maxLines] (grown at the font
 * cap as every line count is) and the line is cut there. What is copied is [copyText], the whole
 * of it, which is [text] unless the shown text is an elided form of it. The system says `Copied`
 * itself on Android 13 and later, as it does for every copy in the app.
 */
@Composable
fun CopyableLine(caption: String, text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, copyText: String = text) {
    val c = Berth.colors
    val clipboard = LocalClipboardManager.current
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(caption.uppercase(), style = BerthType.caption, color = c.text3)
            Text(text, style = BerthType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.text1, maxLines = linesAtFontScale(maxLines), overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(start = 8.dp)) {
            IconAction(onClick = { clipboard.setText(AnnotatedString(copyText)) }, description = "Copy $caption") { BerthIcon(BerthIcons.copy) }
        }
    }
}
