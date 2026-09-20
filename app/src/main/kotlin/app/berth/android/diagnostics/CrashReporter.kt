package app.berth.android.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

enum class ReportKind(val prefix: String, val heading: String, val label: String) {
    /** The process died of an uncaught exception; the sheet on the next launch shows it. */
    CRASH("crash", "Berth crash report", "Crash"),

    /** A connection failed or fell over; listed under Settings \u203A Diagnostics, no sheet. */
    TRANSPORT("transport", "Berth connection report", "Connection"),
}

/** One report in app storage: its file, what kind, when, and its second and third lines. */
data class Report(val file: File, val kind: ReportKind, val at: Long, val title: String, val summary: String) {
    val id: String get() = file.name

    fun read(): String = runCatching { file.readText() }.getOrDefault("")
}

/**
 * Crash and connection reports, written to app storage and never sent anywhere (the vision bans
 * telemetry; a local log is not telemetry). A report is the error with its stack, the app and
 * device, and the tail of the in-app log ring ([BerthLog]); it leaves the phone only through the
 * report sheet's Copy or Share, both the user's own act. The file is the report and nothing else:
 * what it holds and where it may go is said on the sheet, to the phone's owner, not inside the
 * text whoever receives it reads.
 *
 * Installed as the process's default uncaught-exception handler in front of Android's own: the
 * crash is written, the marker for the next launch's sheet set, the [addCrashHook] hooks run for
 * a bounded moment (the session manager saves every open frame there, so a crash loses no more
 * than the persistence already allowed), and then Android's handler ends the process as it would
 * have. Connection failures come through [report] from the session layer with the same content.
 */
@Singleton
class CrashReporter(
    private val dir: File,
    describe: () -> String,
    private val ring: LogRing,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR), { describeInstall(context) }, BerthLog.ring) {
        BerthLog.mirrorDebugToLogcat = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private val install: String by lazy { runCatching(describe).getOrElse { "App       (unavailable: ${it.javaClass.simpleName})" } }
    private val lock = Any()
    private val crashHooks = CopyOnWriteArrayList<() -> Unit>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    private val _reports = MutableStateFlow<List<Report>>(emptyList())

    /** Every report on disk, newest first. */
    val reports: StateFlow<List<Report>> = _reports.asStateFlow()

    private val _unread = MutableStateFlow<Report?>(null)

    /** The crash of a previous run the user has not seen yet; the sheet shows it, [markRead] clears it. */
    val unread: StateFlow<Report?> = _unread.asStateFlow()

    /**
     * Puts this in front of the default handler, at once and on the calling thread, so a crash anywhere
     * after this line is written; a second call finds its own handler in place and leaves it. The store
     * (up to [MAX_REPORTS] files, three lines read from each) is then read on a thread of its own, off
     * the startup path, and [reports] and [unread] are set from there.
     */
    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !is CrashHandler) {
            previous = current
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        }
        Thread(::reload, "berth-reports").apply { isDaemon = true }.start()
    }

    /** Re-reads the store on the calling thread, the way the next launch's [install] does: tests stand in for that launch with this. */
    internal fun reload() {
        synchronized(lock) { refresh() }
    }

    /**
     * Runs when the process is about to die of a crash, after the report is written, on a helper
     * thread the handler waits [HOOK_TIMEOUT_MS] for; a hook that blocks (a lock the crashing thread
     * holds) is abandoned rather than kept alive, since the process ends either way.
     */
    fun addCrashHook(hook: () -> Unit) {
        crashHooks += hook
    }

    /**
     * Writes a report and returns it, or null when the store could not be written. [details] are
     * labelled lines under the device block (a connection report names the host and the phase).
     */
    fun report(kind: ReportKind, title: String, error: Throwable?, details: List<Pair<String, String>> = emptyList(), thread: Thread = Thread.currentThread()): Report? {
        synchronized(lock) {
            val at = now()
            val report = runCatching {
                dir.mkdirs()
                val file = File(dir, "${kind.prefix}-${FILE_TIME.format(Instant.ofEpochMilli(at))}.txt")
                val summary = summarize(error, details, title)
                file.writeText(compose(kind, title, summary, at, thread, error, details))
                Report(file, kind, at, title, summary)
            }.getOrNull()
            prune()
            refresh()
            return report
        }
    }

    fun markRead() {
        synchronized(lock) { File(dir, UNREAD).delete() }
        _unread.value = null
    }

    fun delete(report: Report) {
        synchronized(lock) {
            report.file.delete()
            if (_unread.value?.id == report.id) File(dir, UNREAD).delete()
            refresh()
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            files().forEach { it.delete() }
            File(dir, UNREAD).delete()
            refresh()
        }
    }

    /** The crash path, on the thread that crashed: write, mark for the next launch, let the hooks save, then hand on. */
    internal fun onCrash(thread: Thread, error: Throwable) {
        BerthLog.e("Crash", "uncaught exception on thread ${thread.name}", error)
        val report = report(ReportKind.CRASH, "Uncaught exception on thread ${thread.name}", error, thread = thread)
        if (report != null) runCatching { synchronized(lock) { File(dir, UNREAD).writeText(report.id) } }
        val hooks = crashHooks.toList()
        if (hooks.isNotEmpty()) {
            val worker = Thread({ for (hook in hooks) runCatching { hook() } }, "berth-crash-hooks").apply { isDaemon = true }
            runCatching {
                worker.start()
                worker.join(HOOK_TIMEOUT_MS)
            }
        }
    }

    private inner class CrashHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                onCrash(t, e)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(t, e)
            }
        }
    }

    private fun compose(kind: ReportKind, title: String, summary: String, at: Long, thread: Thread, error: Throwable?, details: List<Pair<String, String>>): String {
        val lines = ring.snapshot()
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) shr 20
        val maxMb = runtime.maxMemory() shr 20
        return buildString {
            appendLine(kind.heading)
            appendLine(title)
            appendLine(summary)
            appendLine()
            appendLine("Written   ${WRITTEN_TIME.format(Instant.ofEpochMilli(at).atZone(zone()))} \u00B7 ${uptime()} after the process started \u00B7 pid ${Process.myPid()} \u00B7 thread ${thread.name}")
            appendLine()
            appendLine(install)
            appendLine("Memory    $usedMb / $maxMb MB heap")
            if (details.isNotEmpty()) {
                appendLine()
                for ((label, value) in details) appendLine(label.padEnd(10) + value)
            }
            appendLine()
            appendLine("Error")
            appendLine(if (error != null) stackTrace(error).trimEnd() else "(none: ${summary})")
            appendLine()
            val kept = lines.size
            val total = ring.total
            appendLine("Log \u00B7 " + if (total > kept) "last $kept lines of $total" else "$kept lines")
            for (line in lines) appendLine(line)
        }
    }

    private fun uptime(): String {
        val ms = runCatching { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() }.getOrDefault(0L).coerceAtLeast(0L)
        val s = ms / 1_000
        return when {
            s < 60 -> "$s s"
            s < 3_600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3_600} h ${s % 3_600 / 60} min"
        }
    }

    private fun refresh() {
        val list = files().mapNotNull { parse(it) }.sortedByDescending { it.at }
        _reports.value = list
        val unreadId = runCatching { File(dir, UNREAD).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        _unread.value = unreadId?.let { id -> list.firstOrNull { it.id == id && it.kind == ReportKind.CRASH } }
        if (unreadId != null && _unread.value == null) File(dir, UNREAD).delete()
    }

    private fun prune() {
        files().sortedByDescending { it.name }.drop(MAX_REPORTS).forEach { it.delete() }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && ReportKind.entries.any { f.name.startsWith(it.prefix + "-") } }?.toList() ?: emptyList()

    private fun parse(file: File): Report? {
        val kind = ReportKind.entries.firstOrNull { file.name.startsWith(it.prefix + "-") } ?: return null
        val stamp = file.name.removePrefix(kind.prefix + "-").removeSuffix(".txt")
        val at = runCatching { Instant.from(FILE_TIME.parse(stamp)).toEpochMilli() }.getOrElse { file.lastModified() }
        val head = runCatching { file.bufferedReader().useLines { it.take(3).toList() } }.getOrDefault(emptyList())
        return Report(file, kind, at, head.getOrElse(1) { kind.label }, head.getOrElse(2) { "" })
    }

    companion object {
        const val DIR = "reports"
        const val MAX_REPORTS = 30
        /** The hooks' budget: a frame written through Room for every open tab has to fit, on a phone that is slow and dying. */
        const val HOOK_TIMEOUT_MS = 5_000L
        private const val UNREAD = "unread"

        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private val WRITTEN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx")

        /** The error's own line, or the first detail, or the title: the report's third line and the row's text. */
        internal fun summarize(error: Throwable?, details: List<Pair<String, String>>, title: String): String {
            val text = when {
                error != null -> error.javaClass.name + (error.message?.let { ": $it" } ?: "")
                details.isNotEmpty() -> details.first().second
                else -> title
            }
            return text.lineSequence().first().take(300)
        }

        fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        /** The app and device as installed; constant for the process, so computed once. */
        fun describeInstall(context: Context): String {
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val label = runCatching { context.applicationInfo.loadLabel(pm).toString() }.getOrDefault("Berth")
            val version = info?.let { "${it.versionName} (${it.longVersionCode})" } ?: "(unknown version)"
            return buildString {
                appendLine("App       $label $version \u00B7 ${context.packageName} \u00B7 ${if (debuggable) "debuggable" else "release"} build")
                appendLine("Mapping   ${mappingLine()}")
                appendLine("Device    ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) \u00B7 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) \u00B7 build ${Build.DISPLAY}")
                appendLine("Hardware  ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                append("Locale    ${Locale.getDefault()} \u00B7 ${TimeZone.getDefault().id}")
            }
        }

        /**
         * How the frames in the report read, from the source file the running code's own frame names. In a
         * release build R8 has renamed the classes and methods, renumbered the lines, and written the
         * release's map id into every class's SourceFile in place of the file name, so a frame is
         * `yp3.Q(r8-map-id-75b2\u2026:57)` and reads only through that release's `mapping.txt`
         * (`retrace mapping.txt report.txt`), whose header carries the same id as `pg_map_id`. The id is in
         * the report's head once, so a report trimmed to its first lines, or retyped, still says which
         * mapping reads it. A debuggable build's frames name their source file and line as written.
         */
        internal fun mappingLine(source: String? = Throwable().stackTrace.firstOrNull()?.fileName): String = when {
            source == null -> "none in the frames; they name classes and methods only"
            source.startsWith(R8_MAP_ID) -> "$source \u00B7 the frames below are R8's and read through this release's mapping.txt"
            else -> "none needed; the frames name their source file and line"
        }

        /** What R8 writes as every class's SourceFile when the attribute is kept: this prefix and the mapping's `pg_map_id`. */
        const val R8_MAP_ID = "r8-map-id-"
    }
}
