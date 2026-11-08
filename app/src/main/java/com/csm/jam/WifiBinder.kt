package com.csm.jam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Keeps all of the app's traffic on the WLAN.
 *
 * A party WLAN or hotspot usually has no internet. Android then marks it as "no internet" and
 * sends app traffic over mobile data instead – discovery broadcasts and the connection to
 * 192.168.x.x would leave through the cellular network and never reach the host.
 * Binding the process to the Wi-Fi network fixes that; loopback (host → own server) is unaffected.
 */
object WifiBinder {
    @Volatile private var started = false
    @Volatile private var paused = false
    private var cm: ConnectivityManager? = null
    @Volatile var wifiNetwork: Network? = null
        private set
    @Volatile var wifiLinkProperties: LinkProperties? = null
        private set

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        this.cm = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Match WLANs without internet too – that's exactly the party case
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifiNetwork = network
                    wifiLinkProperties = cm.getLinkProperties(network)
                    if (!paused) cm.bindProcessToNetwork(network)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (network == wifiNetwork) wifiLinkProperties = linkProperties
                }

                override fun onLost(network: Network) {
                    if (network == wifiNetwork) {
                        wifiNetwork = null
                        wifiLinkProperties = null
                        if (!paused) cm.bindProcessToNetwork(null)
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** While the app runs its own network, guests come in through that one – don't pin sockets to the old WLAN. */
    fun pause() {
        paused = true
        cm?.bindProcessToNetwork(null)
    }

    fun resume() {
        if (!paused) return
        paused = false
        wifiNetwork?.let { cm?.bindProcessToNetwork(it) }
    }

    /** The IPv4 address this phone has in the WLAN it is connected to (null without WLAN). */
    fun wifiIpv4(): String? =
        wifiLinkProperties?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
}
