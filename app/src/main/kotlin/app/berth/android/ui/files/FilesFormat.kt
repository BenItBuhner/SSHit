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

/** `233 of 612 MB` while the two share a unit, `512 KB of 612 MB` until they do: the strip's short form of `done of total`. */
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

/**
 * The Caption line under a transfer's name: the host when transfers from several sessions share a
 * list, then the size while it waits, bytes and speed while it moves, size and time once done, how
 * far it got when cancelled, the reason when it failed, and how many more wait behind it when
 * [others] is given. A folder reads in files as well as bytes: how many are done of how many, the
 * file that already exists while it waits for an answer, and once over how many copied, failed and
 * were skipped. The state word itself is [transferTrailing]'s, so it is not repeated here. [compact]
 * is the strip's one line, where a moving folder's files done of total stand in the trailing slot
 * ([transferTrailing] with the same flag) so its bytes of total, speed and what waits behind it fit.
 */
fun transferCaption(t: Transfer, showHost: Boolean = true, others: Int = 0, compact: Boolean = false): String = buildList {
    if (showHost) add(t.hostName)
    val f = t.folder
    if (f != null) addAll(folderCaption(t, f, compact)) else when (t.state) {
        TransferState.QUEUED -> add(if (t.total > 0) formatSize(t.total) else "Queued")
        TransferState.RUNNING -> {
            add(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else formatSize(t.bytes))
            formatSpeed(t.bytesPerSecond).takeIf { it.isNotEmpty() }?.let(::add)
        }
        TransferState.DONE -> {
            add(formatSize(if (t.total > 0) t.total else t.bytes))
            add(transferDuration(t))
        }
        TransferState.FAILED -> add(t.error ?: "Failed")
        TransferState.CANCELLED -> add(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else "Cancelled")
    }
    if (others > 0) add(if (others == 1) "1 more" else "$others more")
}.joinToString(" \u00B7 ")

private fun folderCaption(t: Transfer, f: FolderProgress, compact: Boolean): List<String> = buildList {
    val conflict = f.conflict
    when (t.state) {
        TransferState.QUEUED -> add("Folder")
        TransferState.RUNNING -> when {
            conflict != null -> add("${conflict.name} already exists")
            f.phase == FolderPhase.SCANNING -> add(if (f.filesTotal > 0) "${f.filesTotal} files so far" else "Folder")
            else -> {
                val bytes = when {
                    f.bytesTotal <= 0 -> null
                    compact -> formatSizeOf(f.bytesDone, f.bytesTotal)
                    else -> "${formatSize(f.bytesDone)} of ${formatSize(f.bytesTotal)}"
                }
                if (!compact || bytes == null) add("${f.filesDone} of ${f.filesTotal} files")
                bytes?.let(::add)
                formatSpeed(t.bytesPerSecond).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        TransferState.DONE -> {
            addAll(folderSummary(f))
            add(formatSize(f.bytesDone))
            add(transferDuration(t))
        }
        TransferState.FAILED -> if (f.filesTotal == 0 && f.failures.isEmpty()) add(t.error ?: "Failed") else addAll(folderSummary(f))
        TransferState.CANCELLED -> {
            add("${f.filesCopied} of ${f.filesTotal} files")
            if (f.bytesTotal > 0) add("${formatSize(f.bytesDone)} of ${formatSize(f.bytesTotal)}")
        }
    }
}

/** `309 copied · 3 failed · 2 skipped`, only the parts that are not zero; skipped covers Skip answers and links the policy left out. */
fun folderSummary(f: FolderProgress): List<String> = buildList {
    add(if (f.filesCopied == 1) "1 file copied" else "${f.filesCopied} files copied")
    if (f.filesFailed > 0) add("${f.filesFailed} failed")
    val skipped = f.filesSkipped + f.filesLeftOut
    if (skipped > 0) add("$skipped skipped")
}

private fun transferDuration(t: Transfer): String {
    val seconds = ((t.finishedAt - t.startedAt) / 1000).coerceAtLeast(1)
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min"
}

/**
 * The trailing word or percentage beside a transfer's name; on the strip ([compact]) a moving
 * folder shows its files done of total there, the percentage being the line above.
 */
fun transferTrailing(t: Transfer, compact: Boolean = false): String = when (t.state) {
    TransferState.RUNNING -> {
        val f = t.folder
        when {
            t.waiting -> "Waiting"
            f?.phase == FolderPhase.SCANNING -> "Scanning"
            compact && f != null && f.bytesTotal > 0 -> "${f.filesDone} of ${f.filesTotal}"
            else -> t.fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(t.bytes)
        }
    }
    TransferState.DONE -> "Done"
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
