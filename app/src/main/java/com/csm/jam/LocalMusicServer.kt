package com.csm.jam

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LocalMusicServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        // Close reasons after which a guest must not auto-reconnect
        const val CLOSE_SESSION_ENDED = "Session ended"
        const val CLOSE_KICKED = "Vom Host aus der Session entfernt"
        const val CLOSE_DENIED = "Vom Host abgelehnt"
        const val CLOSE_NO_SESSION = "Keine aktive Session"

        // Name prefixes shown in every participant list
        const val HOST_PREFIX = "𓆩🜲𓆪 "
        const val GUEST_PREFIX = "◇ "
        const val WEB_PREFIX = "◎ "

        /** How long before a track ends the follow-up PLAY is announced (clients switch seamlessly at the end). */
        const val SEAMLESS_LEAD_MS = 1200L
        /** Lead time for manually started tracks so every device can begin at the same moment. */
        private const val START_LEAD_MS = 800L

        fun plainName(name: String): String =
            name.removePrefix(HOST_PREFIX).removePrefix(GUEST_PREFIX).removePrefix(WEB_PREFIX).trim()
    }

    init {
        isReuseAddr = true
        // Disable built-in ping/pong connection-lost detection.
        // When the host app goes to background, Android throttles CPU/network so heavily
        // that the server can't respond to WebSocket pings in time, causing guests to get kicked.
        // We use our own PING_TIME/PONG_TIME for time sync instead.
        connectionLostTimeout = 0
    }

    // All session state is mutated from several WebSocket worker threads, the scheduler
    // and the HTTP upload thread – every mutation goes through this lock.
    private val stateLock = Any()

    private var currentTrackUrl: String? = null
    private var isPlaying = false
    // Start time of the current playthrough on the session clock (JamClock), usually slightly in the future
    private var startedAtServerMs: Long = 0L
    private var pauseOffsetMs: Long = 0L

    private val queue = Collections.synchronizedList(mutableListOf<String>())

    data class ServerParticipant(
        val id: String,
        var name: String,
        val isHost: Boolean,
        var canMegaphone: Boolean = true,
        var canControl: Boolean = false,
        val isWeb: Boolean = false,
        // Proves identity for uploads and lets the guest reconnect without asking again
        val token: String
    )
    private val participants = ConcurrentHashMap<WebSocket, ServerParticipant>()

    // Only one megaphone announcement at a time – two mixed PCM streams are just noise
    private var voiceSpeaker: WebSocket? = null

    // Guests waiting until the host approves them: every browser, app guests only if the host wants that
    data class PendingJoin(
        val requestId: String,
        var name: String,
        val device: String,
        val isWeb: Boolean,
        var hasRequested: Boolean = false
    )
    private val pendingJoins = ConcurrentHashMap<WebSocket, PendingJoin>()
    // token -> display name of everybody admitted in this session; a known token skips the approval
    private val approvedTokens = ConcurrentHashMap<String, String>()
    var requireAppApproval: Boolean = false
        private set

    // Sync protocol structures
    private var pendingPlayTrackUrl: String? = null
    private val trackReadyClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackFinishedClients = ConcurrentHashMap<String, MutableSet<WebSocket>>()
    private val trackTitles = ConcurrentHashMap<String, String>()   // url -> display title
    private val trackArtworks = ConcurrentHashMap<String, String>() // url -> artwork url
    private val trackDurations = ConcurrentHashMap<String, Long>()  // url -> length in ms (reported by clients)

    // Play history for Skip-Previous: stores URLs of tracks that finished playing
    private val playHistory = Collections.synchronizedList(mutableListOf<String>())
    // Everything that was actually played, for Session Wrapped
    private val playedLog = Collections.synchronizedList(mutableListOf<Pair<String, String>>()) // title, addedBy
    private val trackAddedBy = ConcurrentHashMap<String, String>() // trackUrl -> user name
    private var sessionStartTimeMs: Long = JamClock.nowMs()
    private var totalSongsPlayed: Int = 0

    // Permissions
    var guestsCanControl: Boolean = false
    var hostConnection: WebSocket? = null
    var isRepeatSingle: Boolean = false
    /** Host option: overlap of consecutive tracks in ms (0 = gapless). Only the host can change it. */
    var crossfadeMs: Int = 0
        private set
    // Auto-calibration in progress until this session time
    private var calibrationUntilMs = 0L
    /** Click test: every device plays the same click beat so offsets can be tuned by ear. */
    var syncTestActive: Boolean = false
        private set
    @Volatile var sessionName: String = "CSM Jam"
    // False after the host left (resetSession) until the host connects again – guests are turned away meanwhile
    @Volatile private var sessionActive = false
    // After a restore: continue playing as soon as the host is back
    private var resumeAfterRestore = false

    /** Called after every state change (on the server thread) – the host app persists the session from here. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    // Fallback / Autoplay Pool: round-robin over clients that have registered a fallback folder
    private val fallbackEligibleClients = Collections.synchronizedList(mutableListOf<WebSocket>())
    private var fallbackRoundRobinIndex = 0
    private var fallbackRequestPending = false
    private var fallbackRequestTarget: WebSocket? = null
    private var fallbackTimeoutFuture: ScheduledFuture<*>? = null

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pendingPlayTimeoutFuture: ScheduledFuture<*>? = null

    // Seamless transitions
    private var autoAdvanceFuture: ScheduledFuture<*>? = null
    private var autoAdvanceKey: String? = null
    private var autoAdvanceSkippedKey: String? = null
    private var lastPreload: String? = null
    private var preloadSent = false

    fun getParticipantCount(): Int = participants.size
    fun getCurrentTrackTitle(): String? = currentTrackUrl?.let { trackTitles[it] }

    fun resetSession() {
        val toClose = synchronized(stateLock) {
            pendingPlayTimeoutFuture?.cancel(true)
            pendingPlayTimeoutFuture = null
            fallbackTimeoutFuture?.cancel(false)
            fallbackTimeoutFuture = null
            autoAdvanceFuture?.cancel(false)
            autoAdvanceFuture = null
            autoAdvanceKey = null
            autoAdvanceSkippedKey = null
            lastPreload = null
            preloadSent = false
            currentTrackUrl = null
            isPlaying = false
            startedAtServerMs = 0L
            pauseOffsetMs = 0L
            synchronized(queue) { queue.clear() }
            participants.clear()
            pendingJoins.clear()
            approvedTokens.clear()
            requireAppApproval = false
            pendingPlayTrackUrl = null
            trackReadyClients.clear()
            trackFinishedClients.clear()
            trackTitles.clear()
            trackArtworks.clear()
            trackDurations.clear()
            playHistory.clear()
            playedLog.clear()
            trackAddedBy.clear()
            totalSongsPlayed = 0
            sessionStartTimeMs = JamClock.nowMs()
            guestsCanControl = false
            hostConnection = null
            voiceSpeaker = null
            sessionActive = false
            resumeAfterRestore = false
            isRepeatSingle = false
            crossfadeMs = 0
            calibrationUntilMs = 0L
            syncTestActive = false
            fallbackEligibleClients.clear()
            fallbackRoundRobinIndex = 0
            fallbackRequestPending = false
            fallbackRequestTarget = null
            ArrayList(connections)
        }
        toClose.forEach { try { it.close(1000, CLOSE_SESSION_ENDED) } catch (_: Exception) {} }
    }

    override fun onStart() {
        println("LocalMusicServer gestartet auf Port: $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = synchronized(stateLock) {
        // The host app always connects to its own server via 127.0.0.1. Identifying the host by
        // loopback (instead of "first connection") means a reconnecting host can never be
        // replaced by a guest that happened to connect in between.
        val isLoopback = conn.remoteSocketAddress?.address?.isLoopbackAddress == true
        // Browsers always send an Origin header in the WebSocket handshake; the app client never does.
        // A browser – even one on the host phone itself – can never become host.
        val isBrowser = handshake.hasFieldValue("Origin")
        val isHost = isLoopback && !isBrowser && hostConnection?.isOpen != true

        if (!isHost && !sessionActive) {
            conn.close(1000, CLOSE_NO_SESSION)
            return@synchronized
        }

        if (!isHost && (isBrowser || requireAppApproval)) {
            if (pendingJoins.size >= 20) {
                conn.close(1008, "Zu viele offene Anfragen")
                return@synchronized
            }
            pendingJoins[conn] = PendingJoin(
                requestId = UUID.randomUUID().toString(),
                name = if (isBrowser) "Web-Gast" else "Gast",
                device = if (isBrowser) describeDevice(handshake.getFieldValue("User-Agent") ?: "") else "CSM Jam App",
                isWeb = isBrowser
            )
            println("Gast wartet auf Freigabe: ${conn.remoteSocketAddress}")
            return@synchronized
        }

        println(if (isHost) "Host verbunden: ${conn.remoteSocketAddress}" else "Neuer Gast verbunden: ${conn.remoteSocketAddress}")
        if (isHost) {
            hostConnection = conn
            sessionActive = true
        }
        val token = UUID.randomUUID().toString()
        admitParticipant(conn, if (isHost) HOST_PREFIX + "Host" else GUEST_PREFIX + "Gast", isHost = isHost, isWeb = false, token = token)
        safeSend(conn, JSONObject().put("type", "SESSION_TOKEN").put("token", token).toString())
        if (isHost) {
            sendJoinRequestsToHost()
            sendAppApprovalToHost()
            if (resumeAfterRestore) {
                resumeAfterRestore = false
                currentTrackUrl?.let { url ->
                    currentTrackUrl = null
                    prepareAndPlay(url) // keeps pauseOffsetMs → continues where the session stopped
                }
            }
        }
    }

    /** Adds a connection to the session and brings it up to date with the current playback state. */
    private fun admitParticipant(conn: WebSocket, name: String, isHost: Boolean, isWeb: Boolean, token: String) {
        val connId = conn.remoteSocketAddress?.toString() ?: conn.hashCode().toString()
        val participant = ServerParticipant(
            id = connId,
            name = name,
            isHost = isHost,
            // Browsers can't record over plain http (getUserMedia needs https), so no megaphone for web guests
            canMegaphone = !isWeb,
            // Personal Co-DJ right only; the global "guests may control" switch is checked separately
            canControl = isHost,
            isWeb = isWeb,
            token = token
        )
        participants[conn] = participant
        approvedTokens[token] = plainName(name)

        // If there's a track currently playing or pending, force this new guest to PREPARE it immediately
        val trackToPrep = pendingPlayTrackUrl ?: currentTrackUrl
        if (trackToPrep != null) {
            safeSend(conn, JSONObject().apply {
                put("type", "PREPARE")
                put("trackUrl", trackToPrep)
            }.toString())
        }

        sendPermissions(conn, participant)
        safeSend(conn, JSONObject().put("type", "CROSSFADE_CHANGED").put("ms", crossfadeMs).toString())
        if (syncTestActive) safeSend(conn, JSONObject().put("type", "SYNC_TEST").put("enabled", true).toString())
        broadcastState(conn)
        syncPreload(conn)
        broadcastLists()
    }

    private fun sendPermissions(conn: WebSocket, p: ServerParticipant) {
        safeSend(conn, JSONObject().apply {
            put("type", "GUEST_PERMISSIONS_CHANGED")
            put("guestsCanControl", guestsCanControl)
        }.toString())
        safeSend(conn, JSONObject().apply {
            put("type", "PARTICIPANT_PERMISSIONS_UPDATED")
            put("canMegaphone", p.canMegaphone)
            put("canControl", p.isHost || p.canControl)
        }.toString())
    }

    private fun safeSend(conn: WebSocket, text: String) {
        try {
            if (conn.isOpen) conn.send(text)
        } catch (_: Exception) {}
    }

    /** Sends to every admitted participant – pending guests never receive session data. */
    private fun sendToMembers(text: String, except: WebSocket? = null) {
        participants.keys.toList().forEach { if (it != except) safeSend(it, text) }
    }

    private fun broadcastState(target: WebSocket? = null) {
        val stateMsg = JSONObject().apply {
            put("type", "STATE_UPDATE")
            put("isPlaying", isPlaying)
            put("trackUrl", currentTrackUrl ?: JSONObject.NULL)
            put("startedAtMs", startedAtServerMs)
            put("pauseOffsetMs", pauseOffsetMs)
            put("isRepeatSingle", isRepeatSingle)
        }
        if (target != null) {
            safeSend(target, stateMsg.toString())
        } else {
            sendToMembers(stateMsg.toString())
            syncPreload()
            notifyChanged()
        }
        scheduleAutoAdvance()
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
                    put("isWeb", p.isWeb)
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

            put("playedLog", playedLogJson())
            put("sessionStartTimeMs", sessionStartTimeMs)
            put("totalSongsPlayed", totalSongsPlayed)
            put("sessionName", sessionName)
        }
        sendToMembers(listsMsg.toString())
        syncPreload()
        notifyChanged()
    }

    private fun playedLogJson() = JSONArray().apply {
        synchronized(playedLog) {
            playedLog.forEach { (title, by) -> put(JSONObject().put("title", title).put("by", by)) }
        }
    }

    private fun notifyChanged() {
        try { onStateChanged?.invoke() } catch (_: Exception) {}
    }

    private fun sendJoinRequestsToHost() {
        val host = hostConnection ?: return
        val arr = JSONArray()
        pendingJoins.values.filter { it.hasRequested }.forEach { pj ->
            arr.put(JSONObject().apply {
                put("requestId", pj.requestId)
                put("name", pj.name)
                put("device", pj.device)
                put("isWeb", pj.isWeb)
            })
        }
        safeSend(host, JSONObject().apply {
            put("type", "JOIN_REQUESTS")
            put("requests", arr)
        }.toString())
    }

    private fun sendAppApprovalToHost() {
        val host = hostConnection ?: return
        safeSend(host, JSONObject().put("type", "APP_APPROVAL_CHANGED").put("enabled", requireAppApproval).toString())
    }

    private fun describeDevice(userAgent: String): String {
        val ua = userAgent.lowercase()
        val os = when {
            "iphone" in ua -> "iPhone"
            "ipad" in ua -> "iPad"
            "android" in ua -> "Android"
            "mac os" in ua -> "Mac"
            "windows" in ua -> "Windows"
            "linux" in ua -> "Linux"
            else -> "Gerät"
        }
        val browser = when {
            "crios" in ua || ("chrome" in ua && "edg" !in ua) -> "Chrome"
            "fxios" in ua || "firefox" in ua -> "Firefox"
            "edg" in ua -> "Edge"
            "safari" in ua -> "Safari"
            else -> "Browser"
        }
        return "$os · $browser"
    }

    private fun sanitizeName(raw: String): String =
        raw.replace(Regex("[\\p{Cntrl}]"), "").trim().take(32).ifBlank { "Gast" }

    private fun sendPrepare(url: String) {
        sendToMembers(JSONObject().apply {
            put("type", "PREPARE")
            put("trackUrl", url)
        }.toString())
    }

    private fun sendPlay(target: WebSocket? = null, crossfade: Int = 0, fromTrackUrl: String? = null) {
        val playMsg = JSONObject().apply {
            put("type", "PLAY")
            put("trackUrl", currentTrackUrl)
            put("startedAtMs", startedAtServerMs)
            put("pauseOffsetMs", pauseOffsetMs)
            if (crossfade > 0 && fromTrackUrl != null) {
                // The next track starts while [fromTrackUrl] is still playing; clients fade over [crossfade] ms
                put("crossfadeMs", crossfade)
                put("fromTrackUrl", fromTrackUrl)
            }
        }.toString()
        if (target != null) safeSend(target, playMsg) else sendToMembers(playMsg)
    }

    // ── Seamless playback ────────────────────────────────────────────────

    /** The track that follows the current (or currently preparing) one. */
    private fun nextUrl(): String? = synchronized(queue) {
        val ref = currentTrackUrl ?: pendingPlayTrackUrl
        val idx = if (ref != null) queue.indexOf(ref) else -1
        if (idx >= 0) queue.getOrNull(idx + 1) else queue.firstOrNull { it != ref }
    }

    /** Tells every client which track comes next so it is fully buffered before the current one ends. */
    private fun syncPreload(target: WebSocket? = null) {
        val next = if (isRepeatSingle || (currentTrackUrl == null && pendingPlayTrackUrl == null)) null else nextUrl()
        val msg = JSONObject().put("type", "PRELOAD").put("trackUrl", next ?: JSONObject.NULL).toString()
        if (target != null) {
            safeSend(target, msg)
            return
        }
        if (preloadSent && next == lastPreload) return
        preloadSent = true
        lastPreload = next
        sendToMembers(msg)
    }

    /**
     * When the track length is known, the server announces the follow-up track shortly before
     * the end with startedAtMs = exact end time. Clients have it preloaded and switch gaplessly.
     */
    private fun scheduleAutoAdvance() {
        val url = currentTrackUrl
        val duration = url?.let { trackDurations[it] } ?: 0L
        val key = if (url != null && isPlaying && pendingPlayTrackUrl == null && duration > 0)
            "$url|$startedAtServerMs|$pauseOffsetMs|$duration|$isRepeatSingle|$crossfadeMs" else null
        if (key == autoAdvanceKey) return
        autoAdvanceFuture?.cancel(false)
        autoAdvanceFuture = null
        autoAdvanceKey = key
        if (key == null || key == autoAdvanceSkippedKey) return

        val endAt = startedAtServerMs + (duration - pauseOffsetMs)
        // With crossfade the next track starts before this one ends (never more than a third of the song)
        val fade = if (isRepeatSingle) 0 else minOf(crossfadeMs.toLong(), duration / 3).toInt()
        val nextStart = endAt - fade
        val fireIn = (nextStart - SEAMLESS_LEAD_MS - JamClock.nowMs()).coerceAtLeast(0L)
        autoAdvanceFuture = scheduler.schedule({
            synchronized(stateLock) {
                if (autoAdvanceKey == key) {
                    autoAdvanceKey = null
                    if (!seamlessAdvance(url!!, nextStart, fade)) autoAdvanceSkippedKey = key
                }
            }
        }, fireIn, TimeUnit.MILLISECONDS)
    }

    /** Returns false if there is nothing to continue with (the normal end-of-track path takes over). */
    private fun seamlessAdvance(url: String, endAt: Long, fade: Int = 0): Boolean {
        if (currentTrackUrl != url || !isPlaying) return false
        if (isRepeatSingle) {
            pauseOffsetMs = 0L
            startedAtServerMs = endAt
            trackFinishedClients.remove(url)
            sendPlay()
            broadcastState()
            return true
        }
        val next = nextUrl() ?: return false
        logPlayed(url)
        synchronized(playHistory) { playHistory.add(url) }
        synchronized(queue) { queue.remove(url) }
        trackFinishedClients.remove(url)
        trackReadyClients.remove(url)

        currentTrackUrl = next
        pendingPlayTrackUrl = null
        pauseOffsetMs = 0L
        startedAtServerMs = endAt
        isPlaying = true
        println("Seamless transition $url → $next at $endAt (crossfade $fade ms)")
        sendPlay(crossfade = fade, fromTrackUrl = url)
        broadcastLists()
        broadcastState()
        maybeRequestFallback()
        return true
    }

    private fun logPlayed(url: String) {
        totalSongsPlayed++
        val title = trackTitles[url] ?: url.substringAfterLast('/').substringBeforeLast('.')
        synchronized(playedLog) {
            playedLog.add(title to (trackAddedBy[url] ?: ""))
            if (playedLog.size > 300) playedLog.removeAt(0)
        }
    }

    private fun attemptPendingPlay(force: Boolean = false) {
        val trackUrl = pendingPlayTrackUrl ?: return
        val ready = trackReadyClients[trackUrl] ?: emptySet<WebSocket>()
        val hostReady = hostConnection == null || ready.contains(hostConnection)
        val allReady = participants.keys.all { ready.contains(it) }

        if (allReady || (force && hostReady) || (force && participants.isEmpty())) {
            pendingPlayTimeoutFuture?.cancel(false)
            pendingPlayTimeoutFuture = null

            println("Playback triggering for $trackUrl (allReady=$allReady, force=$force)")
            pendingPlayTrackUrl = null
            currentTrackUrl = trackUrl
            isPlaying = true
            startedAtServerMs = JamClock.nowMs() + START_LEAD_MS

            sendPlay()
            broadcastState()
        } else {
            val missing = participants.size - ready.size
            println("Waiting for $missing more clients to finish buffering...")
            // Failsafe: if clients take too long, start anyway after 2.5 seconds.
            // If even the host is not ready by then, retry again instead of stalling forever.
            if (pendingPlayTimeoutFuture == null) {
                pendingPlayTimeoutFuture = scheduler.schedule({
                    synchronized(stateLock) {
                        pendingPlayTimeoutFuture = null
                        attemptPendingPlay(force = true)
                    }
                }, 2500, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Starts preparing [url] for everybody; playback begins once all clients report ready. */
    private fun prepareAndPlay(url: String) {
        pendingPlayTimeoutFuture?.cancel(false)
        pendingPlayTimeoutFuture = null
        pendingPlayTrackUrl = url
        trackReadyClients[url] = ConcurrentHashMap.newKeySet()
        trackFinishedClients.remove(url)
        sendPrepare(url)
        syncPreload()
        attemptPendingPlay()
    }

    /** Current playback position of the running track in ms (session clock). */
    private fun currentPositionMs(): Long =
        if (isPlaying && startedAtServerMs > 0L)
            (pauseOffsetMs + (JamClock.nowMs() - startedAtServerMs)).coerceAtLeast(0L)
        else pauseOffsetMs

    override fun onMessage(conn: WebSocket, message: java.nio.ByteBuffer) {
        val p = participants[conn] ?: return
        if (conn == voiceSpeaker && (p.isHost || p.canMegaphone)) {
            participants.keys.toList().forEach { other ->
                if (other != conn && other.isOpen) {
                    try {
                        other.send(message.duplicate())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val serverReceiveMs = JamClock.nowMs()
        val json = try { JSONObject(message) } catch (_: Exception) { return }
        val type = json.optString("type")

        // Time sync must not wait for the lock – it would distort the measured round trip.
        if (type == "PING_TIME") {
            val serverTransmitMs = JamClock.nowMs()
            val response = JSONObject().apply {
                put("type", "PONG_TIME")
                put("serverTimeMs", serverTransmitMs)
                put("serverReceiveMs", serverReceiveMs)
                put("serverTransmitMs", serverTransmitMs)
                put("clientSendNano", json.optLong("clientSendNano", 0L))
                put("clientSendMs", json.optLong("clientSendMs", 0L))
            }
            safeSend(conn, response.toString())
            return
        }

        synchronized(stateLock) { handleMessage(conn, type, json) }
    }

    private fun handleMessage(conn: WebSocket, type: String, json: JSONObject) {
        // Guests waiting for approval may only introduce themselves.
        val pending = pendingJoins[conn]
        if (pending != null) {
            if (type == "ACTION_JOIN") handlePendingJoin(conn, pending, json)
            return
        }

        val p = participants[conn] ?: return
        val isHost = conn == hostConnection

        // Guest control gate: block control actions from guests when not permitted.
        val guestControlActions = setOf(
            "ACTION_PLAY", "ACTION_PAUSE", "ACTION_SKIP_NEXT", "ACTION_SKIP_PREV",
            "ACTION_ADD_TRACK", "ACTION_SET_REPEAT_SINGLE", "ACTION_SEEK",
            "ACTION_SHUFFLE", "ACTION_QUEUE_REMOVE", "ACTION_QUEUE_REORDER"
        )
        val canControl = isHost || guestsCanControl || p.canControl
        if (!isHost && type in guestControlActions && !canControl) {
            println("Guest action '$type' blocked (canControl=false)")
            return
        }

        when (type) {
            "ACTION_JOIN" -> {
                val uname = sanitizeName(json.optString("username", "Gast"))
                p.name = when {
                    isHost -> HOST_PREFIX + uname
                    p.isWeb -> WEB_PREFIX + uname
                    else -> GUEST_PREFIX + uname
                }
                approvedTokens[p.token] = uname
                broadcastLists()
            }
            "ACTION_RESPOND_JOIN" -> {
                if (isHost) {
                    val requestId = json.optString("requestId")
                    val accept = json.optBoolean("accept", false)
                    val entry = pendingJoins.entries.find { it.value.requestId == requestId }
                    if (entry != null) {
                        val (target, pj) = entry
                        pendingJoins.remove(target)
                        if (accept) {
                            approveAndAdmit(target, pj.name, pj.isWeb, UUID.randomUUID().toString())
                        } else {
                            safeSend(target, JSONObject().put("type", "JOIN_DENIED").toString())
                            try { target.close(1000, CLOSE_DENIED) } catch (_: Exception) {}
                        }
                    }
                    sendJoinRequestsToHost()
                }
            }
            "ACTION_SET_CROSSFADE" -> {
                if (isHost) {
                    crossfadeMs = json.optInt("ms", 0).coerceIn(0, 12_000)
                    sendToMembers(JSONObject().put("type", "CROSSFADE_CHANGED").put("ms", crossfadeMs).toString())
                    scheduleAutoAdvance()
                    notifyChanged()
                }
            }
            "ACTION_SYNC_TEST" -> {
                if (isHost) {
                    syncTestActive = json.optBoolean("enabled", false)
                    sendToMembers(JSONObject().put("type", "SYNC_TEST").put("enabled", syncTestActive).toString())
                }
            }
            "ACTION_CALIBRATION_START" -> {
                if (isHost && JamClock.nowMs() > calibrationUntilMs) startCalibration()
            }
            "ACTION_CALIBRATION_RESULT" -> {
                if (isHost) {
                    val results = json.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until results.length()) {
                        val r = results.getJSONObject(i)
                        val target = participants.entries.find { it.value.id == r.optString("id") } ?: continue
                        if (target.key == hostConnection || r.isNull("deltaMs")) continue
                        safeSend(target.key, JSONObject().put("type", "CALIBRATION_APPLY")
                            .put("deltaMs", r.getLong("deltaMs").coerceIn(-1500L, 1500L)).toString())
                    }
                    calibrationUntilMs = 0L
                }
            }
            "ACTION_SET_APP_APPROVAL" -> {
                if (isHost) {
                    requireAppApproval = json.optBoolean("enabled", false)
                    sendAppApprovalToHost()
                    notifyChanged()
                }
            }
            "VOICE_START" -> {
                if (isHost || p.canMegaphone) {
                    val current = voiceSpeaker
                    if (current != null && current != conn && current.isOpen) {
                        safeSend(conn, JSONObject().apply {
                            put("type", "VOICE_BUSY")
                            put("speaker", participants[current]?.name?.let { plainName(it) } ?: "Jemand")
                        }.toString())
                        return
                    }
                    voiceSpeaker = conn
                    sendToMembers(JSONObject().apply {
                        put("type", "VOICE_START")
                        put("speaker", json.optString("speaker", plainName(p.name)))
                    }.toString(), except = conn)
                }
            }
            "VOICE_END" -> {
                if (conn == voiceSpeaker) endVoice()
            }
            "ACTION_UPDATE_PARTICIPANT_PERMISSIONS" -> {
                if (isHost) {
                    val targetId = json.getString("targetId")
                    val entry = participants.entries.find { it.value.id == targetId }
                    if (entry != null) {
                        // Web guests can't transmit voice, never grant them the megaphone
                        entry.value.canMegaphone = json.optBoolean("canMegaphone", false) && !entry.value.isWeb
                        entry.value.canControl = json.optBoolean("canControl", false)
                        if (!entry.value.canMegaphone && entry.key == voiceSpeaker) endVoice()
                        sendPermissions(entry.key, entry.value)
                    }
                    broadcastLists()
                }
            }
            "ACTION_KICK_PARTICIPANT" -> {
                if (isHost) {
                    val targetId = json.getString("targetId")
                    val entry = participants.entries.find { it.value.id == targetId }
                    if (entry != null && entry.key != hostConnection) {
                        // A kicked guest must ask again – its reconnect token is revoked
                        approvedTokens.remove(entry.value.token)
                        safeSend(entry.key, JSONObject().put("type", "KICKED").toString())
                        try {
                            entry.key.close(1000, CLOSE_KICKED)
                        } catch (_: Exception) {}
                    }
                }
            }
            "ACTION_SET_PARTY_LIGHT" -> {
                if (isHost) {
                    sendToMembers(JSONObject().apply {
                        put("type", "PARTY_LIGHT_UPDATE")
                        put("enabled", json.getBoolean("enabled"))
                    }.toString())
                }
            }
            "ACTION_SET_GUEST_PERMISSIONS" -> {
                if (isHost) {
                    guestsCanControl = json.getBoolean("allow")
                    println("guestsCanControl set to $guestsCanControl by host")
                    sendToMembers(JSONObject().apply {
                        put("type", "GUEST_PERMISSIONS_CHANGED")
                        put("guestsCanControl", guestsCanControl)
                    }.toString())
                    notifyChanged()
                }
            }
            "ACTION_SHUFFLE" -> {
                synchronized(queue) {
                    if (queue.size > 1) {
                        val currentIndex = currentTrackUrl?.let { queue.indexOf(it) } ?: -1
                        if (currentIndex >= 0 && currentIndex < queue.size - 1) {
                            queue.subList(currentIndex + 1, queue.size).shuffle()
                        } else if (currentIndex == -1) {
                            queue.shuffle()
                        }
                    }
                }
                broadcastLists()
            }
            "ACTION_SET_REPEAT_SINGLE" -> {
                isRepeatSingle = json.getBoolean("repeat")
                println("isRepeatSingle set to $isRepeatSingle")
                broadcastState()
            }
            "ACTION_REGISTER_FALLBACK" -> {
                // Client opts in/out of fallback-eligible pool
                val eligible = json.optBoolean("eligible", true)
                synchronized(fallbackEligibleClients) {
                    if (eligible) {
                        if (!fallbackEligibleClients.contains(conn)) fallbackEligibleClients.add(conn)
                    } else {
                        fallbackEligibleClients.remove(conn)
                    }
                    Unit
                }
                println("Fallback pool size: ${fallbackEligibleClients.size}")
                // Queue (nearly) empty? Start autoplay right away.
                if (eligible) maybeRequestFallback()
            }
            "ACTION_REQUEST_STATE" -> {
                broadcastState(conn)
                syncPreload(conn)
            }
            "ACTION_FORCE_SYNC" -> {
                if (currentTrackUrl != null && isPlaying) {
                    pauseOffsetMs = currentPositionMs()
                    startedAtServerMs = JamClock.nowMs() + 500L
                    sendPlay()
                    broadcastState()
                    println("Server forced synchronization for all clients at offset $pauseOffsetMs ms")
                }
            }
            "ACTION_SEEK" -> {
                val seekDst = json.getLong("seekToMs").coerceAtLeast(0L)
                if (currentTrackUrl != null) {
                    pauseOffsetMs = seekDst
                    if (isPlaying) {
                        startedAtServerMs = JamClock.nowMs() + 600L
                        sendPlay()
                    } else {
                        sendToMembers(JSONObject().apply {
                            put("type", "PAUSE")
                            put("pauseOffsetMs", pauseOffsetMs)
                        }.toString())
                    }
                    broadcastState()
                }
            }
            "ACTION_ADD_TRACK" -> {
                val url = json.getString("trackUrl")
                val title = if (json.has("trackTitle")) json.getString("trackTitle") else null
                val art = if (json.has("trackArtwork")) json.getString("trackArtwork") else null
                val addedBy = json.optString("addedBy", "").ifBlank { plainName(p.name) }
                addTrackLocked(url, title, art, addedBy)
            }
            "ACTION_QUEUE_REMOVE" -> {
                val url = json.getString("trackUrl")
                if (url == currentTrackUrl) return // the playing track is skipped, not removed
                val removed = synchronized(queue) { queue.remove(url) }
                if (removed) {
                    trackReadyClients.remove(url)
                    trackFinishedClients.remove(url)
                    broadcastLists()

                    if (url == pendingPlayTrackUrl) {
                        val nextUrl = synchronized(queue) { queue.firstOrNull() }
                        if (nextUrl != null) {
                            prepareAndPlay(nextUrl)
                        } else {
                            pendingPlayTimeoutFuture?.cancel(false)
                            pendingPlayTimeoutFuture = null
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
                val durationMs = json.optLong("durationMs", 0L)
                if (durationMs > 0L && (p.isHost || !trackDurations.containsKey(url))) {
                    // The host's decoder is the reference; guests only fill in if the host hasn't reported yet
                    trackDurations[url] = durationMs
                    scheduleAutoAdvance()
                }
                val clientsReady = trackReadyClients.getOrPut(url) {
                    ConcurrentHashMap.newKeySet<WebSocket>()
                }

                if (clientsReady.add(conn)) {
                    if (url == pendingPlayTrackUrl) {
                        attemptPendingPlay()
                    } else if (url == currentTrackUrl && isPlaying) {
                        // Late joiner: send targeted PLAY command so they can catch up
                        sendPlay(conn)
                    }
                }
            }
            "ACTION_TRACK_FINISHED" -> {
                val url = json.getString("trackUrl")
                if (url != currentTrackUrl || url == pendingPlayTrackUrl) return
                // This playthrough only just began (seamless repeat) – the report belongs to the previous loop
                if (isPlaying && pauseOffsetMs == 0L && JamClock.nowMs() - startedAtServerMs < 4000L) return

                val finishedClients = trackFinishedClients.getOrPut(url) {
                    ConcurrentHashMap.newKeySet<WebSocket>()
                }
                finishedClients.add(conn)

                // Only advance when the HOST reports track finished.
                // If the host is not connected, fall back to "at least one client".
                // This prevents a fast guest from cutting a song short for everyone.
                val hostDone = hostConnection != null && finishedClients.contains(hostConnection)
                val allDone = participants.keys.all { finishedClients.contains(it) }
                val noHost = hostConnection == null

                if (hostDone || allDone || noHost) {
                    println("Advancing queue from $url")

                    if (isRepeatSingle) {
                        isPlaying = false
                        pauseOffsetMs = 0L
                        startedAtServerMs = 0L
                        prepareAndPlay(url)
                    } else {
                        logPlayed(url)
                        trackFinishedClients.remove(url)
                        trackReadyClients.remove(url)
                        // Save to history so Skip-Previous can go back
                        synchronized(playHistory) { playHistory.add(url) }
                        synchronized(queue) { queue.remove(url) }
                        advanceToNextTrack()
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
                    if (targetUrl == currentTrackUrl && pauseOffsetMs > 0) {
                        // Resuming from pause
                        pendingPlayTimeoutFuture?.cancel(false)
                        pendingPlayTimeoutFuture = null
                        isPlaying = true
                        startedAtServerMs = JamClock.nowMs() + 500L
                        sendPlay()
                        broadcastState()
                    } else {
                        prepareAndPlay(targetUrl)
                    }
                }
            }
            "ACTION_PAUSE" -> {
                if (isPlaying) {
                    pauseOffsetMs = currentPositionMs()
                    isPlaying = false
                }
                sendToMembers(JSONObject().apply {
                    put("type", "PAUSE")
                    put("pauseOffsetMs", pauseOffsetMs)
                }.toString())
                broadcastState()
            }
            "ACTION_SKIP_NEXT" -> {
                val skippedUrl = currentTrackUrl ?: pendingPlayTrackUrl
                if (skippedUrl != null) {
                    // Counts as played if it ran for a while
                    if (skippedUrl == currentTrackUrl && currentPositionMs() > 30_000) logPlayed(skippedUrl)
                    synchronized(playHistory) { playHistory.add(skippedUrl) }
                    synchronized(queue) { queue.remove(skippedUrl) }
                    trackReadyClients.remove(skippedUrl)
                    trackFinishedClients.remove(skippedUrl)
                }
                advanceToNextTrack()
            }
            "ACTION_SKIP_PREV" -> {
                val currentUrl = currentTrackUrl
                if (currentPositionMs() > 3000 || synchronized(playHistory) { playHistory.isEmpty() }) {
                    // More than 3 s into the track (or no history): restart it from the beginning
                    if (currentUrl != null) {
                        isPlaying = false
                        pauseOffsetMs = 0L
                        startedAtServerMs = 0L
                        broadcastState()
                        prepareAndPlay(currentUrl)
                    }
                } else {
                    // Within 3 s: go to previous track from history
                    val prevUrl = synchronized(playHistory) { playHistory.removeAt(playHistory.size - 1) }
                    isPlaying = false
                    pauseOffsetMs = 0L
                    currentTrackUrl = null

                    synchronized(queue) {
                        if (currentUrl != null) queue.remove(currentUrl)
                        queue.remove(prevUrl)
                        queue.add(0, prevUrl)
                        if (currentUrl != null) queue.add(1, currentUrl)
                    }
                    trackReadyClients.remove(prevUrl)
                    broadcastLists()
                    broadcastState()
                    prepareAndPlay(prevUrl)
                }
            }
        }
    }

    /**
     * Every device plays two chirps in turn (host first) while the host phone records.
     * Music is muted everywhere for the duration so the host mic hears only the chirps.
     */
    private fun startCalibration() {
        val members = participants.entries.sortedBy { if (it.key == hostConnection) 0 else 1 }
        if (members.isEmpty()) return
        val spacing = 1000L
        val rounds = 2
        val first = JamClock.nowMs() + 3000L
        val slots = JSONArray()
        val timesByConn = HashMap<WebSocket, JSONArray>()
        for (round in 0 until rounds) {
            members.forEachIndexed { k, (conn, p) ->
                val at = first + (round * members.size + k) * spacing
                timesByConn.getOrPut(conn) { JSONArray() }.put(at)
                slots.put(JSONObject().put("id", p.id).put("name", plainName(p.name)).put("atMs", at).put("isHost", p.isHost))
            }
        }
        calibrationUntilMs = first + rounds * members.size * spacing + 900L
        sendToMembers(JSONObject().put("type", "CALIBRATION_START").put("untilMs", calibrationUntilMs).toString())
        timesByConn.forEach { (conn, times) ->
            safeSend(conn, JSONObject().put("type", "CALIBRATION_CHIRP").put("times", times).toString())
        }
        hostConnection?.let {
            safeSend(it, JSONObject().put("type", "CALIBRATION_PLAN").put("slots", slots).put("untilMs", calibrationUntilMs).toString())
        }
    }

    private fun endVoice() {
        val speaker = voiceSpeaker
        voiceSpeaker = null
        sendToMembers(JSONObject().put("type", "VOICE_END").toString(), except = speaker)
    }

    private fun approveAndAdmit(conn: WebSocket, name: String, isWeb: Boolean, token: String) {
        safeSend(conn, JSONObject().apply {
            put("type", "JOIN_APPROVED")
            put("token", token)
        }.toString())
        admitParticipant(conn, (if (isWeb) WEB_PREFIX else GUEST_PREFIX) + name, isHost = false, isWeb = isWeb, token = token)
    }

    private fun handlePendingJoin(conn: WebSocket, pending: PendingJoin, json: JSONObject) {
        val name = sanitizeName(json.optString("username", pending.name))
        val token = json.optString("token", "")
        // Previously admitted guest (screen lock, wifi hiccup, app restart): let it straight back in
        if (token.isNotBlank() && approvedTokens.containsKey(token)) {
            pendingJoins.remove(conn)
            approveAndAdmit(conn, name, pending.isWeb, token)
            sendJoinRequestsToHost()
            return
        }
        pending.name = name
        pending.hasRequested = true
        safeSend(conn, JSONObject().apply {
            put("type", "JOIN_PENDING")
            put("sessionName", sessionName)
        }.toString())
        sendJoinRequestsToHost()
    }

    private fun addTrackLocked(url: String, title: String?, art: String?, addedBy: String) {
        trackAddedBy[url] = addedBy
        if (title != null) trackTitles[url] = title
        if (art != null) trackArtworks[url] = art

        synchronized(queue) { queue.add(url) }
        fallbackRequestPending = false
        fallbackRequestTarget = null
        fallbackTimeoutFuture?.cancel(false)
        fallbackTimeoutFuture = null
        // A new follow-up track may make the end of the current one seamless again
        autoAdvanceSkippedKey = null
        broadcastLists()
        scheduleAutoAdvance()

        // Auto play if nothing is playing or being prepared
        if (currentTrackUrl == null && pendingPlayTrackUrl == null) {
            pauseOffsetMs = 0L
            prepareAndPlay(synchronized(queue) { queue.first() })
        }
    }

    /** Used by the HTTP server for uploaded songs (browser guests and app guests). */
    fun addTrack(url: String, title: String?, art: String?, addedBy: String) = synchronized(stateLock) {
        addTrackLocked(url, title, art, addedBy)
    }

    /**
     * Returns the display name of the participant owning [token] if they may add songs, else null.
     * [autoplay] uploads are allowed for whoever the server just asked for an autoplay track.
     */
    fun uploaderName(token: String, autoplay: Boolean = false): String? = synchronized(stateLock) {
        val entry = participants.entries.find { it.value.token == token } ?: return@synchronized null
        val p = entry.value
        val allowed = p.isHost || guestsCanControl || p.canControl ||
            (autoplay && fallbackRequestPending && fallbackRequestTarget == entry.key)
        if (allowed) plainName(p.name) else null
    }

    /** Moves on to the first queued track (the finished/skipped one must already be removed). */
    private fun advanceToNextTrack() {
        isPlaying = false
        pauseOffsetMs = 0L
        startedAtServerMs = 0L
        currentTrackUrl = null
        pendingPlayTrackUrl = null
        broadcastLists()

        val nextUrl = synchronized(queue) { queue.firstOrNull() }
        if (nextUrl != null) {
            prepareAndPlay(nextUrl)
        } else {
            pendingPlayTimeoutFuture?.cancel(false)
            pendingPlayTimeoutFuture = null
            broadcastState()
        }
        maybeRequestFallback()
    }

    private fun maybeRequestFallback() {
        if (synchronized(queue) { queue.size } <= 1 && !fallbackRequestPending) {
            requestFallbackFromNext()
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = synchronized(stateLock) {
        if (pendingJoins.remove(conn) != null) {
            sendJoinRequestsToHost()
            return@synchronized
        }
        val wasHost = conn == hostConnection
        println(if (wasHost) "Host getrennt." else "Gast getrennt.")
        // Speaker vanished mid-announcement: end it for everyone, otherwise their music stays ducked
        if (conn == voiceSpeaker) endVoice()
        if (wasHost) hostConnection = null
        if (participants.remove(conn) == null) return@synchronized
        trackReadyClients.values.forEach { it.remove(conn) }
        trackFinishedClients.values.forEach { it.remove(conn) }
        fallbackEligibleClients.remove(conn)
        broadcastLists()
        // If we were waiting for this guest to download, we might be able to start now
        attemptPendingPlay()
    }

    /** Ask the next client in the round-robin fallback pool for a track. */
    private fun requestFallbackFromNext(attemptsLeft: Int = fallbackEligibleClients.size) {
        synchronized(fallbackEligibleClients) {
            // Skip dead connections
            fallbackEligibleClients.removeAll { !it.isOpen }
            if (fallbackEligibleClients.isEmpty() || attemptsLeft <= 0) {
                fallbackRequestPending = false
                fallbackRequestTarget = null
                return
            }
            if (fallbackRoundRobinIndex >= fallbackEligibleClients.size) fallbackRoundRobinIndex = 0
            val target = fallbackEligibleClients[fallbackRoundRobinIndex]
            fallbackRoundRobinIndex = (fallbackRoundRobinIndex + 1) % fallbackEligibleClients.size
            fallbackRequestPending = true
            fallbackRequestTarget = target
            safeSend(target, JSONObject().put("type", "REQUEST_FALLBACK_TRACK").toString())
        }
        // If that client never answers (empty folder, app in background, slow upload…), ask the next one
        fallbackTimeoutFuture?.cancel(false)
        fallbackTimeoutFuture = scheduler.schedule({
            synchronized(stateLock) {
                if (fallbackRequestPending) {
                    fallbackRequestPending = false
                    fallbackRequestTarget = null
                    requestFallbackFromNext(attemptsLeft - 1)
                }
            }
        }, 25, TimeUnit.SECONDS)
    }

    // ── Persistence (host app crash / kill → resume within 10 minutes) ─────

    /** Everything needed to continue the session after the host app was killed. */
    fun snapshot(): JSONObject = synchronized(stateLock) {
        JSONObject().apply {
            put("queue", synchronized(queue) { JSONArray(queue) })
            put("titles", JSONObject(trackTitles as Map<*, *>))
            put("artworks", JSONObject(trackArtworks as Map<*, *>))
            put("addedBy", JSONObject(trackAddedBy as Map<*, *>))
            put("durations", JSONObject(trackDurations as Map<*, *>))
            put("history", synchronized(playHistory) { JSONArray(playHistory) })
            put("playedLog", playedLogJson())
            put("totalSongsPlayed", totalSongsPlayed)
            put("sessionStartTimeMs", sessionStartTimeMs)
            put("guestsCanControl", guestsCanControl)
            put("requireAppApproval", requireAppApproval)
            put("isRepeatSingle", isRepeatSingle)
            put("crossfadeMs", crossfadeMs)
            put("sessionName", sessionName)
            put("tokens", JSONObject(approvedTokens as Map<*, *>))
            put("currentTrackUrl", (currentTrackUrl ?: pendingPlayTrackUrl) ?: JSONObject.NULL)
            put("positionMs", if (currentTrackUrl != null) currentPositionMs() else 0L)
            put("wasPlaying", isPlaying || pendingPlayTrackUrl != null)
        }
    }

    /** Loads a [snapshot] before the host reconnects; playback continues once the host is back. */
    fun restore(s: JSONObject) = synchronized(stateLock) {
        fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
        val q = s.optJSONArray("queue") ?: JSONArray()
        synchronized(queue) {
            queue.clear()
            for (i in 0 until q.length()) queue.add(q.getString(i))
        }
        trackTitles.putAll(s.optJSONObject("titles")?.toStringMap() ?: emptyMap())
        trackArtworks.putAll(s.optJSONObject("artworks")?.toStringMap() ?: emptyMap())
        trackAddedBy.putAll(s.optJSONObject("addedBy")?.toStringMap() ?: emptyMap())
        s.optJSONObject("durations")?.let { d -> d.keys().forEach { k -> trackDurations[k] = d.getLong(k) } }
        val h = s.optJSONArray("history") ?: JSONArray()
        synchronized(playHistory) { for (i in 0 until h.length()) playHistory.add(h.getString(i)) }
        val log = s.optJSONArray("playedLog") ?: JSONArray()
        synchronized(playedLog) {
            for (i in 0 until log.length()) {
                val o = log.getJSONObject(i)
                playedLog.add(o.optString("title") to o.optString("by"))
            }
        }
        totalSongsPlayed = s.optInt("totalSongsPlayed", 0)
        sessionStartTimeMs = s.optLong("sessionStartTimeMs", JamClock.nowMs())
        guestsCanControl = s.optBoolean("guestsCanControl", false)
        requireAppApproval = s.optBoolean("requireAppApproval", false)
        isRepeatSingle = s.optBoolean("isRepeatSingle", false)
        crossfadeMs = s.optInt("crossfadeMs", 0)
        sessionName = s.optString("sessionName", sessionName)
        approvedTokens.putAll(s.optJSONObject("tokens")?.toStringMap() ?: emptyMap())
        val current = if (s.isNull("currentTrackUrl")) null else s.optString("currentTrackUrl").ifBlank { null }
        currentTrackUrl = current
        isPlaying = false
        pauseOffsetMs = if (current != null) s.optLong("positionMs", 0L) else 0L
        startedAtServerMs = 0L
        resumeAfterRestore = current != null && s.optBoolean("wasPlaying", false)
        // Guests reconnecting in the moment before the host app is back must not be told "session ended"
        sessionActive = true
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }
}
