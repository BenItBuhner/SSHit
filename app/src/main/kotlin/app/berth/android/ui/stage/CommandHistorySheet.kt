package app.berth.android.ui.stage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.LocalWallClock
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.snippets.SnippetEditorSheet
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.domain.model.HostCommand
import app.berth.domain.model.SessionState
import app.berth.terminal.TerminalKey
import java.text.DateFormat
import java.util.Calendar

/**
 * The command history sheet (spec C16): the commands this tab's host ran, from every tab that was
 * ever on it, newest first under day headings, narrowed by the field once there are enough to
 * need it; the All hosts chip widens it to every host, each row then naming its host. A tap
 * pastes the command to the prompt; a long-press offers Run, Copy, Save as snippet and Delete.
 * Run and the tap need the session Live. History is kept per host in the store, capped at 2,000
 * a host; the empty state says where commands come from, and the Settings toggle turning it off
 * is reported here rather than silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandHistorySheet(vm: AppViewModel, session: TerminalSession, onDismiss: () -> Unit, onNotice: (String) -> Unit = {}) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val enabled by vm.commandHistoryEnabled.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val tabs by vm.sessions.records.collectAsState()
    val clipboard = LocalClipboardManager.current
    val haptics = rememberDeckHaptics()
    val live = record.state == SessionState.LIVE
    val key = record.hostSnapshot.commandHistoryKey
    var allHosts by rememberSaveable { mutableStateOf(false) }
    val commands by remember(key, allHosts) { if (allHosts) vm.allCommandHistory else vm.commandHistoryOf(key) }.collectAsState(initial = null)
    var filter by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<HostCommand?>(null) }
    var snippetFrom by remember { mutableStateOf<String?>(null) }

    // A row's host, for the All hosts view: the saved host's name (by its key, since a host saved from a Quick connect
    // tab keeps the login's), the login of an unsaved quick connect, or a tab still open on it.
    fun hostLabel(historyKey: String): String =
        hosts.firstOrNull { it.commandHistoryKey == historyKey }?.name
            ?: Host.quickConnectLabel(historyKey)
            ?: tabs.firstOrNull { it.hostSnapshot.commandHistoryKey == historyKey }?.hostSnapshot?.name
            ?: "Removed host"

    val loaded = commands ?: emptyList()
    val clock = LocalWallClock.current
    val rows = remember(commands, filter) {
        val q = filter.trim()
        val shown = loaded.asReversed().filter { q.isEmpty() || it.text.contains(q, ignoreCase = true) }
        val today = clock()
        buildList {
            var day: String? = null
            for (entry in shown) {
                val label = dayLabel(entry.at, today)
                if (label != day) {
                    day = label
                    add(HistoryRow.Day(label))
                }
                add(HistoryRow.Command(entry))
            }
        }
    }
    fun paste(entry: HostCommand) {
        session.paste(entry.text)
        haptics.paste()
        onDismiss()
    }

    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The scope, said plainly: this host or every host, and the count; the chip trades one for the other.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SheetTitle(
                        "History",
                        (if (allHosts) "All hosts" else record.hostSnapshot.name) + " \u00B7 " + when {
                            commands == null -> "loading"
                            loaded.isEmpty() -> "no commands yet"
                            loaded.size == 1 -> "1 command"
                            else -> "${loaded.size} commands"
                        },
                    )
                }
                Chip("All hosts", selected = allHosts, onClick = { allHosts = !allHosts })
            }
            if (!enabled) Text("Command history is off in Settings; nothing new is recorded.", style = BerthType.caption, color = c.danger)
            if (loaded.size > FILTER_FROM) {
                BerthField(filter, { filter = it }, placeholder = "Search", mono = true, modifier = Modifier.semantics { contentDescription = "Search history" })
            }
            when {
                // Nothing until the store has answered, so an empty state never flashes before the rows.
                commands == null -> Unit
                rows.isEmpty() -> Text(
                    when {
                        loaded.isNotEmpty() -> "Nothing matches."
                        allHosts -> "Commands land here as any host's shell runs them, from its prompt marks or from what is typed and echoed."
                        else -> "Commands land here as this host's shell runs them, from its prompt marks or from what is typed and echoed; every tab on ${record.hostSnapshot.name} shares them."
                    },
                    style = BerthType.body,
                    color = c.text2,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is HistoryRow.Day -> Text(
                                row.label.uppercase(),
                                style = BerthType.caption,
                                color = c.text2,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                            )
                            is HistoryRow.Command -> Box {
                                val entry = row.entry
                                val time = timeLabel(entry.at)
                                ListRow(
                                    title = entry.text,
                                    subtitle = if (allHosts) "${hostLabel(entry.hostId)} \u00B7 $time" else time,
                                    surface = Color.Transparent,
                                    minHeight = 44.dp,
                                    titleStyle = MonoBody,
                                    onClick = if (live) ({ paste(entry) }) else ({ menuFor = entry }),
                                    onLongClick = { menuFor = entry },
                                    modifier = Modifier.semantics { contentDescription = "Command ${entry.text}" },
                                )
                                BerthMenu(expanded = menuFor == entry, onDismiss = { menuFor = null }) {
                                    @Composable fun item(text: String, destructive: Boolean = false, action: () -> Unit) {
                                        DropdownMenuItem(
                                            text = { Text(text, style = BerthType.body, color = if (destructive) c.danger else c.text1) },
                                            onClick = { menuFor = null; action() },
                                        )
                                    }
                                    if (live) {
                                        item("Run") {
                                            session.sendText(entry.text)
                                            session.sendKey(TerminalKey.ENTER)
                                            haptics.paste()
                                            onDismiss()
                                        }
                                        item("Paste") { paste(entry) }
                                    }
                                    item("Copy") {
                                        clipboard.setText(AnnotatedString(entry.text))
                                        haptics.copy()
                                        onNotice("Copied")
                                    }
                                    item("Save as snippet") { snippetFrom = entry.text }
                                    item("Delete", destructive = true) { vm.deleteCommand(entry.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    snippetFrom?.let { body ->
        SnippetEditorSheet(vm, existing = null, onDismiss = { snippetFrom = null }, defaultHostId = record.hostId, initialBody = body)
    }
}

/** Under this many commands the sheet is short enough to read without a filter field. */
private const val FILTER_FROM = 6

private sealed interface HistoryRow {
    val key: String

    data class Day(val label: String) : HistoryRow {
        override val key: String get() = "day:$label"
    }

    data class Command(val entry: HostCommand) : HistoryRow {
        override val key: String get() = "command:${entry.id}"
    }
}

/** `Today`, `Yesterday`, then the date, for the sheet's headings. */
private fun dayLabel(at: Long, now: Long): String {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        at >= startOfToday -> "Today"
        at >= startOfToday - day -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(at)
    }
}

private fun timeLabel(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(at)
