package app.berth.android.ui.tabs

/**
 * Everything the strip, the switcher and the drawer can ask of the tab model (UX spec C3). The
 * Stage implements it over the view model; every member has a no-op default so a screenshot test
 * or a preview implements only what it exercises. Members that open UI (rename, group editor,
 * the New tab sheet, the switcher) are requests the host answers with its own sheets.
 */
interface TabActions {
    fun activate(id: String) {}
    fun close(id: String) {}
    fun closeOthers(id: String) {}
    fun duplicate(id: String) {}

    /** Opens the rename sheet for the tab. */
    fun rename(id: String) {}
    fun moveToGroup(id: String, groupId: String) {}

    /** Move to group › New group: creates a group and re-homes the tab there. */
    fun moveToNewGroup(id: String) {}
    fun detach(id: String) {}
    fun reconnect(id: String) {}

    /** A terminal tab's menu › Files: the host's Files tab, riding this terminal, comes on stage (opened when the host has none). */
    fun openFiles(id: String) {}

    /** A Files tab's menu › Terminal: the terminal it rides comes on stage, or a new one opens on its host. */
    fun openTerminal(id: String) {}

    /** Drag and drop: the tab ends up at [toIndex] in strip order, joining [groupId] when given. */
    fun move(id: String, toIndex: Int, groupId: String?) {}

    /** The plus tab and Ctrl+T: opens the New tab sheet. */
    fun newTab() {}

    /** Long-press on the plus tab. */
    fun duplicateActive() {}

    /** The count tile: opens the switcher. */
    fun openSwitcher() {}

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) {}

    /** Chip menu › Rename or Colour: opens the group editor. */
    fun editGroup(groupId: String) {}

    /** Chip menu › New tab here: the New tab sheet targeting this group. */
    fun newTabIn(groupId: String) {}
    fun closeGroup(groupId: String) {}

    /** Chip menu › Delete group; the host confirms and decides what happens to the tabs. */
    fun deleteGroup(groupId: String) {}

    /** Drag a chip: the whole group moves to [toIndex] among the groups. */
    fun moveGroup(groupId: String, toIndex: Int) {}
}
