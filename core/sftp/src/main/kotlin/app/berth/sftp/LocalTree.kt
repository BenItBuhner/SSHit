package app.berth.sftp

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** One thing in a [LocalTree]: a folder or a file, as the tree reported it. */
interface LocalNode {
    val name: String
    val isDirectory: Boolean
    /** Bytes in a file, or -1 when the tree does not know; meaningless for a folder. */
    val size: Long
    /** Epoch milliseconds the file was last changed, or null when the tree does not say. */
    val modifiedAt: Long?
}

/**
 * The device side of a folder transfer: the tree a download is written into or an upload is read
 * from. Nodes are opaque handles the tree hands out and takes back, so one engine copies into a
 * plain directory ([FileTree]) or a documents-provider tree alike. Every method blocks on I/O;
 * callers run them off the main thread.
 */
interface LocalTree {
    val root: LocalNode

    fun children(dir: LocalNode): List<LocalNode>

    /** The folder [name] in [dir], created when it is not there yet; fails when a file is in the way. */
    @Throws(IOException::class)
    fun createDirectory(dir: LocalNode, name: String): LocalNode

    /** A new empty file [name] in [dir]; the caller has checked nothing is there already. */
    @Throws(IOException::class)
    fun createFile(dir: LocalNode, name: String): LocalNode

    /** Opens [file] for writing from the start, truncating what was there. */
    @Throws(IOException::class)
    fun openWrite(file: LocalNode): OutputStream

    @Throws(IOException::class)
    fun openRead(file: LocalNode): InputStream

    /** Removes a file, or an empty folder; nothing happens when it is already gone. */
    fun delete(node: LocalNode)
}

/**
 * A [LocalTree] over a directory on the file system. Symlinks to files are followed and copied as
 * files; symlinks to folders are not descended, so a link cycle cannot run the upload forever.
 */
class FileTree(rootDir: File) : LocalTree {
    private class Node(val file: File) : LocalNode {
        override val name: String get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory && !Files.isSymbolicLink(file.toPath())
        override val size: Long get() = if (file.isFile) file.length() else -1L
        override val modifiedAt: Long? get() = file.lastModified().takeIf { it > 0 }
    }

    override val root: LocalNode = Node(rootDir)

    private fun LocalNode.file(): File = (this as? Node)?.file ?: throw IOException("$name does not belong to this tree.")

    override fun children(dir: LocalNode): List<LocalNode> =
        (dir.file().listFiles() ?: emptyArray())
            .filter { it.isFile || (it.isDirectory && !Files.isSymbolicLink(it.toPath())) }
            .sortedBy { it.name }
            .map { Node(it) }

    override fun createDirectory(dir: LocalNode, name: String): LocalNode {
        val target = File(dir.file(), name)
        if (target.isDirectory) return Node(target)
        if (target.exists()) throw IOException("A file named $name is in the way.")
        if (!target.mkdirs() && !target.isDirectory) throw IOException("Couldn't create the folder $name.")
        return Node(target)
    }

    override fun createFile(dir: LocalNode, name: String): LocalNode {
        val target = File(dir.file(), name)
        if (!target.createNewFile()) throw IOException("$name is already here.")
        return Node(target)
    }

    override fun openWrite(file: LocalNode): OutputStream = FileOutputStream(file.file(), false)

    override fun openRead(file: LocalNode): InputStream = FileInputStream(file.file())

    override fun delete(node: LocalNode) {
        node.file().delete()
    }
}
