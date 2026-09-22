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

/** The name the document provider shows for [uri], or null when it does not say. */
fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** Opens the system file picker for text or JSON documents and reports the file's text. */
@Composable
fun rememberOpenTextFile(onText: (String) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onText)
    return rememberOpenNamedTextFile(arrayOf("application/json", "text/*", "application/octet-stream")) { text, _ -> current(text) }
}

/**
 * Opens the system file picker for any of [mimeTypes] and reports the file's text with its display
 * name, for formats whose files are named after what they hold.
 */
@Composable
fun rememberOpenNamedTextFile(mimeTypes: Array<String> = arrayOf("*/*"), onFile: (text: String, name: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onFile)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readText(context, uri)?.let { current(it, displayName(context, uri)) }
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
