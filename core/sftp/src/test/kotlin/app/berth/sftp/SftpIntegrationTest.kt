package app.berth.sftp

import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.SshAuth
import app.berth.ssh.SshConnection
import app.berth.ssh.SshEndpoint
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SftpClient] against a live sshd whose `sftp` subsystem is enabled. Opt in with
 * `SSH_TEST_HOST`, `SSH_TEST_PORT`, `SSH_TEST_USER` and `SSH_TEST_PASSWORD`; the password only ever
 * comes from the environment. Every test works inside a fresh folder under the login's home.
 */
class SftpIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var connection: SshConnection
    private lateinit var fs: SftpClient
    private lateinit var work: String

    @Before
    fun connect() = runBlocking<Unit> {
        assumeTrue("set SSH_TEST_HOST/PORT/USER/PASSWORD to run", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        SshSecurity.ensureProviders()
        val endpoint = SshEndpoint(host = host, port = port, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }), keepaliveSeconds = 5)
        connection = SshConnection(endpoint, AcceptAllHostKeys)
        connection.connect()
        fs = SftpClient.open(connection)
        work = SftpPaths.join(fs.home(), "berth-sftp-" + UUID.randomUUID().toString().take(8))
        fs.mkdir(work)
    }

    @After
    fun disconnect() {
        if (!::fs.isInitialized) return
        runBlocking { runCatching { fs.delete(work) } }
        fs.close()
        connection.close()
    }

    private suspend fun put(name: String, bytes: ByteArray, dir: String = work): String {
        val path = SftpPaths.join(dir, name)
        fs.upload(ByteArrayInputStream(bytes), bytes.size.toLong(), path)
        return path
    }

    private suspend fun get(path: String): ByteArray = ByteArrayOutputStream().also { fs.download(path, it) }.toByteArray()

    @Test
    fun `home is absolute and the work folder lists with sizes, times, modes and symlink targets`() = runBlocking<Unit> {
        val home = fs.home()
        assertTrue(home.startsWith("/"), "home should be absolute: $home")
        assertEquals(home, fs.canonicalize("."))

        val before = System.currentTimeMillis() - 5_000
        put("notes.txt", "hello berth\n".toByteArray())
        put(".hidden", ByteArray(3))
        fs.mkdir(SftpPaths.join(work, "sub"))
        assertEquals(work, fs.canonicalize("$work/./sub/.."))
        connection.exec("ln -s '$work/sub' '$work/to-sub' && ln -s '$work/notes.txt' '$work/to-notes' && ln -s /nowhere/at/all '$work/dangling'")

        val entries = fs.list(work).associateBy { it.name }
        assertEquals(setOf("notes.txt", ".hidden", "sub", "to-sub", "to-notes", "dangling"), entries.keys)

        val notes = entries.getValue("notes.txt")
        assertEquals(SftpFileType.REGULAR, notes.type)
        assertEquals(12L, notes.size)
        assertEquals(SftpPaths.join(work, "notes.txt"), notes.path)
        assertTrue(notes.modifiedAt >= before, "mtime should be recent: ${notes.modifiedAt}")
        assertTrue(notes.permissionText.startsWith("-rw"), notes.permissionText)
        assertTrue(notes.isRegularFile)
        assertFalse(notes.isHidden)

        assertTrue(entries.getValue(".hidden").isHidden)
        val sub = entries.getValue("sub")
        assertEquals(SftpFileType.DIRECTORY, sub.type)
        assertTrue(sub.isDirectory)
        assertEquals('d', sub.permissionText[0])

        val toSub = entries.getValue("to-sub")
        assertEquals(SftpFileType.SYMLINK, toSub.type)
        assertEquals(SftpFileType.DIRECTORY, toSub.linkTarget)
        assertTrue(toSub.isDirectory)
        assertEquals(SftpFileType.REGULAR, entries.getValue("to-notes").linkTarget)
        assertTrue(entries.getValue("to-notes").isRegularFile)
        assertNull(entries.getValue("dangling").linkTarget)
        assertEquals('l', entries.getValue("dangling").permissionText[0])

        val stat = fs.stat(SftpPaths.join(work, "to-notes"))
        assertEquals(SftpFileType.REGULAR, stat.type, "stat follows the link")
        assertEquals(12L, stat.size)
        assertEquals("to-notes", stat.name)
    }

    @Test
    fun `mkdir, rename and delete round trip, with the collisions named`() = runBlocking<Unit> {
        val dir = SftpPaths.join(work, "folder")
        fs.mkdir(dir)
        assertFailsWith<SftpError.AlreadyExists> { fs.mkdir(dir) }
        assertFailsWith<SftpError.NotFound> { fs.mkdir("$work/missing/parent/child") }

        val file = put("a.txt", "a".toByteArray())
        val other = put("b.txt", "b".toByteArray())
        val renamed = SftpPaths.join(work, "c.txt")
        fs.rename(file, renamed)
        assertEquals(setOf("folder", "b.txt", "c.txt"), fs.list(work).map { it.name }.toSet())
        assertFailsWith<SftpError.AlreadyExists> { fs.rename(renamed, other) }
        assertFailsWith<SftpError.NotFound> { fs.rename("$work/nope", "$work/whatever") }

        val movedInto = SftpPaths.join(dir, "moved.txt")
        fs.rename(renamed, movedInto)
        assertEquals("a", get(movedInto).decodeToString())

        fs.delete(other)
        assertFailsWith<SftpError.NotFound> { fs.stat(other) }

        // A tree: files, a nested folder and a symlink; delete takes the lot without following the link.
        val nested = SftpPaths.join(dir, "nested")
        fs.mkdir(nested)
        put("deep.bin", ByteArray(2048), nested)
        val outside = put("outside.txt", "keep me".toByteArray())
        connection.exec("ln -s '$outside' '$dir/link-out'")
        fs.delete(dir)
        assertFailsWith<SftpError.NotFound> { fs.stat(dir) }
        assertEquals("keep me", get(outside).decodeToString(), "deleting a tree must not follow symlinks out of it")
        assertFailsWith<SftpError.NotFound> { fs.delete(dir) }
    }

    @Test
    fun `upload reports monotonic progress ending at the size and download gives the same bytes back`() = runBlocking<Unit> {
        val bytes = Random(7).nextBytes(300 * 1024 + 17)
        val path = SftpPaths.join(work, "blob.bin")
        val seen = ArrayList<Pair<Long, Long>>()
        fs.upload(ByteArrayInputStream(bytes), bytes.size.toLong(), path) { b, t -> seen += b to t }
        assertTrue(seen.size >= 3, "progress should be reported per chunk, got ${seen.size}")
        assertEquals(0L, seen.first().first)
        assertEquals(bytes.size.toLong(), seen.last().first)
        assertTrue(seen.all { it.second == bytes.size.toLong() })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress must not go backwards")
        assertEquals(bytes.size.toLong(), fs.stat(path).size)

        val down = ArrayList<Long>()
        val sink = ByteArrayOutputStream()
        fs.download(path, sink) { b, t -> down += b; assertEquals(bytes.size.toLong(), t) }
        assertContentEquals(bytes, sink.toByteArray())
        assertEquals(0L, down.first())
        assertEquals(bytes.size.toLong(), down.last())

        // Uploading again truncates rather than appends.
        val shorter = "short".toByteArray()
        fs.upload(ByteArrayInputStream(shorter), shorter.size.toLong(), path)
        assertContentEquals(shorter, get(path))
    }

    @Test
    fun `a multi-megabyte transfer cancels between chunks and the channel stays usable`() = runBlocking<Unit> {
        val size = 8 * 1024 * 1024
        val bytes = Random(11).nextBytes(size)
        val path = SftpPaths.join(work, "big.bin")
        val started = System.nanoTime()
        fs.upload(ByteArrayInputStream(bytes), size.toLong(), path)
        val uploadMs = (System.nanoTime() - started) / 1_000_000
        println("uploaded ${size / 1024 / 1024} MiB in $uploadMs ms")
        assertEquals(size.toLong(), fs.stat(path).size)

        // A sink that takes its time, so the copy is still running when the cancel lands.
        val written = AtomicLong()
        val slow = object : OutputStream() {
            override fun write(b: Int) = error("unused")
            override fun write(b: ByteArray, off: Int, len: Int) {
                Thread.sleep(2)
                written.addAndGet(len.toLong())
            }
        }
        val progress = AtomicLong()
        val job = launch(Dispatchers.IO) { fs.download(path, slow) { b, _ -> progress.set(b) } }
        withTimeout(20_000) { while (progress.get() < 1024 * 1024) delay(5) }
        val cancelAt = System.nanoTime()
        job.cancelAndJoin()
        val cancelMs = (System.nanoTime() - cancelAt) / 1_000_000
        assertTrue(job.isCancelled)
        assertTrue(written.get() < size, "the download should have stopped early: ${written.get()}")
        assertTrue(cancelMs < 5_000, "cancellation should land within a chunk or two, took $cancelMs ms")

        // The channel is still good: listing works and a full download reproduces the file.
        assertTrue(fs.isOpen)
        assertTrue(fs.list(work).any { it.name == "big.bin" })
        val sink = ByteArrayOutputStream(size)
        val downloadStart = System.nanoTime()
        fs.download(path, sink)
        println("downloaded ${size / 1024 / 1024} MiB in ${(System.nanoTime() - downloadStart) / 1_000_000} ms")
        assertContentEquals(bytes, sink.toByteArray())
    }

    @Test
    fun `chmod changes the mode and the permission text and octal follow`() = runBlocking<Unit> {
        val path = put("mode.sh", "#!/bin/sh\n".toByteArray())
        fs.chmod(path, 0b110_000_000)
        var entry = fs.stat(path)
        assertEquals(0b110_000_000, entry.permissions)
        assertEquals("-rw-------", entry.permissionText)
        assertEquals("600", entry.octal)

        fs.chmod(path, 0b111_101_101)
        entry = fs.list(work).first { it.name == "mode.sh" }
        assertEquals("-rwxr-xr-x", entry.permissionText)
        assertEquals("755", entry.octal)

        fs.chmod(path, SftpPermissions.SETUID or 0b111_101_101)
        entry = fs.stat(path)
        assertEquals("-rwsr-xr-x", entry.permissionText)
        assertEquals("4755", entry.octal)

        val dir = SftpPaths.join(work, "locked")
        fs.mkdir(dir)
        fs.chmod(dir, 0)
        assertEquals("d---------", fs.stat(dir).permissionText)
        assertFailsWith<SftpError.PermissionDenied> { fs.list(dir) }
        fs.chmod(dir, 0b111_000_000)
    }

    @Test
    fun `permission denied and missing paths come back as their own errors`() = runBlocking<Unit> {
        // A folder of our own with every bit cleared: the login is not root, so nothing inside is reachable.
        val locked = SftpPaths.join(work, "locked")
        fs.mkdir(locked)
        val inside = put("secret.txt", "x".toByteArray(), locked)
        fs.chmod(locked, 0)
        try {
            val denied = assertFailsWith<SftpError.PermissionDenied> { fs.list(locked) }
            assertEquals(locked, denied.path)
            assertFailsWith<SftpError.PermissionDenied> { fs.mkdir(SftpPaths.join(locked, "child")) }
            assertFailsWith<SftpError.PermissionDenied> {
                fs.upload(ByteArrayInputStream(ByteArray(1)), 1, SftpPaths.join(locked, "new.txt"))
            }
            assertFailsWith<SftpError.PermissionDenied> { fs.download(inside, ByteArrayOutputStream()) }
            assertFailsWith<SftpError.PermissionDenied> { fs.delete(inside) }
            assertFailsWith<SftpError.PermissionDenied> { fs.chmod(inside, 0b111_111_111) }
            assertTrue(denied.message!!.contains(locked))
        } finally {
            fs.chmod(locked, 0b111_000_000)
        }
        // System paths owned by root refuse writes the same way.
        assertFailsWith<SftpError.PermissionDenied> { fs.mkdir("/etc/berth-should-not-exist") }
        assertFailsWith<SftpError.PermissionDenied> {
            fs.upload(ByteArrayInputStream(ByteArray(1)), 1, "/etc/berth-should-not-exist")
        }
        assertFailsWith<SftpError.PermissionDenied> { fs.delete("/etc/hostname") }
        assertFailsWith<SftpError.PermissionDenied> { fs.chmod("/etc/hostname", 0b111_111_111) }

        val missing = assertFailsWith<SftpError.NotFound> { fs.stat("/definitely/not/here") }
        assertEquals("/definitely/not/here", missing.path)
        assertFailsWith<SftpError.NotFound> { fs.list("$work/nope") }
        assertFailsWith<SftpError.NotFound> { fs.download("$work/nope", ByteArrayOutputStream()) }
        assertFailsWith<SftpError.NotFound> { fs.readText("$work/nope", 1024) }
        // realpath tolerates a missing last component but not a missing folder on the way.
        assertFailsWith<SftpError.NotFound> { fs.canonicalize("$work/nope/deeper") }
        assertFailsWith<SftpError.NotFound> { fs.chmod("$work/nope", 0b111_000_000) }

        // Each error carries a sentence the screen can show as is.
        assertTrue(missing.message!!.contains("/definitely/not/here"))
    }

    @Test
    fun `readText returns text, flags binaries and truncates long files`() = runBlocking<Unit> {
        val text = buildString { repeat(40) { appendLine("line $it: server_name berth.example;") } }
        val textPath = put("nginx.conf", text.toByteArray())
        val read = assertIs<TextRead.Text>(fs.readText(textPath, 64 * 1024))
        assertEquals(text, read.content)
        assertFalse(read.truncated)
        assertEquals(text.length.toLong(), read.size)

        val cut = assertIs<TextRead.Text>(fs.readText(textPath, 100))
        assertTrue(cut.truncated)
        assertEquals(100, cut.content.toByteArray().size)
        assertEquals(text.take(100), cut.content)

        val binary = ByteArray(4096) { if (it % 7 == 0) 0 else (it % 251).toByte() }
        val binPath = put("image.bin", binary)
        val flagged = assertIs<TextRead.Binary>(fs.readText(binPath, 64 * 1024))
        assertEquals(4096L, flagged.size)

        val empty = put("empty", ByteArray(0))
        val nothing = assertIs<TextRead.Text>(fs.readText(empty, 1024))
        assertEquals("", nothing.content)
    }

    @Test
    fun `sftp cannot be opened on a closed connection and a second channel shares the login`() = runBlocking<Unit> {
        // Two channels on one connection: the transfer queue opens its own next to the browser's.
        val second = SftpClient.open(connection)
        try {
            assertTrue(second.isOpen)
            assertNotNull(second.list(work))
        } finally {
            second.close()
        }
        assertFalse(second.isOpen)
        assertTrue(fs.isOpen)

        val endpoint = SshEndpoint(host = host, port = port, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }))
        val closed = SshConnection(endpoint, AcceptAllHostKeys)
        closed.connect()
        closed.close()
        assertFailsWith<SftpError.NotConnected> { SftpClient.open(closed) }
    }
}
