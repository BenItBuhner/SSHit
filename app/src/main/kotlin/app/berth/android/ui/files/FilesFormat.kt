package app.berth.android.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.berth.android.files.Transfer
import app.berth.android.files.TransferState
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.sftp.FolderPhase
import app.berth.sftp.FolderProgress
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpFileType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** `812 B`, `4.2 KB`, `12 MB`: one decimal below ten, none above, 1024-based. */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    val units = arrayOf("KB", "MB", "GB", "TB")
    var i = -1
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    val text = if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.roundToInt().toString()
    return "$text ${units[i]}"
}

/** `2.1 MB/s`; blank below a byte a second so a stalled transfer shows nothing rather than `0 B/s`. */
fun formatSpeed(bytesPerSecond: Double): String = if (bytesPerSecond < 1) "" else formatSize(bytesPerSecond.toLong()) + "/s"

/** `233 of 612 MB` while the two share a unit, `512 KB of 612 MB` until they do: the short form of `done of total`, for a line with no room for the long one. */
fun formatSizeOf(done: Long, total: Long): String {
    val a = formatSize(done)
    val b = formatSize(total)
    val unit = " " + b.substringAfterLast(' ')
    return if (a.endsWith(unit)) "${a.removeSuffix(unit)} of $b" else "$a of $b"
}

/**
 * Extensions that are binary wherever they turn up, so the viewer offers a download without reading
 * a byte. Deliberately short: an unknown extension on a server is more often text than not.
 */
private val BinaryExtensions = setOf(
    "apk", "gz", "tgz", "tar", "zip", "7z", "xz", "zst", "bz2",
    "png", "jpg", "jpeg", "gif", "webp", "mp4", "mp3", "pdf",
    "so", "bin", "img", "iso", "jar", "db", "sqlite",
)

/** True when the name alone says the file is binary. */
fun looksBinary(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in BinaryExtensions

/** One piece of a transfer's Caption line; [danger] marks the span a partial folder outcome paints in the danger colour. */
data class CaptionPart(val text: String, val danger: Boolean = false)

/**
 * The Caption line under a transfer's name: the host when transfers from several sessions share a
 * list, then the size while it waits, bytes and speed while it moves, size and time once done, how
 * far it got when cancelled, the reason when it failed, and how many more wait behind it when
 * [others] is given. A folder reads in files as well as bytes: how many are done of how many, the
 * file that already exists while it waits for an answer, and once over how many copied, failed and
 * were skipped. The state word itself is [transferTrailing]'s, so it is not repeated here. [compact]
 * is the strip's one line, which keeps a moving folder's files done of total and its speed and
 * leaves the bytes of total to the sheet, so no line carries two `X of Y` pairs. A single file
 * that found its name taken says so while it waits (the trailing `Answer` says what to do) and,
 * once over, what became of it.
 */
fun transferCaption(t: Transfer, showHost: Boolean = true, others: Int = 0, compact: Boolean = false): String =
    transferCaptionParts(t, showHost, others, compact).joinToString(" \u00B7 ") { it.text }

/** [transferCaption] in its parts, so a row can paint one of them; only a folder's `N failed` is ever marked. */
fun transferCaptionParts(t: Transfer, showHost: Boolean = true, others: Int = 0, compact: Boolean = false): List<CaptionPart> = buildList {
    if (showHost) plain(t.hostName)
    val f = t.folder
    if (f != null) addAll(folderCaption(t, f, compact)) else when (t.state) {
        TransferState.QUEUED -> plain(if (t.total > 0) formatSize(t.total) else "Queued")
        TransferState.RUNNING -> {
            val conflict = t.conflict
            if (conflict != null) {
                plain("${conflict.name} already exists")
            } else {
                plain(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else formatSize(t.bytes))
                formatSpeed(t.bytesPerSecond).takeIf { it.isNotEmpty() }?.let(::plain)
            }
        }
        TransferState.DONE -> {
            t.note?.let(::plain)
            plain(formatSize(if (t.total > 0) t.total else t.bytes))
            plain(transferDuration(t))
        }
        TransferState.SKIPPED -> plain(t.note ?: "Skipped")
        TransferState.FAILED -> plain(t.error ?: "Failed")
        TransferState.CANCELLED -> plain(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else "Cancelled")
    }
    if (others > 0) plain(if (others == 1) "1 more" else "$others more")
}

/** [transferCaption] as one annotated line: a partial folder outcome's `N failed` in the danger colour, the rest in the colour the row gives the text. */
@Composable
fun transferCaptionStyled(t: Transfer, showHost: Boolean = true): AnnotatedString {
    val danger = Berth.colors.danger
    return buildAnnotatedString {
        transferCaptionParts(t, showHost).forEachIndexed { i, part ->
            if (i > 0) append(" \u00B7 ")
            if (part.danger) withStyle(SpanStyle(color = danger)) { append(part.text) } else append(part.text)
        }
    }
}

/**
 * A folder that ended with something copied and something left to retry: an outcome to read, not
 * a copy that failed, so the row keeps the done colour for the copied share and paints only the
 * failed part in danger. The state stays FAILED underneath, since Retry failed needs it.
 */
val Transfer.partialOutcome: Boolean get() = state == TransferState.FAILED && (folder?.filesCopied ?: 0) > 0

private fun MutableList<CaptionPart>.plain(text: String) = add(CaptionPart(text))

private fun folderCaption(t: Transfer, f: FolderProgress, compact: Boolean): List<CaptionPart> = buildList {
    val conflict = f.conflict
    when (t.state) {
        TransferState.QUEUED -> plain("Folder")
        TransferState.RUNNING -> when {
            conflict != null -> plain("${conflict.name} already exists")
            f.phase == FolderPhase.SCANNING -> plain(if (f.filesTotal > 0) "${f.filesTotal} files so far" else "Folder")
            else -> {
                plain(filesOf(f.filesDone, f.filesTotal))
                if (!compact && f.bytesTotal > 0) plain(sizeOf(f.bytesDone, f.bytesTotal))
                formatSpeed(t.bytesPerSecond).takeIf { it.isNotEmpty() }?.let(::plain)
            }
        }
        TransferState.DONE -> {
            addAll(folderSummary(f))
            plain(formatSize(f.bytesDone))
            plain(transferDuration(t))
        }
        // No file of its own failed: the copy failed whole (the scan, or the folder at the destination), and the reason is the line.
        TransferState.FAILED -> if (f.filesFailed == 0) plain(t.error ?: "Failed") else addAll(folderSummary(f))
        TransferState.CANCELLED -> {
            plain(filesOf(f.filesCopied, f.filesTotal))
            if (f.bytesTotal > 0) plain(sizeOf(f.bytesDone, f.bytesTotal))
        }
        TransferState.SKIPPED -> plain("Skipped")
    }
}

/** `513 of 1240 files` held together with non-breaking spaces, so a Caption that wraps breaks on a `·` and never inside the pair. */
private fun filesOf(done: Int, total: Int): String = "$done of $total files".unbroken()

/** `233 MB of 612 MB`, held together the same way. */
private fun sizeOf(done: Long, total: Long): String = "${formatSize(done)} of ${formatSize(total)}".unbroken()

private fun String.unbroken(): String = replace(' ', '\u00A0')

/**
 * `309 files copied · 3 failed · 2 skipped · 1 link left out`, only the parts that are not zero.
 * Skipped is what the user's Skip answers passed over and nothing else; links and special files
 * the policy left out get their own count, since Skip is the user's word in this sheet. The failed
 * part is the one a partial outcome paints in danger.
 */
fun folderSummary(f: FolderProgress): List<CaptionPart> = buildList {
    plain(if (f.filesCopied == 1) "1 file copied" else "${f.filesCopied} files copied")
    if (f.filesFailed > 0) add(CaptionPart("${f.filesFailed} failed", danger = true))
    if (f.filesSkipped > 0) plain("${f.filesSkipped} skipped")
    val links = f.linksLeftOut
    val special = f.filesLeftOut - links
    if (links > 0) plain(if (links == 1) "1 link left out" else "$links links left out")
    if (special > 0) plain(if (special == 1) "1 special file left out" else "$special special files left out")
}

private fun transferDuration(t: Transfer): String {
    val seconds = ((t.finishedAt - t.startedAt) / 1000).coerceAtLeast(1)
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min"
}

/**
 * The trailing word or percentage beside a transfer's name, the strip and the sheet alike: the
 * percentage is the measure of the line above it, so a folder shows it too and keeps its files
 * done of total for the Caption. A copy stopped on a file that exists already reads `Answer`,
 * the one word that says both that it waits and what a tap on the row does; it survives a name
 * too long for the strip's Caption, where a hint after the name would be the part cut.
 */
fun transferTrailing(t: Transfer): String = when (t.state) {
    TransferState.RUNNING -> when {
        t.waiting -> "Answer"
        t.folder?.phase == FolderPhase.SCANNING -> "Scanning"
        else -> t.fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(t.bytes)
    }
    TransferState.DONE -> "Done"
    TransferState.SKIPPED -> "Skipped"
    TransferState.FAILED -> t.folder?.filesFailed?.takeIf { it > 0 }?.let { "$it failed" } ?: "Failed"
    TransferState.CANCELLED -> "Cancelled"
    TransferState.QUEUED -> "Queued"
}

/** `14:07` today, `12 Sep 14:07` this year, `12 Sep 2025` before that; blank when the server sent no time. */
fun formatModified(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "d MMM HH:mm"
        else -> "d MMM yyyy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}

/** Directories first, then the chosen order; ties fall back to the name so the list is stable. */
fun sortEntries(entries: List<SftpEntry>, prefs: FilesPrefs): List<SftpEntry> {
    val shown = if (prefs.showHidden) entries else entries.filterNot { it.isHidden }
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER, SftpEntry::name)
    val key: Comparator<SftpEntry> = when (prefs.sort) {
        FilesSort.NAME -> byName
        FilesSort.SIZE -> compareBy<SftpEntry> { it.size }.then(byName)
        FilesSort.DATE -> compareBy<SftpEntry> { it.modifiedAt }.then(byName)
    }
    val ordered = if (prefs.ascending) key else key.reversed()
    return shown.sortedWith(compareBy<SftpEntry> { !it.isDirectory }.then(ordered))
}

/** The one Caption line under a name: kind or size, modified time, and the mode in Mono. */
@Composable
fun entryCaption(entry: SftpEntry, now: Long): AnnotatedString {
    val c = Berth.colors
    return buildAnnotatedString {
        val kind = when {
            entry.type == SftpFileType.SYMLINK -> when (entry.linkTarget) {
                SftpFileType.DIRECTORY -> "Link to folder"
                SftpFileType.REGULAR -> "Link"
                null -> "Broken link"
                else -> "Link"
            }
            entry.type == SftpFileType.DIRECTORY -> "Folder"
            entry.type == SftpFileType.OTHER -> "Special"
            else -> formatSize(entry.size)
        }
        append(kind)
        val modified = formatModified(entry.modifiedAt, now)
        if (modified.isNotEmpty()) {
            append(" \u00B7 ")
            append(modified)
        }
        append(" \u00B7 ")
        withStyle(SpanStyle(fontFamily = JetBrainsMono, color = if (entry.linkTarget == null && entry.isSymlink) c.text3 else c.text2)) {
            append(entry.permissionText)
        }
    }
}
