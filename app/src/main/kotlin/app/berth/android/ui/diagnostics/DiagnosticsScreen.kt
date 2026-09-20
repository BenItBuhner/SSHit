package app.berth.android.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.diagnostics.ReportKind
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType

/**
 * Settings \u203A Diagnostics: every crash and connection report on the phone, newest first, one
 * row each with what went wrong and when. Tap opens the report in full with Share, Copy and
 * Delete; the header's Delete all clears them. Nothing here is sent anywhere by itself.
 */
@Composable
fun DiagnosticsScreen(reports: CrashReporter, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val list by reports.reports.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Diagnostics", onBack = onBack, actions = {
            if (list.isNotEmpty()) BerthButton("Delete all", onClick = { reports.deleteAll() }, kind = ButtonKind.TEXT)
        })
        if (list.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState("No reports.", "A crash, or a connection that failed or fell over, is written here with the app, the device and the last lines of Berth\u2019s own log. Nothing is sent anywhere unless you share it.") {}
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.id }) { report ->
                    ListRow(
                        title = report.title,
                        subtitle = report.kind.label + if (report.summary.isNotEmpty()) " \u00B7 ${report.summary}" else "",
                        onClick = { open = report.id },
                        trailing = {
                            Text(formatDateTime(report.at), style = BerthType.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
                item {
                    Text(
                        "Reports stay on this phone; the newest ${CrashReporter.MAX_REPORTS} are kept. A crash is shown once on the next launch and listed here after.",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
                    )
                }
            }
        }
    }

    val selected = open?.let { id -> list.firstOrNull { it.id == id } }
    if (selected != null) {
        ReportSheet(
            selected,
            title = selected.title,
            caption = "${if (selected.kind == ReportKind.CRASH) "Crash" else "Connection"} report \u00B7 ${formatDateTime(selected.at)}",
            onDismiss = { open = null },
            last = { BerthButton("Delete", onClick = { reports.delete(selected); open = null }, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth()) },
        )
    }
}
