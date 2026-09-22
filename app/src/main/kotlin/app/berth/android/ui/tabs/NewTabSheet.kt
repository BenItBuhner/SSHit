package app.berth.android.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.sheetFillsHeight
import app.berth.android.ui.hosts.rowSubtitle
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host

/**
 * The New tab sheet (spec C3, Plus tab): a quick connect field, the four most recent hosts as
 * swatches, then the Hosts list as a picker with a search field. Half height; drag up for the full
 * list. A chosen host becomes a tab in [groupId] (the active group when null), directly after the
 * active tab when that tab is in the same group. The two glyphs at the end of a host row open
 * another kind of tab on it instead: the link glyph a Tunnels tab (the host's forwards with no
 * shell, spec C14), the folder glyph its Files tab (or brings it on stage when the host already
 * has one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTabSheet(
    vm: AppViewModel,
    groupId: String?,
    onDismiss: () -> Unit,
    onAddHost: () -> Unit,
) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val groups by vm.workspaces.collectAsState()
    val now = ageTicker()
    var spec by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val target = groupId?.let { id -> groups.firstOrNull { it.id == id } }

    fun choose(host: Host) {
        vm.open(host, groupId)
        onDismiss()
    }
    fun files(host: Host) {
        vm.openFilesForHost(host, groupId)
        onDismiss()
    }
    fun tunnels(host: Host) {
        vm.openTunnels(host, groupId)
        onDismiss()
    }
    fun connectSpec() {
        if (spec.isBlank()) return
        val problem = vm.quickConnect(spec, null, groupId)
        if (problem == null) onDismiss() else error = problem
    }

    val byId = remember(hosts) { hosts.associateBy { it.id } }
    val recent = remember(hosts) { hosts.filter { it.lastConnectedAt != null }.sortedByDescending { it.lastConnectedAt }.take(4) }
    val filtered = remember(hosts, query) {
        val q = query.trim().lowercase()
        val all = hosts.sortedBy { it.name.lowercase() }
        if (q.isEmpty()) all else all.filter { h ->
            h.name.lowercase().contains(q) || h.address.lowercase().contains(q) || h.user.lowercase().contains(q) || h.tags.any { it.lowercase().contains(q) }
        }
    }

    // The one sheet over a list that opens at half height with drag up for the full list (spec A9's
    // Sheet rule, C3): every other sheet opens at its content's height, BerthSheet's default.
    BerthSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        // As a sheet the list fills the height, room for the keyboard under the field; as a dialog
        // the panel is as tall as its rows (spec C23), so six hosts are not a panel that is mostly empty.
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .then(if (sheetFillsHeight()) Modifier.fillMaxHeight() else Modifier),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "title") {
                Box(Modifier.padding(bottom = 6.dp)) {
                    SheetTitle("New tab", target?.let { "In ${it.name}" })
                }
            }
            item(key = "quick") {
                BerthField(
                    value = spec,
                    onValueChange = { spec = it; error = null },
                    placeholder = "user@host:port",
                    mono = true,
                    isError = error != null,
                    helper = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { connectSpec() }),
                    trailing = {
                        IconAction(onClick = ::connectSpec, description = "Connect", enabled = spec.isNotBlank()) {
                            BerthIcon(BerthIcons.chevronRight, tint = if (spec.isNotBlank()) c.text1 else c.text3)
                        }
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (hosts.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No hosts yet.", style = BerthType.headline, color = c.text1)
                        Text("Connect above, or save a host to come back to it.", style = BerthType.body, color = c.text2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BerthButton("Add host", onClick = { onDismiss(); onAddHost() }, kind = ButtonKind.PRIMARY)
                            if (spec.isNotBlank()) BerthButton("Connect", onClick = ::connectSpec)
                        }
                    }
                }
                return@LazyColumn
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-label") { SectionLabel("Recent", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)) }
                item(key = "recent") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (host in recent) RecentHost(host, Modifier.weight(1f)) { choose(host) }
                        repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            item(key = "hosts-label") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Hosts", Modifier.weight(1f))
                }
            }
            item(key = "search") {
                BerthField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search hosts",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    trailing = {
                        if (query.isEmpty()) BerthIcon(BerthIcons.search, tint = c.text3, modifier = Modifier.padding(end = 12.dp))
                        else IconAction(onClick = { query = "" }, description = "Clear search") { BerthIcon(BerthIcons.close, tint = c.text2, size = 20.dp) }
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (filtered.isEmpty()) {
                item(key = "no-match") {
                    Text("No host matches \u201C${query.trim()}\u201D.", style = BerthType.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            items(filtered, key = { it.id }) { host ->
                ListRow(
                    title = host.name,
                    subtitle = host.rowSubtitle(byId),
                    onClick = { choose(host) },
                    leading = { Swatch(host.color, host.monogram, 36.dp) },
                    trailing = {
                        if (host.lastConnectedAt != null) Text(ageText(host.lastConnectedAt, now), style = BerthType.caption, color = c.text3)
                        // Quiet until pressed (the target's own press fill marks it), so they read as second actions, not attributes of the row.
                        IconAction(onClick = { tunnels(host) }, description = "Tunnels on ${host.name}") {
                            BerthIcon(BerthIcons.link, tint = c.text3, size = 20.dp)
                        }
                        IconAction(onClick = { files(host) }, description = "Files on ${host.name}") {
                            BerthIcon(BerthIcons.folder, tint = c.text3, size = 20.dp)
                        }
                    },
                )
            }
        }
    }
}

/** A 48 dp swatch with the host's name in Caption below; the Recent row's unit. */
@Composable
private fun RecentHost(host: Host, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(RoundedCornerShape(BerthRadius.row))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Open ${host.name}"
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Swatch(host.color, host.monogram, 48.dp)
        Text(
            host.name,
            style = BerthType.caption,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}