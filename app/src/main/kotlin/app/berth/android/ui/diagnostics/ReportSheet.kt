package app.berth.android.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.diagnostics.Report
import app.berth.android.diagnostics.ReportKind
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.io.shareText
import app.berth.android.ui.prompts.PromptSheet
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The sheet for the crash of the previous run, shown once on the next launch (the gap audit's
 * crash handling): what was written, in full, so the user can read what would leave the phone,
 * and the two ways it can leave, Share and Copy, both theirs to take. Nothing is sent by itself;
 * the report stays under Settings \u203A Diagnostics whichever button is tapped. Swiping the sheet
 * away is the same as Not now: seen, kept, not asked again.
 */
@Composable
fun CrashReportHost(reports: CrashReporter) {
    val unread by reports.unread.collectAsState()
    val report = unread ?: return
    ReportSheet(
        report,
        title = "Berth crashed last time",
        caption = "The report below was written as it happened. Nothing has been sent anywhere.",
        onDismiss = { reports.markRead() },
        last = { BerthButton("Not now", onClick = { reports.markRead() }, kind = ButtonKind.TEXT, modifier = Modifier.fillMaxWidth()) },
    )
}

/**
 * One report in full, from the sheet on launch or from the Diagnostics list: the text in Mono
 * so it reads as what it is, a plain account of what it holds, then Share, Copy and [last]
 * (Not now on launch, Delete from the list).
 */
@Composable
fun ReportSheet(report: Report, title: String, caption: String, onDismiss: () -> Unit, last: @Composable () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = remember(report.id) { report.read() }
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1_500); copied = false }
    PromptSheet(onDismiss = onDismiss) {
        SheetTitle(title, caption)
        Text(
            text.ifEmpty { "The report could not be read." },
            style = BerthType.mono.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = c.text1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(c.surface2)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
        Text(
            when (report.kind) {
                ReportKind.CRASH -> "It holds the error, the app and device, and the last lines of Berth\u2019s own log: no passwords, keys, passphrases, clipboard text or terminal output. Share it with whoever is looking into the crash, or copy it to paste. It stays under Settings \u203A Diagnostics either way."
                ReportKind.TRANSPORT -> "It holds what the connection reported, the app and device, and the last lines of Berth\u2019s own log: no passwords, keys, passphrases, clipboard text or terminal output."
            },
            style = BerthType.body,
            color = c.text2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BerthButton("Share report", onClick = { shareText(context, "${report.kind.heading} \u00B7 ${formatDateTime(report.at)}", text, mime = "text/plain") }, kind = ButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
            BerthButton(if (copied) "Copied" else "Copy report", onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            last()
        }
    }
}

/** Date and time in the phone's own format, the way the row and the sheet's caption say when. */
internal fun formatDateTime(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
