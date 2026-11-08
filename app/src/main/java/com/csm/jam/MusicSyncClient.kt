package com.csm.jam

import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class MusicSyncClient(
    serverUri: URI,
    val username: String,
    var onStateUpdated: (isPlaying: Boolean, trackUrl: String?, startedAtMs: Long, pauseOffsetMs: Long, isRepeatSingle: Boolean) -> Unit,
    var onListsUpdated: (queue: List<String>, participants: List<String>, trackTitles: Map<String, String>, trackArtworks: Map<String, String>) -> Unit,
    var onPlayReceived: (trackUrl: String, startedAtMs: Long, pauseOffsetMs: Long, crossfadeMs: Int, fromTrackUrl: String?) -> Unit,
    var onPauseReceived: (pauseOffsetMs: Long) -> Unit,
    var onPrepareReceived: (trackUrl: String) -> Unit,
    var onTimeOffsetCalculated: (Long) -> Unit,
    var onGuestPermissionsChanged: (Boolean) -> Unit = {}
) : WebSocketClient(serverUri, Draft_6455(), emptyMap(), 6_000) {

    private var pingStartTimeNano: Long = 0L
    private var serverTimeOffset: Long = 0L
    private var isTimeSynced = false
    private val pendingMessages = mutableListOf<String>()
    var onDisconnected: ((reason: String?, remote: Boolean) -> Unit)? = null
    var intentionalClose: Boolean = false   // set to true before calling close() when leaving session
    var onSyncStatsUpdated: ((offsetMs: Long, rttMs: Long) -> Unit)? = null

    // Voice Streaming & Party Light callbacks
    var onVoiceStart: ((speaker: String) -> Unit)? = null
    var onVoiceChunk: ((data: ByteArray) -> Unit)? = null
    var onVoiceEnd: (() -> Unit)? = null
    var onPartyLightChanged: ((enabled: Boolean) -> Unit)? = null
    var onMyPermissionsChanged: ((canMegaphone: Boolean, canControl: Boolean) -> Unit)? = null
    var onSessionStatsUpdated: ((startTimeMs: Long, totalPlayed: Int, trackAddedBy: Map<String, String>) -> Unit)? = null
    var onFallbackRequested: (() -> Unit)? = null
    var onJoinRequests: ((List<JoinRequest>) -> Unit)? = null
    var onVoiceBusy: ((speaker: String) -> Unit)? = null
    var onPreloadReceived: ((trackUrl: String?) -> Unit)? = null
    var onPlayedLogUpdated: ((List<Pair<String, String>>) -> Unit)? = null
    var onJoinStateChanged: ((pending: Boolean) -> Unit)? = null
    var onAppApprovalChanged: ((enabled: Boolean) -> Unit)? = null
    var onCrossfadeChanged: ((ms: Int) -> Unit)? = null
    var onCalibrationStart: ((untilMs: Long) -> Unit)? = null
    var onCalibrationChirp: ((times: List<Long>) -> Unit)? = null
    var onCalibrationPlan: ((slots: org.json.JSONArray, untilMs: Long) -> Unit)? = null
    var onCalibrationApply: ((deltaMs: Long) -> Unit)? = null
    var onSyncTest: ((enabled: Boolean) -> Unit)? = null
    @Volatile var lastCrossfadeMs: Int = 0

    // Issued by the server: authorises uploads and lets us back in without a new approval
    @Volatile var sessionToken: String = ""
    @Volatile var isJoinPending: Boolean = false
    @Volatile var lastAppApproval: Boolean = false
    @Volatile var lastPreloadUrl: String? = null

    data class JoinRequest(val requestId: String, val name: String, val device: String, val isWeb: Boolean = true)

    // Last values received from the server. Messages can arrive before the UI has wired its
    // callbacks (they are sent right after connect), so the UI reads these when it attaches.
    @Volatile var lastGuestsCanControl: Boolean = false
    @Volatile var lastCanMegaphone: Boolean = false
    @Volatile var lastCanControl: Boolean = false
    @Volatile var lastJoinRequests: List<JoinRequest> = emptyList()
    // Re-sent on every (re)connect because the server forgets it with the old socket
    @Volatile var fallbackEligible: Boolean = false

    /** True if the server ended our membership for good (kick, session ended, ...) – don't auto-reconnect. */
    val isFinalClose: Boolean get() = finalCloseReason != null
    @Volatile var finalCloseReason: String? = null
        private set

    /** Takes over all UI callbacks of a previous client so a reconnect keeps every feature working. */
    fun copyCallbacksFrom(old: MusicSyncClient) {
        onStateUpdated = old.onStateUpdated
        onListsUpdated = old.onListsUpdated
        onPlayReceived = old.onPlayReceived
        onPauseReceived = old.onPauseReceived
        onPrepareReceived = old.onPrepareReceived
        onTimeOffsetCalculated = old.onTimeOffsetCalculated
        onGuestPermissionsChanged = old.onGuestPermissionsChanged
        onSyncStatsUpdated = old.onSyncStatsUpdated
        onVoiceStart = old.onVoiceStart
        onVoiceChunk = old.onVoiceChunk
        onVoiceEnd = old.onVoiceEnd
        onPartyLightChanged = old.onPartyLightChanged
        onMyPermissionsChanged = old.onMyPermissionsChanged
        onSessionStatsUpdated = old.onSessionStatsUpdated
        onFallbackRequested = old.onFallbackRequested
        onJoinRequests = old.onJoinRequests
        onVoiceBusy = old.onVoiceBusy
        onPreloadReceived = old.onPreloadReceived
        onPlayedLogUpdated = old.onPlayedLogUpdated
        onJoinStateChanged = old.onJoinStateChanged
        onAppApprovalChanged = old.onAppApprovalChanged
        onCrossfadeChanged = old.onCrossfadeChanged
        onCalibrationStart = old.onCalibrationStart
        onCalibrationChirp = old.onCalibrationChirp
        onCalibrationPlan = old.onCalibrationPlan
        onCalibrationApply = old.onCalibrationApply
        onSyncTest = old.onSyncTest
        sessionToken = old.sessionToken
        fallbackEligible = old.fallbackEligible
    }
    
    private data class SyncSample(val rtt: Long, val offset: Long, val time: Long = System.currentTimeMillis())
    private val syncSamples = mutableListOf<SyncSample>()
    private var isInitialSyncCompleted = false
    private val syncExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var periodicSyncFuture: java.util.concurrent.ScheduledFuture<*>? = null

    override fun onOpen(handshakedata: ServerHandshake?) {
        println("Verbunden mit Server!")
        setConnectionLostTimeout(0)
        
        // Register username with server
        val joinMsg = JSONObject().apply {
            put("type", "ACTION_JOIN")
            put("username", username)
            if (sessionToken.isNotBlank()) put("token", sessionToken)
        }
        send(joinMsg.toString())
        if (fallbackEligible) sendRegisterFallback(true)

        pingServer(resetHistory = true)
    }

    fun pingServer(resetHistory: Boolean = false) {
        if (!isOpen) return
        if (resetHistory) {
            synchronized(syncSamples) { syncSamples.clear() }
            isInitialSyncCompleted = false
            // Note: We leave isTimeSynced untouched if already true, so ongoing session
            // commands (PLAY/STATE_UPDATE) are not delayed while re-measuring NTP.
        }
        val t1Nano = System.nanoTime()
        val t1Ms = JamClock.nowMs()
        pingStartTimeNano = t1Nano
        val msg = JSONObject().apply {
            put("type", "PING_TIME")
            put("clientSendNano", t1Nano)
            put("clientSendMs", t1Ms)
        }
        send(msg.toString())

        // Retry safety: if no PONG_TIME is received within 1.5 s (e.g. packet loss on first open),
        // send another ping so we don't hang indefinitely with isTimeSynced = false.
        if (!isInitialSyncCompleted) {
            syncExecutor.schedule({
                if (isOpen && !intentionalClose && !isInitialSyncCompleted) {
                    pingServer(resetHistory = false)
                }
            }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    override fun onMessage(message: String) {
        val json = try { JSONObject(message) } catch (_: Exception) { return }
        val type = json.optString("type")

        // Only commands needing synchronized time should be queued until we have the offset.
        if ((type == "PLAY" || type == "STATE_UPDATE") && !isTimeSynced) {
            synchronized(pendingMessages) {
                pendingMessages.add(message)
            }
            return
        }

        when (type) {
            "PREPARE" -> {
                val trackUrl = json.getString("trackUrl")
                onPrepareReceived(trackUrl)
            }
            "PONG_TIME" -> {
                val t4Nano = System.nanoTime()
                val t4Ms = JamClock.nowMs()
                val t1Nano = json.optLong("clientSendNano", pingStartTimeNano)
                val t1Ms = json.optLong("clientSendMs", t4Ms - ((t4Nano - t1Nano) / 1_000_000L))
                val t2Ms = json.optLong("serverReceiveMs", json.optLong("serverTimeMs", t4Ms))
                val t3Ms = json.optLong("serverTransmitMs", json.optLong("serverTimeMs", t4Ms))

                val totalElapsedMs = ((t4Nano - t1Nano) / 1_000_000L).coerceAtLeast(0L)
                val serverProcessingMs = (t3Ms - t2Ms).coerceAtLeast(0L)
                val networkRttMs = (totalElapsedMs - serverProcessingMs).coerceAtLeast(0L)

                // Standard 4-point NTP Offset formula: ((T2 - T1) + (T3 - T4)) / 2
                val offsetMs = ((t2Ms - t1Ms) + (t3Ms - t4Ms)) / 2

                synchronized(syncSamples) {
                    syncSamples.add(SyncSample(networkRttMs, offsetMs))
                    if (syncSamples.size > 7) {
                        syncSamples.removeAt(0)
                    }

                    // Sort by RTT to find median
                    val sortedByRtt = syncSamples.sortedBy { it.rtt }
                    val medianRtt = sortedByRtt[sortedByRtt.size / 2].rtt

                    // Outlier rejection: reject samples with RTT > 1.8x median
                    val validSamples = if (syncSamples.size >= 4) {
                        syncSamples.filter { it.rtt <= (medianRtt * 1.8).toLong().coerceAtLeast(medianRtt + 15) }
                    } else {
                        syncSamples
                    }

                    val bestSample = validSamples.minByOrNull { it.rtt } ?: sortedByRtt.first()
                    // After the initial sync, small changes are blended in (each re-measurement used to
                    // shift the timeline by a few ms and the playback kept chasing it); big ones still jump
                    val delta = bestSample.offset - serverTimeOffset
                    serverTimeOffset = if (isInitialSyncCompleted && kotlin.math.abs(delta) < 40) {
                        serverTimeOffset + Math.round(delta * 0.25)
                    } else bestSample.offset
                    onSyncStatsUpdated?.invoke(serverTimeOffset, bestSample.rtt)
                }

                if (!isInitialSyncCompleted) {
                    val count = synchronized(syncSamples) { syncSamples.size }
                    if (count < 5) {
                        // Gather initial 5 samples rapidly (50ms interval)
                        syncExecutor.schedule({
                            if (isOpen && !intentionalClose) pingServer()
                        }, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } else {
                        isInitialSyncCompleted = true
                        isTimeSynced = true
                        onTimeOffsetCalculated(serverTimeOffset)

                        // Process pending messages
                        val queued = synchronized(pendingMessages) {
                            val list = pendingMessages.toList()
                            pendingMessages.clear()
                            list
                        }
                        queued.forEach { onMessage(it) }

                        // Schedule periodic background drift sync every 10 seconds (never blocks messages)
                        periodicSyncFuture?.cancel(false)
                        periodicSyncFuture = syncExecutor.scheduleWithFixedDelay({
                            if (isOpen && !intentionalClose) {
                                pingServer(resetHistory = false)
                            }
                        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } else {
                    // Periodic update: update offset smoothly without interrupting playback
                    onTimeOffsetCalculated(serverTimeOffset)
                }
            }
            "STATE_UPDATE" -> {
                val isPlaying = json.getBoolean("isPlaying")
                val trackUrl = if (json.has("trackUrl") && !json.isNull("trackUrl")) json.getString("trackUrl") else null
                val startedAtMs = json.optLong("startedAtMs", 0L)
                val pauseOffsetMs = json.optLong("pauseOffsetMs", 0L)
                val isRepeatSingle = json.optBoolean("isRepeatSingle", false)
                onStateUpdated(isPlaying, trackUrl, startedAtMs, pauseOffsetMs, isRepeatSingle)
            }
            "LISTS_UPDATE" -> {
                val qArray = json.getJSONArray("queue")
                val pArray = json.getJSONArray("participants")

                val queue = mutableListOf<String>()
                for (i in 0 until qArray.length()) {
                    queue.add(qArray.getString(i))
                }

                val participants = mutableListOf<String>()
                for (i in 0 until pArray.length()) {
                    participants.add(pArray.getString(i))
                }

                val titles = mutableMapOf<String, String>()
                if (json.has("trackTitles")) {
                    val titlesObj = json.getJSONObject("trackTitles")
                    titlesObj.keys().forEach { key -> titles[key] = titlesObj.getString(key) }
                }

                val arts = mutableMapOf<String, String>()
                if (json.has("trackArtworks")) {
                    val artsObj = json.getJSONObject("trackArtworks")
                    artsObj.keys().forEach { key -> arts[key] = artsObj.getString(key) }
                }

                val addedByMap = mutableMapOf<String, String>()
                if (json.has("trackAddedBy")) {
                    val addedByObj = json.getJSONObject("trackAddedBy")
                    addedByObj.keys().forEach { key -> addedByMap[key] = addedByObj.getString(key) }
                }
                json.optJSONArray("playedLog")?.let { arr ->
                    val log = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.optString("title") to o.optString("by")
                    }
                    onPlayedLogUpdated?.invoke(log)
                }
                val startTimeMs = json.optLong("sessionStartTimeMs", 0L)
                val totalPlayed = json.optInt("totalSongsPlayed", 0)
                if (startTimeMs > 0L) {
                    onSessionStatsUpdated?.invoke(startTimeMs, totalPlayed, addedByMap)
                }

                onListsUpdated(queue, participants, titles, arts)
            }



            "PLAY" -> {
                val trackUrl = json.getString("trackUrl")
                val startedAtMs = json.getLong("startedAtMs")
                val pauseOffsetMs = json.getLong("pauseOffsetMs")
                val fromTrackUrl = if (json.isNull("fromTrackUrl")) null else json.optString("fromTrackUrl").ifBlank { null }
                onPlayReceived(trackUrl, startedAtMs, pauseOffsetMs, json.optInt("crossfadeMs", 0), fromTrackUrl)
            }
            "PAUSE" -> {
                val pauseOffsetMs = json.getLong("pauseOffsetMs")
                onPauseReceived(pauseOffsetMs)
            }
            "GUEST_PERMISSIONS_CHANGED" -> {
                val allow = json.getBoolean("guestsCanControl")
                lastGuestsCanControl = allow
                onGuestPermissionsChanged(allow)
            }
            "VOICE_START" -> {
                val speaker = json.optString("speaker", "Jemand")
                onVoiceStart?.invoke(speaker)
            }
            "VOICE_END" -> {
                onVoiceEnd?.invoke()
            }
            "VOICE_BUSY" -> {
                onVoiceBusy?.invoke(json.optString("speaker", "Jemand"))
            }
            "PARTY_LIGHT_UPDATE" -> {
                val enabled = json.optBoolean("enabled", false)
                onPartyLightChanged?.invoke(enabled)
            }
            "PARTICIPANT_PERMISSIONS_UPDATED" -> {
                val canMegaphone = json.optBoolean("canMegaphone", false)
                val canControl = json.optBoolean("canControl", false)
                lastCanMegaphone = canMegaphone
                lastCanControl = canControl
                onMyPermissionsChanged?.invoke(canMegaphone, canControl)
            }
            "REQUEST_FALLBACK_TRACK" -> {
                onFallbackRequested?.invoke()
            }
            "SESSION_TOKEN" -> {
                sessionToken = json.optString("token", sessionToken)
            }
            "JOIN_PENDING" -> {
                isJoinPending = true
                onJoinStateChanged?.invoke(true)
            }
            "JOIN_APPROVED" -> {
                sessionToken = json.optString("token", sessionToken)
                isJoinPending = false
                onJoinStateChanged?.invoke(false)
            }
            "CROSSFADE_CHANGED" -> {
                lastCrossfadeMs = json.optInt("ms", 0)
                onCrossfadeChanged?.invoke(lastCrossfadeMs)
            }
            "CALIBRATION_START" -> onCalibrationStart?.invoke(json.optLong("untilMs"))
            "CALIBRATION_CHIRP" -> {
                val arr = json.optJSONArray("times") ?: org.json.JSONArray()
                onCalibrationChirp?.invoke((0 until arr.length()).map { arr.getLong(it) })
            }
            "CALIBRATION_PLAN" -> onCalibrationPlan?.invoke(json.optJSONArray("slots") ?: org.json.JSONArray(), json.optLong("untilMs"))
            "CALIBRATION_APPLY" -> onCalibrationApply?.invoke(json.optLong("deltaMs"))
            "SYNC_TEST" -> onSyncTest?.invoke(json.optBoolean("enabled", false))
            "APP_APPROVAL_CHANGED" -> {
                lastAppApproval = json.optBoolean("enabled", false)
                onAppApprovalChanged?.invoke(lastAppApproval)
            }
            "PRELOAD" -> {
                val url = if (json.isNull("trackUrl")) null else json.optString("trackUrl").ifBlank { null }
                lastPreloadUrl = url
                onPreloadReceived?.invoke(url)
            }
            "JOIN_REQUESTS" -> {
                val arr = json.optJSONArray("requests")
                val list = mutableListOf<JoinRequest>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(JoinRequest(o.getString("requestId"), o.optString("name", "Gast"), o.optString("device", "Browser"), o.optBoolean("isWeb", true)))
                    }
                }
                lastJoinRequests = list
                onJoinRequests?.invoke(list)
            }
            "KICKED" -> {
                finalCloseReason = LocalMusicServer.CLOSE_KICKED
            }
        }
    }

    override fun onMessage(bytes: java.nio.ByteBuffer) {
        val arr = ByteArray(bytes.remaining())
        bytes.get(arr)
        onVoiceChunk?.invoke(arr)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        println("Verbindung getrennt. Grund: $reason (intentional=$intentionalClose)")
        if (reason in setOf(
                LocalMusicServer.CLOSE_SESSION_ENDED, LocalMusicServer.CLOSE_KICKED,
                LocalMusicServer.CLOSE_DENIED, LocalMusicServer.CLOSE_NO_SESSION)) {
            finalCloseReason = reason
        }
        try {
            periodicSyncFuture?.cancel(true)
            periodicSyncFuture = null
            syncExecutor.shutdownNow()
        } catch (_: Exception) {}
        onDisconnected?.invoke(reason, remote)
    }

    override fun onError(ex: Exception?) {
        ex?.printStackTrace()
    }
    
    fun sendAddTrack(trackUrl: String, trackTitle: String? = null, trackArtwork: String? = null, addedBy: String? = null) {
        val msg = JSONObject().apply {
            put("type", "ACTION_ADD_TRACK")
            put("trackUrl", trackUrl)
            if (trackTitle != null) put("trackTitle", trackTitle)
            if (trackArtwork != null) put("trackArtwork", trackArtwork)
            if (addedBy != null) put("addedBy", addedBy)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendRemoveTrack(trackUrl: String) {
        val msg = JSONObject().apply {
            put("type", "ACTION_QUEUE_REMOVE")
            put("trackUrl", trackUrl)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendReorderQueue(fromIndex: Int, toIndex: Int) {
        val msg = JSONObject().apply {
            put("type", "ACTION_QUEUE_REORDER")
            put("fromIndex", fromIndex)
            put("toIndex", toIndex)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendTrackReady(trackUrl: String, durationMs: Long = 0L) {
        val msg = JSONObject().apply {
            put("type", "ACTION_TRACK_READY")
            put("trackUrl", trackUrl)
            if (durationMs > 0L) put("durationMs", durationMs)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendPlayAction(trackUrl: String?) {
        val msg = JSONObject().apply {
            put("type", "ACTION_PLAY")
            if (trackUrl != null) put("trackUrl", trackUrl)
        }
        if (isOpen) send(msg.toString())
    }
    
    fun sendPauseAction() {
        val msg = JSONObject().put("type", "ACTION_PAUSE")
        if (isOpen) send(msg.toString())
    }

    fun sendTrackFinished(trackUrl: String) {
        val msg = JSONObject().apply {
            put("type", "ACTION_TRACK_FINISHED")
            put("trackUrl", trackUrl)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendSkipNext() {
        val msg = JSONObject().put("type", "ACTION_SKIP_NEXT")
        if (isOpen) send(msg.toString())
    }

    fun sendSkipPrev() {
        val msg = JSONObject().put("type", "ACTION_SKIP_PREV")
        if (isOpen) send(msg.toString())
    }

    fun sendShuffle() {
        val msg = JSONObject().put("type", "ACTION_SHUFFLE")
        if (isOpen) send(msg.toString())
    }

    fun requestState() {
        val msg = JSONObject().put("type", "ACTION_REQUEST_STATE")
        if (isOpen) send(msg.toString())
    }

    fun sendSetGuestPermissions(allow: Boolean) {
        val msg = JSONObject().apply {
            put("type", "ACTION_SET_GUEST_PERMISSIONS")
            put("allow", allow)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendSetRepeatSingle(repeat: Boolean) {
        val msg = JSONObject().apply {
            put("type", "ACTION_SET_REPEAT_SINGLE")
            put("repeat", repeat)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendSeekAction(seekToMs: Long) {
        val msg = JSONObject().apply {
            put("type", "ACTION_SEEK")
            put("seekToMs", seekToMs)
        }
        if (isOpen) send(msg.toString())
    }

    fun sendForceSync() {
        val msg = JSONObject().put("type", "ACTION_FORCE_SYNC")
        if (isOpen) send(msg.toString())
    }

    fun sendVoiceStart(speaker: String) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "VOICE_START")
            put("speaker", speaker)
        }
        send(msg.toString())
    }

    fun sendVoiceChunk(data: ByteArray) {
        if (!isOpen) return
        try {
            send(data)
        } catch (_: Exception) {}
    }

    fun sendVoiceEnd() {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "VOICE_END")
        }
        send(msg.toString())
    }

    fun sendUpdateParticipantPermissions(targetId: String, canMegaphone: Boolean, canControl: Boolean) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_UPDATE_PARTICIPANT_PERMISSIONS")
            put("targetId", targetId)
            put("canMegaphone", canMegaphone)
            put("canControl", canControl)
        }
        send(msg.toString())
    }

    fun sendKickParticipant(targetId: String) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_KICK_PARTICIPANT")
            put("targetId", targetId)
        }
        send(msg.toString())
    }

    fun sendSetPartyLight(enabled: Boolean) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_SET_PARTY_LIGHT")
            put("enabled", enabled)
        }
        send(msg.toString())
    }

    fun sendSetCrossfade(ms: Int) {
        if (!isOpen) return
        send(JSONObject().put("type", "ACTION_SET_CROSSFADE").put("ms", ms).toString())
    }

    fun sendSyncTest(enabled: Boolean) {
        if (!isOpen) return
        send(JSONObject().put("type", "ACTION_SYNC_TEST").put("enabled", enabled).toString())
    }

    fun sendCalibrationStart() {
        if (!isOpen) return
        send(JSONObject().put("type", "ACTION_CALIBRATION_START").toString())
    }

    fun sendCalibrationResult(results: List<Pair<String, Long?>>) {
        if (!isOpen) return
        val arr = org.json.JSONArray()
        results.forEach { (id, delta) -> arr.put(JSONObject().put("id", id).put("deltaMs", delta ?: JSONObject.NULL)) }
        send(JSONObject().put("type", "ACTION_CALIBRATION_RESULT").put("results", arr).toString())
    }

    fun sendSetAppApproval(enabled: Boolean) {
        if (!isOpen) return
        send(JSONObject().put("type", "ACTION_SET_APP_APPROVAL").put("enabled", enabled).toString())
    }

    fun sendRespondJoin(requestId: String, accept: Boolean) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_RESPOND_JOIN")
            put("requestId", requestId)
            put("accept", accept)
        }
        send(msg.toString())
    }

    fun sendRegisterFallback(eligible: Boolean) {
        fallbackEligible = eligible
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_REGISTER_FALLBACK")
            put("eligible", eligible)
        }
        send(msg.toString())
    }
}
