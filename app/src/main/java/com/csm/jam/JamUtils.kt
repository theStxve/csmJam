package com.csm.jam

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

internal fun extractFileNameFromUrl(url: String?): String {
    if (url == null) return "Nichts"
    val decoded = Uri.decode(url)
    return decoded.substringAfterLast("/").removeSuffix(".mp3")
}

internal fun getAudioTitle(context: Context, uri: Uri): String? {
    return try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
        retriever.release()
        
        if (!title.isNullOrBlank()) {
            if (!artist.isNullOrBlank()) "$title - $artist" else title
        } else null
    } catch (e: Exception) {
        null
    }
}

internal fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Unbekannter Track"
}

/**
 * The address other devices in the party network reach this phone at.
 * Order: own hotspot interface (guests are in our hotspot) > the WLAN address Android reports >
 * WLAN-like interface names. Mobile data, VPN and CGNAT addresses are never used – picking
 * one of those made the QR code and discovery point at an address nobody can reach.
 */
internal fun getRawIpAddress(context: Context): String {
    // Running our own network: guests are in there, so that's the address to hand out
    if (LocalHotspot.isActive) LocalHotspot.ipv4?.let { return it }
    val excludedPrefixes = listOf("rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "pdp", "wwan", "ipsec", "dummy", "p2p", "lo")
    val hotspotName = Regex("""^(ap|swlan|softap)\d*$|tether""")
    val wifiIp = WifiBinder.wifiIpv4()
    val candidates = mutableListOf<Pair<Int, String>>() // (priority, ip)
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return wifiIp ?: "Unbekannt"
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val ifName = iface.name.lowercase()
            if (excludedPrefixes.any { ifName.startsWith(it) }) continue

            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr.isLoopbackAddress || addr !is java.net.Inet4Address) continue
                // Only private LAN ranges (192.168/16, 10/8, 172.16/12) – carrier NAT and public IPs can't be a party LAN
                if (!addr.isSiteLocalAddress) continue
                val ip = addr.hostAddress ?: continue
                val priority = when {
                    hotspotName.containsMatchIn(ifName) -> 0
                    ip == wifiIp -> 1
                    ifName.startsWith("wlan") -> 2
                    ifName.startsWith("eth") || ifName.contains("usb") || ifName.contains("rndis") -> 3
                    else -> 4
                }
                candidates.add(priority to ip)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return candidates.minByOrNull { it.first }?.second ?: wifiIp ?: "Unbekannt"
}

data class WifiNetworkInfo(val ssid: String, val level: Int) // level 0..4

internal fun getWifiNetworkInfo(context: Context): WifiNetworkInfo {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isWifi) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val rawSsid = info?.ssid?.replace("\"", "") ?: ""
            val ssid = if (rawSsid.isBlank() || rawSsid == "<unknown ssid>") "WLAN verbunden" else rawSsid
            val rssi = info?.rssi ?: -100
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm?.calculateSignalLevel(rssi) ?: 3
            } else {
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(rssi, 5)
            }
            return WifiNetworkInfo(ssid, level.coerceIn(0, 4))
        } else {
            val isHotspot = getRawIpAddress(context).startsWith("192.168.43.")
            if (isHotspot) {
                return WifiNetworkInfo("Hotspot aktiv", 4)
            }
            val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            if (isEthernet) {
                return WifiNetworkInfo("LAN / Emulator", 4)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return WifiNetworkInfo("Lokales Netzwerk", 3)
}

fun generateQrCodeBitmap(content: String, sizePx: Int = 512): android.graphics.Bitmap? {
    return try {
        val encoder = BarcodeEncoder()
        encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun parseHostFromQr(scanned: String): String? {
    val trimmed = scanned.trim()
    if (trimmed.startsWith("csmjam://join", ignoreCase = true)) {
        val uri = Uri.parse(trimmed)
        val hostParam = uri.getQueryParameter("host")
        if (!hostParam.isNullOrBlank()) return hostParam.trim()
    }
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        val uri = Uri.parse(trimmed)
        val host = uri.host
        if (!host.isNullOrBlank()) return host.trim()
    }
    val raw = trimmed.substringBefore(":")
    if (raw.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
        return raw
    }
    return null
}

/** All audio files in a picked folder (incl. sub-folders, max. depth 3). */
internal fun scanAudioFolder(context: Context, treeUri: Uri): List<Uri> {
    val extensions = setOf("mp3", "flac", "ogg", "m4a", "aac", "opus", "wav")
    val result = mutableListOf<Uri>()
    fun walk(dir: androidx.documentfile.provider.DocumentFile?, depth: Int) {
        if (dir == null || depth > 3 || result.size >= 2000) return
        dir.listFiles().forEach { f ->
            if (f.isDirectory) walk(f, depth + 1)
            else if (f.name?.substringAfterLast('.', "")?.lowercase() in extensions) result.add(f.uri)
        }
    }
    try {
        walk(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri), 0)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return result
}
