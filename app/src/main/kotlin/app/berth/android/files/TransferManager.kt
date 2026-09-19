package app.berth.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.berth.android.session.SessionManager
import app.berth.android.session.TerminalSession
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class TransferKind {
    DOWNLOAD, UPLOAD;

    val label: String get() = if (this == DOWNLOAD) "Download" else "Upload"
}

enum class TransferState {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

/** One file moving in one direction; the sheet renders these and the notification counts them. */
data class Transfer(
    val id: String,
    val sessionId: String,
    val hostName: String,
    val kind: TransferKind,
    val name: String,
    val remotePath: String,
    /** Bytes expected, or -1 when the source did not say. */
    val total: Long,
    val bytes: Long = 0L,
    val bytesPerSecond: Double = 0.0,
    val state: TransferState = TransferState.QUEUED,
    val error: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
) {
    /** 0..1 when the size is known; null for an indeterminate transfer. */
    val fraction: Float? get() = if (total > 0) (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
}

/**
 * The transfer queue: downloads to SAF documents or the share cache and uploads from picked
 * documents, one at a time per session, each on its own `sftp` channel so the browser's listing
 * never waits behind a copy. Lives in the session scope, so leaving the Files screen changes
 * nothing; the foreground notification names the count through [SessionManager.activeTransfers].
 */
class TransferManager(
    private val context: Context,
    private val sessions: SessionManager,
    private val scope: CoroutineScope = sessions.scope,
) {
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    /** Folders whose contents just changed on the server, so an open browser can re-list. */
    private val _changedFolders = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val changedFolders: SharedFlow<Pair<String, String>> = _changedFolders.asSharedFlow()

    private val jobs = HashMap<String, Job>()
    private val lanes = HashMap<String, Mutex>()

    /** Copies [entry] into the document at [target], which the SAF create-document flow produced. */
    fun download(session: TerminalSession, entry: SftpEntry, target: Uri): String =
        enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { t, progress ->
            val fs = session.openSftp()
            try {
                context.contentResolver.openOutputStream(target, "wt")?.use { out -> fs.download(entry.path, out, progress) }
                    ?: throw IOException("Couldn't open the destination.")
            } finally {
                fs.close()
            }
            t
        }

    /** Copies every file in [entries] into the folder the SAF tree picker returned; folders inside are skipped. */
    fun downloadInto(session: TerminalSession, entries: List<SftpEntry>, tree: Uri): List<String> {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return entries.filter { it.isRegularFile }.map { entry ->
            enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { t, progress ->
                val doc = withContext(Dispatchers.IO) {
                    DocumentsContract.createDocument(context.contentResolver, dir, mimeFor(entry.name), entry.name)
                } ?: throw IOException("Couldn't create ${entry.name} in the chosen folder.")
                val fs = session.openSftp()
                try {
                    context.contentResolver.openOutputStream(doc, "wt")?.use { out -> fs.download(entry.path, out, progress) }
                        ?: throw IOException("Couldn't open the destination.")
                } finally {
                    fs.close()
                }
                t
            }
        }
    }

    /** Downloads [entry] into the app's share cache and hands it to the system share sheet when done. */
    fun share(session: TerminalSession, entry: SftpEntry): String {
        val cacheDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(cacheDir, entry.name.ifBlank { "file" })
        return enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { t, progress ->
            val fs = session.openSftp()
            try {
                file.outputStream().use { out -> fs.download(entry.path, out, progress) }
            } finally {
                fs.close()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(entry.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, entry.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, entry.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
            t
        }
    }

    /** Uploads the picked documents into [dir] on the server, named as the picker reported them. */
    fun upload(session: TerminalSession, uris: List<Uri>, dir: String): List<String> = uris.map { uri ->
        val (name, size) = describe(uri)
        val remote = SftpPaths.join(dir, name)
        enqueue(session, TransferKind.UPLOAD, name, remote, size) { t, progress ->
            val fs = session.openSftp()
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> fs.upload(input, size, remote, progress) }
                    ?: throw IOException("Couldn't read $name.")
            } finally {
                fs.close()
            }
            _changedFolders.tryEmit(session.id to dir)
            t
        }
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        jobs.values.toList().forEach { it.cancel() }
    }

    /** Drops finished, failed and cancelled rows from the sheet. */
    fun clearFinished() {
        _transfers.update { list -> list.filter { it.state.isActive } }
    }

    private fun enqueue(
        session: TerminalSession,
        kind: TransferKind,
        name: String,
        remotePath: String,
        total: Long,
        run: suspend (Transfer, (Long, Long) -> Unit) -> Transfer,
    ): String {
        val id = UUID.randomUUID().toString()
        val transfer = Transfer(id, session.id, session.host.name, kind, name, remotePath, total)
        _transfers.update { it + transfer }
        publishCount()
        val lane = synchronized(lanes) { lanes.getOrPut(session.id) { Mutex() } }
        val job = scope.launch {
            lane.withLock {
                patch(id) { it.copy(state = TransferState.RUNNING, startedAt = System.currentTimeMillis()) }
                publishCount()
                val meter = SpeedMeter()
                var lastPublish = 0L
                val progress: (Long, Long) -> Unit = { bytes, size ->
                    val now = System.nanoTime()
                    val speed = meter.update(bytes, now)
                    if (now - lastPublish > PUBLISH_INTERVAL_NANOS || bytes == size) {
                        lastPublish = now
                        patch(id) { it.copy(bytes = bytes, total = if (size > 0) size else it.total, bytesPerSecond = speed) }
                    }
                }
                try {
                    run(transfer, progress)
                    patch(id) { it.copy(state = TransferState.DONE, bytes = if (it.total > 0) it.total else it.bytes, finishedAt = System.currentTimeMillis()) }
                } catch (e: CancellationException) {
                    patch(id) { it.copy(state = TransferState.CANCELLED, finishedAt = System.currentTimeMillis()) }
                    throw e
                } catch (e: Throwable) {
                    val reason = when (e) {
                        is SftpError -> e.message ?: "The transfer failed."
                        is IOException -> e.message ?: "The transfer failed."
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    patch(id) { it.copy(state = TransferState.FAILED, error = reason, finishedAt = System.currentTimeMillis()) }
                } finally {
                    synchronized(jobs) { jobs.remove(id) }
                    publishCount()
                }
            }
        }
        synchronized(jobs) { jobs[id] = job }
        return id
    }

    private fun patch(id: String, change: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun publishCount() {
        sessions.activeTransfers.value = _transfers.value.count { it.state.isActive }
    }

    /** Display name and size of a picked document; the size is -1 when the provider does not say. */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return (name?.takeIf { SftpPaths.isValidName(it) } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { SftpPaths.isValidName(it) } ?: "upload") to size
    }

    /** Bytes per second smoothed over the last second or so, so the number does not flicker. */
    private class SpeedMeter {
        private var lastBytes = 0L
        private var lastNanos = 0L
        private var speed = 0.0

        fun update(bytes: Long, nanos: Long): Double {
            if (lastNanos == 0L) {
                lastNanos = nanos
                lastBytes = bytes
                return 0.0
            }
            val dt = nanos - lastNanos
            if (dt < SAMPLE_NANOS) return speed
            val instant = (bytes - lastBytes) * 1e9 / dt
            speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
            lastBytes = bytes
            lastNanos = nanos
            return speed
        }
    }

    companion object {
        private const val SHARE_DIR = "shared"
        private const val PUBLISH_INTERVAL_NANOS = 120_000_000L
        private const val SAMPLE_NANOS = 250_000_000L

        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
