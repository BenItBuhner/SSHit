package app.berth.android.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.stage.MonoBody
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionState
import app.berth.sftp.ConflictChoice
import app.berth.sftp.FolderProgress
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpFileType
import app.berth.sftp.SftpPaths
import app.berth.sftp.SftpPermissions
import app.berth.sftp.TextRead

/**
 * The sheet chrome every Files sheet shares: surface.1, the sheet radius, the handle, 20 dp margins.
 * [expanded] sheets skip the half-open stop and is decided once, when the sheet opens; [fillHeight]
 * makes the column take 92 % of the screen for content that scrolls inside (the viewer's text) and
 * may drop later so the sheet shrinks to what is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesSheet(onDismiss: () -> Unit, expanded: Boolean = false, fillHeight: Boolean = expanded, content: @Composable ColumnScope.() -> Unit) {
    val c = Berth.colors
    val skipHalf = remember { expanded }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = skipHalf)
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
                .then(if (fillHeight) Modifier.fillMaxHeight(0.92f) else Modifier)
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

/**
 * New folder and rename share this: one field, the folder it lands in as the caption, and validation
 * against the listing. The field takes focus on open; under Rename the stem is selected (`deploy` of
 * `deploy.sh`) so typing replaces the name and keeps the extension, as every file manager does.
 */
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
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = stemRange(initial))) }
    val focus = remember { FocusRequester() }
    val trimmed = field.text.trim()
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
            field,
            { field = it },
            label = "Name",
            placeholder = if (initial.isEmpty()) "New folder" else initial,
            helper = problem,
            isError = problem != null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            focusRequester = focus,
        )
        // Inside the sheet's own composition, so the field is attached in the sheet window by the time this runs.
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton(confirm, kind = ButtonKind.PRIMARY, enabled = canConfirm, onClick = { done() })
            BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
        }
    }
}

/** The part of a name before its last extension; a dotfile or a name without a dot is all stem. */
private fun stemRange(name: String): TextRange {
    val dot = name.lastIndexOf('.')
    return TextRange(0, if (dot > 0) dot else name.length)
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
 * bits on their own row and the octal field kept in step both ways. Off bits read as `-`, the way
 * the mode string beside the field does. When the selected entries do not agree, the chips show the
 * first entry's bits for reference and the field starts empty, so Apply always writes a mode the
 * user chose rather than one entry's bits spread over the rest.
 */
@Composable
fun ChmodSheet(entries: List<SftpEntry>, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    val sample = entries.firstOrNull()
    val modes = entries.map { it.permissions and SftpPermissions.MASK }.distinct()
    val mixed = modes.size > 1
    val initial = modes.firstOrNull() ?: 0b110_100_100
    var mode by remember { mutableIntStateOf(initial) }
    var octal by remember { mutableStateOf(if (mixed) "" else SftpPermissions.octal(initial)) }
    val octalValid = SftpPermissions.parseOctal(octal) != null
    fun set(bits: Int) {
        mode = bits and SftpPermissions.MASK
        octal = SftpPermissions.octal(mode)
    }
    fun toggle(bit: Int) = set(mode xor bit)
    val caption = when {
        sample == null -> null
        entries.size == 1 -> sample.path
        mixed -> "${entries.size} items in ${SftpPaths.parent(sample.path)} \u00B7 modes differ, showing ${sample.name}"
        else -> "${entries.size} items in ${SftpPaths.parent(sample.path)}"
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
                        val on = mode and bit != 0
                        Box(Modifier.weight(1f)) {
                            Chip(if (on) letter else "-", selected = on, mono = true, onClick = { toggle(bit) })
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
                placeholder = if (mixed) SftpPermissions.octal(initial) else null,
                mono = true,
                isError = octal.isNotEmpty() && !octalValid,
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
            BerthButton("Apply", kind = ButtonKind.PRIMARY, enabled = octalValid && (mixed || mode != initial), onClick = { onApply(mode); onDismiss() })
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

/**
 * Read-only look at a small text file. [tall] is decided from the entry before anything is read:
 * a text file gets the 92 % sheet with a Mono well that fills it; a file the name or size already
 * says is not viewable gets a compact sheet with one Caption line and the ways to take it off the
 * server. When a tall read turns out binary or fails, the well goes and the sheet shrinks to the note.
 */
@Composable
fun FileViewerSheet(
    entry: SftpEntry,
    content: ViewerContent,
    tall: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    val well = content is ViewerContent.Loading || content is ViewerContent.Text
    FilesSheet(onDismiss, expanded = tall, fillHeight = tall && well) {
        SheetTitle(entry.name, "${formatSize(entry.size)} \u00B7 ${SftpPaths.parent(entry.path)}")
        when (content) {
            ViewerContent.Loading, is ViewerContent.Text -> Box(
                Modifier
                    .then(if (tall) Modifier.weight(1f) else Modifier)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BerthRadius.row))
                    .background(c.surface0),
            ) {
                if (content is ViewerContent.Text) {
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
                } else {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { StatusDot(SessionState.CONNECTING, size = 10.dp) }
                }
            }
            is ViewerContent.Binary -> ViewerNote("Binary file. Download it or share it to open it elsewhere.")
            is ViewerContent.TooLarge -> ViewerNote("${formatSize(content.size)} is more than the viewer reads. Download it instead.")
            is ViewerContent.Failed -> ViewerNote("Couldn't read the file. ${content.message}", danger = true)
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

/** One Caption line where the text would have been: what the file is and what to do with it. */
@Composable
private fun ViewerNote(text: String, danger: Boolean = false) {
    Text(text, style = BerthType.caption, color = if (danger) Berth.colors.danger else Berth.colors.text3, modifier = Modifier.padding(start = 4.dp))
}

// ---- Transfers ------------------------------------------------------------------------------------

/**
 * Every transfer this process has run, newest first: per-file progress, speed, and Cancel while it
 * moves. The list is the one weighted child, measured after the title and Clear finished have their
 * height, so it scrolls inside what is left and the button never gets squeezed out. More than a
 * handful of rows opens the sheet fully expanded rather than at the half stop. A folder is one row
 * that opens on a tap to the file moving now and what has failed; one waiting for an answer goes to
 * its question through [onAnswer] instead.
 */
@Composable
fun TransferSheet(
    transfers: List<Transfer>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
    onRetryFailed: (String) -> Unit = {},
    onAnswer: (String) -> Unit = {},
) {
    val c = Berth.colors
    val active = transfers.count { it.state.isActive }
    val done = transfers.count { it.state == TransferState.DONE }
    val failed = transfers.count { it.state == TransferState.FAILED }
    val skipped = transfers.count { it.state == TransferState.SKIPPED }
    val cancelled = transfers.count { it.state == TransferState.CANCELLED }
    val caption = buildList {
        if (active > 0) add(if (active == 1) "1 running" else "$active running")
        if (done > 0) add("$done done")
        if (failed > 0) add("$failed failed")
        if (skipped > 0) add("$skipped skipped")
        if (cancelled > 0) add("$cancelled cancelled")
    }.joinToString(" \u00B7 ").ifEmpty { "Nothing moving" }
    var opened by remember { mutableStateOf(emptySet<String>()) }
    FilesSheet(onDismiss, expanded = transfers.size > 4, fillHeight = false) {
        SheetTitle("Transfers", caption)
        if (transfers.isEmpty()) {
            Text("Downloads and uploads show here while they run and after they finish.", style = BerthType.body, color = c.text2)
        } else {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in transfers.sortedByDescending { if (it.state.isActive) Long.MAX_VALUE else it.finishedAt }) {
                    TransferRow(
                        t,
                        onCancel = { onCancel(t.id) },
                        expanded = t.id in opened,
                        // A row waiting on an answer, a folder's or a single file's, goes to its question; a folder otherwise opens.
                        onToggle = when {
                            t.waiting -> ({ onAnswer(t.id) })
                            t.isFolder -> ({ opened = if (t.id in opened) opened - t.id else opened + t.id })
                            else -> null
                        },
                        onRetryFailed = { onRetryFailed(t.id) },
                    )
                }
            }
        }
        if (transfers.any { !it.state.isActive }) {
            BerthButton("Clear finished", kind = ButtonKind.TEXT, onClick = onClearFinished)
        }
    }
}

/**
 * One transfer in the sheet: the name with its state word or percentage, a 2 dp progress line, and
 * one Caption line naming the host, then bytes and speed. The glyph carries the direction. A folder
 * carries the aggregate on that line, given two like a failure's reason, and a chevron; [expanded]
 * it adds the file moving now with its own line, the last few failures while it runs, and once over
 * everything that failed with Retry failed. A folder that copied most of itself and lost a few
 * files is drawn as that outcome: the line in the done colour for the copied share with the failed
 * share in danger after it, the glyph and Caption in their usual colours and only the `N failed`
 * span and the trailing count in danger. The whole-failure treatment is kept for a copy that
 * failed outright. The per-file rows are as they were; one waiting on an answer taps through to it.
 */
@Composable
fun TransferRow(
    t: Transfer,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onRetryFailed: (() -> Unit)? = null,
) {
    val c = Berth.colors
    val partial = t.partialOutcome
    val failed = t.state == TransferState.FAILED && !partial
    val folder = t.folder
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(c.surface2)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .then(if (folder != null && onToggle != null) Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" } else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                BerthIcon(t.kind.icon, size = 20.dp, tint = if (failed) c.danger else c.text2)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = BerthType.bodyMedium, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(transferTrailing(t), style = BerthType.caption, color = if (t.state == TransferState.FAILED) c.danger else c.text2)
                    if (folder != null) {
                        Spacer(Modifier.width(4.dp))
                        BerthIcon(BerthIcons.chevronRight, size = 16.dp, tint = c.text3, modifier = Modifier.rotate(if (expanded) 90f else 0f))
                    }
                }
                if (partial && folder != null) {
                    val (copied, lost) = folder.outcomeShares()
                    ProgressLine(fraction = copied, active = false, color = c.live, failedFraction = lost)
                } else {
                    ProgressLine(fraction = if (t.state == TransferState.DONE) 1f else t.fraction, active = t.state == TransferState.RUNNING, color = t.lineColor)
                }
                Text(
                    transferCaptionStyled(t),
                    style = BerthType.caption,
                    color = if (failed) c.danger else c.text3,
                    maxLines = if (failed || folder != null || t.note != null) 2 else 1,
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
        if (folder != null && expanded) {
            FolderDetail(t, folder, onRetryFailed = onRetryFailed?.takeIf { !t.state.isActive && folder.filesFailed > 0 })
        }
    }
}

/**
 * The two spans of a partial outcome's line, by bytes as the line always is: the copied share in
 * the done colour, then the failed share in danger. A failure with no bytes behind it (a folder
 * that would not list) still gets a sliver, so the line says something failed; the copied span
 * gives way to it rather than the line running past its end.
 */
private fun FolderProgress.outcomeShares(): Pair<Float, Float> {
    val copied = when {
        bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
        filesTotal > 0 -> (filesCopied.toFloat() / filesTotal).coerceIn(0f, 1f)
        else -> 1f
    }
    val failedShare = if (bytesTotal > 0) (bytesFailed.toDouble() / bytesTotal).toFloat() else 0f
    val failed = if (filesFailed > 0) failedShare.coerceIn(FAILED_SLIVER, 1f) else 0f
    return copied.coerceAtMost(1f - failed) to failed
}

private const val FAILED_SLIVER = 0.03f

/**
 * Under an expanded folder row, aligned with its text on both sides: the file moving now with its
 * own 2 dp line and bytes, its path cut in the middle so the name survives; then the failures, the
 * last three while the copy runs and up to eight once it is over, each as its path and the reason
 * in one Caption; then Retry failed when there is something to retry. Opening a row brings the
 * detail into view, so what the tap was for never lands under the sheet's fold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderDetail(t: Transfer, f: FolderProgress, onRetryFailed: (() -> Unit)?) {
    val c = Berth.colors
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { requester.bringIntoView() }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            // The row's text column ends before Cancel while the copy runs and at the card's padding once it is over; the detail's lines share that right edge.
            .padding(start = 60.dp, end = if (t.state.isActive) 52.dp else 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = f.current
        if (current != null && t.state == TransferState.RUNNING) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current, style = BerthType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (f.currentTotal > 0) "${formatSize(f.currentBytes)} of ${formatSize(f.currentTotal)}" else formatSize(f.currentBytes),
                        style = BerthType.caption,
                        color = c.text3,
                    )
                }
                ProgressLine(fraction = if (f.currentTotal > 0) (f.currentBytes.toDouble() / f.currentTotal).coerceIn(0.0, 1.0).toFloat() else null, active = true)
            }
        }
        if (f.failures.isNotEmpty()) {
            val shown = if (t.state.isActive) f.failures.takeLast(RECENT_FAILURES) else f.failures.take(SUMMARY_FAILURES)
            val more = f.failures.size - shown.size
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (t.state.isActive) "Didn't copy so far" else "Didn't copy",
                    style = BerthType.caption,
                    color = c.text2,
                )
                for (failure in shown) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (failure.retryable) c.danger else c.text3)) { append(failure.relativePath) }
                            append(" \u00B7 ")
                            append(failure.message)
                        },
                        style = BerthType.caption,
                        color = c.text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (more > 0) Text(if (more == 1) "and 1 more" else "and $more more", style = BerthType.caption, color = c.text3)
            }
        }
        if (onRetryFailed != null) {
            BerthButton("Retry failed", onClick = onRetryFailed)
        }
    }
}

private const val RECENT_FAILURES = 3
private const val SUMMARY_FAILURES = 8

// ---- A file that exists already ------------------------------------------------------------------

/**
 * A copy has met a file that already exists where it is going, and waits: a file inside a folder
 * being copied, or a single file of a selection going into the picked folder. The sheet says which
 * file, where it is going and on which host, then what is coming and what is there by size and
 * modified time, the newer of the two saying so, and offers Overwrite, Skip and Keep both, with
 * Apply to all for the files still to come that turn out to exist too; folders never ask, they
 * merge. Dismissing leaves the copy waiting; the strip and the transfer's row bring the question
 * back. [now] dates the times the way the listing does.
 */
@Composable
fun FolderConflictSheet(transfer: Transfer, now: Long, onChoose: (ConflictChoice, applyToAll: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    val conflict = transfer.pendingConflict ?: return
    var applyToAll by remember(conflict) { mutableStateOf(false) }
    val download = transfer.kind == TransferKind.DOWNLOAD
    val incomingWhere = if (download) "On the server" else "On this device"
    val existingWhere = if (download) "On this device" else "On the server"
    // Inside a folder the path is relative to it; a single file's path names the folder it is going into.
    val dir = conflict.relativePath.substringBeforeLast('/', "")
    val within = if (transfer.isFolder) (if (dir.isEmpty()) transfer.name else "${transfer.name}/$dir") else dir
    val newer = conflict.incomingIsNewer
    fun line(size: Long, modified: Long?, isNewer: Boolean, then: String) = buildList {
        formatSize(size).takeIf { it.isNotEmpty() }?.let(::add)
        modified?.let { formatModified(it, now) }?.takeIf { it.isNotEmpty() }?.let { date ->
            add(date)
            if (isNewer) add("newer")
        }
        add(then)
    }.joinToString(" \u00B7 ")
    FilesSheet(onDismiss) {
        SheetTitle("${conflict.name} already exists", listOfNotNull(within.takeIf { it.isNotEmpty() }?.let { "In $it" }, transfer.hostName).joinToString(" \u00B7 "))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ListRow(
                title = incomingWhere,
                subtitle = line(conflict.incomingSize, conflict.incomingModified, newer == true, "coming in"),
                minHeight = 48.dp,
                leading = { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { BerthIcon(transfer.kind.icon, size = 20.dp) } },
            )
            ListRow(
                title = existingWhere,
                subtitle = line(conflict.existingSize, conflict.existingModified, newer == false, "already there"),
                minHeight = 48.dp,
                leading = { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { BerthIcon(BerthIcons.file, size = 20.dp, tint = c.text3) } },
            )
        }
        if (conflict.remaining > 0) {
            ToggleRow(
                title = "Apply to all",
                checked = applyToAll,
                onCheckedChange = { applyToAll = it },
                // The answer is consulted only for a file that turns out to exist; the rest copy regardless, so the count is of candidates, not of files skipped.
                caption = if (conflict.remaining == 1) "For the 1 file still to copy, if it already exists" else "For any of the ${conflict.remaining} files still to copy that already exist",
            )
        }
        // Destructive furthest from the thumb's resting side, the safe choice in the affirmative position.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Overwrite", kind = ButtonKind.DESTRUCTIVE, onClick = { onChoose(ConflictChoice.OVERWRITE, applyToAll) }, modifier = Modifier.weight(1f))
            BerthButton("Skip", onClick = { onChoose(ConflictChoice.SKIP, applyToAll) }, modifier = Modifier.weight(1f))
            BerthButton("Keep both", onClick = { onChoose(ConflictChoice.KEEP_BOTH, applyToAll) }, modifier = Modifier.weight(1f))
        }
        Text(
            "Overwrite replaces it. Skip leaves what is there. Keep both saves the new one as ${SftpPaths.keepBothName(conflict.name, emptySet())}.",
            style = BerthType.caption,
            color = c.text3,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** The direction glyph for a transfer. */
val TransferKind.icon: Int get() = if (this == TransferKind.DOWNLOAD) BerthIcons.download else BerthIcons.upload

/** The progress line's colour by state: danger when failed, muted when cancelled or skipped, live once done, accent while moving. */
val Transfer.lineColor: Color
    @Composable get() = when (state) {
        TransferState.FAILED -> Berth.colors.danger
        TransferState.CANCELLED, TransferState.SKIPPED -> Berth.colors.text3
        TransferState.DONE -> Berth.colors.live
        else -> Berth.colors.accent
    }

/**
 * A 2 dp progress line on surface.4 with the fill in [color]; an unknown [fraction] while active
 * shows a short segment so the row still reads as moving. A [failedFraction] draws a second span
 * in danger right after the first, for a folder that ended with part of it failed. Inside a row
 * the ends are round; as the top [edge] of a band they are square so the line meets the band's sides.
 */
@Composable
fun ProgressLine(fraction: Float?, active: Boolean, color: Color = Berth.colors.accent, modifier: Modifier = Modifier, edge: Boolean = false, failedFraction: Float = 0f) {
    val track = Berth.colors.surface4
    val danger = Berth.colors.danger
    val cap = if (edge) StrokeCap.Butt else StrokeCap.Round
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .then(if (edge) Modifier else Modifier.clip(CircleShape))
            .drawBehind {
                val y = size.height / 2
                drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, cap)
                val f = fraction ?: if (active) 0.15f else 0f
                if (f > 0f) drawLine(color, Offset(0f, y), Offset(size.width * f, y), size.height, cap)
                if (failedFraction > 0f) {
                    val end = (f + failedFraction).coerceAtMost(1f)
                    if (end > f) drawLine(danger, Offset(size.width * f, y), Offset(size.width * end, y), size.height, cap)
                }
            },
    )
}
