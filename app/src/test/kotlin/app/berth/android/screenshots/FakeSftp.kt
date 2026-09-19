package app.berth.android.screenshots

import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.sftp.SftpFileType
import app.berth.sftp.SftpPaths
import app.berth.sftp.TextRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One node of the in-memory server: the fields a listing shows plus the bytes behind a file. */
class FakeNode(
    val type: SftpFileType,
    var size: Long = 0L,
    var modifiedAt: Long = 0L,
    var permissions: Int = 0b110_100_100,
    var content: ByteArray = ByteArray(0),
    val linkTarget: SftpFileType? = null,
)

/**
 * An in-memory [SftpFileSystem] for the offline captures: a path-keyed tree with the errors the
 * screen has to show, a latch to hold a listing so the refreshing state can be photographed, and
 * chunked copies with a per-chunk pause so a transfer stays in flight long enough to see.
 */
class FakeSftpFileSystem(val homePath: String = "/home/demo") : SftpFileSystem {
    val nodes = LinkedHashMap<String, FakeNode>()
    val locked = HashSet<String>()
    /** While set, `list` waits on it; complete it to let the listing through. */
    var holdList: CompletableDeferred<Unit>? = null
    var listDelayMs = 0L
    var chunkDelayMs = 0L
    var lists = 0
    var reads = 0
    override var isOpen: Boolean = true

    init {
        nodes[SftpPaths.ROOT] = FakeNode(SftpFileType.DIRECTORY, permissions = 0b111_101_101)
    }

    fun dir(path: String, modifiedAt: Long, permissions: Int = 0b111_101_101): FakeSftpFileSystem {
        ensureParents(path)
        nodes[path] = FakeNode(SftpFileType.DIRECTORY, modifiedAt = modifiedAt, permissions = permissions)
        return this
    }

    fun file(path: String, content: String, modifiedAt: Long, permissions: Int = 0b110_100_100): FakeSftpFileSystem =
        file(path, content.toByteArray(), modifiedAt, permissions)

    fun file(path: String, content: ByteArray, modifiedAt: Long, permissions: Int = 0b110_100_100): FakeSftpFileSystem {
        ensureParents(path)
        nodes[path] = FakeNode(SftpFileType.REGULAR, size = content.size.toLong(), modifiedAt = modifiedAt, permissions = permissions, content = content)
        return this
    }

    /** A file whose listed size is [size] without holding the bytes; downloads stream zeros. */
    fun bigFile(path: String, size: Long, modifiedAt: Long): FakeSftpFileSystem {
        ensureParents(path)
        nodes[path] = FakeNode(SftpFileType.REGULAR, size = size, modifiedAt = modifiedAt)
        return this
    }

    fun link(path: String, target: SftpFileType?, modifiedAt: Long): FakeSftpFileSystem {
        ensureParents(path)
        nodes[path] = FakeNode(SftpFileType.SYMLINK, modifiedAt = modifiedAt, permissions = 0b111_111_111, linkTarget = target)
        return this
    }

    private fun ensureParents(path: String) {
        var p = SftpPaths.parent(path)
        while (p !in nodes) {
            nodes[p] = FakeNode(SftpFileType.DIRECTORY, permissions = 0b111_101_101)
            if (p == SftpPaths.ROOT) break
            p = SftpPaths.parent(p)
        }
    }

    private fun node(path: String): FakeNode {
        val n = SftpPaths.normalize(path)
        if (locked.any { n == it || n.startsWith("$it/") }) throw SftpError.PermissionDenied(n)
        return nodes[n] ?: throw SftpError.NotFound(n)
    }

    private fun entry(path: String, node: FakeNode) = SftpEntry(
        name = SftpPaths.name(path),
        path = path,
        type = node.type,
        size = node.size,
        modifiedAt = node.modifiedAt,
        permissions = node.permissions,
        uid = 1000,
        gid = 1000,
        linkTarget = node.linkTarget,
    )

    override suspend fun home(): String = homePath

    override suspend fun canonicalize(path: String): String = SftpPaths.normalize(path)

    override suspend fun list(path: String): List<SftpEntry> {
        holdList?.await()
        if (listDelayMs > 0) delay(listDelayMs)
        lists++
        val n = SftpPaths.normalize(path)
        val dir = node(n)
        if (dir.type != SftpFileType.DIRECTORY) throw SftpError.NotADirectory(n)
        return nodes.entries
            .filter { (p, _) -> p != SftpPaths.ROOT && SftpPaths.parent(p) == n }
            .map { (p, node) -> entry(p, node) }
    }

    override suspend fun stat(path: String): SftpEntry {
        val n = SftpPaths.normalize(path)
        return entry(n, node(n))
    }

    override suspend fun mkdir(path: String) {
        val n = SftpPaths.normalize(path)
        node(SftpPaths.parent(n))
        if (n in nodes) throw SftpError.AlreadyExists(n)
        nodes[n] = FakeNode(SftpFileType.DIRECTORY, modifiedAt = System.currentTimeMillis(), permissions = 0b111_101_101)
    }

    override suspend fun rename(from: String, to: String) {
        val f = SftpPaths.normalize(from)
        val t = SftpPaths.normalize(to)
        node(f)
        if (t in nodes) throw SftpError.AlreadyExists(t)
        val moved = nodes.filterKeys { it == f || it.startsWith("$f/") }
        moved.keys.forEach { nodes.remove(it) }
        moved.forEach { (p, n) -> nodes[t + p.removePrefix(f)] = n }
    }

    override suspend fun delete(path: String) {
        val n = SftpPaths.normalize(path)
        node(n)
        nodes.keys.filter { it == n || it.startsWith("$n/") }.forEach { nodes.remove(it) }
    }

    override suspend fun chmod(path: String, permissions: Int) {
        node(SftpPaths.normalize(path)).permissions = permissions and 0xFFF
    }

    override suspend fun download(path: String, sink: OutputStream, onProgress: (bytes: Long, total: Long) -> Unit) {
        val node = node(SftpPaths.normalize(path))
        if (node.type == SftpFileType.DIRECTORY) throw SftpError.IsADirectory(path)
        val total = node.size
        var sent = 0L
        val chunk = ByteArray(64 * 1024)
        while (sent < total) {
            currentCoroutineContext().ensureActive()
            val n = minOf(chunk.size.toLong(), total - sent).toInt()
            if (node.content.isNotEmpty()) sink.write(node.content, sent.toInt(), n) else sink.write(chunk, 0, n)
            sent += n
            onProgress(sent, total)
            if (chunkDelayMs > 0) delay(chunkDelayMs)
        }
        onProgress(sent, total)
    }

    override suspend fun upload(source: InputStream, size: Long, path: String, onProgress: (bytes: Long, total: Long) -> Unit) {
        val n = SftpPaths.normalize(path)
        node(SftpPaths.parent(n))
        val bytes = source.readBytes()
        nodes[n] = FakeNode(SftpFileType.REGULAR, size = bytes.size.toLong(), modifiedAt = System.currentTimeMillis(), content = bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }

    override suspend fun readText(path: String, maxBytes: Int): TextRead {
        reads++
        val node = node(SftpPaths.normalize(path))
        if (node.type == SftpFileType.DIRECTORY) throw SftpError.IsADirectory(path)
        val head = node.content.take(maxBytes).toByteArray()
        if (head.any { it == 0.toByte() }) return TextRead.Binary(node.size)
        return TextRead.Text(head.toString(Charsets.UTF_8), truncated = node.size > head.size, size = node.size)
    }

    override fun close() {
        isOpen = false
    }

    companion object {
        /** A home folder as a developer's box has it, with times spread over today, this year and before. */
        fun demoTree(now: Long): FakeSftpFileSystem {
            val h = TimeUnit.HOURS.toMillis(1)
            val d = TimeUnit.DAYS.toMillis(1)
            val fs = FakeSftpFileSystem()
            fs.dir("/home/demo", now - 2 * h)
            fs.dir("/home/demo/projects", now - 3 * h)
            fs.dir("/home/demo/projects/berth", now - 3 * h)
            fs.dir("/home/demo/projects/berth/app", now - 3 * h)
            fs.dir("/home/demo/projects/berth/core", now - 5 * d)
            fs.file("/home/demo/projects/berth/settings.gradle.kts", "rootProject.name = \"berth\"\ninclude(\":app\")\ninclude(\":core:ssh\")\ninclude(\":core:sftp\")\n", now - 3 * h)
            fs.file("/home/demo/projects/berth/build.gradle.kts", "plugins { alias(libs.plugins.android.application) apply false }\n", now - 40 * d)
            fs.dir("/home/demo/backups", now - 12 * d)
            fs.dir("/home/demo/.config", now - 30 * d)
            fs.dir("/home/demo/.ssh", now - 90 * d, permissions = 0b111_000_000)
            fs.file("/home/demo/.bashrc", "# ~/.bashrc\nexport EDITOR=vim\n", now - 400 * d)
            fs.file("/home/demo/.profile", "# ~/.profile\n", now - 400 * d)
            fs.file(
                "/home/demo/deploy.sh",
                "#!/usr/bin/env bash\nset -euo pipefail\n\n# Ship the current build to the box and restart the service.\nrsync -az --delete build/ deploy@prod-web:/srv/app/\nssh deploy@prod-web 'sudo systemctl restart app'\n",
                now - 5 * h,
                permissions = 0b111_101_101,
            )
            fs.file("/home/demo/notes.txt", "Things to check before the release:\n\n- the tunnel to the database box\n- the tmux session on prod-web\n- rotate the deploy key\n", now - 20 * TimeUnit.MINUTES.toMillis(1))
            fs.bigFile("/home/demo/site-backup-2026-09-18.tar.gz", 348L * 1024 * 1024, now - d)
            fs.file("/home/demo/nginx-access.log", "10.0.0.7 - - [18/Sep/2026:23:59:58 +0000] \"GET / HTTP/1.1\" 200 5123\n".repeat(220), now - 30 * TimeUnit.MINUTES.toMillis(1), permissions = 0b110_100_000)
            fs.file("/home/demo/berth-arm64.apk", ByteArray(4096) { if (it % 7 == 0) 0 else (it % 251).toByte() }, now - 2 * d, permissions = 0b110_100_100)
            fs.link("/home/demo/www", SftpFileType.DIRECTORY, now - 200 * d)
            fs.link("/home/demo/old-link", null, now - 300 * d)
            fs.dir("/var", now - 500 * d)
            fs.dir("/var/log", now - h, permissions = 0b111_101_101)
            fs.dir("/root", now - 700 * d, permissions = 0b111_000_000)
            fs.locked += "/root"
            fs.dir("/home/demo/backups/empty", now - 12 * d)
            return fs
        }
    }
}
