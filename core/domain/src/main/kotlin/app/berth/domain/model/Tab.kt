package app.berth.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one tab runs (UX spec C3). A tab owns exactly one runtime and its frozen frame; the strip
 * never inspects the kind, it only asks for the seven things every kind supplies (swatch,
 * monogram, title, subtitle, state, attention, frame). Sealed so Files/SFTP, a local shell and
 * others can be added without touching the strip.
 */
@Serializable
sealed interface TabKind {
    /** Stable id stored in the database. */
    val id: String

    /** One SSH session on a host. */
    @Serializable
    @SerialName("ssh")
    data object Ssh : TabKind {
        override val id: String get() = ID_SSH
    }

    /**
     * The SFTP browser for a host. It has no connection of its own: it rides one of the host's
     * SSH tabs (the login already made), so its state mirrors that session's and it shows the
     * host's monogram. Its record keeps the folder being shown in [SessionRecord.cwd].
     */
    @Serializable
    @SerialName("files")
    data object Files : TabKind {
        override val id: String get() = ID_FILES
    }

    /**
     * The host's port forwards with no shell (spec C14): one SSH login that only carries tunnels.
     * It has a connection of its own, so it reconnects and holds the foreground service like an
     * SSH tab; its stage shows each forward's state and traffic instead of a terminal.
     */
    @Serializable
    @SerialName("tunnels")
    data object Tunnels : TabKind {
        override val id: String get() = ID_TUNNELS
    }

    companion object {
        const val ID_SSH = "ssh"
        const val ID_FILES = "files"
        const val ID_TUNNELS = "tunnels"

        /** The kind for a stored id; unknown ids fall back to SSH so an old build still opens a tab. */
        fun fromId(id: String?): TabKind = when (id) {
            ID_SSH, null -> Ssh
            ID_FILES -> Files
            ID_TUNNELS -> Tunnels
            else -> Ssh
        }
    }
}

/**
 * A tab group is the successor of a workspace (spec C3, Groups): same record, same name, colour,
 * monogram and settings. The alias names the new role without breaking existing callers.
 */
typealias TabGroup = Workspace

/** Where a tab sits: its group and its position within the group; the strip order is groups then positions. */
@Serializable
data class TabPlacement(val tabId: String, val groupId: String, val position: Int)

/** How the terminal switches tabs by touch (spec C3, Switching; D1). */
@Serializable
enum class TabSwipeGesture {
    /** Two fingers travelling together across the terminal; the default. */
    TWO_FINGER,

    /** A one-finger drag that starts in the 24 dp zone at the right edge. */
    RIGHT_EDGE,

    /** No touch gesture switches tabs; the strip, the switcher and the keyboard do. */
    NONE,
}

/** A side of the split Stage (spec C23), as stored. */
@Serializable
enum class StageSide { LEFT, RIGHT }

/**
 * The Stage split in two, as it is kept across processes (spec C23 with C3's Persistence): the
 * tab sharing the Stage with the active tab, and the side the active tab holds. Written beside the
 * last active tab's id as the split changes, so a relaunch rebuilds both panes; a companion no
 * open tab answers to restores as one tab. The divider's position is a preference of its own
 * (`paneDividerFraction`), since it outlives the split: the panes come back where the user set them.
 */
@Serializable
data class StageSplit(@SerialName("companion") val companionId: String, @SerialName("active_side") val activeSide: StageSide)

/**
 * The strip's ordering rules (spec C3) as pure functions over records, so the session manager
 * only has to persist what comes back. The strip order is groups in their order, then each
 * group's tabs by position; every function returns the records whose placement changed.
 */
object TabOrder {
    /** Groups by their order, tabs by position within the group; tabs of a missing group come last. */
    fun strip(tabs: List<SessionRecord>, groups: List<Workspace>): List<SessionRecord> {
        val groupRank = groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }).withIndex().associate { it.value.id to it.index }
        return tabs.sortedWith(
            compareBy<SessionRecord> { groupRank[it.workspaceId] ?: Int.MAX_VALUE }
                .thenBy { it.sortOrder }
                .thenBy { it.createdAt },
        )
    }

    /** Positions renumbered 0..n-1 per group in encounter order; only the records that moved. */
    fun normalize(strip: List<SessionRecord>): List<SessionRecord> = renumber(strip, emptySet())

    private fun renumber(strip: List<SessionRecord>, always: Set<String>): List<SessionRecord> {
        val next = HashMap<String, Int>()
        return strip.mapNotNull { tab ->
            val position = next.getOrDefault(tab.workspaceId, 0)
            next[tab.workspaceId] = position + 1
            if (tab.sortOrder == position && tab.id !in always) null else tab.copy(sortOrder = position)
        }
    }

    /**
     * Inserts [tab] directly after [afterId] in that tab's group; without an anchor it lands at the
     * end of [groupId] (or the last group). Returns [tab] with its placement plus the tabs it shifted.
     */
    fun insertAfter(strip: List<SessionRecord>, tab: SessionRecord, afterId: String?, groupId: String?): List<SessionRecord> {
        val others = strip.filter { it.id != tab.id }
        val anchor = others.indexOfFirst { it.id == afterId }
        val group = when {
            anchor >= 0 -> others[anchor].workspaceId
            else -> groupId ?: others.lastOrNull()?.workspaceId ?: tab.workspaceId
        }
        val at = when {
            anchor >= 0 -> anchor + 1
            else -> (others.indexOfLast { it.workspaceId == group } + 1).takeIf { it > 0 } ?: others.size
        }
        val result = ArrayList(others).apply { add(at, tab.copy(workspaceId = group)) }
        return renumber(result, setOf(tab.id))
    }

    /**
     * Inserts [tab] into [groupId] at [position] within that group (clamped to the group's size), so
     * a closed tab can be reopened in its old slot. Returns [tab] with its placement plus the tabs it shifted.
     */
    fun insertAt(strip: List<SessionRecord>, tab: SessionRecord, groupId: String, position: Int): List<SessionRecord> {
        val others = strip.filter { it.id != tab.id }
        val groupIndices = others.indices.filter { others[it].workspaceId == groupId }
        val at = when {
            groupIndices.isEmpty() -> others.size
            position >= groupIndices.size -> groupIndices.last() + 1
            else -> groupIndices[position.coerceAtLeast(0)]
        }
        val result = ArrayList(others).apply { add(at, tab.copy(workspaceId = groupId)) }
        return renumber(result, setOf(tab.id))
    }

    /**
     * Moves [tabId] so that it sits at [toIndex] in the resulting strip. It joins [groupId] when given;
     * otherwise it stays in its own group when that group touches the drop point (so dragging to the
     * end of its run never changes group), and else joins the group of the tab it lands before, or
     * after at the very end. [strip] re-sorts by group, so groups stay contiguous on the next read.
     */
    fun move(strip: List<SessionRecord>, tabId: String, toIndex: Int, groupId: String? = null): List<SessionRecord> {
        val moving = strip.firstOrNull { it.id == tabId } ?: return emptyList()
        val others = strip.filter { it.id != tabId }
        val at = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(at - 1)?.workspaceId
        val after = others.getOrNull(at)?.workspaceId
        val group = groupId ?: when {
            moving.workspaceId == before || moving.workspaceId == after -> moving.workspaceId
            else -> after ?: before ?: moving.workspaceId
        }
        val result = ArrayList(others).apply { add(at, moving.copy(workspaceId = group)) }
        return renumber(result, setOf(tabId))
    }

    /** Re-homes [tabId] at the end of [groupId]. */
    fun moveToGroup(strip: List<SessionRecord>, tabId: String, groupId: String): List<SessionRecord> {
        val others = strip.filter { it.id != tabId }
        val end = (others.indexOfLast { it.workspaceId == groupId } + 1).takeIf { it > 0 } ?: others.size
        return move(strip, tabId, end, groupId)
    }

    /** The tab to activate when [closingId] closes: the one to its right, else to its left (Chrome order). */
    fun nextActiveAfterClose(strip: List<SessionRecord>, closingId: String): String? {
        val i = strip.indexOfFirst { it.id == closingId }
        if (i < 0) return strip.firstOrNull()?.id
        return (strip.getOrNull(i + 1) ?: strip.getOrNull(i - 1))?.id
    }

    /** Group order with [groupId] moved to [toIndex]; only the groups whose order changed. */
    fun moveGroup(groups: List<Workspace>, groupId: String, toIndex: Int): List<Workspace> {
        val ordered = groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt })
        val moving = ordered.firstOrNull { it.id == groupId } ?: return emptyList()
        val others = ordered.filter { it.id != groupId }
        val result = ArrayList(others).apply { add(toIndex.coerceIn(0, others.size), moving) }
        return result.mapIndexedNotNull { index, group -> if (group.sortOrder == index) null else group.copy(sortOrder = index) }
    }
}
