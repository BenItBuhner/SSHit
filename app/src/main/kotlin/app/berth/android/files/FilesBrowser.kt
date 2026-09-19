package app.berth.android.files

import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.sftp.SftpPaths
import app.berth.sftp.TextRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The folder the browser is showing, or trying to. */
data class FilesState(
    val path: String,
    val entries: List<SftpEntry> = emptyList(),
    /** True while the first listing of [path] is on its way; the list area shows nothing yet. */
    val loading: Boolean = true,
    /** True while a pull to refresh re-lists [path] over the entries already shown. */
    val refreshing: Boolean = false,
    /** Set when listing [path] failed; [entries] are then empty and the screen offers a way out. */
    val error: SftpError? = null,
    /** The login's home directory once resolved. */
    val home: String? = null,
    /** True when a folder was visited before this one, so the system back gesture can return to it. */
    val canGoBack: Boolean = false,
)

/** A short line for the screen after an action: what happened, or what went wrong. */
data class Notice(val text: String, val isError: Boolean, val at: Long = System.currentTimeMillis())

/**
 * One session's file browser: the current folder, its listing, and the operations on it. The
 * `sftp` channel opens on first use through [open] and reopens after the connection dropped, so
 * a reconnected terminal keeps its browser. All channel work is serialised on one lock; a new
 * navigation cancels a listing that is still on its way.
 */
class FilesBrowser(
    val sessionId: String,
    private val scope: CoroutineScope,
    private val open: suspend () -> SftpFileSystem,
    private val onVisited: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(FilesState(path = SftpPaths.ROOT))
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private var fs: SftpFileSystem? = null
    private val gate = Mutex()
    private var listJob: Job? = null
    private var noticeJob: Job? = null

    /** Folders shown before the current one, most recent last; the back gesture pops them. */
    private val history = ArrayDeque<String>()

    /**
     * Where each folder's list was scrolled to (first visible row and its offset). It lives here rather
     * than in the pane so coming back to a folder, or back to the screen after a tab switch or a
     * rotation, lands where you left. Only the UI thread touches it.
     */
    private val scrollPositions = LinkedHashMap<String, Pair<Int, Int>>()

    fun rememberScroll(path: String, index: Int, offset: Int) {
        scrollPositions.remove(path)
        scrollPositions[path] = index to offset
        while (scrollPositions.size > SCROLL_MAX) scrollPositions.remove(scrollPositions.keys.first())
    }

    /** The saved scroll of [path], or the top when it was never left. */
    fun scrollFor(path: String): Pair<Int, Int> = scrollPositions[path] ?: (0 to 0)

    /** Resolves home, then lists [initialPath] when it is still there or home otherwise. */
    fun start(initialPath: String?) {
        listJob?.cancel()
        listJob = scope.launch {
            val home = runCatching { withChannel { it.home() } }.getOrNull()
            _state.update { it.copy(home = home) }
            val first = initialPath ?: home ?: SftpPaths.ROOT
            if (!list(first, keepEntries = false) && first != (home ?: SftpPaths.ROOT) && _state.value.error is SftpError.NotFound) {
                list(home ?: SftpPaths.ROOT, keepEntries = false)
            }
        }
    }

    fun navigate(path: String) {
        val target = SftpPaths.normalize(if (path.startsWith("/")) path else SftpPaths.join(_state.value.path, path))
        val current = _state.value
        if (target == current.path && current.error == null) {
            refresh()
            return
        }
        // Only a folder that actually listed is worth coming back to.
        if (current.error == null && !current.loading) remember(current.path)
        listJob?.cancel()
        listJob = scope.launch { list(target, keepEntries = false) }
    }

    /** Returns to the folder shown before this one; false when there is none. */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        _state.update { it.copy(canGoBack = history.isNotEmpty()) }
        listJob?.cancel()
        listJob = scope.launch { list(previous, keepEntries = false) }
        return true
    }

    private fun remember(path: String) {
        history.remove(path)
        history.addLast(path)
        while (history.size > HISTORY_MAX) history.removeFirst()
        _state.update { it.copy(canGoBack = true) }
    }

    fun up() = navigate(SftpPaths.parent(_state.value.path))

    fun home() {
        _state.value.home?.let { navigate(it) } ?: navigate(SftpPaths.ROOT)
    }

    /** Re-lists the current folder over what is already shown. */
    fun refresh() {
        listJob?.cancel()
        listJob = scope.launch { list(_state.value.path, keepEntries = true) }
    }

    /** Lists [path]; returns false when the listing failed and the error is on the state. */
    private suspend fun list(path: String, keepEntries: Boolean): Boolean {
        _state.update {
            if (keepEntries && it.path == path && it.error == null) it.copy(refreshing = true)
            else it.copy(path = path, loading = true, refreshing = false, error = null, entries = if (it.path == path) it.entries else emptyList())
        }
        return try {
            val entries = withChannel { it.list(path) }
            _state.update { it.copy(path = path, entries = entries, loading = false, refreshing = false, error = null) }
            onVisited(path)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SftpError) {
            _state.update { it.copy(path = path, entries = emptyList(), loading = false, refreshing = false, error = e) }
            false
        }
    }

    // ---- operations ------------------------------------------------------------------------------

    // Changes run in the session's scope, so leaving the screen mid-way never cuts one short.

    fun mkdir(name: String) = mutate("Created $name") { fs ->
        fs.mkdir(SftpPaths.join(_state.value.path, name))
    }

    fun rename(entry: SftpEntry, newName: String) = mutate("Renamed to $newName") { fs ->
        fs.rename(entry.path, SftpPaths.join(SftpPaths.parent(entry.path), newName))
    }

    /** Deletes in order and stops at the first failure, so what is gone is exactly what was reported. */
    fun delete(entries: List<SftpEntry>) {
        val label = if (entries.size == 1) "Deleted ${entries.single().name}" else "Deleted ${entries.size} items"
        mutate(label) { fs -> for (e in entries) fs.delete(e.path) }
    }

    fun chmod(entries: List<SftpEntry>, permissions: Int) {
        val label = if (entries.size == 1) "Changed ${entries.single().name}" else "Changed ${entries.size} items"
        mutate(label) { fs -> for (e in entries) fs.chmod(e.path, permissions) }
    }

    suspend fun readText(entry: SftpEntry, maxBytes: Int = VIEWER_MAX_BYTES): Result<TextRead> =
        runOp { fs -> fs.readText(entry.path, maxBytes) }

    /** `stat` following symlinks, so a tap on a link knows whether it opens a folder or a file. */
    suspend fun stat(entry: SftpEntry): Result<SftpEntry> = runOp { fs -> fs.stat(entry.path) }

    /** Runs a change on the channel, then re-lists and posts a notice either way. */
    private fun mutate(success: String, block: suspend (SftpFileSystem) -> Unit): Job = scope.launch {
        val result = runOp(block)
        val error = result.exceptionOrNull() as? SftpError
        post(Notice(error?.message ?: success, isError = error != null))
        refresh()
    }

    private suspend fun <T> runOp(block: suspend (SftpFileSystem) -> T): Result<T> = try {
        Result.success(withChannel(block))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SftpError) {
        Result.failure(e)
    }

    /**
     * Hands the open channel to [block] under the lock. A dropped connection surfaces as
     * [SftpError.Io] or [SftpError.NotConnected]; the channel is discarded so the next call reopens.
     */
    private suspend fun <T> withChannel(block: suspend (SftpFileSystem) -> T): T = gate.withLock {
        val channel = fs?.takeIf { it.isOpen } ?: open().also { fs = it }
        try {
            block(channel)
        } catch (e: SftpError.Io) {
            dropChannel()
            throw e
        } catch (e: SftpError.NotConnected) {
            dropChannel()
            throw e
        }
    }

    private fun dropChannel() {
        fs?.let { runCatching(it::close) }
        fs = null
    }

    fun post(notice: Notice) {
        _notice.value = notice
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(if (notice.isError) ERROR_NOTICE_MS else NOTICE_MS)
            _notice.update { if (it === notice) null else it }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun close() {
        listJob?.cancel()
        noticeJob?.cancel()
        scope.launch { gate.withLock { dropChannel() } }
    }

    companion object {
        /** The viewer reads this much; larger files are shown truncated with the size named. */
        const val VIEWER_MAX_BYTES = 256 * 1024
        /** Files above this are not read for the viewer at all; the sheet offers a download instead. */
        const val VIEWER_SKIP_BYTES = 8L * 1024 * 1024
        private const val HISTORY_MAX = 32
        private const val SCROLL_MAX = 64
        private const val NOTICE_MS = 2_500L
        private const val ERROR_NOTICE_MS = 6_000L
    }
}
