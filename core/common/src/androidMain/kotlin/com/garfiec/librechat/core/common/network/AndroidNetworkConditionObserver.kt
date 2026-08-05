package com.garfiec.librechat.core.common.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidNetworkConditionObserver(
    private val context: Context,
) : NetworkConditionObserver {

    // Permission is declared in the app module's AndroidManifest.
    @SuppressLint("MissingPermission")
    override val isUnmetered: Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        fun emitCurrent() {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            trySend(capabilities.isUnmetered())
        }

        emitCurrent()

        val callback = object : ConnectivityManager.NetworkCallback() {
            // Re-read the ACTIVE network rather than trusting the callback's own capabilities: these
            // fire per network, and a phone on Wi-Fi still gets cellular callbacks. Answering from
            // whichever network happened to change would report metered while Wi-Fi carries traffic.
            override fun onAvailable(network: Network) = emitCurrent()
            override fun onLost(network: Network) = emitCurrent()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = emitCurrent()
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Both capabilities are required. `NOT_METERED` alone is true on a Wi-Fi link the user has
     * flagged as metered only when the OS agrees, whereas `TEMPORARILY_NOT_METERED` covers a carrier
     * zero-rating window that should not be mistaken for a home connection. No network at all
     * answers `false` — absent capabilities are not an unmetered link.
     */
    private fun NetworkCapabilities?.isUnmetered(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
