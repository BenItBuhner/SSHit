package app.berth.android.files

import android.content.Context
import app.berth.android.session.FilesTab
import app.berth.android.session.SessionManager
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.domain.model.SessionState
import app.berth.domain.repository.SettingsRepository
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [FilesBrowser] per Files tab and the [TransferManager], both living as long as their
 * tab or session does rather than the screen, so a browser keeps its folder and a copy keeps
 * running while the user is on another tab. The browser follows the tab's ride: a new ride means
 * a fresh channel, a ride coming back means a re-list, and every folder that lists is written to
 * the tab for its title and for reopening after a restart. A transfer stopped on a question is
 * told to the Files tab of its session, which raises attention while the user is elsewhere.
 * Preferences are the JSON document in settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FilesCenter @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
) {
    private val scope = sessions.scope
    val prefs: StateFlow<FilesPrefs> = settings.filesPrefs.stateIn(scope, SharingStarted.Eagerly, FilesPrefs())
    val transfers = TransferManager(context, sessions)

    /**
     * Opens the `sftp` channel a Files tab's browser uses: one on the ride's connection, or
     * [SftpError.NotConnected] while the tab has no live terminal to ride. Tests point this at a
     * fake file system so the tab renders without a server.
     */
    internal var channelFor: suspend (FilesTab) -> SftpFileSystem = { tab -> tab.ride.value?.openSftp() ?: throw SftpError.NotConnected() }

    private class Slot(val browser: FilesBrowser, val following: Job) {
        fun close() {
            following.cancel()
            browser.close()
        }
    }

    private val slots = HashMap<String, Slot>()

    init {
        scope.launch {
            // The flows are the trigger; the maps are the truth, so an emission that predates a new tab never closes its browser.
            sessions.tabs.collect {
                synchronized(slots) { slots.keys.filter { sessions.filesTab(it) == null }.forEach { slots.remove(it)?.close() } }
            }
        }
        scope.launch {
            sessions.sessions.collect {
                transfers.transfers.value.filter { it.state.isActive && sessions.get(it.sessionId) == null }.forEach { transfers.cancel(it.id) }
            }
        }
        scope.launch {
            transfers.changedFolders.collect { (sessionId, dir) ->
                val affected = synchronized(slots) { slots.values.filter { sessions.filesTab(it.browser.tabId)?.ride?.value?.id == sessionId }.map { it.browser } }
                affected.filter { it.state.value.path == dir }.forEach { it.refresh() }
            }
        }
        scope.launch {
            // A copy stopped on a question marks the Files tab riding its session, so the stall shows on the strip and the tile, not only in the pane.
            // Re-checked on any record change too, since that is when a Files tab re-elects its ride; the calls are idempotent, so the round the mark itself causes settles at once.
            val waitingSessions = transfers.transfers.map { list -> list.filter { it.waiting }.mapTo(HashSet()) { it.sessionId } as Set<String> }.distinctUntilChanged()
            combine(waitingSessions, sessions.records, sessions.tabs) { waiting, _, tabs -> waiting to tabs.filterIsInstance<FilesTab>() }.collect { (waiting, filesTabs) ->
                filesTabs.forEach { tab -> tab.waitingOnUser(tab.ride.value?.id in waiting) }
            }
        }
    }

    /**
     * The browser for the Files tab [tabId], started on first use at the tab's folder (the one it
     * showed last), else the folder last visited on that host, else the ride's working directory,
     * else home. Null when the tab is gone.
     */
    fun browser(tabId: String): FilesBrowser? {
        val tab = sessions.filesTab(tabId) ?: return null
        return synchronized(slots) {
            slots.getOrPut(tabId) {
                val hostId = tab.host.id
                val browser = FilesBrowser(tabId, scope, open = { channelFor(tab) }, onVisited = { path -> remember(hostId, path) })
                browser.start(tab.folder ?: prefs.value.recentFor(hostId).firstOrNull() ?: tab.ride.value?.cwd)
                Slot(browser, follow(tab, browser))
            }.browser
        }
    }

    /** Keeps a browser in step with its tab: folders listed go to the tab; the ride's comings and goings drive the channel. */
    private fun follow(tab: FilesTab, browser: FilesBrowser): Job = scope.launch {
        launch {
            browser.state.collect { s -> if (!s.loading && s.error == null) tab.showing(s.path, s.home) }
        }
        launch {
            var lastRide: String? = tab.ride.value?.id
            tab.ride.flatMapLatest { ride ->
                ride?.record?.map { ride.id to it.state }?.distinctUntilChanged() ?: flowOf(null to SessionState.DETACHED)
            }.collect { (rideId, state) ->
                val changed = rideId != lastRide
                lastRide = rideId
                // The channel belongs to one login; a new ride means the next call opens on the new one.
                if (changed) browser.resetChannel()
                val error = browser.state.value.error
                val stranded = error is SftpError.NotConnected || error is SftpError.Io
                if (state == SessionState.LIVE && (changed || stranded)) browser.refresh()
            }
        }
    }

    fun setSort(sort: FilesSort) {
        scope.launch {
            val current = settings.filesPrefs.first()
            // Tapping the active sort flips its direction; a new sort starts ascending.
            val ascending = if (current.sort == sort) !current.ascending else true
            settings.setFilesPrefs(current.copy(sort = sort, ascending = ascending))
        }
    }

    fun setShowHidden(show: Boolean) {
        scope.launch { settings.setFilesPrefs(settings.filesPrefs.first().copy(showHidden = show)) }
    }

    private fun remember(hostId: String, path: String) {
        scope.launch {
            val current = settings.filesPrefs.first()
            if (current.recentFor(hostId).firstOrNull() != path) settings.setFilesPrefs(current.withRecent(hostId, path))
        }
    }
}
