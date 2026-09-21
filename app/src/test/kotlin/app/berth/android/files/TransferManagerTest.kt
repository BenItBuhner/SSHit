package app.berth.android.files

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.FakeSftpFileSystem
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.files.formatSize
import app.berth.android.ui.files.formatSizeOf
import app.berth.android.ui.files.transferCaption
import app.berth.android.ui.files.transferTrailing
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.sftp.ConflictChoice
import app.berth.sftp.FolderFailure
import app.berth.sftp.FolderPhase
import app.berth.sftp.FolderProgress
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.sftp.SftpFileType
import app.berth.sftp.SftpPaths
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import kotlin.io.path.createTempDirectory

/**
 * The transfer queue with folders in it, over the in-memory server and a plain directory standing
 * in for the picked tree: one row per folder whose aggregate climbs in order and lands as done,
 * one lane per session, the conflict a copy waits on and its answers, what fails staying inside
 * the folder with Retry failed running just that part, cancel leaving what finished, a mixed
 * selection, and an upload. Every channel a transfer opens is recorded, so the order files moved
 * in is checked, not just the outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransferManagerTest {
    private lateinit var graph: TestGraph
    private lateinit var manager: TransferManager
    private lateinit var tmp: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeSftpFileSystem()
    /** Every path downloaded or uploaded through any channel, in the order the copies started. */
    private val moved: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
    }
    private val host = Host(id = "h", name = "prod-web", color = SwatchColor.MOSS, monogram = "PW", address = "10.0.0.7", user = "demo", createdAt = 0)

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        tmp = createTempDirectory("berth-transfers").toFile()
        manager = TransferManager(ApplicationProvider.getApplicationContext(), graph.sessions, scope)
        manager.channelFor = { Channel(server) }
        server.dir("/srv/app", 0)
        server.file("/srv/app/a.txt", "alpha\n", 0)
        server.file("/srv/app/z.txt", "zulu\n", 0)
        server.file("/srv/app/b/d.txt", "delta\n", 0)
        server.bigFile("/srv/app/b/c.bin", 3L * 1024 * 1024, 0)
        server.dir("/srv/app/empty", 0)
        server.dir("/home/demo", 0)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    // ---- one folder, one row ---------------------------------------------------------------------

    @Test
    fun `a folder download is one transfer whose aggregate climbs in order and lands as done`() {
        server.chunkDelayMs = 4
        val session = session("s1")
        val seen = ArrayList<Transfer>()
        val watcher = scope.launch { manager.transfers.collect { list -> list.firstOrNull()?.let { synchronized(seen) { seen += it } } } }

        // The first listing is held, so the row is seen scanning: no count, no fraction, the word in the trailing slot.
        val hold = CompletableDeferred<Unit>()
        server.holdList = hold
        val id = manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(tmp))
        await("the scan under way") { transfer(id).state == TransferState.RUNNING && transfer(id).folder?.phase == FolderPhase.SCANNING }
        transfer(id).let { scanning ->
            assertEquals("Scanning", transferTrailing(scanning))
            assertEquals("prod-web \u00B7 Folder", transferCaption(scanning))
            assertNull(scanning.fraction)
            assertEquals(1, graph.sessions.activeTransfers.value)
        }
        hold.complete(Unit)
        val t = awaitFinished(id)
        // The watcher is a collector on its own dispatcher: the finished row reaches it a step after the flow holds it.
        await("the watcher to see the row finish") { synchronized(seen) { seen.lastOrNull()?.state?.isActive == false } }
        watcher.cancel()

        assertEquals(TransferState.DONE, t.state)
        assertNull(t.error)
        val f = t.folder!!
        assertEquals(FolderPhase.FINISHED, f.phase)
        assertEquals(4, f.filesTotal)
        assertEquals(4, f.filesCopied)
        assertEquals(4, f.filesDone)
        assertTrue(f.failures.isEmpty())
        val expectedBytes = 6L + 5L + 6L + 3L * 1024 * 1024
        assertEquals(expectedBytes, f.bytesTotal)
        assertEquals(expectedBytes, f.bytesDone)
        // The row's own bytes and total are the aggregate, so the strip reads a folder like a file.
        assertEquals(expectedBytes, t.total)
        assertEquals(expectedBytes, t.bytes)
        assertEquals(1f, t.fraction)
        assertTrue(transferCaption(t), transferCaption(t).startsWith("prod-web \u00B7 4 files copied \u00B7 3 MB \u00B7 "))
        assertEquals("Done", transferTrailing(t))

        // The files of a folder go before its subfolders, subfolders by name, each the same way.
        assertEquals(listOf("/srv/app/a.txt", "/srv/app/z.txt", "/srv/app/b/c.bin", "/srv/app/b/d.txt"), moved.toList())

        // What the row showed on the way: scanning before copying before finished, and nothing ever went backwards.
        val snapshots = synchronized(seen) { seen.toList() }
        val phases = snapshots.mapNotNull { it.folder?.phase }.distinct()
        assertEquals(listOf(FolderPhase.SCANNING, FolderPhase.COPYING, FolderPhase.FINISHED), phases)
        assertMonotonic("files copied", snapshots.map { it.folder!!.filesCopied.toLong() })
        assertMonotonic("bytes done", snapshots.map { it.bytes })
        assertMonotonic("bytes done inside the folder", snapshots.map { it.folder!!.bytesDone })
        val moving = snapshots.firstOrNull { it.state == TransferState.RUNNING && it.folder?.phase == FolderPhase.COPYING && it.folder.current != null }
        assertNotNull("a running snapshot in the copying phase", moving)
        // The sheet's row reads files done of total, then bytes of total, each pair held together so a wrap falls on a dot; the trailing slot
        // is the percentage, the line's own measure, on the strip and in the sheet alike. The strip's one line keeps the files and the speed
        // and leaves the bytes of total to the sheet, so no line carries two X of Y pairs.
        val done = moving!!.folder!!.filesDone
        val percent = "${(moving.fraction!! * 100).toInt()}%"
        assertTrue(transferCaption(moving), transferCaption(moving).startsWith("prod-web \u00B7 ${nb("$done of 4 files")} \u00B7 ${nb("${formatSize(moving.bytes)} of 3 MB")}"))
        assertEquals(percent, transferTrailing(moving))
        val strip = transferCaption(moving, showHost = false, compact = true)
        assertTrue(strip, strip.startsWith(nb("$done of 4 files")))
        assertFalse("bytes of total belong to the sheet: $strip", strip.contains("of 3"))
        assertEquals("1.5 of 3 MB", formatSizeOf(1536L * 1024, 3L * 1024 * 1024))
        assertEquals("512 KB of 3 MB", formatSizeOf(512L * 1024, 3L * 1024 * 1024))
        assertTrue("the strip's states in order", snapshots.map { it.state }.distinct().let { it == listOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.DONE) || it == listOf(TransferState.RUNNING, TransferState.DONE) })

        // The tree came across whole, the empty folder included.
        val root = File(tmp, "app")
        assertEquals("alpha\n", File(root, "a.txt").readText())
        assertEquals("zulu\n", File(root, "z.txt").readText())
        assertEquals("delta\n", File(root, "b/d.txt").readText())
        assertEquals(3L * 1024 * 1024, File(root, "b/c.bin").length())
        assertTrue(File(root, "empty").isDirectory)
        assertSoon("no transfer active once the folder is over", 0) { graph.sessions.activeTransfers.value }
    }

    // ---- lanes -------------------------------------------------------------------------------------

    @Test
    fun `transfers on one session run one at a time in the order queued while another session's run alongside`() {
        server.chunkDelayMs = 10
        val s1 = session("s1")
        val s2 = session("s2")
        val first = manager.downloadFolder(s1, dirEntry("/srv/app"), Uri.fromFile(File(tmp, "one").apply { mkdirs() }))
        val second = manager.download(s1, server.entry("/srv/app/z.txt"), Uri.fromFile(File(tmp, "z.txt")))
        val other = manager.downloadFolder(s2, dirEntry("/srv/app"), Uri.fromFile(File(tmp, "two").apply { mkdirs() }))
        assertEquals(listOf(first, second, other), manager.transfers.value.map { it.id })

        await("both sessions' first transfers running") { transfer(first).state == TransferState.RUNNING && transfer(other).state == TransferState.RUNNING }
        assertEquals("the second on the same lane waits", TransferState.QUEUED, transfer(second).state)
        assertEquals("queued counts as active for the notification", 3, graph.sessions.activeTransfers.value)
        assertEquals("prod-web \u00B7 5 B", transferCaption(transfer(second)))
        assertEquals("Queued", transferTrailing(transfer(second)))

        awaitFinished(first)
        await("the second starts once the first is over") { transfer(second).state != TransferState.QUEUED }
        awaitFinished(second)
        awaitFinished(other)
        listOf(first, second, other).forEach { assertEquals(TransferState.DONE, transfer(it).state) }
        assertTrue("the second started after the first finished", transfer(second).startedAt >= transfer(first).finishedAt)
        assertEquals("zulu\n", File(tmp, "z.txt").readText())
        assertSoon("no transfer active once all three are over", 0) { graph.sessions.activeTransfers.value }
    }

    @Test
    fun `a burst of transfers on one lane runs in the order queued, whichever coroutine is dispatched first`() {
        val session = session("s1")
        val paths = (0 until 40).map { i -> "/srv/burst/f%02d.txt".format(i).also { server.file(it, "$i\n", 0) } }
        // Queued in one go, the way a multi-selection is: the place in the lane is taken as each is queued, not as each coroutine starts.
        val ids = manager.downloadInto(session, paths.map { server.entry(it) }, Uri.fromFile(tmp))
        assertEquals(paths.size, ids.size)
        ids.forEach { assertEquals(TransferState.DONE, awaitFinished(it).state) }
        assertEquals("the files moved in the order queued", paths, moved.toList())
        assertTrue("each started once the one before it was over", ids.zipWithNext().all { (a, b) -> transfer(b).startedAt >= transfer(a).finishedAt })
    }

    // ---- a file in the way -----------------------------------------------------------------------

    @Test
    fun `a file in the way asks once, waits for the answer, and apply to all covers the files still to come`() {
        val session = session("s1")
        val root = File(tmp, "app").apply { mkdirs() }
        File(root, "a.txt").writeText("mine\n")
        File(root, "z.txt").writeText("mine too\n")
        File(root, "b").mkdirs()
        File(root, "b/d.txt").writeText("and this\n")

        val id = manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(tmp))
        await("the copy waiting on a.txt") { transfer(id).waiting }
        var t = transfer(id)
        val conflict = t.folder!!.conflict!!
        assertEquals("a.txt", conflict.relativePath)
        assertEquals(6L, conflict.incomingSize)
        assertEquals(5L, conflict.existingSize)
        assertEquals(3, conflict.remaining)
        // The sheet compares modified times as well as sizes: the file on the device has one; the fake server sent none for its side.
        assertNotNull(conflict.existingModified)
        assertNull(conflict.incomingModified)
        assertNull("no comparison without both dates", conflict.incomingIsNewer)
        // The trailing word says what a tap does; the strip's Caption keeps to the file, so a long name never cuts the cue.
        assertEquals("Answer", transferTrailing(t))
        assertEquals("prod-web \u00B7 a.txt already exists", transferCaption(t))
        assertEquals("a.txt already exists \u00B7 2 more", transferCaption(t, showHost = false, others = 2, compact = true))
        // The notification counts a copy stopped on a question apart from those that move, and its tap opens the Files tab of the terminal it names.
        assertEquals(1, graph.sessions.activeTransfers.value)
        assertSoon("one copy waiting on the user", 1) { graph.sessions.waitingTransfers.value }
        assertSoon("the waiting copy's session", "s1") { graph.sessions.waitingTransferSession.value }
        // An answer for a transfer that is not waiting goes nowhere.
        manager.resolveConflict("not-a-transfer", ConflictChoice.OVERWRITE, applyToAll = true)

        manager.resolveConflict(id, ConflictChoice.KEEP_BOTH, applyToAll = false)
        await("the copy waiting on z.txt") { transfer(id).waiting && transfer(id).folder?.conflict?.relativePath == "z.txt" }
        t = transfer(id)
        assertEquals(2, t.folder!!.conflict!!.remaining)
        manager.resolveConflict(id, ConflictChoice.SKIP, applyToAll = true)

        t = awaitFinished(id)
        assertEquals(TransferState.DONE, t.state)
        assertEquals(0, graph.sessions.waitingTransfers.value)
        assertNull(graph.sessions.waitingTransferSession.value)
        val f = t.folder!!
        assertNull(f.conflict)
        assertEquals(4, f.filesTotal)
        assertEquals(2, f.filesCopied)
        assertEquals(2, f.filesSkipped)
        assertEquals(4, f.filesDone)
        assertEquals(1f, t.fraction)
        assertTrue(transferCaption(t), transferCaption(t).startsWith("prod-web \u00B7 2 files copied \u00B7 2 skipped \u00B7 3 MB \u00B7 "))
        // d.txt was skipped without asking: the sticky answer covered it, so only c.bin and the kept copy moved.
        assertEquals(listOf("/srv/app/a.txt", "/srv/app/b/c.bin"), moved.toList())
        assertEquals("mine\n", File(root, "a.txt").readText())
        assertEquals("alpha\n", File(root, "a (1).txt").readText())
        assertEquals("mine too\n", File(root, "z.txt").readText())
        assertEquals("and this\n", File(root, "b/d.txt").readText())
        assertEquals(3L * 1024 * 1024, File(root, "b/c.bin").length())
    }

    // ---- failures stay inside the folder ------------------------------------------------------------

    @Test
    fun `a folder the server refuses fails alone, the row ends with a summary, and Retry failed runs just that part`() {
        val session = session("s1")
        server.locked += "/srv/app/b"

        val id = manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(tmp))
        val t = awaitFinished(id)
        assertEquals(TransferState.FAILED, t.state)
        assertEquals("1 folder didn't copy.", t.error)
        val f = t.folder!!
        assertEquals(listOf("b"), f.failures.map { it.relativePath })
        assertTrue(f.failures.single().isDirectory)
        assertTrue(f.failures.single().retryable)
        assertEquals(2, f.filesTotal)
        assertEquals(2, f.filesCopied)
        assertEquals("1 failed", transferTrailing(t))
        assertEquals("prod-web \u00B7 2 files copied \u00B7 1 failed", transferCaption(t))
        assertEquals(listOf("/srv/app/a.txt", "/srv/app/z.txt"), moved.toList())
        assertFalse(File(tmp, "app/b").exists())

        // Nothing to retry on a clean transfer, and a running one is not retried either.
        val clean = manager.download(session, server.entry("/srv/app/a.txt"), Uri.fromFile(File(tmp, "a.txt")))
        awaitFinished(clean)
        assertNull(manager.retryFailed(clean))

        server.locked.clear()
        moved.clear()
        val retry = manager.retryFailed(id)
        assertNotNull(retry)
        val r = awaitFinished(retry!!)
        assertEquals(TransferState.DONE, r.state)
        assertEquals(TransferKind.DOWNLOAD, r.kind)
        assertEquals("app", r.name)
        assertEquals(2, r.folder!!.filesTotal)
        assertEquals(2, r.folder.filesCopied)
        // Only what was under the failed folder moved; the rest of the tree was left as it was.
        assertEquals(listOf("/srv/app/b/c.bin", "/srv/app/b/d.txt"), moved.toList())
        assertEquals("delta\n", File(tmp, "app/b/d.txt").readText())
        assertEquals(3L * 1024 * 1024, File(tmp, "app/b/c.bin").length())
        // The failed row stays in the list beside the retry until Clear finished, which forgets it for good.
        assertEquals(listOf(id, clean, retry), manager.transfers.value.map { it.id })
        manager.clearFinished()
        assertTrue(manager.transfers.value.isEmpty())
        assertNull(manager.retryFailed(id))

        // The folder itself refusing to list is the transfer failing, with the server's reason and nothing to retry.
        server.locked += "/srv/app"
        val whole = awaitFinished(manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(File(tmp, "again").apply { mkdirs() })))
        assertEquals(TransferState.FAILED, whole.state)
        assertEquals("The server refused access to /srv/app.", whole.error)
        assertTrue(whole.folder!!.failures.isEmpty())
        assertEquals("Failed", transferTrailing(whole))
        assertEquals("prod-web \u00B7 The server refused access to /srv/app.", transferCaption(whole))
        assertFalse(File(tmp, "again/app").exists())
        assertNull(manager.retryFailed(whole.id))
    }

    @Test
    fun `the outcome line counts files and folders`() {
        val p = FolderProgress(
            failures = listOf(
                FolderFailure("a", "x"),
                FolderFailure("b", "x"),
                FolderFailure("c", "x", isDirectory = true),
                FolderFailure("link", "Link; skipped.", retryable = false),
            ),
        )
        assertEquals("2 files and 1 folder didn't copy.", TransferManager.folderOutcome(p))
        assertEquals("1 file didn't copy.", TransferManager.folderOutcome(p.copy(failures = p.failures.take(1))))
        assertEquals("1 folder didn't copy.", TransferManager.folderOutcome(p.copy(failures = p.failures.drop(2))))
    }

    // ---- cancel ------------------------------------------------------------------------------------

    @Test
    fun `cancel mid-way leaves what finished and takes the half-written file away`() {
        server.chunkDelayMs = 25
        val session = session("s1")
        val id = manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(tmp))
        await("the big file moving") { transfer(id).folder?.let { it.current == "b/c.bin" && it.currentBytes > 0 } == true }
        val before = transfer(id)
        assertEquals("b/c.bin", before.folder!!.current)
        assertEquals(2, before.folder.filesCopied)
        manager.cancel(id)

        val t = awaitFinished(id)
        assertEquals(TransferState.CANCELLED, t.state)
        assertEquals("Cancelled", transferTrailing(t))
        assertEquals(2, t.folder!!.filesCopied)
        assertTrue(transferCaption(t), transferCaption(t).startsWith("prod-web \u00B7 ${nb("2 of 4 files")} \u00B7 "))
        val root = File(tmp, "app")
        assertEquals("alpha\n", File(root, "a.txt").readText())
        assertEquals("zulu\n", File(root, "z.txt").readText())
        assertFalse("the half-written file is gone", File(root, "b/c.bin").exists())
        assertFalse("nothing after it started", File(root, "b/d.txt").exists())
        assertSoon("no transfer active once the cancel is through", 0) { graph.sessions.activeTransfers.value }
    }

    // ---- a mixed selection -------------------------------------------------------------------------

    @Test
    fun `a mixed selection downloads each file as its own transfer, asking through the sheet when its name is taken, and each folder as one`() {
        val session = session("s1")
        File(tmp, "a.txt").writeText("mine\n")
        val socket = SftpEntry("app.sock", "/srv/app.sock", SftpFileType.OTHER, 0, 0, 0b110_000_000)
        val ids = manager.downloadInto(session, listOf(server.entry("/srv/app/a.txt"), dirEntry("/srv/app/b"), socket), Uri.fromFile(tmp))
        assertEquals("a special file is nothing to copy", 2, ids.size)
        val (fileId, folderId) = ids
        assertFalse(transfer(fileId).isFolder)
        assertEquals("a.txt", transfer(fileId).name)
        assertEquals(6L, transfer(fileId).total)
        assertTrue(transfer(folderId).isFolder)
        assertEquals("b", transfer(folderId).name)
        assertEquals("/srv/app/b", transfer(folderId).remotePath)

        // The file finds its name taken and asks the same question a file inside a folder does, naming the picked folder; the folder behind it on the lane waits its turn.
        await("the file waiting on a.txt") { transfer(fileId).waiting }
        val file = transfer(fileId)
        assertNull(file.folder)
        val conflict = file.conflict!!
        assertEquals("${tmp.name}/a.txt", conflict.relativePath)
        assertEquals("a.txt", conflict.name)
        assertEquals(6L, conflict.incomingSize)
        assertEquals(5L, conflict.existingSize)
        assertEquals("the only file of the selection: apply to all has nothing to cover", 0, conflict.remaining)
        assertNotNull(conflict.existingModified)
        assertEquals("Answer", transferTrailing(file))
        assertEquals("prod-web \u00B7 a.txt already exists", transferCaption(file))
        assertEquals("a.txt already exists", transferCaption(file, showHost = false, compact = true))
        assertEquals(TransferState.QUEUED, transfer(folderId).state)
        assertSoon("one copy waiting on the user", 1) { graph.sessions.waitingTransfers.value }

        // Keep both: the row is named after what is saved before a byte moves, and once done says what was there and what became of it.
        manager.resolveConflict(fileId, ConflictChoice.KEEP_BOTH, applyToAll = false)
        ids.forEach { assertEquals(TransferState.DONE, awaitFinished(it).state) }
        val saved = transfer(fileId)
        assertEquals("a (1).txt", saved.name)
        assertNull(saved.conflict)
        assertEquals("a.txt was there \u00B7 saved as a (1).txt", saved.note)
        assertTrue(transferCaption(saved), transferCaption(saved).startsWith("prod-web \u00B7 a.txt was there \u00B7 saved as a (1).txt \u00B7 6 B \u00B7 "))
        assertEquals("Done", transferTrailing(saved))
        assertEquals(0, graph.sessions.waitingTransfers.value)
        assertEquals("mine\n", File(tmp, "a.txt").readText())
        assertEquals("alpha\n", File(tmp, "a (1).txt").readText())
        assertEquals("delta\n", File(tmp, "b/d.txt").readText())
        assertEquals(3L * 1024 * 1024, File(tmp, "b/c.bin").length())
    }

    @Test
    fun `files of a selection in the way take skip and overwrite, apply to all covers the ones after them that clash, and the rest copy regardless`() {
        val session = session("s1")
        File(tmp, "a.txt").writeText("mine\n")
        File(tmp, "z.txt").writeText("mine too\n")
        val ids = manager.downloadInto(session, listOf(server.entry("/srv/app/a.txt"), server.entry("/srv/app/z.txt"), server.entry("/srv/app/b/d.txt")), Uri.fromFile(tmp))
        val (a, z, d) = ids

        // Overwrite for one: replaced, and the row says so.
        await("a.txt waiting") { transfer(a).waiting }
        assertEquals("two more files of the selection may clash", 2, transfer(a).conflict!!.remaining)
        manager.resolveConflict(a, ConflictChoice.OVERWRITE, applyToAll = false)
        assertEquals(TransferState.DONE, awaitFinished(a).state)
        assertEquals("a.txt", transfer(a).name)
        assertEquals("a.txt was there \u00B7 replaced", transfer(a).note)
        assertEquals("alpha\n", File(tmp, "a.txt").readText())

        // Skip for all: this one is left alone and ends as skipped, not done and not failed.
        await("z.txt waiting") { transfer(z).waiting }
        assertEquals(1, transfer(z).conflict!!.remaining)
        manager.resolveConflict(z, ConflictChoice.SKIP, applyToAll = true)
        val skipped = awaitFinished(z)
        assertEquals(TransferState.SKIPPED, skipped.state)
        assertNull(skipped.error)
        assertEquals("Skipped", transferTrailing(skipped))
        assertEquals("prod-web \u00B7 z.txt was there \u00B7 left as it was", transferCaption(skipped))
        assertEquals("mine too\n", File(tmp, "z.txt").readText())

        // The sticky answer is consulted only for a file that turns out to exist; d.txt is not in the way and comes down without asking.
        val done = awaitFinished(d)
        assertEquals(TransferState.DONE, done.state)
        assertNull(done.note)
        assertEquals("delta\n", File(tmp, "d.txt").readText())
        assertEquals(listOf("/srv/app/a.txt", "/srv/app/b/d.txt"), moved.toList())
        assertEquals(0, graph.sessions.waitingTransfers.value)

        // Skipped rows go with Clear finished like any other finished row.
        manager.clearFinished()
        assertTrue(manager.transfers.value.isEmpty())
    }

    @Test
    fun `Retry failed with the picked tree gone fails as one transfer with nothing to retry`() {
        val session = session("s1")
        server.locked += "/srv/app/b"
        val dest = File(tmp, "picked").apply { mkdirs() }
        val id = manager.downloadFolder(session, dirEntry("/srv/app"), Uri.fromFile(dest))
        assertEquals(TransferState.FAILED, awaitFinished(id).state)
        server.locked.clear()

        // The grant behind the tree is gone: the tree's root cannot be made (a file stands where the folder was), so the retry cannot begin.
        dest.deleteRecursively()
        dest.writeText("not a folder any more")
        val retry = manager.retryFailed(id)
        assertNotNull(retry)
        val r = awaitFinished(retry!!)
        assertEquals(TransferState.FAILED, r.state)
        assertEquals("Couldn't create the folder app.", r.error)
        assertEquals("Failed", transferTrailing(r))
        assertEquals("prod-web \u00B7 Couldn't create the folder app.", transferCaption(r))
        assertTrue("the transfer failed whole; there is no part to retry", r.folder!!.failures.isEmpty())
        assertEquals(0, r.folder.filesCopied)
        assertNull(manager.retryFailed(retry))
    }

    // ---- upload ------------------------------------------------------------------------------------

    @Test
    fun `a folder upload makes the folders, copies files in order, and tells the browser the folder changed`() {
        val session = session("s1")
        val photos = File(tmp, "photos").apply { mkdirs() }
        File(photos, "2026").mkdirs()
        File(photos, "2026/a.jpg").writeBytes(ByteArray(2048) { it.toByte() })
        File(photos, "2026/b.jpg").writeBytes(ByteArray(1024) { (it * 3).toByte() })
        File(photos, "empty").mkdirs()
        File(photos, "notes.txt").writeText("holiday\n")
        val changed = ArrayList<Pair<String, String>>()
        // Undispatched, so the collector is subscribed before the upload is queued.
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.changedFolders.collect { synchronized(changed) { changed += it } } }

        val id = manager.uploadFolder(session, Uri.fromFile(photos), "/home/demo")
        val t = awaitFinished(id)
        // The change is emitted before the row is finished, but the watcher collects it on its own dispatcher, a step later.
        await("the watcher to see the folder change") { synchronized(changed) { changed.isNotEmpty() } }
        watcher.cancel()
        assertEquals(TransferState.DONE, t.state)
        assertEquals(TransferKind.UPLOAD, t.kind)
        assertEquals("photos", t.name)
        assertEquals("/home/demo/photos", t.remotePath)
        assertEquals(3, t.folder!!.filesCopied)
        assertEquals(2048L + 1024L + 8L, t.total)
        assertEquals(t.total, t.bytes)
        assertEquals(listOf("/home/demo/photos/notes.txt", "/home/demo/photos/2026/a.jpg", "/home/demo/photos/2026/b.jpg"), moved.toList())
        assertEquals(SftpFileType.DIRECTORY, server.nodes["/home/demo/photos"]?.type)
        assertEquals(SftpFileType.DIRECTORY, server.nodes["/home/demo/photos/empty"]?.type)
        assertArrayEquals(ByteArray(2048) { it.toByte() }, server.nodes["/home/demo/photos/2026/a.jpg"]?.content)
        assertEquals("holiday\n", server.nodes["/home/demo/photos/notes.txt"]?.content?.toString(Charsets.UTF_8))
        assertEquals(listOf("s1" to "/home/demo"), synchronized(changed) { changed.toList() })
    }

    // ---- the share sheet's drop (spec C24) ---------------------------------------------------------

    @Test
    fun `a drop lands each shared file in a private folder under tmp through the queue, in the order shared, as plain uploads`() {
        server.dir("/tmp", 0, permissions = 0b111_111_111_111)
        val session = session("s1")
        val report = File(tmp, "my report (1).pdf").apply { writeBytes(ByteArray(3000) { it.toByte() }) }
        val notes = File(tmp, "notes.txt").apply { writeText("shared\n") }
        // Another user planted a link at the name a drop into /tmp itself would take.
        server.link("/tmp/notes.txt", SftpFileType.REGULAR, 0)
        val changed = ArrayList<Pair<String, String>>()
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.changedFolders.collect { synchronized(changed) { changed += it } } }

        val ids = manager.dropIntoTmp(session, listOf(Uri.fromFile(report), Uri.fromFile(notes)))
        assertEquals(2, ids.size)
        val rows = ids.map(::awaitFinished)
        assertEquals(listOf(TransferState.DONE, TransferState.DONE), rows.map { it.state })
        assertEquals(listOf(TransferKind.UPLOAD, TransferKind.UPLOAD), rows.map { it.kind })
        // One folder of the login's own, named at random, both files inside it; 700 on the folder, 600 on each file.
        val dir = SftpPaths.parent(rows[0].remotePath)
        assertTrue(dir, Regex("/tmp/berth-[0-9a-f]{8}").matches(dir))
        assertEquals(listOf("$dir/my report (1).pdf", "$dir/notes.txt"), rows.map { it.remotePath })
        assertEquals(listOf("$dir/my report (1).pdf", "$dir/notes.txt"), moved.toList())
        assertEquals(SftpFileType.DIRECTORY, server.nodes[dir]?.type)
        assertEquals(0b111_000_000, server.nodes[dir]?.permissions)
        assertEquals(0b110_000_000, server.nodes["$dir/notes.txt"]?.permissions)
        assertEquals(0b110_000_000, server.nodes["$dir/my report (1).pdf"]?.permissions)
        assertArrayEquals(ByteArray(3000) { it.toByte() }, server.nodes["$dir/my report (1).pdf"]?.content)
        assertEquals("shared\n", server.nodes["$dir/notes.txt"]?.content?.toString(Charsets.UTF_8))
        assertEquals("the planted link is as it was", SftpFileType.SYMLINK, server.nodes["/tmp/notes.txt"]?.type)
        // The browser on the folder is told, once a copy, as any upload tells it.
        await("the watcher to see both drops") { synchronized(changed) { changed.size == 2 } }
        watcher.cancel()
        assertEquals(listOf("s1" to dir, "s1" to dir), synchronized(changed) { changed.toList() })
        assertEquals("/tmp", TransferManager.DROP_DIR)

        // The same name again on the same session: the folder is kept, the first copy is kept, and the second is saved beside it.
        moved.clear()
        notes.writeText("shared again\n")
        val again = awaitFinished(manager.dropIntoTmp(session, listOf(Uri.fromFile(notes))).single())
        assertEquals(TransferState.DONE, again.state)
        assertEquals("$dir/notes (1).txt", again.remotePath)
        assertEquals("notes (1).txt", again.name)
        assertEquals("notes.txt was there \u00B7 saved as notes (1).txt", again.note)
        assertEquals(listOf("$dir/notes (1).txt"), moved.toList())
        assertEquals("shared\n", server.nodes["$dir/notes.txt"]?.content?.toString(Charsets.UTF_8))
        assertEquals("shared again\n", server.nodes["$dir/notes (1).txt"]?.content?.toString(Charsets.UTF_8))
        assertEquals(0b110_000_000, server.nodes["$dir/notes (1).txt"]?.permissions)

        // Another session's drops go to a folder of their own.
        val other = awaitFinished(manager.dropIntoTmp(session("s2"), listOf(Uri.fromFile(notes))).single())
        assertEquals(TransferState.DONE, other.state)
        val otherDir = SftpPaths.parent(other.remotePath)
        assertTrue(otherDir, Regex("/tmp/berth-[0-9a-f]{8}").matches(otherDir))
        assertNotEquals(dir, otherDir)
        assertEquals("$otherDir/notes.txt", other.remotePath)

        // The folder gone meanwhile (a reboot, a tmp cleaner): the next drop makes a new one rather than failing on the old name.
        runBlocking { server.delete(dir) }
        val remade = awaitFinished(manager.dropIntoTmp(session, listOf(Uri.fromFile(notes))).single())
        assertEquals(TransferState.DONE, remade.state)
        val remadeDir = SftpPaths.parent(remade.remotePath)
        assertTrue(remadeDir, Regex("/tmp/berth-[0-9a-f]{8}").matches(remadeDir))
        assertNotEquals(dir, remadeDir)
        assertEquals(0b111_000_000, server.nodes[remadeDir]?.permissions)
        assertEquals("$remadeDir/notes.txt", remade.remotePath)
    }

    @Test
    fun `a drop folder's name already taken is drawn again, and a run of taken names is the drop failing`() {
        server.dir("/tmp", 0, permissions = 0b111_111_111_111)
        val session = session("s1")
        val notes = File(tmp, "notes.txt").apply { writeText("shared\n") }

        // The first two names are taken (by anyone; mkdir is the check): the third is the folder.
        mkdirsRefused = 2
        val row = awaitFinished(manager.dropIntoTmp(session, listOf(Uri.fromFile(notes))).single())
        assertEquals(TransferState.DONE, row.state)
        assertEquals(0, mkdirsRefused)
        assertEquals(3, mkdirs.size)
        assertEquals("three names drawn, all different", 3, mkdirs.toSet().size)
        assertEquals(SftpPaths.parent(row.remotePath), mkdirs.last())
        assertEquals(0b111_000_000, server.nodes[mkdirs.last()]?.permissions)

        // Every name taken, past what the drop will try: the row fails with the server's word for it, and nothing is pasted.
        val other = session("s2")
        mkdirsRefused = Int.MAX_VALUE
        val failed = awaitFinished(manager.dropIntoTmp(other, listOf(Uri.fromFile(notes))).single())
        assertEquals(TransferState.FAILED, failed.state)
        assertNotNull(failed.error)
        assertTrue(failed.error!!, failed.error!!.contains("already exists"))
    }

    @Test
    fun `a drop folder's suffix is eight hex digits from a secure source`() {
        val suffixes = (0 until 200).map { TransferManager.dropFolderSuffix() }
        assertTrue(suffixes.all { Regex("[0-9a-f]{8}").matches(it) })
        assertEquals("no two of two hundred alike", suffixes.size, suffixes.toSet().size)
    }

    @Test
    fun `a path is quoted for the shell only when it needs it, and a quote inside survives`() {
        assertEquals("/tmp/notes.txt", TransferManager.shellQuote("/tmp/notes.txt"))
        assertEquals("/tmp/site-backup_2026-09-21.tar.gz", TransferManager.shellQuote("/tmp/site-backup_2026-09-21.tar.gz"))
        assertEquals("/tmp/a+b@c:d,e=f%g", TransferManager.shellQuote("/tmp/a+b@c:d,e=f%g"))
        assertEquals("'/tmp/my report (1).pdf'", TransferManager.shellQuote("/tmp/my report (1).pdf"))
        assertEquals("'/tmp/\$HOME.txt'", TransferManager.shellQuote("/tmp/\$HOME.txt"))
        assertEquals("'/tmp/it'\\''s.txt'", TransferManager.shellQuote("/tmp/it's.txt"))
        assertEquals("'/tmp/caf\u00e9.txt'", TransferManager.shellQuote("/tmp/caf\u00e9.txt"))
        assertEquals("'/tmp/a;rm -rf ~'", TransferManager.shellQuote("/tmp/a;rm -rf ~"))
        assertEquals("''", TransferManager.shellQuote(""))
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun session(id: String): TerminalSession {
        val record = SessionRecord(id = id, workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)
        return TerminalSession(record, scope, env) {}
    }

    private fun dirEntry(path: String) = SftpEntry(path.substringAfterLast('/'), path, SftpFileType.DIRECTORY, 0, 0, 0b111_101_101)

    /** A Caption pair as the formatter holds it together: every space a non-breaking one. */
    private fun nb(text: String) = text.replace(' ', '\u00A0')

    private fun FakeSftpFileSystem.entry(path: String): SftpEntry = runBlocking { stat(path) }

    private fun transfer(id: String): Transfer = manager.transfers.value.first { it.id == id }

    private fun awaitFinished(id: String): Transfer {
        await("transfer $id to finish") { !transfer(id).state.isActive }
        return transfer(id)
    }

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(10)
        }
        assertTrue("timed out waiting for $what", condition())
    }

    /**
     * The notifier's counts ([SessionManager.activeTransfers], [SessionManager.waitingTransfers] and
     * the session they name) are published a step after the row they are counted from, so a read
     * right after a row was seen to change may still find the count from before it. Awaited, then
     * asserted, so a miss says what the value was.
     */
    private fun <T> assertSoon(what: String, expected: T, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && actual() != expected) Thread.sleep(10)
        assertEquals(what, expected, actual())
    }

    private fun assertMonotonic(what: String, values: List<Long>) {
        values.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("$what went from $a to $b at step $i", b >= a) }
    }

    /** Every folder any channel was asked to make, in order; and how many of the next `mkdir`s are refused as a name already taken. */
    private val mkdirs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var mkdirsRefused = 0

    /**
     * One channel over the shared server, the way each transfer opens its own: closing it closes
     * this channel only, the copies that start on it are written to [moved], and the folders it
     * makes to [mkdirs], the first [mkdirsRefused] of them refused as another user's.
     */
    private inner class Channel(private val inner: FakeSftpFileSystem) : SftpFileSystem by inner {
        override var isOpen: Boolean = true
            private set

        override suspend fun mkdir(path: String, permissions: Int) {
            mkdirs += path
            if (mkdirsRefused > 0) {
                if (mkdirsRefused != Int.MAX_VALUE) mkdirsRefused--
                throw SftpError.AlreadyExists(path)
            }
            inner.mkdir(path, permissions)
        }

        override suspend fun download(path: String, sink: OutputStream, onProgress: (bytes: Long, total: Long) -> Unit) {
            moved += path
            inner.download(path, sink, onProgress)
        }

        override suspend fun upload(source: InputStream, size: Long, path: String, permissions: Int, onProgress: (bytes: Long, total: Long) -> Unit) {
            moved += path
            inner.upload(source, size, path, permissions, onProgress)
        }

        override fun close() {
            isOpen = false
        }
    }
}
