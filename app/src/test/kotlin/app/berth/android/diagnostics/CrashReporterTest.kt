package app.berth.android.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.slf4j.LoggerFactory
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The report store: what a report holds and in what order, the crash path (written, marked for
 * the next launch, the hooks given their moment, Android's handler handed the crash), the marker's
 * life across launches, pruning, and the ring behind it all, sshj's lines included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReporterTest {
    private lateinit var dir: File
    private val ring = LogRing(capacity = 5, zone = { ZoneOffset.UTC })
    private var clock = 1_758_340_800_000L // 2025-09-20 04:00:00 UTC
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private fun reporter(ring: LogRing = this.ring): CrashReporter = CrashReporter(dir, { "App       Berth 0.1.0 (1) \u00B7 app.berth.android \u00B7 test build\nDevice    Robolectric" }, ring, now = { clock }, zone = { ZoneOffset.UTC })

    @Before
    fun setUp() {
        dir = createTempDirectory("berth-reports").toFile()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        dir.deleteRecursively()
    }

    @Test
    fun `a connection report holds the title, the error line, when, the install, the details, the stack and the ring's tail`() {
        val reports = reporter()
        ring.log(LogRing.Level.INFO, "Session", "[prod-web] connecting \u2192 live", at = clock - 5_000)
        ring.log(LogRing.Level.WARN, "Session", "[prod-web] connection lost: the server closed the connection", at = clock - 1_000)
        val error = java.io.IOException("Broken pipe")
        val report = reports.report(
            ReportKind.TRANSPORT,
            "Connection lost: prod-web",
            error,
            details = listOf("Host" to "prod-web \u00B7 ben@10.0.0.7:22", "Phase" to "Connection lost", "Reason" to "the server closed the connection"),
        )!!

        assertEquals(ReportKind.TRANSPORT, report.kind)
        assertEquals(clock, report.at)
        assertEquals("Connection lost: prod-web", report.title)
        assertEquals("java.io.IOException: Broken pipe", report.summary)
        assertEquals("transport-20250920-040000-000.txt", report.id)
        val text = report.read()
        val lines = text.lines()
        assertEquals(listOf("Berth connection report", "Connection lost: prod-web", "java.io.IOException: Broken pipe", ""), lines.take(4))
        assertTrue(lines[4], lines[4].startsWith("Written   2025-09-20 04:00:00.000 +00:00 \u00B7 "))
        // The file is the report and nothing else: the install block follows Written directly, and what the
        // report holds or where it may go is said on the sheet, to the phone's owner, not to whoever receives it.
        assertEquals("", lines[5])
        assertTrue(lines[6], lines[6].startsWith("App       "))
        assertFalse(text, text.contains("leaves the phone"))
        assertTrue(text, Regex("""\nApp       Berth 0\.1\.0 \(1\) \u00B7 app\.berth\.android \u00B7 test build\nDevice    Robolectric\nMemory    \d+ / \d+ MB heap\n""").containsMatchIn(text))
        assertTrue(text, text.contains("\nHost      prod-web \u00B7 ben@10.0.0.7:22\nPhase     Connection lost\nReason    the server closed the connection\n"))
        assertTrue(text, text.contains("\nError\njava.io.IOException: Broken pipe\n\tat "))
        assertTrue(text, text.contains("\nLog \u00B7 2 lines\n09-20 03:59:55.000 I Session: [prod-web] connecting \u2192 live\n09-20 03:59:59.000 W Session: [prod-web] connection lost: the server closed the connection\n"))

        // The list is the store, newest first; nothing about a connection report is unread.
        assertEquals(listOf(report), reports.reports.value)
        assertNull(reports.unread.value)
    }

    @Test
    fun `the summary is the error's first line, else the first detail, else the title`() {
        assertEquals("java.lang.IllegalStateException: boom", CrashReporter.summarize(IllegalStateException("boom\nsecond line"), emptyList(), "t"))
        assertEquals("java.lang.IllegalStateException", CrashReporter.summarize(IllegalStateException(), emptyList(), "t"))
        assertEquals("prod-web", CrashReporter.summarize(null, listOf("Host" to "prod-web"), "t"))
        assertEquals("Gave up", CrashReporter.summarize(null, emptyList(), "Gave up"))
        assertEquals(300, CrashReporter.summarize(RuntimeException("x".repeat(500)), emptyList(), "t").length)
    }

    @Test
    fun `a crash is written, the hooks run, Android's handler is handed the crash, and the next launch shows it once`() {
        val android = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(android)
        // Over the process's own ring, as installed: the crash path logs the crash there before it copies the tail.
        val reports = reporter(BerthLog.ring)
        reports.install()
        val hookThread = arrayOfNulls<String>(1)
        reports.addCrashHook { hookThread[0] = Thread.currentThread().name }
        BerthLog.d("App", "process started")

        val installed = Thread.getDefaultUncaughtExceptionHandler()!!
        val error = IllegalStateException("boom")
        installed.uncaughtException(Thread.currentThread(), error)

        assertEquals("the hooks ran, on a thread of their own", "berth-crash-hooks", hookThread[0])
        assertEquals("Android's handler ends the process as it always did", listOf(Thread.currentThread() to error), android.seen)
        val report = reports.reports.value.single()
        assertEquals(ReportKind.CRASH, report.kind)
        assertEquals("Uncaught exception on thread ${Thread.currentThread().name}", report.title)
        assertEquals("java.lang.IllegalStateException: boom", report.summary)
        val text = report.read()
        assertTrue(text, text.startsWith("Berth crash report\nUncaught exception on thread ${Thread.currentThread().name}\njava.lang.IllegalStateException: boom\n"))
        assertTrue(text, text.contains("\nError\njava.lang.IllegalStateException: boom\n\tat "))
        // The crash itself is the last line of the ring the report carries.
        assertTrue(text, text.contains(" D App: process started\n"))
        assertTrue(text, text.contains(" E Crash: uncaught exception on thread ${Thread.currentThread().name} ! java.lang.IllegalStateException: boom\n"))

        // The process that crashed shows no sheet; the next launch over the same store does, once. install()
        // reads the store on a thread of its own, off the startup path, so the launch waits for it here.
        assertNull(reports.unread.value)
        val next = reporter().also { it.install() }
        await("the next launch has read the store") { next.unread.value != null }
        assertEquals(report.id, next.unread.value?.id)
        next.markRead()
        assertNull(next.unread.value)
        assertNull("read stays read", reporter().also { it.reload() }.unread.value)
        assertEquals("the report itself is kept", listOf(report.id), next.reports.value.map { it.id })

        // Installing twice does not stack handlers: the second call finds its own kind in place.
        next.install()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === installed)
    }

    @Test
    fun `a hook that hangs is abandoned after the timeout and the crash still goes on`() {
        val android = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(android)
        val reports = reporter()
        reports.install()
        val stuck = CountDownLatch(1)
        reports.addCrashHook { stuck.await() }
        val ran = arrayOf(false)
        reports.addCrashHook { ran[0] = true }

        val started = System.nanoTime()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("stuck"))
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("waited about the timeout, not forever: $tookMs ms", tookMs in CrashReporter.HOOK_TIMEOUT_MS - 200..CrashReporter.HOOK_TIMEOUT_MS + 5_000)
        assertFalse("the hook behind the stuck one never got its turn", ran[0])
        assertEquals(1, android.seen.size)
        assertEquals(1, reports.reports.value.size)
        stuck.countDown()
    }

    @Test
    fun `a crash marked unread is dropped from the marker if its report is gone`() {
        val reports = reporter()
        reports.onCrash(Thread.currentThread(), RuntimeException("gone"))
        val report = reports.reports.value.single()
        report.file.delete()
        val next = reporter().also { it.reload() }
        assertNull(next.unread.value)
        assertTrue(next.reports.value.isEmpty())
        assertFalse(File(dir, "unread").exists())
    }

    @Test
    fun `the head says how the frames read, from the source file the running code's frame names`() {
        // R8 writes the release's map id into every class's SourceFile, so the frames retrace with that mapping.
        val release = CrashReporter.mappingLine("${CrashReporter.R8_MAP_ID}75b21c223e555e811e4802716327302f8ba3df3ab6b6cfadd2c7483bb8787ba6")
        assertTrue(release, release.startsWith("r8-map-id-75b21c223e555e811e4802716327302f8ba3df3ab6b6cfadd2c7483bb8787ba6 \u00B7 "))
        assertTrue(release, release.contains("mapping.txt"))
        // A debuggable build's frames name their source; this JVM's do.
        assertEquals("none needed; the frames name their source file and line", CrashReporter.mappingLine("CrashReporter.kt"))
        assertEquals("none needed; the frames name their source file and line", CrashReporter.mappingLine())
        assertEquals("none in the frames; they name classes and methods only", CrashReporter.mappingLine(null))
        val install = CrashReporter.describeInstall(ApplicationProvider.getApplicationContext())
        assertTrue(install, Regex("""^App       .*\nMapping   none needed; the frames name their source file and line\nDevice    """).containsMatchIn(install))
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `the store keeps the newest thirty reports and delete forgets one or all`() {
        val reports = reporter()
        repeat(CrashReporter.MAX_REPORTS + 5) {
            clock += 1_000
            reports.report(ReportKind.TRANSPORT, "report $it", null, details = listOf("Reason" to "n$it"))
        }
        val kept = reports.reports.value
        assertEquals(CrashReporter.MAX_REPORTS, kept.size)
        assertEquals("newest first", "report ${CrashReporter.MAX_REPORTS + 4}", kept.first().title)
        assertEquals("the oldest five are gone", "report 5", kept.last().title)
        assertEquals(kept.map { it.at }, kept.map { it.at }.sortedDescending())
        assertEquals("n${CrashReporter.MAX_REPORTS + 4}", kept.first().summary)

        reports.delete(kept.first())
        assertEquals(CrashReporter.MAX_REPORTS - 1, reports.reports.value.size)
        assertFalse(kept.first().file.exists())
        reports.deleteAll()
        assertTrue(reports.reports.value.isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `the ring keeps the last lines only, counts them all, and formats a line with its error`() {
        val small = LogRing(capacity = 3, zone = { ZoneOffset.UTC })
        small.log(LogRing.Level.DEBUG, "A", "one", at = clock)
        small.log(LogRing.Level.INFO, "B", "two", at = clock + 1)
        small.log(LogRing.Level.WARN, "C", "three", error = IllegalArgumentException("bad"), at = clock + 2)
        small.log(LogRing.Level.ERROR, "D", "four", error = RuntimeException(), at = clock + 3)
        assertEquals(4L, small.total)
        assertEquals(
            listOf(
                "09-20 04:00:00.001 I B: two",
                "09-20 04:00:00.002 W C: three ! java.lang.IllegalArgumentException: bad",
                "09-20 04:00:00.003 E D: four ! java.lang.RuntimeException",
            ),
            small.snapshot(),
        )
        // A report over a ring that has dropped lines says so.
        val reports = CrashReporter(dir, { "App       test" }, small, now = { clock }, zone = { ZoneOffset.UTC })
        val text = reports.report(ReportKind.TRANSPORT, "t", null)!!.read()
        assertTrue(text, text.contains("\nLog \u00B7 last 3 lines of 4\n"))
        small.clear()
        assertTrue(small.snapshot().isEmpty())
        assertEquals(0L, small.total)
    }

    @Test
    fun `what sshj logs through slf4j lands in the ring under the logger's simple name, TRACE excluded`() {
        val before = BerthLog.ring.total
        val log = LoggerFactory.getLogger("net.schmizz.sshj.transport.TransportImpl")
        assertTrue("the app's binding is the one slf4j found: ${log.javaClass.name}", log.javaClass.name.startsWith("app.berth.android.diagnostics."))
        log.trace("packet {}", "contents")
        log.debug("Client identity string: {}", "SSH-2.0-Berth")
        log.warn("Disconnected - {}", "BY_APPLICATION", IllegalStateException("closed"))
        val lines = BerthLog.ring.snapshot().takeLast(2)
        assertEquals(before + 2, BerthLog.ring.total)
        assertTrue(lines[0], lines[0].endsWith(" D TransportImpl: Client identity string: SSH-2.0-Berth"))
        assertTrue(lines[1], lines[1].endsWith(" W TransportImpl: Disconnected - BY_APPLICATION ! java.lang.IllegalStateException: closed"))
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        val seen = ArrayList<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            seen += t to e
        }
    }
}
