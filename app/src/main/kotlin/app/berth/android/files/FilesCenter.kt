package app.berth.android.files

import android.content.Context
import app.berth.android.session.SessionManager
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [FilesBrowser] per session and the [TransferManager], both living as long as the
 * session does rather than the screen, so a browser keeps its folder and a copy keeps running
 * while the user is back on the terminal. Preferences are the JSON document in settings.
 */
@Singleton
class FilesCenter @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
) {
    private val scope = sessions.scope
    val prefs: StateFlow<FilesPrefs> = settings.filesPrefs.stateIn(scope, SharingStarted.Eagerly, FilesPrefs())
    val transfers = TransferManager(context, sessions)

    private val browsers = HashMap<String, FilesBrowser>()

    init {
        scope.launch {
            sessions.sessions.collect { live ->
                val ids = live.map { it.id }.toSet()
                val gone = synchronized(browsers) { browsers.keys.filter { it !in ids }.also { keys -> keys.forEach { browsers.remove(it)?.close() } } }
                if (gone.isNotEmpty()) transfers.transfers.value.filter { it.sessionId in gone && it.state.isActive }.forEach { transfers.cancel(it.id) }
            }
        }
        scope.launch {
            transfers.changedFolders.collect { (sessionId, dir) ->
                val browser = synchronized(browsers) { browsers[sessionId] } ?: return@collect
                if (browser.state.value.path == dir) browser.refresh()
            }
        }
    }

    /**
     * The browser for [sessionId], started on first use at the folder last visited on that host,
     * else the shell's working directory, else home. Null when the session is gone.
     */
    fun browser(sessionId: String): FilesBrowser? {
        val session = sessions.get(sessionId) ?: return null
        return synchronized(browsers) {
            browsers.getOrPut(sessionId) {
                val hostId = session.host.id
                FilesBrowser(sessionId, scope, open = { session.openSftp() }, onVisited = { path -> remember(hostId, path) }).also { browser ->
                    val recent = prefs.value.recentFor(hostId).firstOrNull()
                    browser.start(recent ?: session.cwd)
                }
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
