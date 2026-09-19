package app.berth.sftp

import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.SshAuth
import app.berth.ssh.SshConnection
import app.berth.ssh.SshEndpoint
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [FolderTransfer] against a live sshd, both ways, on a three-level tree with a few
 * hundred small files, one 20 MB file, empty folders, links and a folder the login cannot read.
 * Opt in with `SSH_TEST_HOST`, `SSH_TEST_PORT`, `SSH_TEST_USER` and `SSH_TEST_PASSWORD`; the
 * password only ever comes from the environment. The server side of every test is a fresh folder
 * under the login's home; the device side is a temp folder on this machine through [FileTree].
 */
class FolderTransferIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var connection: SshConnection
    private lateinit var fs: SftpClient
    private lateinit var work: String
    private val temps = ArrayList<File>()

    @Before
    fun connect() = runBlocking<Unit> {
        assumeTrue("set SSH_TEST_HOST/PORT/USER/PASSWORD to run", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        SshSecurity.ensureProviders()
        val endpoint = SshEndpoint(host = host, port = port, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }), keepaliveSeconds = 5)
        connection = SshConnection(endpoint, AcceptAllHostKeys)
        connection.connect()
        fs = SftpClient.open(connection)
        work = SftpPaths.join(fs.home(), "berth-folder-" + UUID.randomUUID().toString().take(8))
        fs.mkdir(work)
    }

    @After
    fun disconnect() {
        temps.forEach { it.deleteRecursively() }
        if (!::fs.isInitialized) return
        // The locked folder cannot be removed over sftp, so the shell opens it up first.
        runBlocking { runCatching { connection.exec("chmod -R u+rwx '$work'; rm -rf '$work'") } }
        fs.close()
        connection.close()
    }

    // ---- the tree ------------------------------------------------------------------------------

    /**
     * `tree/` on the server: two files at the top (the 20 MB one sorts after the README, so a cancel
     * during it leaves one complete file behind), `docs/` with a guide and three links, an empty
     * folder, a folder with every bit cleared, and `src/main` and `src/test` with 150 small files
     * each plus an empty folder three levels down. 304 regular files reach the device.
     */
    private suspend fun makeRemoteTree(): String {
        val root = "$work/tree"
        connection.exec(
            """
            set -e
            cd '$work'
            mkdir -p tree/src/main/nested-empty tree/src/test tree/docs tree/empty tree/locked
            printf 'Berth folder transfer test\n' > tree/README.txt
            head -c 20971520 /dev/urandom > tree/site-backup.tar.gz
            for i in $(seq 0 149); do
              n=$(printf %03d ${'$'}i)
              seq 1 $((i + 5)) > tree/src/main/a${'$'}n.txt
              seq 100 $((i + 105)) > tree/src/test/t${'$'}n.txt
            done
            printf '# guide\n' > tree/docs/guide.md
            ln -s ../README.txt tree/docs/link-to-readme
            ln -s ../src tree/docs/link-to-src
            ln -s /nowhere/at/all tree/docs/dangling
            printf 'secret\n' > tree/locked/secret.txt
            chmod 000 tree/locked
            """.trimIndent(),
        )
        assertEquals("tree", SftpPaths.name(root))
        return root
    }

    private fun tempDir(): File = createTempDirectory("berth-folder").toFile().also { temps += it }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Relative path to SHA-256 of every regular file under [dir] on the server, as `sha256sum` sees them. */
    private suspend fun remoteHashes(dir: String): Map<String, String> =
        connection.exec("cd '$dir' && find . -type f -exec sha256sum {} + 2>/dev/null").lines().filter { it.isNotBlank() }.associate { line ->
            line.substring(66).removePrefix("./") to line.substring(0, 64)
        }

    private suspend fun remoteDirs(dir: String): Set<String> =
        connection.exec("cd '$dir' && find . -type d 2>/dev/null").lines().filter { it.isNotBlank() && it != "." }.mapTo(HashSet()) { it.removePrefix("./") }

    private fun localHashes(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.associate { it.relativeTo(root).path.replace(File.separatorChar, '/') to sha256(it.readBytes()) }

    private fun assertMonotonic(seen: List<FolderProgress>) {
        seen.zipWithNext().forEach { (a, b) ->
            assertTrue(b.bytesDone >= a.bytesDone || b.failures.size > a.failures.size, "bytes went backwards without a failure: ${a.bytesDone} -> ${b.bytesDone}")
            assertTrue(b.filesDone >= a.filesDone, "files done went backwards")
        }
    }

    // ---- download ------------------------------------------------------------------------------

    @Test
    fun `downloads the tree with empty folders kept, a link to a file followed, and a locked folder failing alone`() = runBlocking<Unit> {
        val remote = makeRemoteTree()
        val dest = tempDir()
        val seen = ArrayList<FolderProgress>()
        val started = System.nanoTime()
        val result = FolderTransfer(fs, FileTree(dest), onProgress = { seen += it }).download(remote)
        println("downloaded ${result.filesCopied} files, ${result.bytesDone / 1024 / 1024} MiB in ${(System.nanoTime() - started) / 1_000_000} ms")

        val root = File(dest, "tree")
        assertTrue(root.isDirectory, "the copy lands as a folder named after the source")
        assertTrue(File(root, "empty").isDirectory && File(root, "empty").list()!!.isEmpty(), "an empty folder is made")
        assertTrue(File(root, "src/main/nested-empty").isDirectory, "an empty folder three levels down is made")
        assertFalse(File(root, "docs/link-to-src").exists(), "a link to a folder is not followed")
        assertFalse(File(root, "docs/dangling").exists(), "a dangling link is left out")
        assertTrue(File(root, "locked").isDirectory && File(root, "locked").list()!!.isEmpty(), "the folder that would not list is there and empty")

        val remoteHashes = remoteHashes(remote)
        val expected = remoteHashes + ("docs/link-to-readme" to remoteHashes.getValue("README.txt"))
        assertEquals(304, expected.size)
        assertEquals(expected, localHashes(root), "every file reaches the device with the same bytes")

        assertEquals(FolderPhase.FINISHED, result.phase)
        assertEquals(304, result.filesTotal)
        assertEquals(304, result.filesCopied)
        assertEquals(0, result.filesSkipped)
        assertEquals(1f, result.fraction)
        val expectedBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(expectedBytes, result.bytesDone)
        assertEquals(expectedBytes, result.bytesTotal)

        val locked = result.failures.single { it.retryable }
        assertEquals("locked", locked.relativePath)
        assertTrue(locked.isDirectory)
        assertTrue(locked.message.contains("refused"), locked.message)
        assertEquals(1, result.filesFailed)
        val leftOut = result.failures.filter { !it.retryable }.associate { it.relativePath to it.message }
        assertEquals(setOf("docs/link-to-src", "docs/dangling"), leftOut.keys)
        assertEquals("Link to a folder; skipped.", leftOut["docs/link-to-src"])
        assertEquals("Broken link; skipped.", leftOut["docs/dangling"])
        assertEquals(2, result.filesLeftOut)

        // Progress: scanning first with the count growing, then copying with ticks inside the big file, bytes never going backwards.
        assertEquals(FolderPhase.SCANNING, seen.first().phase)
        assertTrue(seen.any { it.phase == FolderPhase.SCANNING && it.filesTotal > 0 })
        assertTrue(seen.count { it.current == "site-backup.tar.gz" && it.currentBytes > 0 } >= 5, "the 20 MB file should tick several times")
        assertTrue(seen.any { it.current == "src/test/t149.txt" }, "the current file is named as it moves")
        assertMonotonic(seen)
        assertEquals(304, seen.last().filesDone)
    }

    @Test
    fun `cancel mid-way stops within a chunk, removes the half-written file and leaves the complete ones`() = runBlocking<Unit> {
        val remote = makeRemoteTree()
        val dest = tempDir()
        var last: FolderProgress? = null
        lateinit var job: Job
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            FolderTransfer(fs, FileTree(dest), onProgress = { p ->
                last = p
                if (p.current == "site-backup.tar.gz" && p.currentBytes > 1024 * 1024) job.cancel()
            }).download(remote)
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)

        val root = File(dest, "tree")
        val readme = File(root, "README.txt")
        assertTrue(readme.isFile, "the file copied before the cancel stays")
        assertEquals(remoteHashes(remote).getValue("README.txt"), sha256(readme.readBytes()))
        assertFalse(File(root, "site-backup.tar.gz").exists(), "the half-written file is taken away")
        assertEquals(emptyList<File>(), File(root, "src/main").listFiles()!!.filter { it.isFile }, "nothing after the cancel point was copied")
        val p = assertNotNull(last)
        assertEquals(1, p.filesCopied)
        assertTrue(p.currentBytes in (1024L * 1024)..(20L * 1024 * 1024), "cancelled mid-file at ${p.currentBytes}")

        // The channel is still good and a second copy over the same destination merges into it.
        assertTrue(fs.isOpen)
        assertTrue(fs.list(work).any { it.name == "tree" })
        val again = FolderTransfer(fs, FileTree(dest), resolve = { ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true) }).download(remote)
        assertEquals(304, again.filesCopied)
        assertEquals(remoteHashes(remote) + ("docs/link-to-readme" to remoteHashes(remote).getValue("README.txt")), localHashes(root))
    }

    @Test
    fun `each conflict policy, apply to all, and only for a retry`() = runBlocking<Unit> {
        val remote = makeRemoteTree()
        val remoteHashes = remoteHashes(remote)
        val only = setOf("README.txt", "src/main/a000.txt", "src/main/a001.txt")
        fun seed(): File {
            val dest = tempDir()
            File(dest, "tree/src/main").mkdirs()
            File(dest, "tree/README.txt").writeText("old")
            File(dest, "tree/src/main/a000.txt").writeText("old")
            return dest
        }

        // Skip, asked for each: the two files that exist stay as they were; the third comes down.
        run {
            val dest = seed()
            val asked = ArrayList<FolderConflict>()
            val result = FolderTransfer(fs, FileTree(dest), resolve = { asked += it; ConflictResolution(ConflictChoice.SKIP, applyToAll = false) }).download(remote, only)
            assertEquals(listOf("README.txt", "src/main/a000.txt"), asked.map { it.relativePath })
            assertEquals(listOf(2, 1), asked.map { it.remaining }, "how many files are still to come after each")
            assertEquals(3L, asked[0].existingSize)
            assertEquals(File(dest, "tree/README.txt").length(), 3L)
            assertEquals("old", File(dest, "tree/README.txt").readText())
            assertEquals("old", File(dest, "tree/src/main/a000.txt").readText())
            assertEquals(remoteHashes.getValue("src/main/a001.txt"), sha256(File(dest, "tree/src/main/a001.txt").readBytes()))
            assertEquals(3, result.filesTotal)
            assertEquals(1, result.filesCopied)
            assertEquals(2, result.filesSkipped)
            assertEquals(3, result.filesDone)
            assertEquals(1f, result.fraction, "skipped bytes still bring the fraction to the end")
            assertTrue(result.failures.isEmpty(), "a skip is not a failure")
            assertEquals(3, localHashes(File(dest, "tree")).size, "only the three named files are touched")
        }

        // Overwrite for all: asked once, both replaced with the server's bytes.
        run {
            val dest = seed()
            var asked = 0
            val result = FolderTransfer(fs, FileTree(dest), resolve = { asked++; ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true) }).download(remote, only)
            assertEquals(1, asked)
            assertEquals(remoteHashes.filterKeys { it in only }, localHashes(File(dest, "tree")))
            assertEquals(3, result.filesCopied)
            assertEquals(0, result.filesSkipped)
        }

        // Keep both: the old files stay and the new ones land beside them with a counter before the extension.
        run {
            val dest = seed()
            val result = FolderTransfer(fs, FileTree(dest), resolve = { ConflictResolution(ConflictChoice.KEEP_BOTH, applyToAll = true) }).download(remote, only)
            assertEquals("old", File(dest, "tree/README.txt").readText())
            assertEquals("old", File(dest, "tree/src/main/a000.txt").readText())
            assertEquals(remoteHashes.getValue("README.txt"), sha256(File(dest, "tree/README (1).txt").readBytes()))
            assertEquals(remoteHashes.getValue("src/main/a000.txt"), sha256(File(dest, "tree/src/main/a000 (1).txt").readBytes()))
            assertEquals(3, result.filesCopied)
            // Once more and the counter moves on rather than overwriting the first copy.
            FolderTransfer(fs, FileTree(dest), resolve = { ConflictResolution(ConflictChoice.KEEP_BOTH, applyToAll = true) }).download(remote, setOf("README.txt"))
            assertTrue(File(dest, "tree/README (2).txt").isFile)
        }

        // A folder that exists already never asks; only files do.
        run {
            val dest = tempDir()
            File(dest, "tree/src/main").mkdirs()
            var asked = 0
            FolderTransfer(fs, FileTree(dest), resolve = { asked++; ConflictResolution(ConflictChoice.SKIP, applyToAll = false) }).download(remote, only)
            assertEquals(0, asked)
        }
    }

    // ---- upload --------------------------------------------------------------------------------

    /**
     * `up/` on the device: two files at the top, a link to one of them (copied as a file) and a link
     * to a folder (not descended), an empty folder, and a folder three levels deep with a file and
     * another empty folder. Four regular files reach the server.
     */
    private fun makeLocalTree(): Pair<File, Map<String, String>> {
        val base = tempDir()
        val root = File(base, "up").apply { mkdirs() }
        val notes = "notes for the server\n".toByteArray()
        val photo = Random(41).nextBytes(2 * 1024 * 1024 + 331)
        val deep = "three levels down\n".toByteArray()
        File(root, "notes.txt").writeBytes(notes)
        File(root, "photo.bin").writeBytes(photo)
        File(root, "empty").mkdirs()
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/empty2").mkdirs()
        File(root, "a/b/c/deep.txt").writeBytes(deep)
        Files.createSymbolicLink(File(root, "link-to-notes").toPath(), File("notes.txt").toPath())
        Files.createSymbolicLink(File(root, "link-to-a").toPath(), File("a").toPath())
        val hashes = mapOf(
            "notes.txt" to sha256(notes),
            "photo.bin" to sha256(photo),
            "a/b/c/deep.txt" to sha256(deep),
            "link-to-notes" to sha256(notes),
        )
        return root to hashes
    }

    @Test
    fun `uploads the tree making folders as needed with sane modes, a link to a file as a file, and reports progress`() = runBlocking<Unit> {
        val (root, hashes) = makeLocalTree()
        val seen = ArrayList<FolderProgress>()
        val result = FolderTransfer(fs, FileTree(root), onProgress = { seen += it }).upload(work)

        val remote = "$work/up"
        assertEquals(hashes, remoteHashes(remote), "every file reaches the server with the same bytes, the link as a file")
        assertEquals(setOf("empty", "a", "a/b", "a/b/c", "a/b/empty2"), remoteDirs(remote), "folders are made as needed, empty ones included, and a link to a folder is not descended")
        assertEquals(0b111_101_101, fs.stat(remote).permissions, "new folders are 755")
        assertEquals(0b111_101_101, fs.stat("$remote/a/b/empty2").permissions)
        assertEquals(0b110_100_100, fs.stat("$remote/notes.txt").permissions, "new files are 644")
        assertEquals(0b110_100_100, fs.stat("$remote/photo.bin").permissions)

        assertEquals(FolderPhase.FINISHED, result.phase)
        assertEquals(4, result.filesTotal)
        assertEquals(4, result.filesCopied)
        assertTrue(result.failures.isEmpty(), "${result.failures}")
        val bytes = hashes.keys.sumOf { File(root, it).length() }
        assertEquals(bytes, result.bytesDone)
        assertEquals(bytes, result.bytesTotal)
        assertEquals(1f, result.fraction)
        assertTrue(seen.count { it.current == "photo.bin" && it.currentBytes > 0 } >= 3, "the 2 MB file should tick a few times")
        assertMonotonic(seen)

        // The same tree again onto what is there: folders merge without a question, files ask, and Overwrite replaces.
        File(root, "notes.txt").writeText("changed on the device\n")
        val asked = ArrayList<FolderConflict>()
        val again = FolderTransfer(fs, FileTree(root), resolve = { asked += it; ConflictResolution(ConflictChoice.OVERWRITE, applyToAll = true) }).upload(work)
        assertEquals(1, asked.size)
        assertEquals(4, again.filesCopied)
        assertEquals(sha256("changed on the device\n".toByteArray()), remoteHashes(remote).getValue("notes.txt"))
        assertEquals(notesSize(), asked.single().existingSize, "the size on the server is what the conflict reports as existing")
    }

    private fun notesSize(): Long = "notes for the server\n".length.toLong()

    @Test
    fun `a folder the server will not take writes in fails file by file while the rest goes on`() = runBlocking<Unit> {
        val base = tempDir()
        val root = File(base, "up2").apply { mkdirs() }
        File(root, "ok.txt").writeText("fine\n")
        File(root, "ro").mkdirs()
        File(root, "ro/x.txt").writeText("blocked\n")
        File(root, "ro/y.txt").writeText("also blocked\n")
        File(root, "after.txt").writeText("still fine\n")
        fs.mkdir("$work/up2")
        fs.mkdir("$work/up2/ro")
        fs.chmod("$work/up2/ro", 0b101_101_101)

        val result = FolderTransfer(fs, FileTree(root)).upload(work)
        assertEquals(4, result.filesTotal)
        assertEquals(2, result.filesCopied)
        assertEquals(2, result.filesFailed)
        assertEquals(setOf("ro/x.txt", "ro/y.txt"), result.failures.mapTo(HashSet()) { it.relativePath })
        assertTrue(result.failures.all { it.retryable && !it.isDirectory && it.message.contains("refused") }, "${result.failures}")
        assertEquals(setOf("ok.txt", "after.txt"), remoteHashes("$work/up2").keys)
        assertEquals(1f, result.fraction, "failed bytes still bring the fraction to the end")
        assertEquals(4, result.filesDone)
        assertEquals(FolderPhase.FINISHED, result.phase)

        // Opened up, a retry narrowed to what failed copies just those two.
        fs.chmod("$work/up2/ro", 0b111_101_101)
        val retry = FolderTransfer(fs, FileTree(root)).upload(work, only = result.failures.mapTo(HashSet()) { it.relativePath })
        assertEquals(2, retry.filesTotal)
        assertEquals(2, retry.filesCopied)
        assertEquals(setOf("ok.txt", "after.txt", "ro/x.txt", "ro/y.txt"), remoteHashes("$work/up2").keys)
        assertContentEquals("blocked\n".toByteArray(), fs.let { c -> java.io.ByteArrayOutputStream().also { c.download("$work/up2/ro/x.txt", it) }.toByteArray() })
    }
}
