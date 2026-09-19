package app.berth.sftp

import app.berth.ssh.SshConnection
import app.berth.ssh.SshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * [SftpFileSystem] over one sshj `sftp` channel. Open it with [open] on a connected
 * [SshConnection]; the login is reused, no second authentication happens. Every method runs its
 * blocking sshj calls on [Dispatchers.IO] and maps failures to [SftpError].
 */
class SftpClient internal constructor(private val raw: SFTPClient) : SftpFileSystem {
    override val isOpen: Boolean get() = runCatching { raw.sftpEngine.subsystem.isOpen }.getOrDefault(false)

    override suspend fun home(): String = canonicalize(".")

    override suspend fun canonicalize(path: String): String = io(path, "resolving a path") { raw.canonicalize(path) }

    override suspend fun list(path: String): List<SftpEntry> = io(path, "listing a folder") {
        val infos = raw.ls(path)
        var resolved = 0
        infos.map { info ->
            var entry = info.toEntry()
            if (entry.type == SftpFileType.SYMLINK && resolved < MAX_LINKS_RESOLVED) {
                resolved++
                ensureActive()
                entry = entry.copy(linkTarget = runCatching { raw.stat(entry.path).type.toSftpType() }.getOrNull())
            }
            entry
        }
    }

    override suspend fun stat(path: String): SftpEntry = io(path, "reading attributes") {
        val attrs = raw.stat(path)
        attrs.toEntry(name = SftpPaths.name(path), path = SftpPaths.normalize(path))
    }

    override suspend fun mkdir(path: String) = io(path, "creating a folder") {
        try {
            raw.sftpEngine.makeDir(path, DIRECTORY_ATTRIBUTES)
        } catch (e: SFTPException) {
            // Protocol 3 servers report "exists" as a plain failure; say what actually happened.
            if (e.statusCode == Response.StatusCode.FAILURE && raw.statExistence(path) != null) throw SftpError.AlreadyExists(path)
            throw e
        }
    }

    override suspend fun rename(from: String, to: String) = io(from, "renaming") {
        try {
            raw.rename(from, to)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.FAILURE && raw.statExistence(to) != null) throw SftpError.AlreadyExists(to)
            throw e
        }
    }

    override suspend fun delete(path: String) = io(path, "deleting") {
        if (raw.lstat(path).type == FileMode.Type.DIRECTORY) removeTree(path) else raw.rm(path)
    }

    private fun removeTree(dir: String) {
        for (child in raw.ls(dir)) {
            if (child.attributes.type == FileMode.Type.DIRECTORY) removeTree(child.path) else raw.rm(child.path)
        }
        raw.rmdir(dir)
    }

    override suspend fun chmod(path: String, permissions: Int) = io(path, "changing permissions") {
        raw.chmod(path, permissions and SftpPermissions.MASK)
    }

    override suspend fun download(path: String, sink: OutputStream, onProgress: (Long, Long) -> Unit) = io(path, "downloading") {
        val file = raw.open(path, EnumSet.of(OpenMode.READ))
        try {
            val total = file.length()
            val input = file.ReadAheadRemoteFileInputStream(READ_AHEAD_PACKETS)
            val buffer = ByteArray(CHUNK_BYTES)
            var copied = 0L
            onProgress(0L, total)
            while (true) {
                ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                copied += n
                onProgress(copied, total)
            }
            sink.flush()
        } finally {
            runCatching { file.close() }
        }
    }

    override suspend fun upload(source: InputStream, size: Long, path: String, onProgress: (Long, Long) -> Unit) = io(path, "uploading") {
        val file = raw.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC), FILE_ATTRIBUTES)
        try {
            val out = file.RemoteFileOutputStream(0L, WRITE_AHEAD_PACKETS)
            val buffer = ByteArray(CHUNK_BYTES)
            var copied = 0L
            onProgress(0L, size)
            while (true) {
                ensureActive()
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                copied += n
                onProgress(copied, size)
            }
            // Waits for the server to confirm the writes still in flight.
            out.close()
        } finally {
            runCatching { file.close() }
        }
    }

    override suspend fun readText(path: String, maxBytes: Int): TextRead = io(path, "reading") {
        val file = raw.open(path, EnumSet.of(OpenMode.READ))
        try {
            val size = file.length()
            val want = minOf(size, maxBytes.toLong()).toInt()
            val bytes = ByteArray(want)
            val input = file.ReadAheadRemoteFileInputStream(READ_AHEAD_PACKETS)
            var read = 0
            while (read < want) {
                ensureActive()
                val n = input.read(bytes, read, want - read)
                if (n < 0) break
                read += n
            }
            val sample = minOf(read, BINARY_SNIFF_BYTES)
            for (i in 0 until sample) if (bytes[i] == 0.toByte()) return@io TextRead.Binary(size)
            TextRead.Text(String(bytes, 0, read, Charsets.UTF_8), truncated = size > read, size = size)
        } finally {
            runCatching { file.close() }
        }
    }

    override fun close() {
        runCatching { raw.close() }
    }

    /**
     * Runs [block] on the IO dispatcher and turns sshj's failures into [SftpError]. Cancellation
     * passes straight through: it is not an IOException.
     */
    private suspend inline fun <T> io(path: String?, operation: String, crossinline block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: SftpError) {
                throw e
            } catch (e: SFTPException) {
                throw e.toSftpError(path, operation)
            } catch (e: ConnectionException) {
                throw SftpError.Io(e)
            } catch (e: TransportException) {
                throw SftpError.Io(e)
            } catch (e: SshError) {
                throw SftpError.Io(e)
            } catch (e: IOException) {
                throw SftpError.Io(e)
            }
        }

    private fun SFTPException.toSftpError(path: String?, operation: String): SftpError {
        val p = path ?: "the path"
        return when (statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> SftpError.NotFound(p)
            Response.StatusCode.PERMISSION_DENIED, Response.StatusCode.WRITE_PROTECT -> SftpError.PermissionDenied(p)
            Response.StatusCode.FILE_ALREADY_EXISTS -> SftpError.AlreadyExists(p)
            Response.StatusCode.DIR_NOT_EMPTY -> SftpError.NotEmpty(p)
            Response.StatusCode.NOT_A_DIRECTORY -> SftpError.NotADirectory(p)
            Response.StatusCode.FILE_IS_A_DIRECTORY -> SftpError.IsADirectory(p)
            Response.StatusCode.OP_UNSUPPORTED -> SftpError.Unsupported(operation)
            Response.StatusCode.NO_CONNECTION, Response.StatusCode.CONNECITON_LOST -> SftpError.Io(this)
            else -> SftpError.Failed(path, "The server could not finish $operation" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."), this)
        }
    }

    private fun RemoteResourceInfo.toEntry(): SftpEntry = attributes.toEntry(name, path)

    private fun FileAttributes.toEntry(name: String, path: String): SftpEntry = SftpEntry(
        name = name,
        path = path,
        type = type.toSftpType(),
        size = if (has(FileAttributes.Flag.SIZE)) size else 0L,
        modifiedAt = if (has(FileAttributes.Flag.ACMODTIME)) mtime * 1000L else 0L,
        permissions = if (has(FileAttributes.Flag.MODE)) mode.permissionsMask and SftpPermissions.MASK else 0,
        uid = if (has(FileAttributes.Flag.UIDGID)) uid else -1,
        gid = if (has(FileAttributes.Flag.UIDGID)) gid else -1,
    )

    private fun FileMode.Type.toSftpType(): SftpFileType = when (this) {
        FileMode.Type.DIRECTORY -> SftpFileType.DIRECTORY
        FileMode.Type.REGULAR -> SftpFileType.REGULAR
        FileMode.Type.SYMLINK -> SftpFileType.SYMLINK
        else -> SftpFileType.OTHER
    }

    companion object {
        /** Reads and writes in flight before waiting on the server; 16 x 32 KiB keeps a LAN link busy. */
        private const val READ_AHEAD_PACKETS = 16
        private const val WRITE_AHEAD_PACKETS = 16
        private const val CHUNK_BYTES = 32 * 1024
        private const val BINARY_SNIFF_BYTES = 8 * 1024
        /** Symlinks per listing whose target is looked up; the rest resolve on tap. */
        private const val MAX_LINKS_RESOLVED = 24

        /**
         * The mode a new file or folder asks for, sent with the create so it costs no round trip:
         * 644 and 755, the same on every server whatever its umask leaves as the default (a
         * stricter umask still takes bits away, as it should).
         */
        private val FILE_ATTRIBUTES: FileAttributes = FileAttributes.Builder().withPermissions(0b110_100_100).build()
        private val DIRECTORY_ATTRIBUTES: FileAttributes = FileAttributes.Builder().withPermissions(0b111_101_101).build()

        /** Opens the sftp subsystem on [connection]; throws [SftpError.NotConnected] when it has no live client. */
        suspend fun open(connection: SshConnection): SftpClient {
            if (!connection.isConnected) throw SftpError.NotConnected()
            return try {
                SftpClient(connection.openSftp())
            } catch (e: SshError) {
                throw SftpError.NotConnected()
            } catch (e: SFTPException) {
                throw if (e.statusCode == Response.StatusCode.OP_UNSUPPORTED) SftpError.Unsupported("sftp") else SftpError.Failed(null, "Couldn't open the sftp subsystem: ${e.message}", e)
            } catch (e: IOException) {
                throw SftpError.Failed(null, "Couldn't open the sftp subsystem: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }
}
