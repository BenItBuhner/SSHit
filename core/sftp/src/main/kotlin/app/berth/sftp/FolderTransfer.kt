package app.berth.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** How symlinks met inside a folder being copied are treated. */
enum class LinkPolicy {
    /**
     * A link to a regular file is copied as the file it points at. A link to a folder is not
     * followed (a cycle, or a link out of the tree, would copy without end) and a dangling link
     * points at nothing; both are skipped and named in the summary.
     */
    FOLLOW_FILE_LINKS,

    /** Every link is skipped and named in the summary. */
    SKIP_LINKS,
}

/** What to do with a file whose destination already has something. Folders always merge. */
enum class ConflictChoice { SKIP, OVERWRITE, KEEP_BOTH }

data class ConflictResolution(val choice: ConflictChoice, val applyToAll: Boolean)

/** A file about to be copied has something at its destination already; the copy waits for an answer. */
data class FolderConflict(
    /** Where in the folder being copied, `logs/app.log`. */
    val relativePath: String,
    val incomingSize: Long,
    val existingSize: Long,
    /** Files still to copy after this one, so an apply-to-all can say how many it covers. */
    val remaining: Int,
) {
    val name: String get() = relativePath.substringAfterLast('/')
}

/**
 * One thing in a folder that did not copy: a file or folder the server or the device refused, or
 * a link the [LinkPolicy] left out. Only the former are [retryable]; a retry of the latter would
 * end the same way.
 */
data class FolderFailure(
    val relativePath: String,
    val message: String,
    val isDirectory: Boolean = false,
    val retryable: Boolean = true,
)

enum class FolderPhase { SCANNING, COPYING, FINISHED }

/**
 * Where a folder copy stands. Files are counted once the scan has walked the tree; a folder that
 * would not list has no count, so it is one failure rather than an unknown number. [bytesDone] is
 * what actually moved; [bytesPassed] is the size of what was skipped or failed, so the fraction
 * still reaches the end.
 */
data class FolderProgress(
    val phase: FolderPhase = FolderPhase.SCANNING,
    val filesTotal: Int = 0,
    val filesCopied: Int = 0,
    /** Files left alone on a Skip answer. */
    val filesSkipped: Int = 0,
    val bytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val bytesPassed: Long = 0L,
    /** The relative path of the file moving now. */
    val current: String? = null,
    val currentBytes: Long = 0L,
    val currentTotal: Long = 0L,
    val failures: List<FolderFailure> = emptyList(),
    /** Set while the copy waits for the user's answer. */
    val conflict: FolderConflict? = null,
) {
    val filesFailed: Int get() = failures.count { it.retryable }

    /** Links and special files the policy left out. */
    val filesLeftOut: Int get() = failures.count { !it.retryable }

    /** Files no longer pending: copied, skipped or failed. */
    val filesDone: Int get() = filesCopied + filesSkipped + failures.count { it.retryable && !it.isDirectory }

    /** 0..1 once the scan knows the totals; null while scanning. */
    val fraction: Float? get() = when {
        phase == FolderPhase.SCANNING -> null
        bytesTotal > 0 -> ((bytesDone + bytesPassed).toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
        filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
        phase == FolderPhase.FINISHED -> 1f
        else -> null
    }
}

/**
 * Copies a folder tree between an [SftpFileSystem] and a [LocalTree], one file at a time: the tree
 * is walked first so the totals are known, then folders are made (empty ones included) and files
 * copied in a stable order: a folder's files, then its subfolders by name, each the same way. A
 * file or folder that fails is recorded and the rest goes on; only a lost connection, or the folder
 * itself not listing, ends the copy early, and then everything still pending is recorded as failed
 * with that reason so a retry covers it. Cancellation stops at the next chunk, removes the
 * half-written file and leaves the complete ones in place. When a file's destination already has
 * something, [resolve] is asked, unless an earlier answer applied to all; folders merge without asking.
 *
 * Every copy lands inside the destination as a folder named after the source: a download of
 * `/srv/app` into the tree makes `app/` under the tree's root, an upload of the tree's root `photos`
 * into `/home/demo` makes `/home/demo/photos`. [only] narrows a copy to the relative paths given and
 * what is under them, for retrying failures; the folders on the way to them are made as needed.
 */
class FolderTransfer(
    private val remote: SftpFileSystem,
    private val local: LocalTree,
    private val linkPolicy: LinkPolicy = LinkPolicy.FOLLOW_FILE_LINKS,
    private val onProgress: (FolderProgress) -> Unit = {},
    private val resolve: suspend (FolderConflict) -> ConflictResolution = { ConflictResolution(ConflictChoice.SKIP, applyToAll = true) },
) {
    private var progress = FolderProgress()
    private var bytesBefore = 0L
    private var sticky: ConflictChoice? = null

    /** The folders to make, parents first, and the files to copy, in the order the walk found them. */
    private class Plan {
        val dirs = ArrayList<String>()
        val files = ArrayList<PlannedFile>()
        var bytes = 0L
    }

    private class PlannedFile(val relativePath: String, val size: Long, val remotePath: String? = null, val localNode: LocalNode? = null) {
        val name: String get() = relativePath.substringAfterLast('/')
        val dir: String get() = relativePath.substringBeforeLast('/', "")
    }

    // ---- download ----------------------------------------------------------------------------------

    suspend fun download(remoteDir: String, only: Set<String>? = null): FolderProgress {
        reset()
        val plan = scanRemote(remoteDir, only)
        publish(progress.copy(phase = FolderPhase.COPYING, filesTotal = plan.files.size, bytesTotal = plan.bytes))

        val nodes = HashMap<String, LocalNode>()
        nodes[""] = io { local.createDirectory(local.root, SftpPaths.name(remoteDir)) }
        for (dir in plan.dirs) {
            if (dir.isEmpty()) continue
            val parent = nodes[dir.substringBeforeLast('/', "")]
            if (parent == null) {
                failDir(dir, "The folder above it couldn't be made.")
                continue
            }
            try {
                nodes[dir] = io { local.createDirectory(parent, dir.substringAfterLast('/')) }
            } catch (e: IOException) {
                failDir(dir, e.message ?: "Couldn't make the folder.")
            }
        }

        // What each destination folder holds is read once, so a conflict check costs no round trip per file.
        val listings = HashMap<String, MutableMap<String, LocalNode>?>()
        for ((index, file) in plan.files.withIndex()) {
            val dir = nodes[file.dir]
            if (dir == null) {
                failFile(file, "The folder it belongs in couldn't be made.")
                continue
            }
            if (file.dir !in listings) {
                listings[file.dir] = try {
                    io { local.children(dir) }.associateByTo(LinkedHashMap()) { it.name }
                } catch (e: IOException) {
                    null
                }
            }
            val existing = listings[file.dir]
            if (existing == null) {
                failFile(file, "Couldn't read what the folder holds already.")
                continue
            }
            try {
                copyDown(file, dir, existing, remaining = plan.files.size - index - 1)
            } catch (e: SftpError) {
                // Only a lost connection gets out of copyDown; the rest is recorded so a retry covers it.
                failRest(plan.files, index, e)
                throw e
            }
        }
        return finish()
    }

    private suspend fun scanRemote(root: String, only: Set<String>?): Plan {
        val plan = Plan()
        plan.dirs += ""
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val rel = stack.removeLast()
            val path = if (rel.isEmpty()) root else SftpPaths.join(root, rel)
            val entries = try {
                remote.list(path)
            } catch (e: SftpError) {
                // The folder itself not listing fails the copy with the server's reason; one inside
                // is a failure in the summary and is not made at the destination, empty and misleading.
                if (e.isConnectionLoss() || rel.isEmpty()) throw e
                plan.dirs.remove(rel)
                failDir(rel, e.message ?: "Couldn't list the folder.")
                continue
            }
            val below = ArrayList<String>()
            for (entry in entries.sortedWith(byName)) {
                val childRel = if (rel.isEmpty()) entry.name else "$rel/${entry.name}"
                if (!wanted(childRel, only)) continue
                when (entry.type) {
                    SftpFileType.DIRECTORY -> {
                        plan.dirs += childRel
                        below += childRel
                    }
                    SftpFileType.REGULAR -> plan.add(PlannedFile(childRel, entry.size, remotePath = entry.path))
                    SftpFileType.SYMLINK -> planLink(entry, childRel, plan)
                    SftpFileType.OTHER -> leaveOut(childRel, "Special file; skipped.")
                }
            }
            for (dir in below.asReversed()) stack.addLast(dir)
            publish(progress.copy(filesTotal = plan.files.size, bytesTotal = plan.bytes))
        }
        return plan
    }

    /** Applies the [LinkPolicy]: a link to a file joins the plan with the size behind it, anything else is named and left out. */
    private suspend fun planLink(entry: SftpEntry, rel: String, plan: Plan) {
        if (linkPolicy == LinkPolicy.SKIP_LINKS) {
            leaveOut(rel, "Link; skipped.")
            return
        }
        if (entry.linkTarget == SftpFileType.DIRECTORY) {
            leaveOut(rel, "Link to a folder; skipped.")
            return
        }
        val target = try {
            remote.stat(entry.path)
        } catch (e: SftpError.NotFound) {
            leaveOut(rel, "Broken link; skipped.")
            return
        } catch (e: SftpError) {
            if (e.isConnectionLoss()) throw e
            failFile(rel, e.message ?: "Couldn't follow the link.")
            return
        }
        when (target.type) {
            SftpFileType.REGULAR -> plan.add(PlannedFile(rel, target.size, remotePath = entry.path))
            SftpFileType.DIRECTORY -> leaveOut(rel, "Link to a folder; skipped.")
            else -> leaveOut(rel, "Link to a special file; skipped.")
        }
    }

    private suspend fun copyDown(file: PlannedFile, dir: LocalNode, existing: MutableMap<String, LocalNode>, remaining: Int) {
        var target: LocalNode? = existing[file.name]
        var targetName = file.name
        if (target != null) {
            when (decide(file, target.size, remaining)) {
                ConflictChoice.SKIP -> {
                    skip(file)
                    return
                }
                ConflictChoice.OVERWRITE -> Unit
                ConflictChoice.KEEP_BOTH -> {
                    targetName = SftpPaths.keepBothName(file.name, existing.keys)
                    target = null
                }
            }
        }
        begin(file)
        var created: LocalNode? = null
        // The destination opens on the first byte, so a file the server refuses to read leaves
        // nothing behind, and one being overwritten is not emptied before the copy can start.
        val sink = LazySink {
            val node = target ?: local.createFile(dir, targetName).also {
                created = it
                existing[targetName] = it
            }
            local.openWrite(node)
        }
        try {
            remote.download(file.remotePath!!, sink) { bytes, _ -> tick(bytes) }
            sink.close()
            done(file, sink.written)
        } catch (e: CancellationException) {
            discard(sink, created, target, existing, targetName)
            throw e
        } catch (e: Exception) {
            discard(sink, created, target, existing, targetName)
            failFile(file, e.message ?: "The copy did not finish.")
            if (e is SftpError && e.isConnectionLoss()) throw e
        }
    }

    /** Takes a half-written file away; a completed one before it stays. */
    private suspend fun discard(sink: LazySink, created: LocalNode?, target: LocalNode?, existing: MutableMap<String, LocalNode>, name: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { sink.close() }
            val partial = created ?: target.takeIf { sink.opened }
            if (partial != null) {
                runCatching { local.delete(partial) }
                existing.remove(name)
            }
        }
    }

    // ---- upload ------------------------------------------------------------------------------------

    suspend fun upload(remoteParent: String, only: Set<String>? = null): FolderProgress {
        reset()
        val plan = scanLocal(only)
        publish(progress.copy(phase = FolderPhase.COPYING, filesTotal = plan.files.size, bytesTotal = plan.bytes))

        val root = SftpPaths.join(remoteParent, local.root.name)
        fun pathFor(rel: String) = if (rel.isEmpty()) root else SftpPaths.join(root, rel)
        val made = HashSet<String>()
        val fresh = HashSet<String>()
        for (dir in plan.dirs) {
            if (dir.isNotEmpty() && dir.substringBeforeLast('/', "") !in made) {
                failDir(dir, "The folder above it couldn't be made.")
                continue
            }
            try {
                remote.mkdir(pathFor(dir))
                fresh += dir
                made += dir
            } catch (e: SftpError.AlreadyExists) {
                made += dir
            } catch (e: SftpError) {
                if (e.isConnectionLoss()) throw e
                if (dir.isEmpty()) throw e
                failDir(dir, e.message ?: "Couldn't make the folder.")
            }
        }

        // A folder made just now is known empty; one that was there is listed once for the conflict checks.
        val listings = HashMap<String, MutableMap<String, Long>?>()
        for ((index, file) in plan.files.withIndex()) {
            if (file.dir !in made) {
                failFile(file, "The folder it belongs in couldn't be made.")
                continue
            }
            try {
                if (file.dir !in listings) {
                    listings[file.dir] = if (file.dir in fresh) LinkedHashMap() else try {
                        remote.list(pathFor(file.dir)).associateTo(LinkedHashMap()) { it.name to it.size }
                    } catch (e: SftpError) {
                        if (e.isConnectionLoss()) throw e
                        null
                    }
                }
                val existing = listings[file.dir]
                if (existing == null) {
                    failFile(file, "Couldn't read what the folder holds already.")
                    continue
                }
                copyUp(file, pathFor(file.dir), existing, remaining = plan.files.size - index - 1)
            } catch (e: SftpError) {
                failRest(plan.files, index, e)
                throw e
            }
        }
        return finish()
    }

    private suspend fun scanLocal(only: Set<String>?): Plan {
        val plan = Plan()
        plan.dirs += ""
        val stack = ArrayDeque<Pair<String, LocalNode>>()
        stack.addLast("" to local.root)
        while (stack.isNotEmpty()) {
            val (rel, node) = stack.removeLast()
            val children = try {
                io { local.children(node) }
            } catch (e: IOException) {
                if (rel.isEmpty()) throw e
                plan.dirs.remove(rel)
                failDir(rel, e.message ?: "Couldn't list the folder.")
                continue
            }
            val below = ArrayList<Pair<String, LocalNode>>()
            for (child in children.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (!wanted(childRel, only)) continue
                if (child.isDirectory) {
                    plan.dirs += childRel
                    below += childRel to child
                } else {
                    plan.add(PlannedFile(childRel, child.size, localNode = child))
                }
            }
            for (pair in below.asReversed()) stack.addLast(pair)
            publish(progress.copy(filesTotal = plan.files.size, bytesTotal = plan.bytes))
        }
        return plan
    }

    private suspend fun copyUp(file: PlannedFile, remoteDir: String, existing: MutableMap<String, Long>, remaining: Int) {
        var targetName = file.name
        val there = existing[file.name]
        if (there != null) {
            when (decide(file, there, remaining)) {
                ConflictChoice.SKIP -> {
                    skip(file)
                    return
                }
                ConflictChoice.OVERWRITE -> Unit
                ConflictChoice.KEEP_BOTH -> targetName = SftpPaths.keepBothName(file.name, existing.keys)
            }
        }
        val remotePath = SftpPaths.join(remoteDir, targetName)
        begin(file)
        val input = try {
            io { local.openRead(file.localNode!!) }
        } catch (e: IOException) {
            failFile(file, e.message ?: "Couldn't read the file.")
            return
        }
        var moved = 0L
        try {
            try {
                remote.upload(input, file.size, remotePath) { bytes, _ ->
                    moved = bytes
                    tick(bytes)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { input.close() } }
            }
            existing[targetName] = moved
            done(file, moved)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { remote.delete(remotePath) } }
            throw e
        } catch (e: Exception) {
            if (!(e is SftpError && e.isConnectionLoss())) runCatching { remote.delete(remotePath) }
            failFile(file, e.message ?: "The copy did not finish.")
            if (e is SftpError && e.isConnectionLoss()) throw e
        }
    }

    // ---- shared ------------------------------------------------------------------------------------

    private suspend fun decide(file: PlannedFile, existingSize: Long, remaining: Int): ConflictChoice {
        sticky?.let { return it }
        val conflict = FolderConflict(file.relativePath, incomingSize = file.size, existingSize = existingSize, remaining = remaining)
        publish(progress.copy(conflict = conflict))
        val answer = try {
            resolve(conflict)
        } finally {
            publish(progress.copy(conflict = null))
        }
        if (answer.applyToAll) sticky = answer.choice
        return answer.choice
    }

    private fun Plan.add(file: PlannedFile) {
        files += file
        bytes += file.size.coerceAtLeast(0L)
    }

    private fun wanted(rel: String, only: Set<String>?): Boolean =
        only == null || only.any { it == rel || it.startsWith("$rel/") || rel.startsWith("$it/") }

    private fun reset() {
        progress = FolderProgress()
        bytesBefore = 0L
        onProgress(progress)
    }

    private fun publish(p: FolderProgress) {
        progress = p
        onProgress(p)
    }

    private fun begin(file: PlannedFile) = publish(progress.copy(current = file.relativePath, currentBytes = 0L, currentTotal = file.size))

    private fun tick(bytes: Long) = publish(progress.copy(currentBytes = bytes, bytesDone = bytesBefore + bytes))

    private fun done(file: PlannedFile, moved: Long) {
        bytesBefore += moved
        val planned = file.size.coerceAtLeast(0L)
        publish(progress.copy(filesCopied = progress.filesCopied + 1, bytesDone = bytesBefore, bytesTotal = progress.bytesTotal + (moved - planned), current = null, currentBytes = 0L, currentTotal = 0L))
    }

    private fun skip(file: PlannedFile) = publish(progress.copy(filesSkipped = progress.filesSkipped + 1, bytesPassed = progress.bytesPassed + file.size.coerceAtLeast(0L)))

    private fun failFile(file: PlannedFile, message: String) {
        publish(progress.copy(bytesDone = bytesBefore, bytesPassed = progress.bytesPassed + file.size.coerceAtLeast(0L), current = null, currentBytes = 0L, currentTotal = 0L, failures = progress.failures + FolderFailure(file.relativePath, message)))
    }

    private fun failFile(rel: String, message: String) = publish(progress.copy(failures = progress.failures + FolderFailure(rel, message)))

    private fun failDir(rel: String, message: String) = publish(progress.copy(failures = progress.failures + FolderFailure(rel, message, isDirectory = true)))

    private fun leaveOut(rel: String, message: String) = publish(progress.copy(failures = progress.failures + FolderFailure(rel, message, retryable = false)))

    /** The connection is gone: everything after [index] is recorded with that reason so a retry covers it. */
    private fun failRest(files: List<PlannedFile>, index: Int, cause: SftpError) {
        val reason = cause.message ?: "The connection dropped."
        var passed = progress.bytesPassed
        val failures = ArrayList(progress.failures)
        for (i in index + 1 until files.size) {
            passed += files[i].size.coerceAtLeast(0L)
            failures += FolderFailure(files[i].relativePath, reason)
        }
        publish(progress.copy(phase = FolderPhase.FINISHED, bytesPassed = passed, failures = failures, current = null, conflict = null))
    }

    private fun finish(): FolderProgress {
        publish(progress.copy(phase = FolderPhase.FINISHED, current = null, currentBytes = 0L, currentTotal = 0L, conflict = null))
        return progress
    }

    /**
     * A failure the copy cannot get past. [SftpError.Io] also wraps a device-side stream error
     * during a copy, so the channel decides: still open means this one file failed and the rest
     * can go on.
     */
    private fun SftpError.isConnectionLoss(): Boolean = this is SftpError.NotConnected || (this is SftpError.Io && !remote.isOpen)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** An output stream that opens its destination on the first byte (or flush), so a copy that never starts creates nothing. */
    private class LazySink(private val open: () -> OutputStream) : OutputStream() {
        private var out: OutputStream? = null
        var opened = false
            private set
        var written = 0L
            private set

        private fun stream(): OutputStream = out ?: open().also {
            out = it
            opened = true
        }

        override fun write(b: Int) {
            stream().write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            stream().write(b, off, len)
            written += len
        }

        override fun flush() {
            stream().flush()
        }

        override fun close() {
            out?.close()
        }
    }

    private companion object {
        val byName: Comparator<SftpEntry> = compareBy(String.CASE_INSENSITIVE_ORDER, SftpEntry::name).thenBy(SftpEntry::name)
    }
}
