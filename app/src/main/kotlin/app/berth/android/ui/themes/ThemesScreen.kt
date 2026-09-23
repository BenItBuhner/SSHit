package app.berth.android.ui.themes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.io.PickedText
import app.berth.android.ui.io.rememberOpenNamedTextFile
import app.berth.android.ui.io.rememberSaveTextFile
import app.berth.android.ui.io.shareText
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.NoticeBar
import app.berth.android.ui.terminal.PreviewScript
import app.berth.android.ui.terminal.TerminalPreview
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TerminalThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The terminal theme gallery (UX spec C19): every theme as a tile that is a live mini-render of
 * its own colours. Tap opens the editor; long-press offers default, duplicate, export and delete.
 * Setting as the app default a theme that suggests an accent other than the interface's offers
 * it at the foot for the notice bar's six seconds (A10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpen: (themeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val context = LocalContext.current
    val themes by vm.terminalThemes.collectAsState()
    val default by vm.defaultTerminalTheme.collectAsState()
    val font by vm.terminalFont.collectAsState()
    val appTheme by vm.interfaceTheme.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var pasteSheet by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<TerminalTheme?>(null) }
    val saver = rememberSaveTextFile()
    // The theme whose accent is on offer; the bar keeps showing the last one through its exit.
    var accentOffer by remember { mutableStateOf<TerminalTheme?>(null) }
    val shownOffer = remember { mutableStateOf<TerminalTheme?>(null) }
    if (accentOffer != null) shownOffer.value = accentOffer
    LaunchedEffect(accentOffer) {
        if (accentOffer != null) {
            delay(NOTICE_BAR_MS)
            accentOffer = null
        }
    }

    val scope = rememberCoroutineScope()
    // iTerm2, Ghostty and Termux files carry no name of their own; the file's name stands in.
    fun importText(text: String, fileName: String? = null) {
        scope.launch {
            val imported = withContext(Dispatchers.Default) { TerminalThemes.importAll(text, TerminalThemes.nameFromFile(fileName)) }
            importNote = when {
                imported.isEmpty() -> "That text is not a terminal theme Berth can read."
                else -> {
                    imported.forEach { t ->
                        // Never overwrite a stock theme on import; a clash gets a fresh id.
                        val id = if (themes.any { it.id == t.id && it.builtIn }) AppViewModel.newThemeId() else t.id
                        vm.saveTerminalTheme(t.copy(id = id))
                    }
                    if (imported.size == 1) "Imported ${imported[0].name}." else "Imported ${imported.size} themes."
                }
            }
        }
    }
    val openFile = rememberOpenNamedTextFile { picked ->
        if (picked is PickedText.Read) importText(picked.text, picked.name) else importNote = picked.refusal("a theme")
    }

    fun newTheme() {
        val id = AppViewModel.newThemeId()
        vm.saveTerminalTheme(default.duplicate(id, "${default.name} copy"))
        onOpen(id)
    }

    Box(modifier.fillMaxSize().background(c.surface0)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Terminal themes", onBack = onBack) {
            IconAction(onClick = ::newTheme, description = "New theme") { BerthIcon(BerthIcons.add) }
            Box {
                IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuItem("Import file") { menu = false; openFile() }
                    MenuItem("Paste theme text") { menu = false; pasteSheet = true }
                    MenuItem("Export custom themes") {
                        menu = false
                        val custom = themes.filter { !it.builtIn }
                        val text = if (custom.isEmpty()) default.toJson() else "[\n" + custom.joinToString(",\n") { it.toJson() } + "\n]"
                        shareText(context, "Berth terminal themes", text)
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = BerthSpace.screenMargin, end = BerthSpace.screenMargin, top = 4.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(BerthSpace.panelGap),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.panelGap),
        ) {
            items(themes, key = { it.id }) { theme ->
                ThemeTile(
                    theme = theme,
                    font = font,
                    isDefault = theme.id == default.id,
                    onOpen = { onOpen(theme.id) },
                    onSetDefault = {
                        vm.setDefaultTerminalTheme(theme.id)
                        accentOffer = theme.takeIf { t -> t.suggestedAccent?.let { AccentChoice.Colour(it) != appTheme.accentChoice } == true }
                    },
                    onDuplicate = {
                        val id = AppViewModel.newThemeId()
                        vm.saveTerminalTheme(theme.duplicate(id))
                        onOpen(id)
                    },
                    onExport = { exporting = theme },
                    onDelete = if (theme.builtIn) null else ({ vm.deleteTerminalTheme(theme.id) }),
                )
            }
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BerthButton("Import file", onClick = openFile)
                        BerthButton("Paste theme text", onClick = { pasteSheet = true })
                    }
                    val n = importNote
                    if (n != null) Text(n, style = BerthType.caption, color = c.text1, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
    NoticeBar(
        visible = accentOffer != null,
        text = "${shownOffer.value?.name ?: ""} is the app default",
        action = "Use its accent",
        onAction = {
            shownOffer.value?.suggestedAccent?.let { vm.setInterfaceTheme(appTheme.withAccent(AccentChoice.Colour(it))) }
            accentOffer = null
        },
        modifier = Modifier.align(Alignment.BottomCenter),
        // A long theme name at the font cap would cut "app default" off; the line is read whole.
        maxLines = 2,
    )
    }

    if (pasteSheet) {
        PasteTextSheet(
            title = "Paste theme text",
            caption = "Berth JSON, iTerm2, Ghostty, Windows Terminal, base16 or Termux colours",
            action = "Import",
            placeholder = null,
            onDismiss = { pasteSheet = false },
            onSubmit = { text ->
                importText(text)
                pasteSheet = false
            },
        )
    }
    exporting?.let { theme -> ThemeExportSheet(theme, saver, onDismiss = { exporting = null }) }
}

/**
 * One gallery tile: the theme's own background at `panel` radius, a live mini-render whose palette
 * strip is clipped at `swatchSmall` so the tile has a concentric inner element, then the name with
 * its state in Caption beneath; both in the theme's foreground, so they read on light themes too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeTile(
    theme: TerminalTheme,
    font: app.berth.domain.model.TerminalFont,
    isDefault: Boolean,
    onOpen: () -> Unit,
    onSetDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = Berth.colors
    var menu by remember { mutableStateOf(false) }
    val fg = theme.foreground.toColor()
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BerthRadius.panel))
                .background(theme.background.toColor())
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics { contentDescription = "Theme ${theme.name}" + if (isDefault) ", app default" else "" }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TerminalPreview(
                theme = theme,
                font = font.copy(sizeSp = 9),
                script = PreviewScript.TILE,
                showCursor = false,
                modifier = Modifier.clip(RoundedCornerShape(BerthRadius.swatchSmall)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The eighteen stock tiles fill whole rows and the two stock names that wrap share one; a
                // custom name could wrap beside a one-line tile and unbalance its row.
                Text(theme.name, style = BerthType.label, color = fg, maxLines = if (theme.builtIn) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        isDefault -> "App default"
                        theme.builtIn -> "Stock"
                        else -> "Custom"
                    },
                    style = BerthType.caption,
                    color = fg.copy(alpha = if (isDefault) 0.85f else 0.6f),
                )
            }
        }
        BerthMenu(expanded = menu, onDismiss = { menu = false }) {
            if (!isDefault) MenuItem("Set as app default") { menu = false; onSetDefault() }
            MenuItem("Duplicate") { menu = false; onDuplicate() }
            MenuItem("Export") { menu = false; onExport() }
            if (onDelete != null) MenuItem("Delete", destructive = true) { menu = false; onDelete() }
        }
    }
}

@Composable
internal fun MenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    val c = Berth.colors
    DropdownMenuItem(text = { Text(text, style = BerthType.body, color = if (destructive) c.danger else c.text1) }, onClick = onClick)
}

/** A sheet with one multi-line field for pasted JSON or Termux text, a single action and an optional second one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteTextSheet(
    title: String,
    caption: String,
    action: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    error: String? = null,
    secondary: Pair<String, () -> Unit>? = null,
    placeholder: String? = "{ ... }",
) {
    val c = Berth.colors
    var text by remember { mutableStateOf("") }
    BerthSheet(onDismiss = onDismiss, scrimColor = c.scrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetTitle(title, caption)
            BerthField(text, { text = it }, mono = true, singleLine = false, minLines = 6, placeholder = placeholder, helper = error, isError = error != null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(action, onClick = { onSubmit(text) }, kind = ButtonKind.PRIMARY, enabled = text.isNotBlank())
                if (secondary != null) BerthButton(secondary.first, onClick = secondary.second)
                BerthButton("Cancel", onClick = onDismiss, kind = ButtonKind.TEXT)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
