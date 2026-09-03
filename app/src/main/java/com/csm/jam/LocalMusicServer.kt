package com.csm.jam

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Collections
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
    private var playActuallyStartedAtMs: Long = 0L
    // Scheduled start time broadcast to all devices (slightly in the future)
    private var startedAtServerMs: Long = 0L
    private var pauseOffsetMs: Long = 0L

    // Thread-safe queue backed by a synchronized list so all read/write ops are safe
    // across WebSocket worker threads and the UI thread.
    private val queue = Collections.synchronizedList(mutableListOf<String>())

    data class ServerParticipant(
        val id: String,
        var name: String,
        val isHost: Boolean,
        var canMegaphone: Boolean = true,
        var canControl: Boolean = false
    )
    private val participants = ConcurrentHashMap<WebSocket, ServerParticipant>()

    // Sync protocol structures
    private var pendingPlayTrackUrl: String? = null
    // ConcurrentHashMap.newKeySet() is fully thread-safe for concurrent add/remove
    private val trackReadyClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackFinishedClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackTitles = ConcurrentHashMap<String, String>()   // url -> display title
    private val trackArtworks = ConcurrentHashMap<String, String>() // url -> artwork url

    // Play history for Skip-Previous: stores URLs of tracks that finished playing
    private val playHistory = Collections.synchronizedList(mutableListOf<String>())
    private val trackAddedBy = ConcurrentHashMap<String, String>() // trackUrl -> user name
    private var sessionStartTimeMs: Long = System.currentTimeMillis()
    private var totalSongsPlayed: Int = 0

    // Permissions
    var guestsCanControl: Boolean = false
    var hostConnection: WebSocket? = null
    var isRepeatSingle: Boolean = false

    // Fallback / Autoplay Pool: round-robin over clients that have registered a fallback folder
    private val fallbackEligibleClients = Collections.synchronizedList(mutableListOf<WebSocket>())
    private var fallbackRoundRobinIndex = 0
    private var fallbackRequestPending = false

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pendingPlayTimeoutFuture: java.util.concurrent.ScheduledFuture<*>? = null

    fun getParticipantCount(): Int = participants.size
    fun getCurrentTrackTitle(): String? = currentTrackUrl?.let { trackTitles[it] }

    fun resetSession() {
        pendingPlayTimeoutFuture?.cancel(true)
        pendingPlayTimeoutFuture = null
        currentTrackUrl = null
        isPlaying = false
        playActuallyStartedAtMs = 0L
        startedAtServerMs = 0L
        pauseOffsetMs = 0L
        synchronized(queue) { queue.clear() }
        participants.clear()
        pendingPlayTrackUrl = null
        trackReadyClients.clear()
        trackFinishedClients.clear()
        trackTitles.clear()
        trackArtworks.clear()
        playHistory.clear()
        trackAddedBy.clear()
        totalSongsPlayed = 0
        sessionStartTimeMs = System.currentTimeMillis()
        guestsCanControl = false
        hostConnection = null
        isRepeatSingle = false
        fallbackEligibleClients.clear()
        fallbackRoundRobinIndex = 0
        fallbackRequestPending = false

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
        val connId = conn.remoteSocketAddress?.toString() ?: conn.hashCode().toString()
        participants[conn] = ServerParticipant(
            id = connId,
            name = if (isHost) "𓆩🜲𓆪 Host" else "Gast",
            isHost = isHost,
            canMegaphone = true,
            canControl = isHost || guestsCanControl
        )

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
            val partArr = JSONArray()
            participants.values.forEach { p ->
                partArr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("isHost", p.isHost)
                    put("canMegaphone", p.canMegaphone)
                    put("canControl", p.canControl)
                })
            }
            put("participants", partArr)

            val titlesObj = JSONObject()
            trackTitles.forEach { (url, title) -> titlesObj.put(url, title) }
            put("trackTitles", titlesObj)

            val artsObj = JSONObject()
            trackArtworks.forEach { (url, art) -> artsObj.put(url, art) }
            put("trackArtworks", artsObj)

            val addedByObj = JSONObject()
            trackAddedBy.forEach { (url, user) -> addedByObj.put(url, user) }
            put("trackAddedBy", addedByObj)

            put("sessionStartTimeMs", sessionStartTimeMs)
            put("totalSongsPlayed", totalSongsPlayed)
        }
        broadcast(listsMsg.toString())
    }

    private fun attemptPendingPlay(force: Boolean = false) {
        val trackUrl = pendingPlayTrackUrl ?: return
        val ready = trackReadyClients[trackUrl] ?: emptySet<WebSocket>()
        val hostReady = hostConnection == null || ready.contains(hostConnection)
        val allReady = participants.keys.all { ready.contains(it) }

        if (allReady || (force && hostReady)) {
            pendingPlayTimeoutFuture?.cancel(false)
            pendingPlayTimeoutFuture = null

            println("Playback triggering for $trackUrl (allReady=$allReady, force=$force)")
            pendingPlayTrackUrl = null
            currentTrackUrl = trackUrl
            isPlaying = true
            val leadTimeMs = 1000L
            val now = System.currentTimeMillis()
            startedAtServerMs = now + leadTimeMs
            playActuallyStartedAtMs = startedAtServerMs

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
            println("Waiting for $missing more clients to finish buffering...")
            // Failsafe: if clients take too long, start anyway after 2.5 seconds
            if (pendingPlayTimeoutFuture == null) {
                pendingPlayTimeoutFuture = scheduler.schedule({
                    attemptPendingPlay(force = true)
                }, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: java.nio.ByteBuffer) {
        val p = participants[conn]
        val isHost = conn == hostConnection
        if (isHost || p?.canMegaphone == true) {
            val clients = ArrayList(connections)
            clients.forEach { other ->
                if (other != conn && other.isOpen) {
                    try {
                        other.send(message.duplicate())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val serverReceiveMs = System.currentTimeMillis()
        val json = JSONObject(message)
        val type = json.getString("type")
        val isHost = conn == hostConnection
        val p = participants[conn]

        // Guest control gate: block control actions from guests when not permitted.
        val guestControlActions = setOf(
            "ACTION_PLAY", "ACTION_PAUSE", "ACTION_SKIP_NEXT", "ACTION_SKIP_PREV",
            "ACTION_ADD_TRACK", "ACTION_SET_REPEAT_SINGLE", "ACTION_SEEK",
            "ACTION_SHUFFLE", "ACTION_QUEUE_REMOVE", "ACTION_QUEUE_REORDER"
        )
        val canControl = isHost || guestsCanControl || (p?.canControl == true)
        if (!isHost && type in guestControlActions && !canControl) {
            println("Guest action '$type' blocked (canControl=false)")
            return
        }

        when (type) {
            "PING_TIME" -> {
                val clientSendNano = json.optLong("clientSendNano", 0L)
                val clientSendMs = json.optLong("clientSendMs", 0L)
                val serverTransmitMs = System.currentTimeMillis()
                val response = JSONObject().apply {
                    put("type", "PONG_TIME")
                    put("serverTimeMs", serverTransmitMs)
                    put("serverReceiveMs", serverReceiveMs)
                    put("serverTransmitMs", serverTransmitMs)
                    put("clientSendNano", clientSendNano)
                    put("clientSendMs", clientSendMs)
                }
                conn.send(response.toString())
            }
            "ACTION_JOIN" -> {
                val uname = json.optString("username", "Gast")
                val isConnHost = (conn == hostConnection)
                val connId = conn.remoteSocketAddress?.toString() ?: conn.hashCode().toString()
                val existing = participants[conn]
                participants[conn] = ServerParticipant(
                    id = connId,
                    name = if (isConnHost) "𓆩🜲𓆪 $uname" else "◇ $uname",
                    isHost = isConnHost,
                    canMegaphone = existing?.canMegaphone ?: true,
                    canControl = isConnHost || guestsCanControl
                )
                broadcastLists()
            }
            "VOICE_START" -> {
                val part = participants[conn]
                if (isHost || part?.canMegaphone == true) {
                    val speaker = json.optString("speaker", part?.name ?: "Jemand")
                    val voiceMsg = JSONObject().apply {
                        put("type", "VOICE_START")
                        put("speaker", speaker)
                    }
                    val allConns = ArrayList(connections)
                    allConns.forEach { other ->
                        if (other != conn && other.isOpen) other.send(voiceMsg.toString())
                    }
                }
            }
            "VOICE_END" -> {
                val endMsg = JSONObject().apply {
                    put("type", "VOICE_END")
                }
                val allConns = ArrayList(connections)
                allConns.forEach { other ->
                    if (other != conn && other.isOpen) other.send(endMsg.toString())
                }
            }
            "ACTION_UPDATE_PARTICIPANT_PERMISSIONS" -> {
                if (isHost) {
                    val targetId = json.getString("targetId")
                    val canMegaphone = json.optBoolean("canMegaphone", false)
                    val canControl = json.optBoolean("canControl", false)
                    val entry = participants.entries.find { it.value.id == targetId }
                    if (entry != null) {
                        entry.value.canMegaphone = canMegaphone
                        entry.value.canControl = canControl
                        val permMsg = JSONObject().apply {
                            put("type", "PARTICIPANT_PERMISSIONS_UPDATED")
                            put("canMegaphone", canMegaphone)
                            put("canControl", canControl)
                        }
                        try {
                            entry.key.send(permMsg.toString())
                        } catch (_: Exception) {}
                    }
                    broadcastLists()
                }
            }
            "ACTION_KICK_PARTICIPANT" -> {
                if (isHost) {
                    val targetId = json.getString("targetId")
                    val entry = participants.entries.find { it.value.id == targetId }
                    if (entry != null && entry.key != hostConnection) {
                        try {
                            entry.key.close(1000, "Vom Host aus der Session entfernt")
                        } catch (_: Exception) {}
                    }
                }
            }
            "ACTION_SET_PARTY_LIGHT" -> {
                if (isHost) {
                    val enabled = json.getBoolean("enabled")
                    val lightMsg = JSONObject().apply {
                        put("type", "PARTY_LIGHT_UPDATE")
                        put("enabled", enabled)
                    }
                    broadcast(lightMsg.toString())
                }
            }
            "ACTION_SET_GUEST_PERMISSIONS" -> {
                if (isHost) {
                    guestsCanControl = json.getBoolean("allow")
                    println("guestsCanControl set to $guestsCanControl by host")
                    val permMsg = JSONObject().apply {
                        put("type", "GUEST_PERMISSIONS_CHANGED")
                        put("guestsCanControl", guestsCanControl)
                    }
                    broadcast(permMsg.toString())
                }
            }
            "ACTION_SHUFFLE" -> {
                synchronized(queue) {
                    if (queue.size > 1) {
                        val currentIndex = currentTrackUrl?.let { queue.indexOf(it) } ?: -1
                        if (currentIndex >= 0 && currentIndex < queue.size - 1) {
                            val subList = queue.subList(currentIndex + 1, queue.size)
                            subList.shuffle()
                        } else if (currentIndex == -1) {
                            queue.shuffle()
                        }
                    }
                }
                broadcastLists()
            }
            "ACTION_SET_REPEAT_SINGLE" -> {
                val allow = json.getBoolean("repeat")
                isRepeatSingle = allow
                println("isRepeatSingle set to $isRepeatSingle")
                broadcastState()
            }
            "ACTION_REGISTER_FALLBACK" -> {
                // Client opts in/out of fallback-eligible pool
                val eligible = json.optBoolean("eligible", true)
                synchronized(fallbackEligibleClients) {
                    if (eligible) {
                        if (!fallbackEligibleClients.contains(conn)) {
                            fallbackEligibleClients.add(conn)
                        }
                    } else {
                        fallbackEligibleClients.remove(conn)
                    }
                    Unit
                }
                println("Fallback pool size: ${fallbackEligibleClients.size}")
            }
            "ACTION_REQUEST_STATE" -> {
                broadcastState(conn)
            }
            "ACTION_FORCE_SYNC" -> {
                if (currentTrackUrl != null && isPlaying) {
                    val currentPlayTimeMs = (System.currentTimeMillis() - playActuallyStartedAtMs).coerceAtLeast(0L)
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
                        val leadTimeMs = 700L
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
                val addedBy = json.optString("addedBy", participants[conn]?.name?.removePrefix("𓆩🜲𓆪 ")?.removePrefix("◇ ")?.trim() ?: "Jemand")
                trackAddedBy[url] = addedBy

                if (title != null) trackTitles[url] = title
                if (art != null) trackArtworks[url] = art

                synchronized(queue) { queue.add(url) }
                fallbackRequestPending = false
                broadcastLists()

                // Auto play if it's the first track and nothing is playing
                if (synchronized(queue) { queue.size } == 1 && currentTrackUrl == null) {
                    pauseOffsetMs = 0L
                    pendingPlayTrackUrl = url
                    trackReadyClients[url]?.clear()

                    val prepMsg = JSONObject().apply {
                        put("type", "PREPARE")
                        put("trackUrl", url)
                    }
                    broadcast(prepMsg.toString())
                    attemptPendingPlay()
                }
            }
            "ACTION_QUEUE_REMOVE" -> {
                val url = json.getString("trackUrl")
                val removed = synchronized(queue) { queue.remove(url) }
                if (removed) {
                    trackReadyClients.remove(url)
                    trackFinishedClients.remove(url)
                    broadcastLists()

                    if (url == pendingPlayTrackUrl) {
                        pendingPlayTimeoutFuture?.cancel(false)
                        pendingPlayTimeoutFuture = null
                        val nextUrl = synchronized(queue) { queue.firstOrNull() }
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
                synchronized(queue) {
                    if (from in queue.indices && to in queue.indices) {
                        val item = queue.removeAt(from)
                        queue.add(to, item)
                    }
                }
                broadcastLists()
            }
            "ACTION_TRACK_READY" -> {
                val url = json.getString("trackUrl")
                val clientsReady = trackReadyClients.getOrPut(url) {
                    ConcurrentHashMap.newKeySet<WebSocket>()
                }

                val isNewReady = clientsReady.add(conn)
                if (isNewReady) {
                    println("Client ${conn.remoteSocketAddress} is ready for $url")

                    if (url == pendingPlayTrackUrl) {
                        attemptPendingPlay()
                    } else if (url == currentTrackUrl && isPlaying) {
                        // Late joiner: send targeted PLAY command so they can catch up
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
                if (url != currentTrackUrl || url == pendingPlayTrackUrl) return

                val finishedClients = trackFinishedClients.getOrPut(url) {
                    ConcurrentHashMap.newKeySet<WebSocket>()
                }
                finishedClients.add(conn)
                println("Client ${conn.remoteSocketAddress} finished $url (${finishedClients.size}/${participants.size})")

                // FIX: Only advance when the HOST reports track finished.
                // If the host is not connected, fall back to "at least one client".
                // This prevents a fast guest from cutting a song short for everyone.
                val hostDone = hostConnection != null && finishedClients.contains(hostConnection)
                val allDone = participants.keys.all { finishedClients.contains(it) }
                val noHost = hostConnection == null

                if (hostDone || allDone || (noHost && finishedClients.size >= 1)) {
                    println("Advancing queue from $url")

                    if (isRepeatSingle) {
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
                        totalSongsPlayed++
                        trackFinishedClients.remove(url)
                        trackReadyClients.remove(url)
                        isPlaying = false
                        pauseOffsetMs = 0L
                        // Save to history so Skip-Previous can go back
                        synchronized(playHistory) { playHistory.add(url) }
                        synchronized(queue) { queue.remove(url) }
                        currentTrackUrl = null
                        broadcastLists()

                        val nextUrl = synchronized(queue) { queue.firstOrNull() }
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

                        // Check if fallback pool needs to supply more tracks
                        if (synchronized(queue) { queue.size } <= 1 && !fallbackRequestPending) {
                            requestFallbackFromNext()
                        }
                    }
                }
            }
            "ACTION_PLAY" -> {
                var targetUrl: String? = null
                if (json.has("trackUrl")) {
                    targetUrl = json.getString("trackUrl")
                    pauseOffsetMs = 0L
                } else if (currentTrackUrl == null && synchronized(queue) { queue.isNotEmpty() }) {
                    targetUrl = synchronized(queue) { queue.first() }
                    pauseOffsetMs = 0L
                } else if (!isPlaying && currentTrackUrl != null) {
                    targetUrl = currentTrackUrl
                }

                if (targetUrl != null) {
                    pendingPlayTimeoutFuture?.cancel(false)
                    pendingPlayTimeoutFuture = null

                    if (targetUrl == currentTrackUrl && pauseOffsetMs > 0) {
                        // Resuming from pause
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
                        // New track
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
                pendingPlayTimeoutFuture?.cancel(false)
                pendingPlayTimeoutFuture = null

                val skippedUrl = currentTrackUrl
                if (skippedUrl != null) {
                    synchronized(playHistory) { playHistory.add(skippedUrl) }
                    synchronized(queue) { queue.remove(skippedUrl) }
                }
                trackReadyClients.remove(skippedUrl)
                trackFinishedClients.remove(skippedUrl)
                isPlaying = false
                pauseOffsetMs = 0L
                currentTrackUrl = null
                broadcastLists()
                broadcastState()

                val nextUrl = synchronized(queue) { queue.firstOrNull() }
                if (nextUrl != null) {
                    pendingPlayTrackUrl = nextUrl
                    trackReadyClients[nextUrl] = ConcurrentHashMap.newKeySet()
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
                pendingPlayTimeoutFuture?.cancel(false)
                pendingPlayTimeoutFuture = null

                val currentUrl = currentTrackUrl
                val elapsedMs = if (isPlaying && playActuallyStartedAtMs > 0)
                    (System.currentTimeMillis() - playActuallyStartedAtMs + pauseOffsetMs).coerceAtLeast(0L)
                else pauseOffsetMs

                if (elapsedMs > 3000) {
                    // More than 3 s into the track: restart it from the beginning
                    if (currentUrl != null) {
                        isPlaying = false
                        pauseOffsetMs = 0L
                        startedAtServerMs = 0L
                        trackReadyClients[currentUrl] = ConcurrentHashMap.newKeySet()
                        trackFinishedClients.remove(currentUrl)

                        val prepMsg = JSONObject().apply {
                            put("type", "PREPARE")
                            put("trackUrl", currentUrl)
                        }
                        broadcast(prepMsg.toString())
                        pendingPlayTrackUrl = currentUrl
                        broadcastState()
                        attemptPendingPlay()
                    }
                } else {
                    // Within 3 s: go to previous track from history (if any), else restart
                    val prevUrl = synchronized(playHistory) {
                        if (playHistory.isNotEmpty()) playHistory.removeAt(playHistory.size - 1) else null
                    }

                    if (prevUrl != null) {
                        // Move current track back to front of queue, add prev before it
                        isPlaying = false
                        pauseOffsetMs = 0L
                        currentTrackUrl = null

                        synchronized(queue) {
                            if (currentUrl != null) queue.remove(currentUrl)
                            queue.add(0, prevUrl)
                            if (currentUrl != null) queue.add(1, currentUrl)
                        }
                        trackReadyClients.remove(prevUrl)
                        trackFinishedClients.remove(prevUrl)
                        broadcastLists()

                        pendingPlayTrackUrl = prevUrl
                        val prepMsg = JSONObject().apply {
                            put("type", "PREPARE")
                            put("trackUrl", prevUrl)
                        }
                        broadcast(prepMsg.toString())
                        broadcastState()
                        attemptPendingPlay()
                    } else if (currentUrl != null) {
                        // No history: just restart current track
                        isPlaying = false
                        pauseOffsetMs = 0L
                        startedAtServerMs = 0L
                        trackReadyClients[currentUrl] = ConcurrentHashMap.newKeySet()
                        trackFinishedClients.remove(currentUrl)

                        val prepMsg = JSONObject().apply {
                            put("type", "PREPARE")
                            put("trackUrl", currentUrl)
                        }
                        broadcast(prepMsg.toString())
                        pendingPlayTrackUrl = currentUrl
                        broadcastState()
                        attemptPendingPlay()
                    }
                }
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val wasHost = conn == hostConnection
        println(if (wasHost) "Host getrennt." else "Gast getrennt.")
        if (wasHost) hostConnection = null
        participants.remove(conn)
        trackReadyClients.values.forEach { it.remove(conn) }
        trackFinishedClients.values.forEach { it.remove(conn) }
        broadcastLists()
        // If we were waiting for this guest to download, we might be able to start now
        attemptPendingPlay()
        // Clean up from fallback-eligible pool
        fallbackEligibleClients.remove(conn)
    }

    /** Ask the next client in the round-robin fallback pool for a track. */
    private fun requestFallbackFromNext() {
        synchronized(fallbackEligibleClients) {
            if (fallbackEligibleClients.isEmpty()) return
            // Skip dead connections
            fallbackEligibleClients.removeAll { !it.isOpen }
            if (fallbackEligibleClients.isEmpty()) return
            if (fallbackRoundRobinIndex >= fallbackEligibleClients.size) fallbackRoundRobinIndex = 0
            val target = fallbackEligibleClients[fallbackRoundRobinIndex]
            fallbackRoundRobinIndex = (fallbackRoundRobinIndex + 1) % fallbackEligibleClients.size
            fallbackRequestPending = true
            val msg = JSONObject().apply { put("type", "REQUEST_FALLBACK_TRACK") }
            try { target.send(msg.toString()) } catch (_: Exception) {}
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }
}
