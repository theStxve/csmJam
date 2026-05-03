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
    
    private data class SyncSample(val rtt: Long, val offset: Long)
    private val syncSamples = mutableListOf<SyncSample>()

    override fun onOpen(handshakedata: ServerHandshake?) {
        println("Verbunden mit Server!")
        setConnectionLostTimeout(0)
        
        // Register username with server
        val joinMsg = JSONObject().apply {
            put("type", "ACTION_JOIN")
            put("username", username)
        }
        send(joinMsg.toString())
        
        pingServer()
    }

    fun pingServer(resetHistory: Boolean = false) {
        if (!isOpen) return
        if (resetHistory) {
            syncSamples.clear()
            isTimeSynced = false
        }
        pingStartTimeNano = System.nanoTime()
        val msg = JSONObject().put("type", "PING_TIME")
        send(msg.toString())
    }

    override fun onMessage(message: String) {
        val json = JSONObject(message)
        val type = json.getString("type")

        // Only commands needing synchronized time should be queued until we have the offset.
        if ((type == "PLAY" || type == "STATE_UPDATE") && !isTimeSynced) {
            pendingMessages.add(message)
            return
        }

        when (type) {
            "PREPARE" -> {
                val trackUrl = json.getString("trackUrl")
                onPrepareReceived(trackUrl)
            }
            "PONG_TIME" -> {
                val serverTime = json.getLong("serverTimeMs")
                val nowNano = System.nanoTime()
                val nowMs = System.currentTimeMillis()
                
                val rttNano = nowNano - pingStartTimeNano
                val rttMs = rttNano / 1_000_000
                val latencyMs = rttMs / 2
                
                // Offset = ServerTime - (LocalTimeAtServerReceive)
                // LocalTimeAtServerReceive approx LocalNow - Latency
                val offset = serverTime - (nowMs - latencyMs)
                
                syncSamples.add(SyncSample(rttMs, offset))
                
                if (syncSamples.size < 5) {
                    // Collect more samples for better accuracy
                    pingServer()
                } else {
                    // Pick the best sample (the one with lowest RTT)
                    val bestSample = syncSamples.minByOrNull { it.rtt }
                    if (bestSample != null) {
                        serverTimeOffset = bestSample.offset
                        println("Sync completed. Best RTT: ${bestSample.rtt}ms, Final Offset: $serverTimeOffset ms")
                    }
                    
                    isTimeSynced = true
                    onTimeOffsetCalculated(serverTimeOffset)
                    
                    // Process pending messages
                    val queued = pendingMessages.toList()
                    pendingMessages.clear()
                    queued.forEach { onMessage(it) }

                    // Schedule next periodic sync in 30 seconds
                    Thread {
                        try {
                            Thread.sleep(30000)
                            if (isOpen && !intentionalClose) {
                                pingServer(true) // Reset history for fresh sync
                            }
                        } catch (e: Exception) {}
                    }.start()
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
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        println("Verbindung getrennt. Grund: $reason (intentional=$intentionalClose)")
        onDisconnected?.invoke(reason, remote)
    }

    override fun onError(ex: Exception?) {
        ex?.printStackTrace()
    }
    
    fun sendAddTrack(trackUrl: String, trackTitle: String? = null, trackArtwork: String? = null) {
        val msg = JSONObject().apply {
            put("type", "ACTION_ADD_TRACK")
            put("trackUrl", trackUrl)
            if (trackTitle != null) put("trackTitle", trackTitle)
            if (trackArtwork != null) put("trackArtwork", trackArtwork)
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
}
