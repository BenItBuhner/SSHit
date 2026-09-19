package app.berth.android.ui.tabs

import app.berth.android.session.TabSlot
import app.berth.android.session.TabSource
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Workspace
import kotlinx.coroutines.flow.MutableStateFlow

/** A tab as the strip sees one: an id and a record that can change under it. */
internal class FakeTab(override val id: String, record: SessionRecord) : TabSource {
    override val record = MutableStateFlow(record)
}

internal fun fakeHost(name: String, color: SwatchColor = SwatchColor.SLATE) = Host(
    id = "h-$name",
    name = name,
    color = color,
    monogram = Host.monogramFor(name),
    address = "$name.internal",
    port = 22,
    user = "ben",
    auth = AuthMethod.AskEachTime,
    createdAt = 0L,
)

internal fun fakeRecord(
    id: String,
    workspaceId: String,
    order: Int,
    title: String = id,
    state: SessionState = SessionState.DETACHED,
    kind: TabKind = TabKind.Ssh,
): SessionRecord {
    val host = fakeHost(title)
    return SessionRecord(
        id = id,
        workspaceId = workspaceId,
        hostId = host.id,
        hostSnapshot = host,
        state = state,
        title = title,
        sortOrder = order,
        createdAt = 0L,
        kind = kind,
    )
}

/** [count] tabs `prefix0`… in [workspaceId], numbered on from [from] in strip order. */
internal fun fakeSlots(count: Int, workspaceId: String, prefix: String = "t", from: Int = 0): List<TabSlot> =
    (0 until count).map { i -> TabSlot(FakeTab("$prefix$i", fakeRecord("$prefix$i", workspaceId, from + i, title = "tab $prefix$i")), workspaceId) }

internal fun group(id: String, name: String, order: Int, collapsed: Boolean = false) =
    Workspace(id, name, SwatchColor.COPPER, name.take(2).uppercase(), sortOrder = order, createdAt = 0L, collapsed = collapsed)
