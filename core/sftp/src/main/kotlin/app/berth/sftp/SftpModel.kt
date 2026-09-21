package app.berth.sftp

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

enum class SftpFileType { DIRECTORY, REGULAR, SYMLINK, OTHER }

/**
 * One directory entry as the server reported it (`lstat` semantics: a symlink stays a symlink).
 * [linkTarget] is the type behind a symlink when it was resolved; null for a dangling link or one
 * that was not followed.
 */
data class SftpEntry(
    val name: String,
    val path: String,
    val type: SftpFileType,
    val size: Long,
    /** Epoch milliseconds; 0 when the server sent no time. */
    val modifiedAt: Long,
    /** The low twelve mode bits: rwx for owner, group and others plus setuid, setgid and sticky. */
    val permissions: Int,
    val uid: Int = -1,
    val gid: Int = -1,
    val linkTarget: SftpFileType? = null,
) {
    /** A directory, or a symlink that points at one. */
    val isDirectory: Boolean get() = type == SftpFileType.DIRECTORY || linkTarget == SftpFileType.DIRECTORY

    /** A regular file, or a symlink that points at one. */
    val isRegularFile: Boolean get() = type == SftpFileType.REGULAR || linkTarget == SftpFileType.REGULAR

    val isSymlink: Boolean get() = type == SftpFileType.SYMLINK

    val isHidden: Boolean get() = name.startsWith(".")

    /** `drwxr-xr-x` style: a type letter then nine permission letters, with s/S and t/T for the special bits. */
    val permissionText: String get() = SftpPermissions.text(type, permissions)

    /** `755` or `4755` when a special bit is set. */
    val octal: String get() = SftpPermissions.octal(permissions)
}

object SftpPermissions {
    const val SETUID = 0b100_000_000_000
    const val SETGID = 0b010_000_000_000
    const val STICKY = 0b001_000_000_000
    const val MASK = 0xFFF

    /** The mode a new file asks for, 644: the same on every server whatever its umask leaves as the default (a stricter umask still takes bits away). */
    const val FILE = 0b110_100_100

    /** The mode a new folder asks for, 755. */
    const val DIRECTORY = 0b111_101_101

    /** A file the login alone reads and writes, 600. */
    const val PRIVATE_FILE = 0b110_000_000

    /** A folder the login alone enters, 700. */
    const val PRIVATE_DIRECTORY = 0b111_000_000

    fun text(type: SftpFileType, permissions: Int): String {
        val p = permissions and MASK
        val sb = StringBuilder(10)
        sb.append(
            when (type) {
                SftpFileType.DIRECTORY -> 'd'
                SftpFileType.SYMLINK -> 'l'
                SftpFileType.REGULAR -> '-'
                SftpFileType.OTHER -> '?'
            },
        )
        for (who in 2 downTo 0) {
            val shift = who * 3
            sb.append(if (p and (4 shl shift) != 0) 'r' else '-')
            sb.append(if (p and (2 shl shift) != 0) 'w' else '-')
            val x = p and (1 shl shift) != 0
            val special = when (who) {
                2 -> p and SETUID != 0
                1 -> p and SETGID != 0
                else -> p and STICKY != 0
            }
            sb.append(
                when {
                    special && who == 0 -> if (x) 't' else 'T'
                    special -> if (x) 's' else 'S'
                    x -> 'x'
                    else -> '-'
                },
            )
        }
        return sb.toString()
    }

    fun octal(permissions: Int): String {
        val p = permissions and MASK
        return if (p > 0b111_111_111) Integer.toOctalString(p) else Integer.toOctalString(p).padStart(3, '0')
    }

    /** Parses `644`, `0644` or `4755`; null when the text is not up to four octal digits. */
    fun parseOctal(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4 || t.any { it !in '0'..'7' }) return null
        return t.toInt(8) and MASK
    }
}

/** Path arithmetic for POSIX paths as the sftp subsystem sees them. */
object SftpPaths {
    const val ROOT = "/"

    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += segment
            }
        }
        val joined = out.joinToString("/")
        return if (absolute) "/$joined" else joined.ifEmpty { "." }
    }

    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == ROOT) return ROOT
        val i = n.lastIndexOf('/')
        return if (i <= 0) ROOT else n.substring(0, i)
    }

    fun name(path: String): String {
        val n = normalize(path)
        return if (n == ROOT) ROOT else n.substringAfterLast('/')
    }

    /** The path split for a breadcrumb: `/home/demo` gives `[/, home, demo]` paired with their full paths. */
    fun crumbs(path: String): List<Pair<String, String>> {
        val n = normalize(path)
        val result = ArrayList<Pair<String, String>>()
        result += ROOT to ROOT
        if (n == ROOT) return result
        var acc = ""
        for (segment in n.split('/').filter { it.isNotEmpty() }) {
            acc += "/$segment"
            result += segment to acc
        }
        return result
    }

    /**
     * A name one entry can have: not empty, not `.` or `..`, no `/`, and no control character
     * (below space, or DEL): a newline or an escape inside a name is nothing a user typed and, pasted
     * into a shell inside quotes, drops it to a continuation prompt or moves its cursor.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && name.none { it.code < 0x20 || it.code == 0x7F }

    /**
     * The name a copy takes beside the original when both are kept: `report (1).pdf`, then
     * `report (2).pdf`, the counter before the last extension; a dotfile or a name without a dot
     * takes it at the end. The first not in [taken] wins.
     */
    fun keepBothName(name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }
}

/** Everything the file browser can hit, so the screen can say something useful instead of a code. */
sealed class SftpError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConnected : SftpError("The session is not connected.")
    class NotFound(val path: String) : SftpError("There is nothing at $path.")
    class PermissionDenied(val path: String) : SftpError("The server refused access to $path.")
    class AlreadyExists(val path: String) : SftpError("${SftpPaths.name(path)} already exists here.")
    class NotEmpty(val path: String) : SftpError("${SftpPaths.name(path)} is not empty.")
    class NotADirectory(val path: String) : SftpError("${SftpPaths.name(path)} is not a folder.")
    class IsADirectory(val path: String) : SftpError("${SftpPaths.name(path)} is a folder.")
    class Unsupported(val operation: String) : SftpError("The server does not support $operation.")
    class Failed(val path: String?, detail: String, cause: Throwable? = null) : SftpError(detail, cause)
    class Io(cause: Throwable) : SftpError("The connection dropped.", cause)
}

/** What came back from reading a file for the viewer. */
sealed interface TextRead {
    data class Text(val content: String, val truncated: Boolean, val size: Long) : TextRead
    data class Binary(val size: Long) : TextRead
}

/**
 * The file operations the browser and the transfer queue need, on one open channel. Every call
 * suspends, runs its blocking work off the caller's thread, and throws [SftpError].
 */
interface SftpFileSystem : Closeable {
    val isOpen: Boolean

    /** The login's home directory as an absolute path. */
    suspend fun home(): String

    /** Resolves `.`, `..` and symlinks into an absolute path the server agrees exists. */
    suspend fun canonicalize(path: String): String

    /** The entries of [path] without `.` and `..`; symlinks carry their target type when it could be resolved. */
    suspend fun list(path: String): List<SftpEntry>

    /** `stat` following symlinks. */
    suspend fun stat(path: String): SftpEntry

    /**
     * `lstat`: the entry at [path] itself, a symlink as a symlink with nothing behind it resolved,
     * for a caller that has to know what stands at a name before trusting it.
     */
    suspend fun lstat(path: String): SftpEntry

    /**
     * Makes the folder at [path] with [permissions], the mode sent with the create so it never
     * stands open between two round trips; [SftpPermissions.DIRECTORY] by default, or
     * [SftpPermissions.PRIVATE_DIRECTORY] for one that is the login's alone. Throws
     * [SftpError.AlreadyExists] when something is already there.
     */
    suspend fun mkdir(path: String, permissions: Int = SftpPermissions.DIRECTORY)

    suspend fun rename(from: String, to: String)

    /** Removes a file or symlink, or a directory with everything in it. */
    suspend fun delete(path: String)

    suspend fun chmod(path: String, permissions: Int)

    /**
     * Streams [path] into [sink]. [onProgress] gets the bytes copied so far and the file size; the
     * copy stops at the next chunk when the calling coroutine is cancelled.
     */
    suspend fun download(path: String, sink: OutputStream, onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> })

    /**
     * Streams [source] into the file at [path]; [size] is what the progress total shows. With
     * [permissions] left as [SftpPermissions.FILE] the file is created or truncated, as a copy into
     * a folder the user chose is. Any other mode is a private file the login alone reads
     * ([SftpPermissions.PRIVATE_FILE]): it is created new with that mode and never opened when
     * something is already at [path], a file or a link pointing anywhere, which is
     * [SftpError.AlreadyExists], so nothing another user planted at the name is followed or written.
     */
    suspend fun upload(source: InputStream, size: Long, path: String, permissions: Int = SftpPermissions.FILE, onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> })

    /** Reads up to [maxBytes] for the viewer, deciding whether the file is text. */
    suspend fun readText(path: String, maxBytes: Int): TextRead
}
