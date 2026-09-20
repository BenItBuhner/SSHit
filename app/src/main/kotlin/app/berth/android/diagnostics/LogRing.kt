package app.berth.android.diagnostics

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The in-app log: the last few hundred lines, in memory and nowhere else. Nothing here is written
 * out on its own; a crash or a connection report copies the ring's tail into the report
 * ([CrashReporter]), and a report leaves the phone only when the user copies or shares it.
 *
 * Lines carry no secrets, by rule at every call site: what happened and to which host, never a
 * password, a key, a passphrase, clipboard text or anything a terminal showed.
 */
class LogRing(val capacity: Int = DEFAULT_CAPACITY, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    enum class Level(val letter: Char, val priority: Int) {
        DEBUG('D', Log.DEBUG), INFO('I', Log.INFO), WARN('W', Log.WARN), ERROR('E', Log.ERROR),
    }

    private val lines = ArrayDeque<String>(capacity)
    private val lock = Any()

    /** How many lines have been logged in all, so a report can say how many the ring has dropped. */
    var total: Long = 0L
        private set

    fun log(level: Level, tag: String, message: String, error: Throwable? = null, at: Long = System.currentTimeMillis()) {
        val line = buildString {
            append(TIME.format(Instant.ofEpochMilli(at).atZone(zone())))
            append(' ').append(level.letter).append(' ').append(tag).append(": ").append(message)
            if (error != null) {
                append(" ! ").append(error.javaClass.name)
                error.message?.let { append(": ").append(it) }
            }
        }
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
            total++
        }
    }

    /** The ring's lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) {
        lines.clear()
        total = 0L
    }

    companion object {
        const val DEFAULT_CAPACITY = 400
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}

/**
 * Where the app logs: one ring for the process, reachable without wiring since the sessions,
 * transfers and the SSH transport all report through it. Debug lines stay in the ring; the rest
 * are mirrored to logcat as well, and a debuggable build mirrors everything.
 */
object BerthLog {
    val ring = LogRing()

    /** Set once by [CrashReporter] from the installed package's debuggable flag. */
    @Volatile var mirrorDebugToLogcat: Boolean = false

    fun d(tag: String, message: String) = write(LogRing.Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(LogRing.Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write(LogRing.Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(LogRing.Level.ERROR, tag, message, error)

    fun write(level: LogRing.Level, tag: String, message: String, error: Throwable?) {
        ring.log(level, tag, message, error)
        if (level != LogRing.Level.DEBUG || mirrorDebugToLogcat) {
            runCatching { Log.println(level.priority, "Berth/$tag", if (error != null) "$message\n${Log.getStackTraceString(error)}" else message) }
        }
    }
}
