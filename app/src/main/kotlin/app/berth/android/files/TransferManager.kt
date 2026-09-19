package app.berth.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.berth.android.session.SessionManager
import app.berth.android.session.TerminalSession
import app.berth.domain.model.SessionState
import app.berth.sftp.ConflictChoice
import app.berth.sftp.ConflictResolution
import app.berth.sftp.FileTree
import app.berth.sftp.FolderProgress
import app.berth.sftp.FolderTransfer
import app.berth.sftp.LinkPolicy
import app.berth.sftp.LocalTree
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.sftp.SftpPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/**
 * One file, or one folder, moving in one direction; the sheet renders these and the notification
 * counts them. A folder carries its [folder] progress: counts, the file moving now, what failed,
 * and the conflict it waits on. For a folder [bytes] and [total] are the aggregate, so the strip's
 * line and speed read the same either way.
 */
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
    val folder: FolderProgress? = null,
) {
    val isFolder: Boolean get() = folder != null

    /** 0..1 when the size is known; null for an indeterminate transfer or a folder still being scanned. */
    val fraction: Float? get() = folder?.fraction ?: if (total > 0) (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

    /** True while a folder copy waits for the user to say what to do with a file that exists already. */
    val waiting: Boolean get() = state == TransferState.RUNNING && folder?.conflict != null
}

/**
 * The transfer queue: downloads to SAF documents or the share cache and uploads from picked
 * documents, one at a time per session, each on its own `sftp` channel so the browser's listing
 * never waits behind a copy. Folders go the same way as one transfer each, walked and copied by
 * [FolderTransfer] into or out of the tree the user picked; a conflict inside one pauses it until
 * [resolveConflict] answers, and what failed can go again with [retryFailed]. Lives in the session
 * scope, so leaving the Files screen changes nothing; the foreground notification names the count
 * through [SessionManager.activeTransfers].
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

    /** Opens the channel a transfer runs on; tests point this at a fake file system. */
    internal var channelFor: suspend (TerminalSession) -> SftpFileSystem = { it.openSftp() }

    /** How links inside a folder are treated; see [LinkPolicy]. */
    var linkPolicy: LinkPolicy = LinkPolicy.FOLLOW_FILE_LINKS

    private val jobs = HashMap<String, Job>()
    private val lanes = HashMap<String, Mutex>()
    private val conflicts = HashMap<String, CompletableDeferred<ConflictResolution>>()
    /** Answers that arrived in the moment between a conflict showing and the copy asking for it. */
    private val answers = HashMap<String, ConflictResolution>()
    private val folders = HashMap<String, FolderSpec>()

    /** What a folder transfer was, so a retry can run the failed part of it again. */
    private class FolderSpec(val session: TerminalSession, val kind: TransferKind, val entry: SftpEntry?, val remoteDir: String, val tree: Uri)

    /** Copies [entry] into the document at [target], which the SAF create-document flow produced. */
    fun download(session: TerminalSession, entry: SftpEntry, target: Uri): String =
        enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { h ->
            val fs = channelFor(session)
            try {
                context.contentResolver.openOutputStream(target, "wt")?.use { out -> fs.download(entry.path, out, h.bytes) }
                    ?: throw IOException("Couldn't open the destination.")
            } finally {
                fs.close()
            }
        }

    /**
     * Copies [entries] into the folder the SAF tree picker returned: each file as one transfer
     * (renamed `name (1)` when the name is taken), each folder as one folder transfer.
     */
    fun downloadInto(session: TerminalSession, entries: List<SftpEntry>, tree: Uri): List<String> = entries.mapNotNull { entry ->
        when {
            entry.isDirectory -> downloadFolder(session, entry, tree)
            entry.isRegularFile -> enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { h ->
                val local = treeFor(tree)
                val fs = channelFor(session)
                try {
                    val out = withContext(Dispatchers.IO) {
                        val taken = local.children(local.root).mapTo(HashSet()) { it.name }
                        val name = if (entry.name in taken) SftpPaths.keepBothName(entry.name, taken) else entry.name
                        local.openWrite(local.createFile(local.root, name))
                    }
                    out.use { fs.download(entry.path, it, h.bytes) }
                } finally {
                    fs.close()
                }
            }
            else -> null
        }
    }

    /**
     * Copies the folder [entry] and everything under it into the picked [tree], as a folder of the
     * same name there. Files already in place ask through the conflict on the transfer; folders merge.
     */
    fun downloadFolder(session: TerminalSession, entry: SftpEntry, tree: Uri, only: Set<String>? = null): String {
        val spec = FolderSpec(session, TransferKind.DOWNLOAD, entry, entry.path, tree)
        return enqueueFolder(spec, entry.name) { h, fs, local ->
            FolderTransfer(fs, local, linkPolicy, onProgress = h.folder, resolve = { awaitAnswer(h.id) }).download(entry.path, only)
        }
    }

    /** Copies the picked [tree] into [dir] on the server, as a folder of the tree's name, making folders as needed. */
    fun uploadFolder(session: TerminalSession, tree: Uri, dir: String, only: Set<String>? = null): String {
        val local = treeFor(tree)
        val name = local.root.name
        val spec = FolderSpec(session, TransferKind.UPLOAD, null, dir, tree)
        return enqueueFolder(spec, name, local) { h, fs, tree ->
            try {
                FolderTransfer(fs, tree, linkPolicy, onProgress = h.folder, resolve = { awaitAnswer(h.id) }).upload(dir, only)
            } finally {
                _changedFolders.tryEmit(session.id to dir)
            }
        }
    }

    /** Downloads [entry] into the app's share cache and hands it to the system share sheet when done. */
    fun share(session: TerminalSession, entry: SftpEntry): String {
        val cacheDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(cacheDir, entry.name.ifBlank { "file" })
        return enqueue(session, TransferKind.DOWNLOAD, entry.name, entry.path, entry.size) { h ->
            val fs = channelFor(session)
            try {
                file.outputStream().use { out -> fs.download(entry.path, out, h.bytes) }
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
        }
    }

    /** Uploads the picked documents into [dir] on the server, named as the picker reported them. */
    fun upload(session: TerminalSession, uris: List<Uri>, dir: String): List<String> = uris.map { uri ->
        val (name, size) = describe(uri)
        val remote = SftpPaths.join(dir, name)
        enqueue(session, TransferKind.UPLOAD, name, remote, size) { h ->
            val fs = channelFor(session)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> fs.upload(input, size, remote, h.bytes) }
                    ?: throw IOException("Couldn't read $name.")
            } finally {
                fs.close()
            }
            _changedFolders.tryEmit(session.id to dir)
        }
    }

    /** Answers the conflict a folder transfer waits on; nothing happens when it is not waiting. */
    fun resolveConflict(id: String, choice: ConflictChoice, applyToAll: Boolean) {
        val answer = ConflictResolution(choice, applyToAll)
        val asked = synchronized(conflicts) {
            conflicts[id] ?: run {
                // The row shows the question a moment before the copy starts waiting on it; an answer in that moment is kept for it.
                if (_transfers.value.any { it.id == id && it.waiting }) answers[id] = answer
                null
            }
        }
        asked?.complete(answer)
    }

    /**
     * Runs the failed part of a finished folder transfer again as a new transfer: the files and
     * folders that failed, nothing else. Null when there is nothing to retry or the session is
     * closed; one that is merely down runs and fails as not connected, the way any transfer would.
     */
    fun retryFailed(id: String): String? {
        val transfer = _transfers.value.firstOrNull { it.id == id } ?: return null
        val failed = transfer.folder?.failures?.filter { it.retryable }?.mapTo(LinkedHashSet()) { it.relativePath } ?: return null
        if (failed.isEmpty() || transfer.state.isActive) return null
        val spec = synchronized(folders) { folders[id] } ?: return null
        if (spec.session.state == SessionState.CLOSED) return null
        // The folder itself failing means everything under it goes again.
        val only = if ("" in failed) null else failed
        return when (spec.kind) {
            TransferKind.DOWNLOAD -> downloadFolder(spec.session, spec.entry!!, spec.tree, only)
            TransferKind.UPLOAD -> uploadFolder(spec.session, spec.tree, spec.remoteDir, only)
        }
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    fun cancelAll() {
        synchronized(jobs) { jobs.values.toList() }.forEach { it.cancel() }
    }

    /** Drops finished, failed and cancelled rows from the sheet. */
    fun clearFinished() {
        _transfers.update { list -> list.filter { it.state.isActive } }
        val kept = _transfers.value.mapTo(HashSet()) { it.id }
        synchronized(folders) { folders.keys.retainAll(kept) }
    }

    /** The tree behind a picked URI: a plain directory for `file:` (tests), the documents provider otherwise. */
    private fun treeFor(uri: Uri): LocalTree = if (uri.scheme == "file") FileTree(File(uri.path!!)) else SafTree(context, uri)

    private fun enqueueFolder(spec: FolderSpec, name: String, local: LocalTree? = null, run: suspend (Handle, SftpFileSystem, LocalTree) -> FolderProgress): String {
        val remotePath = spec.entry?.path ?: SftpPaths.join(spec.remoteDir, name)
        // The picker's grant is tied to the activity that asked; the copy may outlive it, so the grant is kept until the copy ends.
        val persisted = spec.tree.scheme == "content" && runCatching {
            context.contentResolver.takePersistableUriPermission(spec.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        val id = enqueue(spec.session, spec.kind, name, remotePath, total = -1L, folder = FolderProgress()) { h ->
            val tree = local ?: treeFor(spec.tree)
            val fs = channelFor(spec.session)
            try {
                run(h, fs, tree)
            } finally {
                fs.close()
                if (persisted) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(spec.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }
            }
        }
        synchronized(folders) { folders[id] = spec }
        return id
    }

    private suspend fun awaitAnswer(id: String): ConflictResolution {
        val deferred = CompletableDeferred<ConflictResolution>()
        synchronized(conflicts) {
            answers.remove(id)?.let { return it }
            conflicts[id] = deferred
        }
        try {
            return deferred.await()
        } finally {
            synchronized(conflicts) { conflicts.remove(id) }
        }
    }

    /** What a running transfer reports through: bytes for a file, the whole picture for a folder. */
    private class Handle(val id: String, val bytes: (Long, Long) -> Unit, val folder: (FolderProgress) -> Unit)

    private fun enqueue(
        session: TerminalSession,
        kind: TransferKind,
        name: String,
        remotePath: String,
        total: Long,
        folder: FolderProgress? = null,
        run: suspend (Handle) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        val transfer = Transfer(id, session.id, session.host.name, kind, name, remotePath, total, folder = folder)
        _transfers.update { it + transfer }
        publishCount()
        val lane = synchronized(lanes) { lanes.getOrPut(session.id) { Mutex() } }
        val job = scope.launch {
            lane.withLock {
                patch(id) { it.copy(state = TransferState.RUNNING, startedAt = System.currentTimeMillis()) }
                publishCount()
                val meter = SpeedMeter()
                var lastPublish = 0L
                val bytes: (Long, Long) -> Unit = { copied, size ->
                    val now = System.nanoTime()
                    val speed = meter.update(copied, now)
                    if (now - lastPublish > PUBLISH_INTERVAL_NANOS || copied == size) {
                        lastPublish = now
                        patch(id) { it.copy(bytes = copied, total = if (size > 0) size else it.total, bytesPerSecond = speed) }
                    }
                }
                var lastFolder: FolderProgress? = null
                val folderProgress: (FolderProgress) -> Unit = { p ->
                    val now = System.nanoTime()
                    val speed = meter.update(p.bytesDone, now)
                    // Chunks are throttled like bytes; a change of shape (a file done, a conflict, a failure) goes out at once.
                    if (lastFolder?.sameShape(p) != true || now - lastPublish > PUBLISH_INTERVAL_NANOS) {
                        lastPublish = now
                        lastFolder = p
                        patch(id) { it.copy(folder = p, bytes = p.bytesDone, total = p.bytesTotal, bytesPerSecond = speed) }
                    }
                }
                try {
                    run(Handle(id, bytes, folderProgress))
                    patch(id) { t ->
                        val f = t.folder
                        when {
                            f == null -> t.copy(state = TransferState.DONE, bytes = if (t.total > 0) t.total else t.bytes, finishedAt = System.currentTimeMillis())
                            f.filesFailed > 0 -> t.copy(state = TransferState.FAILED, error = folderOutcome(f), finishedAt = System.currentTimeMillis())
                            else -> t.copy(state = TransferState.DONE, finishedAt = System.currentTimeMillis())
                        }
                    }
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
                    synchronized(conflicts) { answers.remove(id) }
                    publishCount()
                }
            }
        }
        synchronized(jobs) { jobs[id] = job }
        return id
    }

    private fun FolderProgress.sameShape(other: FolderProgress): Boolean =
        phase == other.phase && filesTotal == other.filesTotal && filesDone == other.filesDone && filesCopied == other.filesCopied &&
            current == other.current && conflict == other.conflict && failures.size == other.failures.size

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

        /** The one line a finished folder with failures carries: how many, and of what. */
        fun folderOutcome(p: FolderProgress): String {
            val files = p.failures.count { it.retryable && !it.isDirectory }
            val dirs = p.failures.count { it.retryable && it.isDirectory }
            return buildList {
                if (files > 0) add(if (files == 1) "1 file" else "$files files")
                if (dirs > 0) add(if (dirs == 1) "1 folder" else "$dirs folders")
            }.joinToString(" and ") + " didn't copy."
        }
    }
}
