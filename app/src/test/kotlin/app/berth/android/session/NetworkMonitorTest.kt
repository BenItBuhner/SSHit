package app.berth.android.session

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NetworkMonitor] against the platform's callbacks (vision §4.4, A23): [NetworkMonitor.changes]
 * is one default-network callback held only while someone listens, and it speaks when a network
 * comes, goes or keeps its identity but not its addresses, never for a capability reading, which
 * says nothing about a socket; [NetworkMonitor.available] speaks when a network is up or becomes
 * validated, so a reconnect's wait can end early, and stays quiet on a loss; [NetworkMonitor.isOnline]
 * reads the active network's internet capability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(manager)
    private val monitor = NetworkMonitor(context)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val network: Network = manager.activeNetwork!!

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `changes holds one default callback while collected and lets it go when the collector stops`() {
        assertTrue(shadow.networkCallbacks.isEmpty())
        val job = count(monitor.changes)
        await("the callback to register") { shadow.networkCallbacks.size == 1 }
        job.cancel()
        await("the callback to unregister") { shadow.networkCallbacks.isEmpty() }
    }

    @Test
    fun `changes says a network came, went or changed its addresses, and nothing of its capabilities`() {
        val seen = AtomicInteger()
        count(monitor.changes, seen)
        await("the callback to register") { shadow.networkCallbacks.size == 1 }
        val callback = shadow.networkCallbacks.single()

        callback.onAvailable(network)
        await("the arrival") { seen.get() == 1 }
        callback.onLost(network)
        await("the loss") { seen.get() == 2 }
        callback.onLinkPropertiesChanged(network, LinkProperties())
        await("the new addresses") { seen.get() == 3 }

        // A capability reading (validated, metered, a signal strength on some devices) is not a change of network.
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_INTERNET))
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        Thread.sleep(150)
        assertEquals("capability readings are not changes", 3, seen.get())
    }

    @Test
    fun `available says a network is up or validated, and nothing on a loss or an unvalidated reading`() {
        val seen = AtomicInteger()
        count(monitor.available, seen)
        await("the callback to register") { shadow.networkCallbacks.size == 1 }
        val callback = shadow.networkCallbacks.single()

        callback.onAvailable(network)
        await("the arrival") { seen.get() == 1 }
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        await("the validation") { seen.get() == 2 }

        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        callback.onLost(network)
        callback.onLinkPropertiesChanged(network, LinkProperties())
        Thread.sleep(150)
        assertEquals("a loss, new addresses and an unvalidated reading say nothing about a way back", 2, seen.get())
    }

    @Test
    fun `the two flows are two callbacks, each let go on its own`() {
        val changes = count(monitor.changes)
        val available = count(monitor.available)
        await("both callbacks") { shadow.networkCallbacks.size == 2 }
        changes.cancel()
        await("one left") { shadow.networkCallbacks.size == 1 }
        available.cancel()
        await("none left") { shadow.networkCallbacks.isEmpty() }
    }

    @Test
    fun `isOnline reads the active network's internet capability`() {
        shadow.setNetworkCapabilities(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(monitor.isOnline)
        shadow.setNetworkCapabilities(network, capabilities(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertFalse("a network without internet is not online", monitor.isOnline)
        shadow.setNetworkCapabilities(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        shadow.setDefaultNetworkActive(false)
        assertFalse("no active network is not online", monitor.isOnline)
    }

    private fun count(flow: Flow<Unit>, into: AtomicInteger = AtomicInteger()): Job = scope.launch { flow.collect { into.incrementAndGet() } }

    private fun capabilities(vararg caps: Int): NetworkCapabilities {
        val result = ShadowNetworkCapabilities.newInstance()
        for (cap in caps) shadowOf(result).addCapability(cap)
        return result
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
