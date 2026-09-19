package app.berth.android.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.files.Transfer
import app.berth.android.files.TransferKind
import app.berth.android.files.TransferState
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.stage.MonoBody
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionState
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpFileType
import app.berth.sftp.SftpPaths
import app.berth.sftp.SftpPermissions
import app.berth.sftp.TextRead

/** The sheet chrome every Files sheet shares: surface.1, the sheet radius, the handle, 20 dp margins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesSheet(onDismiss: () -> Unit, tall: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Berth.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = tall)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier)
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ---- Go to folder --------------------------------------------------------------------------------

/**
 * The path editor behind the breadcrumb: type or paste an absolute path, or pick a place. Recent
 * folders come from the preferences document; the terminal's directory from OSC 7.
 */
@Composable
fun PathEditorSheet(
    current: String,
    home: String?,
    terminalCwd: String?,
    recent: List<String>,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    var text by remember { mutableStateOf(current) }
    val trimmed = text.trim()
    val valid = trimmed.startsWith("/") || trimmed.startsWith("~")
    fun go(path: String) {
        val resolved = if (path.startsWith("~")) (home ?: "/") + path.removePrefix("~") else path
        onGo(SftpPaths.normalize(resolved))
        onDismiss()
    }
    FilesSheet(onDismiss) {
        SheetTitle("Go to folder", current)
        BerthField(
            text,
            { text = it },
            label = "Path",
            placeholder = "/var/log",
            mono = true,
            helper = if (trimmed.isNotEmpty() && !valid) "Paths start with / or ~." else null,
            isError = trimmed.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (valid) go(trimmed) }),
        )
        val places = buildList {
            if (home != null) add(Triple("Home", home, BerthIcons.home))
            if (terminalCwd != null && terminalCwd != home) add(Triple("Terminal directory", terminalCwd, BerthIcons.terminal))
            add(Triple("Root", SftpPaths.ROOT, BerthIcons.folder))
        }
        SectionLabel("Places", Modifier.padding(start = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, path, icon) in places) {
                ListRow(
                    title = label,
                    subtitle = path,
                    subtitleStyle = BerthType.mono.copy(fontSize = BerthType.caption.fontSize),
                    minHeight = 48.dp,
                    onClick = { go(path) },
                    leading = { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { BerthIcon(icon, size = 20.dp) } },
                )
            }
        }
        val others = recent.filter { it != current && it != home && it != terminalCwd && it != SftpPaths.ROOT }
        if (others.isNotEmpty()) {
            SectionLabel("Recent", Modifier.padding(start = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (path in others.take(6)) {
                    ListRow(
                        title = SftpPaths.name(path),
                        subtitle = path,
                        subtitleStyle = BerthType.mono.copy(fontSize = BerthType.caption.fontSize),
                        minHeight = 48.dp,
                        onClick = { go(path) },
                        leading = { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { BerthIcon(BerthIcons.folder, size = 20.dp, tint = c.text3) } },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Go", kind = ButtonKind.PRIMARY, enabled = valid, onClick = { go(trimmed) })
            BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
        }
    }
}

// ---- Names ----------------------------------------------------------------------------------------

/** New folder and rename share this: one field, the folder it lands in as the caption, and validation against the listing. */
@Composable
fun NameSheet(
    title: String,
    folder: String,
    initial: String,
    confirm: String,
    existing: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val problem = when {
        trimmed.isEmpty() -> null
        !SftpPaths.isValidName(trimmed) -> "Names cannot contain / and cannot be . or .."
        trimmed != initial && trimmed in existing -> "$trimmed is already here."
        else -> null
    }
    val canConfirm = trimmed.isNotEmpty() && problem == null && trimmed != initial
    fun done() {
        if (canConfirm) {
            onConfirm(trimmed)
            onDismiss()
        }
    }
    FilesSheet(onDismiss) {
        SheetTitle(title, folder)
        BerthField(
            name,
            { name = it },
            label = "Name",
            placeholder = if (initial.isEmpty()) "New folder" else initial,
            helper = problem,
            isError = problem != null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton(confirm, kind = ButtonKind.PRIMARY, enabled = canConfirm, onClick = { done() })
            BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
        }
    }
}

// ---- Delete ---------------------------------------------------------------------------------------

/** Deleting is the one action that asks first: what goes, and that folders take their contents. */
@Composable
fun DeleteSheet(entries: List<SftpEntry>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    val folders = entries.count { it.type == SftpFileType.DIRECTORY }
    val title = if (entries.size == 1) "Delete ${entries.single().name}?" else "Delete ${entries.size} items?"
    FilesSheet(onDismiss) {
        SheetTitle(title, entries.firstOrNull()?.let { SftpPaths.parent(it.path) })
        if (entries.size > 1) {
            Text(
                entries.take(6).joinToString(", ") { it.name } + if (entries.size > 6) " and ${entries.size - 6} more" else "",
                style = BerthType.body,
                color = c.text2,
            )
        }
        Text(
            when {
                folders == 0 -> "This cannot be undone on the server."
                folders == entries.size && entries.size == 1 -> "Everything inside the folder goes with it. This cannot be undone on the server."
                else -> "Folders go with everything inside them. This cannot be undone on the server."
            },
            style = BerthType.caption,
            color = c.text3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Delete", kind = ButtonKind.DESTRUCTIVE, onClick = { onConfirm(); onDismiss() })
            BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
        }
    }
}

// ---- Permissions ----------------------------------------------------------------------------------

/**
 * chmod as a grid of chips, owner, group and others by read, write and execute, with the special
 * bits on their own row and the octal field kept in step both ways.
 */
@Composable
fun ChmodSheet(entries: List<SftpEntry>, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    val initial = entries.map { it.permissions }.distinct().singleOrNull() ?: entries.firstOrNull()?.permissions ?: 0b110_100_100
    var mode by remember { mutableIntStateOf(initial and SftpPermissions.MASK) }
    var octal by remember { mutableStateOf(SftpPermissions.octal(initial)) }
    val octalValid = SftpPermissions.parseOctal(octal) != null
    fun set(bits: Int) {
        mode = bits and SftpPermissions.MASK
        octal = SftpPermissions.octal(mode)
    }
    fun toggle(bit: Int) = set(mode xor bit)
    val sample = entries.firstOrNull()
    val caption = when (entries.size) {
        0 -> null
        1 -> entries.single().path
        else -> "${entries.size} items in ${SftpPaths.parent(entries.first().path)}"
    }
    FilesSheet(onDismiss) {
        SheetTitle("Permissions", caption)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(72.dp))
                for (head in listOf("Read", "Write", "Exec")) {
                    Text(head.uppercase(), style = BerthType.caption, color = c.text2, modifier = Modifier.weight(1f))
                }
            }
            val rows = listOf("Owner" to 6, "Group" to 3, "Others" to 0)
            for ((label, shift) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = BerthType.body, color = c.text1, modifier = Modifier.width(72.dp))
                    for ((i, letter) in listOf("r", "w", "x").withIndex()) {
                        val bit = (4 shr i) shl shift
                        Box(Modifier.weight(1f)) {
                            Chip(letter, selected = mode and bit != 0, mono = true, onClick = { toggle(bit) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Special", style = BerthType.body, color = c.text1, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f)) { Chip("setuid", selected = mode and SftpPermissions.SETUID != 0, onClick = { toggle(SftpPermissions.SETUID) }) }
                Box(Modifier.weight(1f)) { Chip("setgid", selected = mode and SftpPermissions.SETGID != 0, onClick = { toggle(SftpPermissions.SETGID) }) }
                Box(Modifier.weight(1f)) { Chip("sticky", selected = mode and SftpPermissions.STICKY != 0, onClick = { toggle(SftpPermissions.STICKY) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            BerthField(
                octal,
                { text ->
                    val digits = text.filter { it in '0'..'7' }.take(4)
                    octal = digits
                    SftpPermissions.parseOctal(digits)?.let { mode = it }
                },
                label = "Octal",
                mono = true,
                isError = !octalValid,
                modifier = Modifier.width(112.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            Text(
                SftpPermissions.text(sample?.type ?: SftpFileType.REGULAR, mode),
                style = MonoBody,
                color = c.text2,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Apply", kind = ButtonKind.PRIMARY, enabled = octalValid && mode != (initial and SftpPermissions.MASK), onClick = { onApply(mode); onDismiss() })
            BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
        }
    }
}

// ---- Viewer ---------------------------------------------------------------------------------------

/** What the viewer has for a file. */
sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Text(val read: TextRead.Text) : ViewerContent
    data class Binary(val size: Long) : ViewerContent
    data class TooLarge(val size: Long) : ViewerContent
    data class Failed(val message: String) : ViewerContent
}

/** Read-only look at a small text file; anything else gets its size and the ways to take it off the server. */
@Composable
fun FileViewerSheet(
    entry: SftpEntry,
    content: ViewerContent,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    FilesSheet(onDismiss, tall = true) {
        SheetTitle(entry.name, "${formatSize(entry.size)} \u00B7 ${SftpPaths.parent(entry.path)}")
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(c.surface0),
        ) {
            when (content) {
                ViewerContent.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { StatusDot(SessionState.CONNECTING, size = 10.dp) }
                is ViewerContent.Text -> {
                    val text = content.read.content.ifEmpty { "This file is empty." }
                    SelectionContainer {
                        Text(
                            text,
                            style = BerthType.mono,
                            color = if (content.read.content.isEmpty()) c.text3 else c.text1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
                is ViewerContent.Binary -> ViewerNote("This is a binary file.", "Download it or share it to open it in another app.")
                is ViewerContent.TooLarge -> ViewerNote("Too large to view here.", "${formatSize(content.size)} is more than the viewer reads. Download it instead.")
                is ViewerContent.Failed -> ViewerNote("Couldn't read the file.", content.message, danger = true)
            }
        }
        if (content is ViewerContent.Text && content.read.truncated) {
            Text("Showing the first ${formatSize(content.read.content.toByteArray().size.toLong())} of ${formatSize(content.read.size)}.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Download", onClick = { onDownload(); onDismiss() }, modifier = Modifier.weight(1f))
            BerthButton("Share", onClick = { onShare(); onDismiss() }, modifier = Modifier.weight(1f))
            BerthButton("Copy path", onClick = onCopyPath, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ViewerNote(title: String, body: String, danger: Boolean = false) {
    val c = Berth.colors
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = BerthType.headline, color = if (danger) c.danger else c.text1)
        Text(body, style = BerthType.body, color = c.text2)
    }
}

// ---- Transfers ------------------------------------------------------------------------------------

/** Every transfer this process has run, newest first: per-file progress, speed, and Cancel while it moves. */
@Composable
fun TransferSheet(transfers: List<Transfer>, onCancel: (String) -> Unit, onClearFinished: () -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    val active = transfers.count { it.state.isActive }
    val done = transfers.count { it.state == TransferState.DONE }
    val failed = transfers.count { it.state == TransferState.FAILED || it.state == TransferState.CANCELLED }
    val caption = buildList {
        if (active > 0) add(if (active == 1) "1 running" else "$active running")
        if (done > 0) add("$done done")
        if (failed > 0) add("$failed stopped")
    }.joinToString(" \u00B7 ").ifEmpty { "Nothing moving" }
    FilesSheet(onDismiss) {
        SheetTitle("Transfers", caption)
        if (transfers.isEmpty()) {
            Text("Downloads and uploads show here while they run and after they finish.", style = BerthType.body, color = c.text2)
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()).fillMaxHeight(0.7f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in transfers.sortedByDescending { if (it.state.isActive) Long.MAX_VALUE else it.finishedAt }) {
                    TransferRow(t, onCancel = { onCancel(t.id) })
                }
            }
        }
        if (transfers.any { !it.state.isActive }) {
            BerthButton("Clear finished", kind = ButtonKind.TEXT, onClick = onClearFinished)
        }
    }
}

/**
 * One transfer: the name, a 2 dp progress line, and one Caption line with bytes and speed. The glyph
 * carries the direction; [showHost] names the host when transfers from several sessions share a
 * list, and [others] counts the queue behind this one for the strip.
 */
@Composable
fun TransferRow(
    t: Transfer,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    surface: Color = Berth.colors.surface2,
    showHost: Boolean = true,
    others: Int = 0,
) {
    val c = Berth.colors
    val fraction = t.fraction
    val caption = buildList {
        if (showHost) add(t.hostName)
        when (t.state) {
            TransferState.QUEUED -> add("Queued")
            TransferState.RUNNING -> {
                add(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else formatSize(t.bytes))
                formatSpeed(t.bytesPerSecond).takeIf { it.isNotEmpty() }?.let(::add)
            }
            TransferState.DONE -> {
                add(formatSize(if (t.total > 0) t.total else t.bytes))
                val seconds = ((t.finishedAt - t.startedAt) / 1000).coerceAtLeast(1)
                add(if (seconds < 60) "$seconds s" else "${seconds / 60} min")
            }
            TransferState.FAILED -> add(t.error ?: "Failed")
            TransferState.CANCELLED -> add("Cancelled")
        }
        if (others > 0) add(if (others == 1) "1 more" else "$others more")
    }.joinToString(" \u00B7 ")
    val trailing = when (t.state) {
        TransferState.RUNNING -> fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(t.bytes)
        TransferState.DONE -> "Done"
        TransferState.FAILED -> "Failed"
        TransferState.CANCELLED -> "Cancelled"
        TransferState.QUEUED -> "Queued"
    }
    val lineColor = when (t.state) {
        TransferState.FAILED -> c.danger
        TransferState.CANCELLED -> c.text3
        TransferState.DONE -> c.live
        else -> c.accent
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(surface)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            BerthIcon(if (t.kind == TransferKind.DOWNLOAD) BerthIcons.download else BerthIcons.upload, size = 20.dp, tint = if (t.state == TransferState.FAILED) c.danger else c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = BerthType.bodyMedium, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(trailing, style = BerthType.caption, color = if (t.state == TransferState.FAILED) c.danger else c.text2)
            }
            ProgressLine(fraction = if (t.state == TransferState.DONE) 1f else fraction, active = t.state == TransferState.RUNNING, color = lineColor)
            Text(
                caption,
                style = BerthType.caption,
                color = if (t.state == TransferState.FAILED) c.danger else c.text3,
                maxLines = if (t.state == TransferState.FAILED) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (t.state.isActive) {
            Spacer(Modifier.width(4.dp))
            IconAction(onClick = onCancel, description = "Cancel ${t.name}") { BerthIcon(BerthIcons.close, size = 20.dp) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * A 2 dp progress line on surface.4 with the fill in [color]; an unknown [fraction] while active
 * shows a short segment so the row still reads as moving.
 */
@Composable
fun ProgressLine(fraction: Float?, active: Boolean, color: Color = Berth.colors.accent, modifier: Modifier = Modifier) {
    val track = Berth.colors.surface4
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(CircleShape)
            .drawBehind {
                val y = size.height / 2
                drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                val f = fraction ?: if (active) 0.15f else 0f
                if (f > 0f) drawLine(color, Offset(0f, y), Offset(size.width * f, y), size.height, StrokeCap.Round)
            },
    )
}
