package com.csm.jam

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class LocalMusicServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
        // Disable built-in ping/pong connection-lost detection.
        // When the host app goes to background, Android throttles CPU/network so heavily
        // that the server can't respond to WebSocket pings in time, causing guests to get kicked.
        // We use our own PING_TIME/PONG_TIME for time sync instead.
        connectionLostTimeout = 0
    }

    private var currentTrackUrl: String? = null
    private var isPlaying = false
    // The time at which the track logically started playing (in server wall clock ms)
    // This is startedAtServerMs - 500ms future offset (i.e. the actual play moment)
    private var playActuallyStartedAtMs: Long = 0L
    // This is what we broadcast: scheduled slightly in the future for all devices to start simultaneously
    private var startedAtServerMs: Long = 0L
    private var pauseOffsetMs: Long = 0L

    private val queue = mutableListOf<String>()
    private val participants = ConcurrentHashMap<WebSocket, String>()
    
    // Sync protocol structures
    private var pendingPlayTrackUrl: String? = null
    private val trackReadyClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackFinishedClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackTitles = ConcurrentHashMap<String, String>() // url -> display title
    private val trackArtworks = ConcurrentHashMap<String, String>() // url -> artwork url

    // Permissions
    var guestsCanControl: Boolean = false
    var hostConnection: WebSocket? = null  // set when host connects
    var isRepeatSingle: Boolean = false


    fun resetSession() {
        currentTrackUrl = null
        isPlaying = false
        playActuallyStartedAtMs = 0L
        startedAtServerMs = 0L
        pauseOffsetMs = 0L
        queue.clear()
        participants.clear()
        pendingPlayTrackUrl = null
        trackReadyClients.clear()
        trackFinishedClients.clear()
        trackTitles.clear()
        trackArtworks.clear()
        guestsCanControl = false
        hostConnection = null
        isRepeatSingle = false
        
        val allConnections = ArrayList(connections)
        allConnections.forEach { it.close(1000, "Session ended") }
    }

    override fun onStart() {
        println("LocalMusicServer gestartet auf Port: $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val isHost = hostConnection == null // first connection is the host
        println(if (isHost) "Host verbunden: ${conn.remoteSocketAddress}" else "Neuer Gast verbunden: ${conn.remoteSocketAddress}")
        if (isHost) hostConnection = conn
        participants[conn] = if (isHost) "👑 Host" else "Gast"
        
        // If there's a track currently playing or pending, force this new guest to PREPARE it immediately
        val trackToPrep = pendingPlayTrackUrl ?: currentTrackUrl
        if (trackToPrep != null) {
            val prepMsg = JSONObject().apply {
                put("type", "PREPARE")
                put("trackUrl", trackToPrep)
            }
            conn.send(prepMsg.toString())
        }

        broadcastState(conn)
        broadcastLists()
    }

    private fun broadcastState(target: WebSocket? = null) {
        val stateMsg = JSONObject().apply {
            put("type", "STATE_UPDATE")
            put("isPlaying", isPlaying)
            put("trackUrl", currentTrackUrl)
            put("startedAtMs", startedAtServerMs)
            put("pauseOffsetMs", pauseOffsetMs)
            put("isRepeatSingle", isRepeatSingle)
        }
        if (target != null) {
            target.send(stateMsg.toString())
        } else {
            broadcast(stateMsg.toString())
        }
    }

    private fun broadcastLists() {
        val listsMsg = JSONObject().apply {
            put("type", "LISTS_UPDATE")
            synchronized(queue) {
                put("queue", JSONArray(queue))
            }
            put("participants", JSONArray(participants.values.toList()))
            
            val titlesObj = JSONObject()
            trackTitles.forEach { (url, title) -> titlesObj.put(url, title) }
            put("trackTitles", titlesObj)

            val artsObj = JSONObject()
            trackArtworks.forEach { (url, art) -> artsObj.put(url, art) }
            put("trackArtworks", artsObj)
        }
        broadcast(listsMsg.toString())
    }



    private fun attemptPendingPlay() {
        val trackUrl = pendingPlayTrackUrl ?: return
        val ready = trackReadyClients[trackUrl] ?: emptySet<WebSocket>()
        val allReady = participants.keys.all { ready.contains(it) }

        if (allReady) {
            println("All clients ready! Broadcasting PLAY for $trackUrl")
            pendingPlayTrackUrl = null
            currentTrackUrl = trackUrl
            isPlaying = true
            // Scheduled in the future for all devices to start simultaneously.
            // Increased to 1000ms for more stable sync across varied devices/WLAN.
            val leadTimeMs = 1000L
            val now = System.currentTimeMillis()
            startedAtServerMs = now + leadTimeMs
            playActuallyStartedAtMs = startedAtServerMs // will be past by the time track really plays
            
            val playMsg = JSONObject().apply {
                put("type", "PLAY")
                put("trackUrl", currentTrackUrl)
                put("startedAtMs", startedAtServerMs)
                put("pauseOffsetMs", pauseOffsetMs)
            }
            broadcast(playMsg.toString())
            broadcastState()
        } else {
            val missing = participants.size - ready.size
            println("Waiting for $missing more clients to finish downloading...")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = JSONObject(message)
        val type = json.getString("type")
        val isHost = conn == hostConnection

        // Guest control gate: block control actions from guests when not permitted
        val guestControlActions = setOf("ACTION_PLAY", "ACTION_PAUSE", "ACTION_SKIP_NEXT", "ACTION_SKIP_PREV", "ACTION_ADD_TRACK", "ACTION_SET_REPEAT_SINGLE", "ACTION_SEEK")
        if (!isHost && type in guestControlActions && !guestsCanControl) {
            println("Guest action '$type' blocked (guestsCanControl=false)")
            return
        }

        when (type) {
            "PING_TIME" -> {
                val response = JSONObject()
                response.put("type", "PONG_TIME")
                response.put("serverTimeMs", System.currentTimeMillis())
                conn.send(response.toString())
            }
            "ACTION_JOIN" -> {
                val uname = json.optString("username", "Gast")
                val isConnHost = (conn == hostConnection)
                participants[conn] = if (isConnHost) "👑 $uname" else "👤 $uname"
                broadcastLists()
            }
            "ACTION_SET_GUEST_PERMISSIONS" -> {
                if (isHost) {
                    guestsCanControl = json.getBoolean("allow")
                    println("guestsCanControl set to $guestsCanControl by host")
                    // Broadcast new permissions state to all clients so their UI can update
                    val permMsg = JSONObject().apply {
                        put("type", "GUEST_PERMISSIONS_CHANGED")
                        put("guestsCanControl", guestsCanControl)
                    }
                    broadcast(permMsg.toString())
                }
            }
            "ACTION_SHUFFLE" -> {
                if (queue.size > 1) {
                    val currentIndex = currentTrackUrl?.let { queue.indexOf(it) } ?: -1
                    if (currentIndex >= 0 && currentIndex < queue.size - 1) {
                        // Shuffle only tracks after the current track
                        val subList = queue.subList(currentIndex + 1, queue.size)
                        subList.shuffle()
                    } else if (currentIndex == -1) {
                        // Nothing playing, shuffle everything
                        queue.shuffle()
                    }
                    broadcastLists()
                }
            }
            "ACTION_SET_REPEAT_SINGLE" -> {
                val allow = json.getBoolean("repeat")
                isRepeatSingle = allow
                println("isRepeatSingle set to $isRepeatSingle")
                broadcastState()
            }
            "ACTION_REQUEST_STATE" -> {
                broadcastState(conn)
            }
            "ACTION_FORCE_SYNC" -> {
                if (currentTrackUrl != null && isPlaying) {
                    val currentPlayTimeMs = System.currentTimeMillis() - playActuallyStartedAtMs
                    val newPauseOffset = pauseOffsetMs + currentPlayTimeMs
                    val leadTimeMs = 500L
                    startedAtServerMs = System.currentTimeMillis() + leadTimeMs
                    playActuallyStartedAtMs = startedAtServerMs
                    pauseOffsetMs = newPauseOffset
                    
                    val playMsg = JSONObject().apply {
                        put("type", "PLAY")
                        put("trackUrl", currentTrackUrl)
                        put("startedAtMs", startedAtServerMs)
                        put("pauseOffsetMs", pauseOffsetMs)
                    }
                    broadcast(playMsg.toString())
                    broadcastState()
                    println("Server forced synchronization for all clients at offset $pauseOffsetMs ms")
                }
            }
            "ACTION_SEEK" -> {
                val seekDst = json.getLong("seekToMs")
                if (currentTrackUrl != null) {
                    pauseOffsetMs = seekDst
                    if (isPlaying) {
                        val leadTimeMs = 500L
                        startedAtServerMs = System.currentTimeMillis() + leadTimeMs
                        playActuallyStartedAtMs = startedAtServerMs
                        val playMsg = JSONObject().apply {
                            put("type", "PLAY")
                            put("trackUrl", currentTrackUrl)
                            put("startedAtMs", startedAtServerMs)
                            put("pauseOffsetMs", pauseOffsetMs)
                        }
                        broadcast(playMsg.toString())
                    } else {
                        val pauseMsg = JSONObject().apply {
                            put("type", "PAUSE")
                            put("pauseOffsetMs", pauseOffsetMs)
                        }
                        broadcast(pauseMsg.toString())
                    }
                    broadcastState()
                }
            }
            "ACTION_ADD_TRACK" -> {
                val url = json.getString("trackUrl")
                val title = if (json.has("trackTitle")) json.getString("trackTitle") else null
                val art = if (json.has("trackArtwork")) json.getString("trackArtwork") else null
                
                if (title != null) trackTitles[url] = title
                if (art != null) trackArtworks[url] = art
                
                queue.add(url)
                broadcastLists()


                
                // Auto play if it's the first track and nothing is playing
                if (queue.size == 1 && currentTrackUrl == null) {
                    pauseOffsetMs = 0L
                    pendingPlayTrackUrl = url
                    trackReadyClients[url]?.clear()
                    
                    val prepMsg = JSONObject().apply {
                        put("type", "PREPARE")
                        put("trackUrl", url)
                    }
                    broadcast(prepMsg.toString())
                    
                    // We don't play immediately, we wait for all to download/buffer
                    attemptPendingPlay()
                }
            }
            "ACTION_QUEUE_REMOVE" -> {
                val url = json.getString("trackUrl")
                if (queue.remove(url)) {
                    trackReadyClients.remove(url)
                    trackFinishedClients.remove(url)
                    broadcastLists()
                    
                    if (url == pendingPlayTrackUrl) {
                        val nextUrl = queue.firstOrNull()
                        if (nextUrl != null) {
                            pendingPlayTrackUrl = nextUrl
                            trackReadyClients[nextUrl]?.clear()
                            val prepMsg = JSONObject().apply {
                                put("type", "PREPARE")
                                put("trackUrl", nextUrl)
                            }
                            broadcast(prepMsg.toString())
                            attemptPendingPlay()
                        } else {
                            pendingPlayTrackUrl = null
                            broadcastState()
                        }
                    }
                }
            }
            "ACTION_QUEUE_REORDER" -> {
                val from = json.getInt("fromIndex")
                val to = json.getInt("toIndex")
                if (from in queue.indices && to in queue.indices) {
                    val item = queue.removeAt(from)
                    queue.add(to, item)
                    broadcastLists()
                }
            }
            "ACTION_TRACK_READY" -> {
                val url = json.getString("trackUrl")
                val clientsReady = trackReadyClients.getOrPut(url) { mutableSetOf() }
                
                // Wir fügen die Verbindung hinzu und prüfen, ob sie NEU in der Liste ist. 
                // Das verhindert den berüchtigten Stotter/Seek-Loop, wenn ExoPlayer nachladen muss!
                val isNewReady = clientsReady.add(conn)
                
                if (isNewReady) {
                    println("Client ${conn.remoteSocketAddress} is ready for $url")
                    
                    // If this is the pending track we are waiting for, check if we can start now
                    if (url == pendingPlayTrackUrl) {
                        attemptPendingPlay()
                    } else if (url == currentTrackUrl && isPlaying) {
                        // Late joiner who just finished preparing the *currently playing* track!
                        // Let's send them a targeted PLAY command so they can catch up to the rest
                        val catchUpMsg = JSONObject().apply {
                            put("type", "PLAY")
                            put("trackUrl", currentTrackUrl)
                            put("startedAtMs", startedAtServerMs)
                            put("pauseOffsetMs", pauseOffsetMs)
                        }
                        conn.send(catchUpMsg.toString())
                    }
                }
            }
            "ACTION_TRACK_FINISHED" -> {
                val url = json.getString("trackUrl")
                // Only advance if this is actually the track we think is currently playing
                // and we haven't already scheduled it to be replayed!
                if (url == currentTrackUrl && url != pendingPlayTrackUrl) {
                    val finishedClients = trackFinishedClients.getOrPut(url) { mutableSetOf() }
                    finishedClients.add(conn)
                    println("Client ${conn.remoteSocketAddress} finished $url (${finishedClients.size}/${participants.size})")

                    // Advance as soon as at least one client reports done
                    // (others will catch up via the next PREPARE broadcast)
                    if (finishedClients.size >= 1) {
                        println("Advancing queue from $url")
                        
                        if (isRepeatSingle) {
                            // Repeat Single logic: Don't remove from queue, just replay
                            isPlaying = false
                            pauseOffsetMs = 0L
                            startedAtServerMs = 0L
                            trackFinishedClients.remove(url)
                            trackReadyClients.remove(url)
                            
                            val prepMsg = JSONObject().apply {
                                put("type", "PREPARE")
                                put("trackUrl", url)
                            }
                            broadcast(prepMsg.toString())
                            pendingPlayTrackUrl = url
                            attemptPendingPlay()
                        } else {
                            // Normal advance track logic
                            trackFinishedClients.remove(url)
                            trackReadyClients.remove(url)
                            isPlaying = false
                            pauseOffsetMs = 0L
                            queue.remove(url)
                            currentTrackUrl = null
                            broadcastLists()

                            val nextUrl = queue.firstOrNull()
                            if (nextUrl != null) {
                                pauseOffsetMs = 0L
                                pendingPlayTrackUrl = nextUrl
                                trackReadyClients[nextUrl]?.clear()

                                val prepMsg = JSONObject().apply {
                                    put("type", "PREPARE")
                                    put("trackUrl", nextUrl)
                                }
                                broadcast(prepMsg.toString())
                                attemptPendingPlay()
                            } else {
                                broadcastState()
                            }
                        }
                    }
                }
            }
            "ACTION_PLAY" -> {
                var targetUrl: String? = null
                if (json.has("trackUrl")) {
                    targetUrl = json.getString("trackUrl")
                    pauseOffsetMs = 0L
                } else if (currentTrackUrl == null && queue.isNotEmpty()) {
                    targetUrl = queue.first()
                    pauseOffsetMs = 0L
                } else if (!isPlaying && currentTrackUrl != null) {
                    // Just unpausing the current track, no download needed
                    targetUrl = currentTrackUrl
                }
                
                if (targetUrl != null) {
                    if (targetUrl == currentTrackUrl && pauseOffsetMs > 0) {
                        // Resuming from pause. Everyone should already have it downloaded.
                        isPlaying = true
                        val leadTimeMs = 500L
                        startedAtServerMs = System.currentTimeMillis() + leadTimeMs
                        val playMsg = JSONObject().apply {
                            put("type", "PLAY")
                            put("trackUrl", currentTrackUrl)
                            put("startedAtMs", startedAtServerMs)
                            put("pauseOffsetMs", pauseOffsetMs)
                        }
                        broadcast(playMsg.toString())
                        broadcastState()
                    } else {
                        // New track, tell everyone to PREPARE and wait for them to be READY
                        pendingPlayTrackUrl = targetUrl
                        trackReadyClients[targetUrl]?.clear()

                        val prepMsg = JSONObject().apply {
                            put("type", "PREPARE")
                            put("trackUrl", targetUrl)
                        }
                        broadcast(prepMsg.toString())

                        attemptPendingPlay()
                    }
                }
            }
            "ACTION_PAUSE" -> {
                if (isPlaying) {
                    val now = System.currentTimeMillis()
                    // startedAtServerMs was set to (now + 500ms) at play time as lead time
                    // so the track actually started playing 500ms AFTER startedAtServerMs
                    // meaning actual track play time = now - startedAtServerMs - 500ms... 
                    // WAIT: by the time user presses pause, startedAtServerMs is already in the past!
                    // e.g. played 10 sec: startedAtServerMs = T+500, now = T+10500
                    // now - startedAtServerMs = 10000ms  ← that IS correct, 500ms has already elapsed
                    val runTimeMs = now - startedAtServerMs
                    pauseOffsetMs += if (runTimeMs > 0) runTimeMs else 0
                    isPlaying = false
                }
                val pauseMsg = JSONObject().apply {
                    put("type", "PAUSE")
                    put("pauseOffsetMs", pauseOffsetMs)
                }
                broadcast(pauseMsg.toString())
            }
            "ACTION_SKIP_NEXT" -> {
                // Skip to the next track in the queue
                val skippedUrl = currentTrackUrl
                if (skippedUrl != null) {
                    queue.remove(skippedUrl)
                }
                trackReadyClients.remove(skippedUrl)
                trackFinishedClients.remove(skippedUrl)
                isPlaying = false
                pauseOffsetMs = 0L
                currentTrackUrl = null
                broadcastLists()
                broadcastState()

                val nextUrl = queue.firstOrNull()
                if (nextUrl != null) {
                    pendingPlayTrackUrl = nextUrl
                    trackReadyClients[nextUrl] = mutableSetOf()
                    val prepMsg = JSONObject().apply {
                        put("type", "PREPARE")
                        put("trackUrl", nextUrl)
                    }
                    broadcast(prepMsg.toString())
                    attemptPendingPlay()
                } else {
                    broadcastState()
                }
            }
            "ACTION_SKIP_PREV" -> {
                // Restart current track from the beginning
                val trackUrl = currentTrackUrl ?: return
                isPlaying = false
                pauseOffsetMs = 0L
                startedAtServerMs = 0L
                trackReadyClients[trackUrl] = mutableSetOf()
                trackFinishedClients.remove(trackUrl)

                val prepMsg = JSONObject().apply {
                    put("type", "PREPARE")
                    put("trackUrl", trackUrl)
                }
                broadcast(prepMsg.toString())
                pendingPlayTrackUrl = trackUrl
                broadcastState()
                attemptPendingPlay()
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val wasHost = conn == hostConnection
        println(if (wasHost) "Host getrennt." else "Gast getrennt.")
        if (wasHost) hostConnection = null
        participants.remove(conn)
        // Clean up from ready/finished states
        trackReadyClients.values.forEach { it.remove(conn) }
        trackFinishedClients.values.forEach { it.remove(conn) }
        broadcastLists()
        // If we were waiting for this guest to download, we might be able to start now
        attemptPendingPlay()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }
}
