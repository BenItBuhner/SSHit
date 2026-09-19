package app.berth.android.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.berth.sftp.LocalNode
import app.berth.sftp.LocalTree
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [LocalTree] over a documents-provider tree the user picked with `ACTION_OPEN_DOCUMENT_TREE`.
 * Every node is a document URI built on the tree, so the tree's grant covers all of them. Provider
 * failures of every kind come out as [IOException], which is what the copy engine records per file.
 */
class SafTree(private val context: Context, private val tree: Uri) : LocalTree {
    private val resolver: ContentResolver get() = context.contentResolver

    private class Node(val uri: Uri, override val name: String, override val isDirectory: Boolean, override val size: Long, override val modifiedAt: Long? = null) : LocalNode

    override val root: LocalNode by lazy {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // The picker's URI names the folder after a colon (`primary:Download/site`); the provider's display name is better when it answers.
        var name = tree.lastPathSegment?.substringAfterLast(':')?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "folder"
        runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
        }
        Node(uri, name, isDirectory = true, size = -1L)
    }

    private fun LocalNode.uri(): Uri = (this as? Node)?.uri ?: throw IOException("$name does not belong to this tree.")

    override fun children(dir: LocalNode): List<LocalNode> = saf("Couldn't read the folder ${dir.name}.") {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir.uri()))
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null) ?: throw IOException("Couldn't read the folder ${dir.name}.")
        cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val out = ArrayList<LocalNode>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(idColumn) ?: continue
                val name = c.getString(nameColumn) ?: continue
                val mime = if (mimeColumn >= 0) c.getString(mimeColumn) else null
                val size = if (sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn) else -1L
                val modified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn).takeIf { it > 0 } else null
                out += Node(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size, modified)
            }
            out
        }
    }

    override fun createDirectory(dir: LocalNode, name: String): LocalNode = saf("Couldn't make the folder $name.") {
        children(dir).firstOrNull { it.name == name }?.let { there ->
            if (there.isDirectory) return@saf there
            throw IOException("A file named $name is in the way.")
        }
        val uri = DocumentsContract.createDocument(resolver, dir.uri(), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Couldn't make the folder $name.")
        Node(uri, name, isDirectory = true, size = -1L)
    }

    override fun createFile(dir: LocalNode, name: String): LocalNode = saf("Couldn't create $name in the chosen folder.") {
        val uri = DocumentsContract.createDocument(resolver, dir.uri(), TransferManager.mimeFor(name), name)
            ?: throw IOException("Couldn't create $name in the chosen folder.")
        Node(uri, name, isDirectory = false, size = 0L)
    }

    override fun openWrite(file: LocalNode): OutputStream = saf("Couldn't open ${file.name} for writing.") {
        resolver.openOutputStream(file.uri(), "wt") ?: throw IOException("Couldn't open ${file.name} for writing.")
    }

    override fun openRead(file: LocalNode): InputStream = saf("Couldn't read ${file.name}.") {
        resolver.openInputStream(file.uri()) ?: throw IOException("Couldn't read ${file.name}.")
    }

    override fun delete(node: LocalNode) {
        runCatching { DocumentsContract.deleteDocument(resolver, node.uri()) }
    }

    private inline fun <T> saf(fallback: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message?.takeIf { it.isNotBlank() } ?: fallback, e)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
