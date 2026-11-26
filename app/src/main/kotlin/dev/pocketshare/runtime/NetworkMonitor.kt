package dev.pocketshare.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkStatus(
    val online: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val metered: Boolean,
)

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val initialNetwork = runCatching { connectivityManager?.activeNetwork }.getOrNull()
    private val mutableStatus = MutableStateFlow(readStatus(initialNetwork))
    val status: StateFlow<NetworkStatus> = mutableStatus.asStateFlow()

    /** Convenience signal for the tunnel supervisor, which only cares about reachability. */
    private val mutableOnline = MutableStateFlow(mutableStatus.value.online)
    val online: StateFlow<Boolean> = mutableOnline.asStateFlow()

    /**
     * Increments whenever the default network or its meaningful capabilities change. Consumers
     * use it to drop stale probe results instead of treating a Wi-Fi/cellular handover as an
     * outage.
     */
    private val mutableGeneration = MutableStateFlow(0L)
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    private var lastNetwork: Network? = initialNetwork

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
            }

            override fun onLost(network: Network) {
                refresh()
            }

            override fun onUnavailable() {
                refresh()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                refresh()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                refresh()
            }
        }

    init {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }

    fun refresh() {
        val manager = connectivityManager
        val network = runCatching { manager?.activeNetwork }.getOrNull()
        val next = readStatus(network)
        val previous = mutableStatus.value
        mutableStatus.value = next
        mutableOnline.value = next.online
        if (network != lastNetwork ||
            previous.online != next.online ||
            previous.isWifi != next.isWifi ||
            previous.isCellular != next.isCellular
        ) {
            lastNetwork = network
            mutableGeneration.value += 1
        }
    }

    private fun readStatus(network: Network?): NetworkStatus {
        val manager = connectivityManager ?: return NetworkStatus(false, false, false, false)
        if (network == null) return NetworkStatus(false, false, false, false)
        val capabilities =
            runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
                ?: return NetworkStatus(false, false, false, false)
        // INTERNET alone also matches captive portals and "connected, no internet" Wi-Fi.
        // VALIDATED means Android actually reached the internet over this network.
        val online =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkStatus(online = online, isWifi = wifi, isCellular = cellular, metered = metered)
    }
}
