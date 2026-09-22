package app.berth.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.importer.ImportKnownHostsSheet
import app.berth.android.ui.prompts.Fingerprint
import app.berth.android.ui.prompts.formatDate
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.android.ui.theme.MonoFontFeatures
import app.berth.domain.model.KnownHostKey
import app.berth.ssh.SshKeys

/**
 * Every server key the user has trusted (C13): searchable, one row per key with the algorithm and
 * fingerprint, a pill on pinned keys. Tap opens the detail sheet with pin and forget. The header's
 * overflow, and the empty screen, offer the `known_hosts` import (A16), the same sheet the Hosts
 * overflow and Settings › Data open.
 */
@Composable
fun KnownHostsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val known by vm.knownHosts.collectAsState()
    val hosts by vm.hosts.collectAsState()
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val q = query.trim().lowercase()
    val shown = known
        .filter { k ->
            q.isEmpty() || k.endpoint.lowercase().contains(q) || k.algorithmLabel.lowercase().contains(q) ||
                k.fingerprintSha256.lowercase().replace(" ", "").contains(q.replace(" ", "")) || hostNamesFor(k, hosts).any { it.lowercase().contains(q) }
        }
        .sortedWith(compareBy({ it.host }, { it.port }, { it.keyType }))

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(
            "Known hosts",
            onBack = onBack,
            actions = {
                Box {
                    IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                    BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                        BerthMenuItem("Import known_hosts", onClick = { menu = false; importing = true })
                    }
                }
            },
        )
        if (known.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState("No saved server keys.", "The first connection to each server asks you to trust its key; the keys you trust are listed here.") {
                BerthButton("Import known_hosts", onClick = { importing = true }, kind = ButtonKind.TEXT)
            }
        } else {
            if (known.size > 4) {
                // 4 of the 12 dp over the first row end here, outside the list, so the 44 dp field's
                // 48 dp reach is not cut by the list's claim on the space (as the Hosts search).
                BerthField(
                    query,
                    { query = it },
                    placeholder = "Search hosts and fingerprints",
                    modifier = Modifier.padding(start = BerthSpace.screenMargin, end = BerthSpace.screenMargin, bottom = 4.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = BerthSpace.screenMargin, end = BerthSpace.screenMargin, top = if (known.size > 4) 8.dp else 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { k ->
                    val names = hostNamesFor(k, hosts)
                    ListRow(
                        title = k.endpoint + if (names.isNotEmpty()) "  \u00B7  ${names.joinToString(", ")}" else "",
                        // One Caption line: algorithm, the pin, then the hash and its leading groups in Mono (K1).
                        subtitle = buildAnnotatedString {
                            append(k.algorithmLabel)
                            if (k.pinned) {
                                append(" \u00B7 ")
                                withStyle(SpanStyle(color = c.accent)) { append("pinned") }
                            }
                            append(" \u00B7 ")
                            withStyle(SpanStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, fontFeatureSettings = MonoFontFeatures)) {
                                append(shortFingerprint(k.fingerprintSha256, prefixLength = length))
                            }
                        },
                        onClick = { detail = k.id },
                        trailing = {
                            Text(formatDate(k.lastSeenAt), style = BerthType.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
                if (shown.isEmpty()) {
                    item { Text("Nothing matches.", style = BerthType.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
                }
                item {
                    Text(
                        "A pinned key is the only one accepted for its server; anything else is refused without asking.",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
            }
        }
    }

    val selected = detail?.let { id -> known.firstOrNull { it.id == id } }
    if (selected != null) {
        KnownHostSheet(vm, selected, hostNamesFor(selected, hosts), onDismiss = { detail = null })
    }
    if (importing) {
        ImportKnownHostsSheet(vm, onDismiss = { importing = false })
    }
}

/** Saved hosts that point at this endpoint, so the row can say which host a key belongs to. */
private fun hostNamesFor(k: KnownHostKey, hosts: List<app.berth.domain.model.Host>): List<String> =
    hosts.filter { it.address.equals(k.host, ignoreCase = true) && it.port == k.port }.map { it.name }

/** Characters of Caption that fit beside the trailing date on a phone-width row. */
private const val ROW_CAPTION_BUDGET = 38

/**
 * `SHA256:` and as many whole four-character groups (four at most) as fit after [prefixLength]
 * characters of algorithm and pin, so the row never cuts a group in half (K1). The sheet has it all.
 */
internal fun shortFingerprint(fingerprint: String, prefixLength: Int): String {
    val groups = SshKeys.groupedFingerprint(fingerprint).split(' ')
    val count = ((ROW_CAPTION_BUDGET - prefixLength - "SHA256:".length) / 5).coerceIn(1, 4)
    return "SHA256:" + groups.take(count).joinToString(" ") + " \u2026"
}

/** One key in full: fingerprint, dates, pin toggle, copy and forget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostSheet(vm: AppViewModel, key: KnownHostKey, hostNames: List<String>, onDismiss: () -> Unit) {
    val c = Berth.colors
    val clipboard = LocalClipboardManager.current
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(key.endpoint, (listOf(key.algorithmLabel) + hostNames).joinToString(" \u00B7 "))
            Fingerprint(key.keyType, key.fingerprintSha256)
            Spacer(Modifier.height(4.dp))
            Panel {
                Fact("First seen", formatDate(key.firstSeenAt))
                Fact("Last seen", formatDate(key.lastSeenAt))
                ToggleRow(
                    "Pin this key",
                    key.pinned,
                    { vm.setKnownHostPinned(key.id, it) },
                    caption = if (key.pinned) "Any other key from this server is refused" else "Refuse any other key from this server",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Copy public key", onClick = { clipboard.setText(AnnotatedString("${key.keyType} ${key.publicKeyBase64}")) })
                Spacer(Modifier.weight(1f))
                BerthButton("Forget", kind = ButtonKind.DESTRUCTIVE, onClick = { vm.forgetKnownHost(key.id); onDismiss() })
            }
            Text(
                "Forgetting a key means the next connection asks you to trust the server again.",
                style = BerthType.caption,
                color = c.text3,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = Berth.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = BerthType.body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = BerthType.body, color = c.text1)
    }
}
