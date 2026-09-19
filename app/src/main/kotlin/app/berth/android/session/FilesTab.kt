package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TabKind
import app.berth.sftp.SftpPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * A Files tab (spec C3, tab kinds): the SFTP browser for one host. It has no connection of its
 * own; it rides the login of one of the host's terminal tabs, re-elected as those connect, drop
 * and close ([follow]), so closing one terminal never strands the browser while another to the
 * same host is up. The browser itself lives in `FilesCenter`, keyed by this tab's id. The tab
 * holds what the strip and the record need: the ride, its mirrored state, the folder being shown
 * (kept as `cwd` so the browser reopens there after a restart) and a title made from it.
 */
class FilesTab(
    initial: SessionRecord,
    private val scope: CoroutineScope,
    private val onRecordChanged: suspend (SessionRecord) -> Unit,
) : ManagedTab {
    override val id: String = initial.id
    override val kind: TabKind get() = TabKind.Files

    private val _record = MutableStateFlow(initial)
    override val record: StateFlow<SessionRecord> = _record.asStateFlow()
    override val host: Host get() = _record.value.hostSnapshot
    override val state: SessionState get() = _record.value.state

    @Volatile override var onStage: Boolean = false

    /** The terminal whose login the browser uses; null while the host has no terminal tab at all. */
    private val _ride = MutableStateFlow<TerminalSession?>(null)
    val ride: StateFlow<TerminalSession?> = _ride.asStateFlow()

    /** The terminal Files was opened from; it wins the next election, then the ride is sticky again. */
    private var preferredId: String? = null

    /** The folder the browser is showing, or the one to start at; the pane's "current folder". */
    val folder: String? get() = _record.value.cwd

    /** Asks the next [follow] to ride [sessionId] when it is connecting or Live. */
    @Synchronized
    fun prefer(sessionId: String?) {
        preferredId = sessionId
    }

    /**
     * Re-elects the ride from [candidates], the host's terminal tabs in strip order, and mirrors
     * its state onto the record so the tab's dot says whether the browser can list. Called by the
     * manager whenever any record changes; cheap and idempotent.
     */
    @Synchronized
    fun follow(candidates: List<TerminalSession>) {
        val records = candidates.map { it.record.value }
        val chosenId = electRide(records, currentId = _ride.value?.id, preferredId = preferredId)
        // One election consumes the preference, honoured or not; from here the ride is sticky.
        preferredId = null
        val chosen = chosenId?.let { id -> candidates.firstOrNull { it.id == id } }
        _ride.value = chosen
        val ridden = chosen?.record?.value
        val state = ridden?.state ?: SessionState.DETACHED
        val lastLiveAt = ridden?.lastLiveAt ?: _record.value.lastLiveAt
        val current = _record.value
        if (state != current.state || lastLiveAt != current.lastLiveAt) patch { copy(state = state, lastLiveAt = lastLiveAt) }
    }

    /**
     * The folder the browser has listed, from `FilesCenter`: kept as `cwd` so the tab reopens
     * there after a restart, and named in the title, which reads "Files · host" at home and
     * "Files · folder" anywhere else.
     */
    fun showing(path: String, home: String?) {
        val title = titleFor(host.name, path, home)
        val current = _record.value
        if (path == current.cwd && title == current.title) return
        patch { copy(cwd = path, title = title) }
    }

    override fun place(workspaceId: String, sortOrder: Int): SessionRecord =
        _record.updateAndGet { if (it.workspaceId == workspaceId && it.sortOrder == sortOrder) it else it.copy(workspaceId = workspaceId, sortOrder = sortOrder) }

    override fun rename(title: String?) = patch { copy(customTitle = title?.trim()?.takeIf { it.isNotEmpty() }) }

    /** A Files tab never raises attention, so there is nothing to clear. */
    override fun markSeen() = Unit

    override fun close() {
        _ride.value = null
        patch { copy(state = SessionState.CLOSED) }
    }

    private fun patch(change: SessionRecord.() -> SessionRecord) {
        val before = _record.value
        val updated = _record.updateAndGet { it.change() }
        if (updated != before) scope.launch { onRecordChanged(updated) }
    }

    companion object {
        /**
         * Which of a host's terminal tabs the browser rides, from their records in strip order:
         * the preferred one while it is connecting or Live, else the current ride while it is
         * (so a browser never hops between two live terminals), else the first Live, the first
         * connecting, then the preferred or current one in any state, then any open tab (a
         * detached ride is what the pane's Reconnect button reconnects). Null when the host has
         * no terminal tab.
         */
        fun electRide(candidates: List<SessionRecord>, currentId: String?, preferredId: String?): String? {
            val open = candidates.filter { it.state != SessionState.CLOSED }
            fun active(id: String?): SessionRecord? = id?.let { wanted -> open.firstOrNull { it.id == wanted && it.state.isActive } }
            fun any(id: String?): SessionRecord? = id?.let { wanted -> open.firstOrNull { it.id == wanted } }
            return (
                active(preferredId)
                    ?: active(currentId)
                    ?: open.firstOrNull { it.state == SessionState.LIVE }
                    ?: open.firstOrNull { it.state.isActive }
                    ?: any(preferredId)
                    ?: any(currentId)
                    ?: open.firstOrNull()
                )?.id
        }

        /** "Files · host" at home (or before any folder listed), "Files · folder" elsewhere, "Files · /" at the root. */
        fun titleFor(hostName: String, path: String?, home: String?): String {
            val where = if (path == null || path == home) hostName else SftpPaths.name(path)
            return "Files \u00B7 $where"
        }

        /** The record a fresh Files tab for [host] starts from; the caller places it and writes it. */
        fun newRecord(id: String, host: Host, workspaceId: String, createdAt: Long, folder: String? = null): SessionRecord = SessionRecord(
            id = id,
            workspaceId = workspaceId,
            hostId = host.id,
            hostSnapshot = host,
            state = SessionState.DETACHED,
            title = titleFor(host.name, null, null),
            cwd = folder,
            createdAt = createdAt,
            kind = TabKind.Files,
        )
    }
}
