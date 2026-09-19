package app.berth.domain.model

/** The four gestures a Deck slot responds to, in the order the editor lists them. */
enum class DeckGesture(val title: String) {
    TAP("Tap"), UP("Swipe up"), DOWN("Swipe down"), HOLD("Hold");
}

fun DeckKey.action(gesture: DeckGesture): DeckAction? = when (gesture) {
    DeckGesture.TAP -> tap
    DeckGesture.UP -> up
    DeckGesture.DOWN -> down
    DeckGesture.HOLD -> hold
}

/** A copy with [gesture] bound to [action]; setting any gesture clears the Nub and snippets roles. */
fun DeckKey.withAction(gesture: DeckGesture, action: DeckAction?): DeckKey {
    val base = if (action != null) copy(nub = false, snippets = false) else this
    return when (gesture) {
        DeckGesture.TAP -> base.copy(tap = action)
        DeckGesture.UP -> base.copy(up = action)
        DeckGesture.DOWN -> base.copy(down = action)
        DeckGesture.HOLD -> base.copy(hold = action)
    }
}

/** True when the slot does nothing at all; the editor renders it as an empty key. */
val DeckKey.isEmpty: Boolean get() = tap == null && up == null && down == null && hold == null && !nub && !snippets

/** Full name of a non-printing key for pickers and descriptions. */
fun DeckKeyCode.fullName(): String = when (this) {
    DeckKeyCode.ESC -> "Escape"
    DeckKeyCode.TAB -> "Tab"
    DeckKeyCode.ENTER -> "Enter"
    DeckKeyCode.BACKSPACE -> "Backspace"
    DeckKeyCode.INS -> "Insert"
    DeckKeyCode.DEL -> "Delete"
    DeckKeyCode.HOME -> "Home"
    DeckKeyCode.END -> "End"
    DeckKeyCode.PGUP -> "Page up"
    DeckKeyCode.PGDN -> "Page down"
    DeckKeyCode.UP -> "Up"
    DeckKeyCode.DOWN -> "Down"
    DeckKeyCode.LEFT -> "Left"
    DeckKeyCode.RIGHT -> "Right"
    else -> name
}

fun DeckModifier.title(): String = name.lowercase().replaceFirstChar { it.uppercase() }

fun DeckAppAction.title(): String = when (this) {
    DeckAppAction.HIDE_KEYBOARD -> "Hide keyboard"
    DeckAppAction.PASTE -> "Paste"
    DeckAppAction.TOGGLE_PREDICTIVE_TEXT -> "Toggle predictive text"
    DeckAppAction.NEXT_SESSION -> "Next session"
    DeckAppAction.PREVIOUS_SESSION -> "Previous session"
    DeckAppAction.SPLIT -> "Split"
    DeckAppAction.DETACH -> "Detach"
    DeckAppAction.JUMP_TO_UNREAD -> "Jump to unread"
    DeckAppAction.OPEN_SESSION_SHEET -> "Open session sheet"
    DeckAppAction.NEXT_LAYER -> "Next layer"
    DeckAppAction.PREVIOUS_LAYER -> "Previous layer"
    DeckAppAction.OPEN_DECK_EDITOR -> "Open Deck editor"
}

/** `CTRL c` as `Ctrl+C`, `SHIFT TAB` as `Shift+Tab`. */
fun DeckAction.Combo.pretty(): String {
    val mods = combo.trim().split(' ').dropLast(1).filter { it.isNotBlank() }.map { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    val t = target
    val key = runCatching { DeckKeyCode.valueOf(t.uppercase()) }.getOrNull()
    val targetText = key?.let(DeckKey::keyLabel) ?: if (t.length == 1) t.uppercase() else t
    return (mods + targetText).joinToString("+")
}

/** One line that says what the action does, for the slot panel and accessibility. */
fun DeckAction.describe(): String = when (this) {
    is DeckAction.Key -> key.fullName()
    is DeckAction.Modifier -> "${modifier.title()} (modifier)"
    is DeckAction.Text -> "Text \u201C$text\u201D"
    is DeckAction.Combo -> pretty()
    is DeckAction.Macro -> "Macro ${macro.replace("PREFIX", "Prefix")}"
    is DeckAction.Snippet -> "Snippet $snippetId"
    is DeckAction.App -> action.title()
    is DeckAction.Strip -> if (strip.size >= 2) "${DeckKey.keyLabel(strip.first())}\u2013${DeckKey.keyLabel(strip.last())} strip" else "Key strip"
}

// ---- Layout edits: every operation returns a new layout and leaves the input untouched ----------

/** Practical ceiling for one row on a phone before keys drop under 40 dp. */
const val DECK_MAX_KEYS_PER_LAYER = 10

fun DeckLayout.updateLayer(index: Int, transform: (DeckLayer) -> DeckLayer): DeckLayout {
    if (index !in layers.indices) return this
    return copy(layers = layers.mapIndexed { i, layer -> if (i == index) transform(layer) else layer })
}

fun DeckLayout.setKey(layer: Int, slot: Int, key: DeckKey): DeckLayout = updateLayer(layer) { l ->
    if (slot !in l.keys.indices) l else l.copy(keys = l.keys.mapIndexed { i, k -> if (i == slot) key else k })
}

/** Inserts [key] so that it ends up at [at] (clamped); refuses beyond [DECK_MAX_KEYS_PER_LAYER]. */
fun DeckLayout.insertKey(layer: Int, at: Int, key: DeckKey = DeckKey()): DeckLayout = updateLayer(layer) { l ->
    if (l.keys.size >= DECK_MAX_KEYS_PER_LAYER) l
    else l.copy(keys = l.keys.toMutableList().also { it.add(at.coerceIn(0, it.size), key) })
}

fun DeckLayout.removeKey(layer: Int, slot: Int): DeckLayout = updateLayer(layer) { l ->
    if (slot !in l.keys.indices) l else l.copy(keys = l.keys.filterIndexed { i, _ -> i != slot })
}

/** Moves the key at [from] so it sits at [to] in the resulting list. */
fun DeckLayout.moveKey(layer: Int, from: Int, to: Int): DeckLayout = updateLayer(layer) { l ->
    if (from !in l.keys.indices || from == to) l
    else l.copy(keys = l.keys.toMutableList().also { it.add(to.coerceIn(0, it.size - 1), it.removeAt(from)) })
}

fun DeckLayout.addLayer(name: String, keys: List<DeckKey> = emptyList(), prefix: String? = null): DeckLayout =
    copy(layers = layers + DeckLayer(name = uniqueLayerName(name), keys = keys, prefix = prefix))

fun DeckLayout.renameLayer(index: Int, name: String): DeckLayout = updateLayer(index) { it.copy(name = name.trim().ifEmpty { it.name }) }

fun DeckLayout.setLayerPrefix(index: Int, prefix: String?): DeckLayout = updateLayer(index) { it.copy(prefix = prefix?.trim()?.ifEmpty { null }) }

fun DeckLayout.moveLayer(from: Int, to: Int): DeckLayout {
    if (from !in layers.indices || to !in layers.indices || from == to) return this
    return copy(layers = layers.toMutableList().also { it.add(to, it.removeAt(from)) })
}

/** Removes a layer; the last remaining layer is kept so the Deck always has something to show. */
fun DeckLayout.removeLayer(index: Int): DeckLayout {
    if (index !in layers.indices || layers.size <= 1) return this
    return copy(layers = layers.filterIndexed { i, _ -> i != index })
}

/** Restores the stock layer with the same name when there is one, otherwise empties the layer. */
fun DeckLayout.resetLayer(index: Int): DeckLayout = updateLayer(index) { l ->
    DeckLayout.default().layers.firstOrNull { it.name.equals(l.name, ignoreCase = true) } ?: l.copy(keys = emptyList())
}

private fun DeckLayout.uniqueLayerName(base: String): String {
    val wanted = base.trim().ifEmpty { "Layer" }
    if (layers.none { it.name == wanted }) return wanted
    var n = 2
    while (layers.any { it.name == "$wanted $n" }) n++
    return "$wanted $n"
}

/** Problems worth flagging in the editor without blocking the save. */
fun DeckLayout.warnings(): List<String> = buildList {
    layers.forEachIndexed { i, layer ->
        if (layer.keys.size > 7) add("${layer.name}: ${layer.keys.size} keys will be narrower than 40 dp on phones")
        if (layer.keys.isEmpty() && layers.size > 1) add("${layer.name}: no keys; layer ${i + 1} shows only the layer key")
        if (layer.keys.count { it.nub } > 1) add("${layer.name}: more than one Nub")
    }
    if (layers.isEmpty()) add("No layers; the Deck will be hidden")
}
