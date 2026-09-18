package app.berth.android.ui.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Hands [text] to the system share sheet as a document; themes and Decks leave the app this way. */
fun shareText(context: Context, title: String, text: String, mime: String = "application/json") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
}

/** Reads a whole document as UTF-8 text, or null when it cannot be opened. */
fun readText(context: Context, uri: Uri, maxBytes: Int = 2 shl 20): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes()
        if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
    }
}.getOrNull()

/** Opens the system file picker for text or JSON documents and reports the file's text. */
@Composable
fun rememberOpenTextFile(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readText(context, uri)?.let { current(it) }
    }
    return { runCatching { launcher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) } }
}

/** A "save as" flow: call [TextFileSaver.save] with a suggested name and the text to write. */
class TextFileSaver internal constructor(private val start: (String, String) -> Unit) {
    fun save(suggestedName: String, text: String) = start(suggestedName, text)
}

@Composable
fun rememberSaveTextFile(mime: String = "application/json"): TextFileSaver {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        }
    }
    return remember(launcher) {
        TextFileSaver { name, text ->
            pending = text
            runCatching { launcher.launch(name) }
        }
    }
}
