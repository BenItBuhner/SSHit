package app.berth.android.ui.hosts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.a11y.reorderActions
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.LeadingMenuAnchor
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.TrailingMenuAnchor
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host

/**
 * The host editor's jump chain (OpenSSH's ProxyJump): the hops the login goes through, top first,
 * each a saved host with its own identity and its own known-host check. A hop is a row led by its
 * number in the chain (the `1 of 2` the trust sheet and a failure count by) in caption, then the
 * host's swatch, name and `user@address:port`; its menu moves it up or down the chain or removes
 * it. The last row adds a saved host that is not [selfId], not already in the chain, and not one
 * whose own chain leads back here (that login would never finish); its menu opens under the label,
 * each choice with the host's swatch so two hosts named alike can be told apart. A hop whose host
 * has since been deleted stays as a row that says so, so it can be removed rather than silently
 * dropped.
 */
@Composable
fun JumpHostsPicker(
    hosts: List<Host>,
    selfId: String?,
    chain: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val c = Berth.colors
    val byId = remember(hosts) { hosts.associateBy { it.id } }
    // The numeral's column, which the Add row's + shares, and the swatch's; the Add label starts where the swatches do.
    val numeralColumn = 20.dp
    val numeralGap = 12.dp
    val glyphColumn = 28.dp

    chain.forEachIndexed { index, id ->
        val host = byId[id]?.takeIf { it.id != selfId }
        var menu by remember(id) { mutableStateOf(false) }
        Box {
            ListRow(
                title = host?.name ?: if (id == selfId) "This host" else "Missing host",
                subtitle = when {
                    host != null -> "${host.user}@${host.address}:${host.port}"
                    id == selfId -> "A host can\u2019t jump through itself; remove it."
                    else -> "Deleted since it was added; remove it."
                },
                surface = Color.Transparent,
                minHeight = 44.dp,
                onClick = { menu = true },
                onLongClick = { menu = true },
                // Tap and long-press both open the menu, which a screen reader cannot reorder through;
                // the same three moves are the row's accessibility actions (TalkBack's actions menu).
                modifier = Modifier
                    .semantics { stateDescription = "Hop ${index + 1} of ${chain.size}" }
                    .reorderActions(
                        onMoveUp = if (index > 0) ({ onChange(chain.swapped(index, index - 1)) }) else null,
                        onMoveDown = if (index < chain.lastIndex) ({ onChange(chain.swapped(index, index + 1)) }) else null,
                        onRemove = { onChange(chain.filterIndexed { i, _ -> i != index }) },
                    ),
                titleColor = if (host != null) c.text1 else c.text2,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(numeralColumn), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = BerthType.mono.copy(fontSize = BerthType.caption.fontSize), color = c.text3)
                        }
                        Spacer(Modifier.width(numeralGap))
                        Box(Modifier.size(glyphColumn), contentAlignment = Alignment.Center) {
                            if (host != null) Swatch(host.color, host.monogram, glyphColumn) else BerthIcon(BerthIcons.close, tint = c.danger, size = 20.dp)
                        }
                    }
                },
                trailing = { BerthIcon(BerthIcons.moreHoriz, tint = c.text3, size = 20.dp) },
            )
            TrailingMenuAnchor {
                BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                    if (index > 0) BerthMenuItem("Move up", onClick = { onChange(chain.swapped(index, index - 1)); menu = false })
                    if (index < chain.lastIndex) BerthMenuItem("Move down", onClick = { onChange(chain.swapped(index, index + 1)); menu = false })
                    BerthMenuItem("Remove", destructive = true, onClick = { onChange(chain.filterIndexed { i, _ -> i != index }); menu = false })
                }
            }
        }
    }

    val candidates = remember(hosts, selfId, chain) {
        hosts.filter { it.id != selfId && it.id !in chain && !leadsBackTo(it, selfId, byId) }
    }
    var add by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = "Add jump host",
            subtitle = if (candidates.isEmpty()) "No other saved host to add." else null,
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = { add = true },
            enabled = candidates.isNotEmpty(),
            titleColor = c.accent,
            leading = {
                Box(Modifier.size(numeralColumn), contentAlignment = Alignment.Center) {
                    BerthIcon(BerthIcons.add, tint = c.accent, size = 20.dp)
                }
            },
        )
        // Under the label it belongs to (the row's padding, the + column and the gap in), not at the panel's far edge.
        LeadingMenuAnchor(inset = 12.dp + numeralColumn + numeralGap) {
            BerthMenu(expanded = add, onDismiss = { add = false }) {
                for (host in candidates) {
                    BerthMenuItem(host.name, leading = { Swatch(host.color, host.monogram, 24.dp) }, onClick = { onChange(chain + host.id); add = false })
                }
            }
        }
    }
    PanelNote(
        if (chain.isEmpty()) {
            "Direct login. Add a host to log in through it first; each hop uses its own key and its own known-host check."
        } else {
            "The login goes through each hop in order, top first, each with its own key and its own known-host check."
        },
    )
}

private fun List<String>.swapped(a: Int, b: Int): List<String> = toMutableList().also { it[a] = this[b]; it[b] = this[a] }

/** True when [candidate]'s own chain, followed through saved hosts, reaches [selfId]. */
private fun leadsBackTo(candidate: Host, selfId: String?, byId: Map<String, Host>): Boolean {
    if (selfId == null) return false
    val seen = HashSet<String>()
    val queue = ArrayDeque(candidate.jumpHostIds)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (id == selfId) return true
        if (!seen.add(id)) continue
        byId[id]?.jumpHostIds?.let(queue::addAll)
    }
    return false
}
