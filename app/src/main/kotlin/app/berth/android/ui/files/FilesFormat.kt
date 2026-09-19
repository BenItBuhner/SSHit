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
 * [others] is given. The state word itself is [transferTrailing]'s, so it is not repeated here.
 */
fun transferCaption(t: Transfer, showHost: Boolean = true, others: Int = 0): String = buildList {
    if (showHost) add(t.hostName)
    when (t.state) {
        TransferState.QUEUED -> add(if (t.total > 0) formatSize(t.total) else "Queued")
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
        TransferState.CANCELLED -> add(if (t.total > 0) "${formatSize(t.bytes)} of ${formatSize(t.total)}" else "Cancelled")
    }
    if (others > 0) add(if (others == 1) "1 more" else "$others more")
}.joinToString(" \u00B7 ")

/** The trailing word or percentage beside a transfer's name. */
fun transferTrailing(t: Transfer): String = when (t.state) {
    TransferState.RUNNING -> t.fraction?.let { "${(it * 100).toInt()}%" } ?: formatSize(t.bytes)
    TransferState.DONE -> "Done"
    TransferState.FAILED -> "Failed"
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
