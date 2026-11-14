package `in`.aniscroll.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTier {
    FAST_WIFI,
    STANDARD,
    CONSTRAINED,
    OFFLINE
}

/**
 * Observes Android network connectivity and classifies the active link so ExoPlayer
 * can pick an appropriate initial adaptive bitrate cap and recover smoothly after drops.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager: ConnectivityManager? = runCatching {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

    private val _isConnected = MutableStateFlow(checkCurrentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _networkTier = MutableStateFlow(evaluateNetworkTier())
    val networkTier: StateFlow<NetworkTier> = _networkTier.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshState()
        }

        override fun onLost(network: Network) {
            refreshState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshState()
        }
    }

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    fun refreshState(): NetworkTier {
        val connected = checkCurrentlyConnected()
        val tier = evaluateNetworkTier()
        _isConnected.value = connected
        _networkTier.value = tier
        return tier
    }

    fun currentTier(): NetworkTier = evaluateNetworkTier()

    fun unregister() {
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
    }

    private fun checkCurrentlyConnected(): Boolean {
        val cm = connectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    private fun evaluateNetworkTier(): NetworkTier {
        val cm = connectivityManager ?: return NetworkTier.STANDARD
        val active = cm.activeNetwork ?: return NetworkTier.OFFLINE
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkTier.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkTier.OFFLINE
        }

        val downstreamKbps = caps.linkDownstreamBandwidthKbps
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        return when {
            isMetered || (downstreamKbps in 1 until 5_000) -> NetworkTier.CONSTRAINED
            isWifiOrEthernet && (downstreamKbps <= 0 || downstreamKbps >= 15_000) -> NetworkTier.FAST_WIFI
            else -> NetworkTier.STANDARD
        }
    }
}
