package app.berth.android.diagnostics

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * slf4j's binding in this app, found through `META-INF/services`: what sshj says about the
 * transport (key exchange, the auth methods tried, a disconnect and its reason) lands in the
 * in-app log ring under the logger's simple name, DEBUG and up. TRACE is never on; that is where
 * sshj prints packet contents.
 */
class RingLoggerProvider : SLF4JServiceProvider {
    private val loggers = RingLoggerFactory()
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggers
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}

private class RingLoggerFactory : ILoggerFactory {
    private val cache = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger = cache.getOrPut(name) { RingLogger(name) }
}

private class RingLogger(loggerName: String) : LegacyAbstractLogger() {
    private val tag = loggerName.substringAfterLast('.')

    init {
        name = loggerName
    }

    override fun isTraceEnabled(): Boolean = false
    override fun isDebugEnabled(): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments) ?: messagePattern ?: ""
        val ring = when (level) {
            Level.ERROR -> LogRing.Level.ERROR
            Level.WARN -> LogRing.Level.WARN
            Level.INFO -> LogRing.Level.INFO
            Level.DEBUG, Level.TRACE -> LogRing.Level.DEBUG
        }
        BerthLog.write(ring, tag, message, throwable)
    }
}
