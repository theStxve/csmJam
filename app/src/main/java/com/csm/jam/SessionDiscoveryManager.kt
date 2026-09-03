package com.csm.jam

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredSession(
    val sessionName: String,
    val hostIp: String,
    val port: Int = 8887,
    val participantsCount: Int = 0,
    val currentTrack: String? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

object SessionDiscoveryManager {

    private const val DISCOVERY_PORT = 8888
    private const val TAG_QUERY = "CSM_JAM_QUERY"
    private const val TAG_ANNOUNCE = "CSM_JAM_ANNOUNCE"
    private const val TAG_BYE = "CSM_JAM_BYE"

    // Host Advertiser state
    private var advertiserSocket: DatagramSocket? = null
    private var isAdvertising = false
    private var advertiserThread: Thread? = null
    private var advertiserListenerThread: Thread? = null

    // Guest Discovery state
    private var discoverySocket: DatagramSocket? = null
    private var isDiscovering = false
    private var discoveryReceiveThread: Thread? = null
    private var discoveryQueryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val discoveredSessions = ConcurrentHashMap<String, DiscoveredSession>()

    // =========================================================================
    // HOST MODE: BROADCAST & RESPOND
    // =========================================================================

    fun startAdvertising(
        context: Context,
        sessionName: String,
        hostIp: String,
        port: Int = 8887,
        getInfo: () -> Pair<Int, String?> = { Pair(0, null) }
    ) {
        stopAdvertising()

        isAdvertising = true
        advertiserThread = Thread {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                advertiserSocket = socket

                // Listener for direct unicast queries from guests
                advertiserListenerThread = Thread {
                    val buffer = ByteArray(1024)
                    while (isAdvertising && !socket.isClosed) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length).trim()
                            if (message.contains(TAG_QUERY)) {
                                val (count, track) = getInfo()
                                val responseJson = JSONObject().apply {
                                    put("type", TAG_ANNOUNCE)
                                    put("name", sessionName)
                                    put("ip", hostIp)
                                    put("port", port)
                                    put("participants", count)
                                    if (track != null) put("track", track)
                                }
                                val sendBytes = responseJson.toString().toByteArray()
                                val replyPacket = DatagramPacket(sendBytes, sendBytes.size, packet.socketAddress)
                                socket.send(replyPacket)
                            }
                        } catch (e: Exception) {
                            if (!isAdvertising) break
                        }
                    }
                }.also { it.start() }

                // Periodic broadcast loop (every 1.5 seconds)
                while (isAdvertising && !socket.isClosed) {
                    try {
                        val (count, track) = getInfo()
                        val json = JSONObject().apply {
                            put("type", TAG_ANNOUNCE)
                            put("name", sessionName)
                            put("ip", hostIp)
                            put("port", port)
                            put("participants", count)
                            if (track != null) put("track", track)
                        }
                        val bytes = json.toString().toByteArray()

                        // Send to 255.255.255.255 and all subnet broadcast addresses
                        val broadcastAddresses = getBroadcastAddresses()
                        for (addr in broadcastAddresses) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, addr, DISCOVERY_PORT)
                                socket.send(packet)
                            } catch (ignored: Exception) {}
                        }
                    } catch (e: Exception) {
                        if (!isAdvertising) break
                    }

                    try {
                        Thread.sleep(1500)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.also { it.start() }
    }

    fun stopAdvertising() {
        isAdvertising = false
        try {
            advertiserSocket?.let { sock ->
                if (!sock.isClosed) {
                    // Send BYE packet to notify guests immediately
                    Thread {
                        try {
                            val byeJson = JSONObject().apply { put("type", TAG_BYE) }
                            val bytes = byeJson.toString().toByteArray()
                            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                            sock.send(packet)
                        } catch (ignored: Exception) {}
                        finally {
                            try { sock.close() } catch (ignored: Exception) {}
                        }
                    }.start()
                }
            }
        } catch (ignored: Exception) {}
        advertiserSocket = null
        advertiserThread?.interrupt()
        advertiserThread = null
        advertiserListenerThread?.interrupt()
        advertiserListenerThread = null
    }

    // =========================================================================
    // GUEST MODE: DISCOVERY LISTENER
    // =========================================================================

    fun startDiscovery(
        context: Context,
        onSessionsUpdated: (List<DiscoveredSession>) -> Unit
    ) {
        stopDiscovery()

        // Acquire MulticastLock so Wi-Fi driver does not discard UDP broadcasts
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("csm_jam_discovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (ignored: Exception) {}

        discoveredSessions.clear()
        isDiscovering = true

        val socket: DatagramSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            // If port 8888 is already bound (e.g. Host is on same device or another app), use ephemeral port
            DatagramSocket().apply { broadcast = true }
        }
        discoverySocket = socket

        // Receiver loop
        discoveryReceiveThread = Thread {
            val buffer = ByteArray(2048)
            while (isDiscovering && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val content = String(packet.data, 0, packet.length).trim()

                    if (content.startsWith("{") && content.endsWith("}")) {
                        val json = JSONObject(content)
                        val type = json.optString("type")
                        val senderIp = packet.address.hostAddress ?: ""

                        if (type == TAG_ANNOUNCE) {
                            var hostIp = json.optString("ip")
                            if (hostIp.isBlank() || hostIp == "127.0.0.1" || hostIp == "0.0.0.0") {
                                hostIp = senderIp
                            }
                            val session = DiscoveredSession(
                                sessionName = json.optString("name", "CSM Jam Session"),
                                hostIp = hostIp,
                                port = json.optInt("port", 8887),
                                participantsCount = json.optInt("participants", 0),
                                currentTrack = json.optString("track").takeIf { it.isNotBlank() },
                                lastSeenMs = System.currentTimeMillis()
                            )
                            discoveredSessions[hostIp] = session
                            onSessionsUpdated(cleanAndGetSessions())
                        } else if (type == TAG_BYE) {
                            val hostIp = json.optString("ip", senderIp)
                            discoveredSessions.remove(hostIp)
                            onSessionsUpdated(cleanAndGetSessions())
                        }
                    }
                } catch (e: Exception) {
                    if (!isDiscovering) break
                }
            }
        }.also { it.start() }

        // Query probe loop (sends CSM_JAM_QUERY every 2 seconds & purges stale sessions)
        discoveryQueryThread = Thread {
            while (isDiscovering && !socket.isClosed) {
                try {
                    val queryBytes = JSONObject().apply { put("type", TAG_QUERY) }.toString().toByteArray()
                    val broadcastAddresses = getBroadcastAddresses()
                    for (addr in broadcastAddresses) {
                        try {
                            val packet = DatagramPacket(queryBytes, queryBytes.size, addr, DISCOVERY_PORT)
                            socket.send(packet)
                        } catch (ignored: Exception) {}
                    }

                    // Purge stale sessions (> 5s without response)
                    val updated = cleanAndGetSessions()
                    onSessionsUpdated(updated)
                } catch (e: Exception) {
                    if (!isDiscovering) break
                }

                try {
                    Thread.sleep(2000)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
    }

    fun stopDiscovery() {
        isDiscovering = false
        try {
            discoverySocket?.close()
        } catch (ignored: Exception) {}
        discoverySocket = null

        discoveryReceiveThread?.interrupt()
        discoveryReceiveThread = null

        discoveryQueryThread?.interrupt()
        discoveryQueryThread = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
        multicastLock = null

        discoveredSessions.clear()
    }

    private fun cleanAndGetSessions(): List<DiscoveredSession> {
        val now = System.currentTimeMillis()
        discoveredSessions.entries.removeIf { now - it.value.lastSeenMs > 5000 }
        return discoveredSessions.values.sortedByDescending { it.lastSeenMs }
    }

    // =========================================================================
    // BROADCAST ADDRESS RESOLVER
    // =========================================================================

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (ignored: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                for (ia in iface.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && !list.contains(bcast)) {
                        list.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
