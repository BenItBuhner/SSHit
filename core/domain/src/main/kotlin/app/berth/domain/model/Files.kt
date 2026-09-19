package app.berth.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the file browser sorts by; the direction lives next to it in [FilesPrefs]. */
@Serializable
enum class FilesSort {
    @SerialName("name") NAME,
    @SerialName("size") SIZE,
    @SerialName("date") DATE;

    val label: String get() = when (this) {
        NAME -> "Name"
        SIZE -> "Size"
        DATE -> "Date"
    }
}

/**
 * The file browser's small preferences, kept as one JSON document in the preferences table so no
 * schema changes: how the list is sorted, whether dotfiles show, and the folders visited most
 * recently per host so the browser opens where the user left off.
 */
@Serializable
data class FilesPrefs(
    val sort: FilesSort = FilesSort.NAME,
    val ascending: Boolean = true,
    @SerialName("show_hidden") val showHidden: Boolean = false,
    /** Absolute folders by host id, most recent first, at most [MAX_RECENT] each. */
    @SerialName("recent_paths") val recentPaths: Map<String, List<String>> = emptyMap(),
) {
    fun recentFor(hostId: String): List<String> = recentPaths[hostId].orEmpty()

    /** Moves [path] to the front of [hostId]'s list, dropping the oldest beyond [MAX_RECENT]. */
    fun withRecent(hostId: String, path: String): FilesPrefs {
        val updated = (listOf(path) + recentFor(hostId).filter { it != path }).take(MAX_RECENT)
        return copy(recentPaths = recentPaths + (hostId to updated))
    }

    companion object {
        const val MAX_RECENT = 8
    }
}
