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
    var onPlayReceived: (trackUrl: String, startedAtMs: Long, pauseOffsetMs: Long) -> Unit,
    var onPauseReceived: (pauseOffsetMs: Long) -> Unit,
    var onPrepareReceived: (trackUrl: String) -> Unit,
    var onTimeOffsetCalculated: (Long) -> Unit,
    var onGuestPermissionsChanged: (Boolean) -> Unit = {}
) : WebSocketClient(serverUri, Draft_6455(), emptyMap(), 60_000) {

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
        }
        send(joinMsg.toString())
        
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
        val t1Ms = System.currentTimeMillis()
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
        val json = JSONObject(message)
        val type = json.getString("type")

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
                val t4Ms = System.currentTimeMillis()
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
                    serverTimeOffset = bestSample.offset
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
                onPlayReceived(trackUrl, startedAtMs, pauseOffsetMs)
            }
            "PAUSE" -> {
                val pauseOffsetMs = json.getLong("pauseOffsetMs")
                onPauseReceived(pauseOffsetMs)
            }
            "GUEST_PERMISSIONS_CHANGED" -> {
                val allow = json.getBoolean("guestsCanControl")
                onGuestPermissionsChanged(allow)
            }
            "VOICE_START" -> {
                val speaker = json.optString("speaker", "Jemand")
                onVoiceStart?.invoke(speaker)
            }
            "VOICE_END" -> {
                onVoiceEnd?.invoke()
            }
            "PARTY_LIGHT_UPDATE" -> {
                val enabled = json.optBoolean("enabled", false)
                onPartyLightChanged?.invoke(enabled)
            }
            "PARTICIPANT_PERMISSIONS_UPDATED" -> {
                val canMegaphone = json.optBoolean("canMegaphone", false)
                val canControl = json.optBoolean("canControl", false)
                onMyPermissionsChanged?.invoke(canMegaphone, canControl)
            }
            "REQUEST_FALLBACK_TRACK" -> {
                onFallbackRequested?.invoke()
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

    fun sendTrackReady(trackUrl: String) {
        val msg = JSONObject().apply {
            put("type", "ACTION_TRACK_READY")
            put("trackUrl", trackUrl)
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

    fun sendRegisterFallback(eligible: Boolean) {
        if (!isOpen) return
        val msg = JSONObject().apply {
            put("type", "ACTION_REGISTER_FALLBACK")
            put("eligible", eligible)
        }
        send(msg.toString())
    }
}
