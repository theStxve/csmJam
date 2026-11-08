package com.csm.jam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/**
 * The app's own party network: Android's "local-only hotspot" (no internet, only for the
 * phones among each other). For places without WLAN or with a guest WLAN that blocks
 * devices from seeing each other. SSID and password are chosen by Android.
 */
object LocalHotspot {

    data class Credentials(val ssid: String, val password: String)

    val credentialsState = mutableStateOf<Credentials?>(null)
    val isStartingState = mutableStateOf(false)

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    /** Our address inside the own network – what guests in it connect to. */
    @Volatile var ipv4: String? = null
        private set

    val isActive: Boolean get() = reservation != null

    /** Android 13+: "nearby devices"; before: location (Android ties hotspot APIs to it). */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermissions(context: Context): Boolean = requiredPermissions()
        .filter { it != Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Before Android 13 the location toggle must be on, otherwise the hotspot silently fails. */
    fun locationSwitchRequired(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return !enabled
    }

    /** [onResult] runs on the main thread: null = network is up, otherwise a message for the user. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, onResult: (String?) -> Unit) {
        if (isActive) {
            onResult(null)
            return
        }
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val handler = Handler(Looper.getMainLooper())
        isStartingState.value = true
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    credentialsState.value = credentialsOf(res)
                    // Our sockets must not stick to the old WLAN while guests come in through this one
                    WifiBinder.pause()
                    // The interface address appears a moment after the start
                    Thread {
                        repeat(30) {
                            findHotspotAddress()?.let { ip ->
                                ipv4 = ip
                                handler.post {
                                    isStartingState.value = false
                                    onResult(null)
                                }
                                return@Thread
                            }
                            Thread.sleep(100)
                        }
                        handler.post {
                            isStartingState.value = false
                            onResult(null) // still usable: getRawIpAddress falls back to interface names
                        }
                    }.start()
                }

                override fun onStopped() {
                    clear()
                }

                override fun onFailed(reason: Int) {
                    isStartingState.value = false
                    clear()
                    onResult(
                        when (reason) {
                            ERROR_INCOMPATIBLE_MODE -> "Der normale Hotspot ist an – bitte ausschalten und nochmal"
                            ERROR_TETHERING_DISALLOWED -> "Auf diesem Gerät ist ein eigenes WLAN gesperrt"
                            ERROR_NO_CHANNEL -> "Kein freier WLAN-Kanal – WLAN kurz aus- und wieder einschalten"
                            else -> "Eigenes WLAN konnte nicht gestartet werden"
                        }
                    )
                }
            }, handler)
        } catch (e: SecurityException) {
            isStartingState.value = false
            onResult("Berechtigung fehlt – bitte erlauben und nochmal")
        } catch (e: Exception) {
            isStartingState.value = false
            onResult("Eigenes WLAN wird von diesem Gerät nicht unterstützt")
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        clear()
    }

    private fun clear() {
        reservation = null
        credentialsState.value = null
        ipv4 = null
        WifiBinder.resume()
    }

    @Suppress("DEPRECATION")
    private fun credentialsOf(res: WifiManager.LocalOnlyHotspotReservation): Credentials? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) config.wifiSsid?.toString()?.trim('"') else config.ssid
            Credentials(ssid.orEmpty(), config.passphrase.orEmpty())
        } else {
            val config = res.wifiConfiguration
            Credentials(config?.SSID.orEmpty().trim('"'), config?.preSharedKey.orEmpty().trim('"'))
        }
    } catch (_: Exception) {
        null
    }

    /** Site-local IPv4 on a WLAN/AP interface that isn't the address of the normal WLAN. */
    private fun findHotspotAddress(): String? {
        val stationIp = WifiBinder.wifiIpv4()
        val names = Regex("""^(wlan|ap|swlan|softap)""")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || !names.containsMatchIn(iface.name.lowercase())) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress && addr.hostAddress != stationIp) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Standard WLAN QR code – iPhone and Android cameras join the network directly. */
    fun wifiQrPayload(c: Credentials): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return "WIFI:T:WPA;S:${esc(c.ssid)};P:${esc(c.password)};;"
    }
}
