package app.berth.android.ui.io

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** The most a picked text document may hold. */
const val MAX_TEXT_BYTES = 2 shl 20

/** What reading a picked document gave: its text and display name, or why there is none. */
sealed interface PickedText {
    data class Read(val text: String, val name: String?) : PickedText
    data object TooBig : PickedText
    data object Unreadable : PickedText

    /** The sentence that says why a document picked as [what] ("a theme") gave no text, or null when it did. */
    fun refusal(what: String): String? = when (this) {
        is Read -> null
        TooBig -> "That file is over ${MAX_TEXT_BYTES shr 20} MB, too big for $what."
        Unreadable -> "Berth could not read that file."
    }
}

/** [input] as UTF-8 text, or null when it holds more than [maxBytes]; at most one byte past the limit is read. */
fun readBounded(input: InputStream, maxBytes: Int): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() <= maxBytes) {
        val n = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
        if (n < 0) return out.toByteArray().toString(Charsets.UTF_8)
        out.write(buffer, 0, n)
    }
    return null
}

/** Reads [uri] as UTF-8 text of at most [maxBytes]; this blocks, so call it off the main thread. */
fun readDocument(context: Context, uri: Uri, maxBytes: Int = MAX_TEXT_BYTES): PickedText = try {
    val input = context.contentResolver.openInputStream(uri)
    when (val text = input?.use { readBounded(it, maxBytes) }) {
        null -> if (input == null) PickedText.Unreadable else PickedText.TooBig
        else -> PickedText.Read(text, displayName(context, uri))
    }
} catch (_: Exception) {
    PickedText.Unreadable
}

/** The name the document provider shows for [uri], or null when it does not say. */
fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** The picker's types for Berth's JSON and plain-text documents. */
val TEXT_DOCUMENT_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

/**
 * Opens the system file picker for any of [mimeTypes] and reports what reading the file gave: its
 * text with its display name, for formats whose files are named after what they hold, or that it
 * was too big or could not be read. The read runs on the IO dispatcher; [onFile] runs on the main thread.
 */
@Composable
fun rememberOpenNamedTextFile(mimeTypes: Array<String> = arrayOf("*/*"), onFile: (PickedText) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onFile)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { current(withContext(Dispatchers.IO) { readDocument(context, uri) }) }
    }
    return { runCatching { launcher.launch(mimeTypes) } }
}

/** A "save as" flow: call [TextFileSaver.save] with a suggested name and the text to write. */
class TextFileSaver internal constructor(private val start: (String, String, String?) -> Unit) {
    /** [mime] overrides the saver's own type for this one file. */
    fun save(suggestedName: String, text: String, mime: String? = null) = start(suggestedName, text, mime)
}

/** CreateDocument with the type chosen per launch rather than fixed when the launcher is made. */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

@Composable
fun rememberSaveTextFile(mime: String = "application/json"): TextFileSaver {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        }
    }
    return remember(launcher, mime) {
        TextFileSaver { name, text, type ->
            pending = text
            runCatching { launcher.launch(name to (type ?: mime)) }
        }
    }
}
