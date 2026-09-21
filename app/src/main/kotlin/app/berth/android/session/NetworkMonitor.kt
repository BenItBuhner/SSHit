package app.berth.android.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the sessions need to know about the network: when one comes back, so a reconnect can skip
 * its wait ([available]), and when the one under the app changes, so a live socket can be asked
 * whether it survived ([changes]).
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    /** Emits whenever a default network with internet capability appears, so reconnects can skip the wait. */
    val available: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * Emits when the network under the app changes (vision §4.4, A23): the default network is
     * another one, is lost, or keeps its identity but not its addresses (a handoff from Wi-Fi to
     * mobile, a new lease, a VPN going up or down). A socket opened on the old network does not
     * learn of this on its own; a live session probes it on each emission and finds a dead one in
     * seconds rather than after the keepalive count. Registration replays the current network's
     * state, so a new collector hears one change at once; a probe then costs one packet, and a
     * socket that is already dead is found the sooner. Capability changes (validated, metered, a
     * signal reading on some devices) are left out: they say nothing about the socket.
     */
    val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(Unit)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    val isOnline: Boolean
        get() {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
}
