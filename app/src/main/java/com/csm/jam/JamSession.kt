package com.csm.jam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session state + logic behind the UI (the "ViewModel" of the app).
 *
 * Deliberately process-wide instead of an androidx ViewModel: the jam keeps running in the
 * foreground service after the Activity is gone, and a re-opened Activity must find the
 * exact same session again. Composables only read this state and call its actions.
 */
class JamSession private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: JamSession? = null

        fun get(context: Context): JamSession = instance ?: synchronized(this) {
            instance ?: JamSession(context.applicationContext).also { instance = it }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine get() = PlaybackEngine.get(context)
    private val prefs = context.getSharedPreferences("CSM_Prefs", Context.MODE_PRIVATE)

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun toast(text: String, long: Boolean = false) = ui {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ── Connection ────────────────────────────────────────────────────────
    val isConnectedState = mutableStateOf(MainActivity.isSessionActive)
    var isConnected by isConnectedState
    val isHostState = mutableStateOf(MainActivity.isHostMode)
    var isHost by isHostState
    val hostIpState = mutableStateOf("192.168.")
    var username: String = prefs.getString("username", "")?.takeIf { it.isNotBlank() } ?: "Gast"
    val isConnectingState = mutableStateOf(false)
    var isConnecting by isConnectingState
    val isStartingHostState = mutableStateOf(false)
    var isStartingHost by isStartingHostState
    /** The host we're connected to (127.0.0.1 for the host itself) – guests upload songs there. */
    @Volatile var connectedHostIp: String = ""

    // ── Per device: player as card on top or as bar at the bottom (Spotify style, default) ──
    val playerAtBottomState = mutableStateOf(prefs.getBoolean("player_bottom", true))
    var playerAtBottom: Boolean
        get() = playerAtBottomState.value
        set(value) {
            playerAtBottomState.value = value
            prefs.edit().putBoolean("player_bottom", value).apply()
        }

    // ── Lists & stats ─────────────────────────────────────────────────────
    val queue = mutableStateListOf<String>()
    val participants = mutableStateListOf<String>()
    val trackTitles = mutableStateMapOf<String, String>()
    val trackArtworks = mutableStateMapOf<String, String>()
    val trackAddedBy = mutableStateMapOf<String, String>()
    val playedLog = mutableStateListOf<Pair<String, String>>()
    val sessionStartTimeMsState = mutableStateOf(System.currentTimeMillis())
    val totalSongsPlayedState = mutableStateOf(0)

    // ── Permissions & approvals ───────────────────────────────────────────
    val guestsCanControlState = mutableStateOf(false)
    var guestsCanControl by guestsCanControlState
    val isRepeatSingleState = mutableStateOf(false)
    val myCanMegaphoneState = mutableStateOf(false)
    var myCanMegaphone by myCanMegaphoneState
    val joinRequests = mutableStateListOf<MusicSyncClient.JoinRequest>()
    /** Guest: waiting for the host to accept us. */
    val awaitingApprovalState = mutableStateOf(false)
    var awaitingApproval by awaitingApprovalState
    /** Host: app guests must be accepted too. */
    val requireAppApprovalState = mutableStateOf(false)
    var requireAppApproval by requireAppApprovalState
    /** Set when the server ended our membership (kick / host ended session). */
    val terminatedReasonState = mutableStateOf<String?>(null)

    // ── Megaphone ─────────────────────────────────────────────────────────
    val activeVoiceSpeakerState = mutableStateOf<String?>(null)
    var activeVoiceSpeaker by activeVoiceSpeakerState
    val isTransmittingState = mutableStateOf(false)
    var isTransmitting by isTransmittingState
    val voicePlayback = VoicePlaybackManager()
    val voiceRecorder = VoiceManager { chunk -> MainActivity.syncClient?.sendVoiceChunk(chunk) }

    // ── Party light ───────────────────────────────────────────────────────
    val partyLight = PartyLightManager(context, { engine.activePlayer }, { engine.isPlaying })
    val isPartyLightActiveState = mutableStateOf(false)
    var isPartyLightActive by isPartyLightActiveState
    val partyLightModeState = mutableStateOf(PartyLightMode.BEAT)
    var partyLightMode by partyLightModeState

    /** Guests run the party light only for themselves; the host switches it for everybody. */
    fun setPartyLight(on: Boolean) {
        if (isHost) {
            MainActivity.syncClient?.sendSetPartyLight(on)
            return
        }
        isPartyLightActive = on
        if (on) partyLight.start(scope, partyLightMode) else partyLight.stop()
    }

    fun changePartyLightMode(mode: PartyLightMode) {
        partyLightMode = mode
        partyLight.mode = mode
        if (isPartyLightActive) {
            partyLight.stop()
            partyLight.start(scope, mode)
        }
    }

    // ── Uploads & autoplay folder ─────────────────────────────────────────
    val uploadStatusState = mutableStateOf<String?>(null)
    var uploadStatus by uploadStatusState
    val uploadProgressState = mutableStateOf(0f)
    var uploadProgress by uploadProgressState
    val fallbackFolderState = mutableStateOf<Uri?>(null)
    var fallbackFolder by fallbackFolderState
    val fallbackTracks = mutableStateListOf<Uri>()
    val isScanningFallbackState = mutableStateOf(false)
    var isScanningFallback by isScanningFallbackState

    // ── Auto-calibration (host) ───────────────────────────────────────────
    sealed class Calibration {
        object Idle : Calibration()
        data class Running(val step: String) : Calibration()
        data class Done(val results: List<AutoCalibrator.Result>) : Calibration()
        data class Failed(val message: String) : Calibration()
    }
    val calibrationState = mutableStateOf<Calibration>(Calibration.Idle)
    private var calibrator: AutoCalibrator? = null

    init {
        // Safety net: if an announcement's end never arrives (speaker's wifi died), un-duck after 4 s of silence
        scope.launch {
            while (isActive) {
                delay(1000)
                if (activeVoiceSpeaker != null && System.currentTimeMillis() - voicePlayback.lastChunkAtMs > 4000) {
                    voicePlayback.stop()
                    activeVoiceSpeaker = null
                    if (!isTransmitting) engine.setDucked(false)
                }
            }
        }
        // Position moves without any server message – save the hosted session regularly
        scope.launch {
            while (isActive) {
                delay(5000)
                if (isConnected && isHost) {
                    MainActivity.localServer?.let {
                        SessionStore.requestSave(context, it, MainActivity.localFileServer, getRawIpAddress(context))
                    }
                }
            }
        }
    }

    /** Hooks every UI-relevant server message of [client] into this state. */
    fun attach(client: MusicSyncClient) {
        client.onListsUpdated = { q, p, titles, arts ->
            ui {
                queue.clear(); queue.addAll(q)
                participants.clear(); participants.addAll(p)
                trackTitles.clear(); trackTitles.putAll(titles)
                trackArtworks.clear(); trackArtworks.putAll(arts)
            }
        }
        client.onStateUpdated = { playing, track, startedAtMs, pauseOffsetMs, repeatSingle ->
            engine.onState(playing, track, startedAtMs, pauseOffsetMs, repeatSingle)
            ui {
                isRepeatSingleState.value = repeatSingle
                val title = track?.let { trackTitles[it] ?: extractFileNameFromUrl(it) } ?: "Warten auf Song..."
                JamSessionService.updateTrack(context, if (MainActivity.isHostMode) "host" else "guest", title, playing)
            }
        }
        client.onGuestPermissionsChanged = { allow ->
            // For guests the flag means "may I control": global switch OR my personal Co-DJ right
            ui { guestsCanControl = allow || (!MainActivity.isHostMode && MainActivity.syncClient?.lastCanControl == true) }
        }
        client.onMyPermissionsChanged = { canMega, canCtrl ->
            ui {
                myCanMegaphone = MainActivity.isHostMode || canMega
                if (!MainActivity.isHostMode) guestsCanControl = canCtrl || MainActivity.syncClient?.lastGuestsCanControl == true
            }
        }
        client.onVoiceStart = { speaker ->
            voicePlayback.start()
            engine.setDucked(true)
            ui { activeVoiceSpeaker = speaker }
        }
        client.onVoiceChunk = { data -> voicePlayback.write(data) }
        client.onVoiceEnd = {
            voicePlayback.stop()
            ui {
                activeVoiceSpeaker = null
                if (!isTransmitting) engine.setDucked(false)
            }
        }
        client.onVoiceBusy = { speaker ->
            ui {
                stopTransmit(notifyServer = false)
                toast("Gerade spricht schon $speaker")
            }
        }
        client.onPartyLightChanged = { enabled ->
            ui {
                isPartyLightActive = enabled
                if (enabled) partyLight.start(scope, partyLightMode) else partyLight.stop()
            }
        }
        client.onSessionStatsUpdated = { startMs, totalPlayed, addedByMap ->
            ui {
                if (startMs > 0L) sessionStartTimeMsState.value = startMs
                totalSongsPlayedState.value = totalPlayed
                trackAddedBy.clear()
                trackAddedBy.putAll(addedByMap)
            }
        }
        client.onJoinRequests = { requests ->
            ui {
                val isNew = requests.any { r -> joinRequests.none { it.requestId == r.requestId } }
                joinRequests.clear()
                joinRequests.addAll(requests)
                if (isNew) toast("◇ Neue Beitrittsanfrage")
            }
        }
        client.onJoinStateChanged = { pending -> ui { awaitingApproval = pending } }
        client.onAppApprovalChanged = { enabled -> ui { requireAppApproval = enabled } }
        client.onPlayedLogUpdated = { log -> ui { playedLog.clear(); playedLog.addAll(log) } }
        client.onCalibrationPlan = { slots, untilMs -> runCalibration(slots, untilMs) }
        client.onFallbackRequested = { ui { supplyAutoplayTrack() } }

        // Values the server sent before these callbacks were attached
        ui {
            awaitingApproval = client.isJoinPending
            requireAppApproval = client.lastAppApproval
            myCanMegaphone = MainActivity.isHostMode || client.lastCanMegaphone
            guestsCanControl = client.lastGuestsCanControl || (!MainActivity.isHostMode && client.lastCanControl)
            joinRequests.clear()
            joinRequests.addAll(client.lastJoinRequests)
            // Autoplay folder from an earlier session – reuse it if we still have access
            if (fallbackFolder == null) {
                prefs.getString("autoplay_folder", null)?.let { Uri.parse(it) }?.let { saved ->
                    val stillGranted = context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
                    if (stillGranted) loadFallbackFolder(saved) else prefs.edit().remove("autoplay_folder").apply()
                }
            }
        }
        client.requestState()
    }

    // ── Songs ─────────────────────────────────────────────────────────────

    /** Host: serve the files directly. Guests: upload them to the host so they survive the guest leaving. */
    fun addTracks(uris: List<Uri>) {
        if (!MainActivity.isHostMode) {
            uploadToHost(uris, autoplay = false)
            return
        }
        val myIp = getRawIpAddress(context)
        var added = 0
        uris.forEach { uri ->
            // Persist read permission so the URI stays valid across process boundaries
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            if (hostLocally(uri, myIp, username)) added++
        }
        if (added > 0) toast("$added Track(s) hinzugefügt!")
    }

    private fun hostLocally(uri: Uri, myIp: String, addedBy: String): Boolean {
        val (fileId, hasArt) = MainActivity.localFileServer?.hostFile(uri) ?: return false
        val fileName = getFileName(context, uri)
        val title = getAudioTitle(context, uri) ?: fileName.substringBeforeLast('.')
        val trackUrl = "http://$myIp:8081/$fileId/${Uri.encode(fileName)}"
        val artUrl = if (hasArt) "http://$myIp:8081/art/$fileId" else null
        MainActivity.syncClient?.sendAddTrack(trackUrl, title, artUrl, addedBy)
        return true
    }

    fun uploadToHost(uris: List<Uri>, autoplay: Boolean) {
        val client = MainActivity.syncClient ?: return
        val hostAddress = connectedHostIp
        if (client.sessionToken.isBlank() || hostAddress.isBlank()) {
            if (!autoplay) toast("Noch nicht mit dem Host verbunden")
            return
        }
        Thread {
            var ok = 0
            uris.forEachIndexed { i, uri ->
                val name = getFileName(context, uri)
                if (!autoplay) ui { uploadStatus = (if (uris.size > 1) "${i + 1}/${uris.size} · " else "") + name; uploadProgress = 0f }
                val error = TrackUploader.upload(context, hostAddress, client.sessionToken, uri, name, autoplay) { f ->
                    if (!autoplay) ui { uploadProgress = f }
                }
                if (error == null) ok++ else if (!autoplay) toast(error)
            }
            ui {
                uploadStatus = null
                if (!autoplay && ok > 0) toast("✦ $ok Song(s) hinzugefügt")
            }
        }.start()
    }

    /** The server asked us for an autoplay track (round-robin over everybody with a folder). */
    private fun supplyAutoplayTrack() {
        val pick = fallbackTracks.toList().randomOrNull() ?: return
        if (MainActivity.isHostMode) {
            Thread { hostLocally(pick, getRawIpAddress(context), "$username (Autoplay)") }.start()
        } else {
            uploadToHost(listOf(pick), autoplay = true)
        }
    }

    /** Scans the folder (incl. sub-folders) off the UI thread and registers for the autoplay pool. */
    fun loadFallbackFolder(uri: Uri) {
        fallbackFolder = uri
        isScanningFallback = true
        Thread {
            val found = scanAudioFolder(context, uri)
            ui {
                isScanningFallback = false
                if (fallbackFolder != uri) return@ui
                fallbackTracks.clear()
                fallbackTracks.addAll(found)
                MainActivity.syncClient?.sendRegisterFallback(found.isNotEmpty())
            }
        }.start()
    }

    fun chooseFallbackFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
        // Remembered for the next sessions
        prefs.edit().putString("autoplay_folder", uri.toString()).apply()
        loadFallbackFolder(uri)
    }

    fun forgetFallbackFolder() {
        prefs.edit().remove("autoplay_folder").apply()
        fallbackFolder?.let { uri ->
            try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        fallbackFolder = null
        fallbackTracks.clear()
        MainActivity.syncClient?.sendRegisterFallback(false)
    }

    // ── Megaphone ─────────────────────────────────────────────────────────

    /** Microphone permission must already be granted (the UI asks for it). */
    fun startTransmit() {
        if (isTransmitting) return
        activeVoiceSpeaker?.let {
            toast("Gerade spricht schon $it")
            return
        }
        // Announce first so the server relays our very first chunk
        MainActivity.syncClient?.sendVoiceStart(username)
        if (!voiceRecorder.startRecording(context)) {
            MainActivity.syncClient?.sendVoiceEnd()
            toast("Mikrofon konnte nicht gestartet werden")
            return
        }
        isTransmitting = true
        // Duck our own music too, otherwise the mic broadcasts it back to everyone
        engine.setDucked(true)
    }

    fun stopTransmit(notifyServer: Boolean = true) {
        if (!isTransmitting) return
        isTransmitting = false
        voiceRecorder.stopRecording()
        if (notifyServer) MainActivity.syncClient?.sendVoiceEnd()
        if (activeVoiceSpeaker == null) engine.setDucked(false)
    }

    // ── Auto-calibration ──────────────────────────────────────────────────

    /** Host only, microphone permission granted. */
    fun startAutoCalibration() {
        if (calibrationState.value is Calibration.Running) return
        calibrationState.value = Calibration.Running("Bereite vor …")
        MainActivity.syncClient?.sendCalibrationStart()
    }

    private fun runCalibration(slotsJson: org.json.JSONArray, untilMs: Long) {
        val slots = (0 until slotsJson.length()).map { i ->
            val o = slotsJson.getJSONObject(i)
            AutoCalibrator.Slot(o.getString("id"), o.optString("name"), o.getLong("atMs"), o.optBoolean("isHost"))
        }
        ui { calibrationState.value = Calibration.Running("Hört zu … bitte leise sein") }
        val cal = AutoCalibrator(context).also { calibrator = it }
        Thread {
            val (results, error) = cal.run(slots, untilMs) { JamClock.nowMs() + engine.serverTimeOffset }
            calibrator = null
            if (results == null) {
                ui { calibrationState.value = Calibration.Failed(error ?: "Fehlgeschlagen") }
                return@Thread
            }
            MainActivity.syncClient?.sendCalibrationResult(results.filter { !it.isHost }.map { it.participantId to it.deltaMs })
            ui { calibrationState.value = Calibration.Done(results) }
        }.start()
    }

    fun dismissCalibration() {
        calibrator?.cancel()
        calibrationState.value = Calibration.Idle
    }

    // ── Hosting & joining ─────────────────────────────────────────────────

    fun updateUsername(name: String) {
        username = name.trim().ifEmpty { "Gast" }
        prefs.edit().putString("username", name.trim()).apply()
    }

    /**
     * Opens the app's own WLAN (for places without WLAN or with a guest WLAN) and hosts in it.
     * [onReady] runs once the session is up, e.g. to show the QR codes.
     */
    fun hostWithOwnNetwork(onReady: () -> Unit) {
        if (isStartingHost || isConnected || LocalHotspot.isStartingState.value) return
        LocalHotspot.start(context) { error ->
            if (error != null) {
                toast(error, long = true)
                return@start
            }
            host(freshSockets = true, onReady = onReady)
        }
    }

    /** Starts the local server and joins it; [restore] continues a session that was killed. */
    fun host(restore: SessionStore.Saved? = null, freshSockets: Boolean = false, onReady: () -> Unit = {}) {
        if (isStartingHost || isConnected) return
        isStartingHost = true
        Thread {
            val ok = try {
                if (freshSockets) {
                    // Sockets created while the app was pinned to the old WLAN would answer through it –
                    // recreate both servers so they serve the new network
                    MainActivity.localServer?.let { try { it.stop(500) } catch (_: Exception) {} }
                    MainActivity.localServer = null
                    MainActivity.localFileServer?.let { old ->
                        old.stop()
                        MainActivity.localFileServer = LocalFileServer(context, 8081).also { it.start() }
                    }
                }
                if (MainActivity.localServer == null) {
                    MainActivity.localServer = LocalMusicServer(8887).also { it.start() }
                    // Wait briefly for the server socket to bind
                    Thread.sleep(400)
                }
                // Continue a killed session: files first (their ids are referenced by the track URLs)
                if (restore != null) {
                    MainActivity.localServer?.resetSession()
                    MainActivity.localFileServer?.restore(restore.files)
                    MainActivity.localServer?.restore(restore.server)
                }
                connect("127.0.0.1")
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            ui {
                isStartingHost = false
                if (!ok) {
                    toast("Fehler beim Starten des Host-Servers!")
                    return@ui
                }
                isHost = true
                MainActivity.isHostMode = true
                isConnected = true
                MainActivity.isSessionActive = true
                JamSessionService.start(context, "host")
                val myIp = getRawIpAddress(context)
                val sessionName = if (username.isNotBlank() && username != "Gast") "${username}s Jam" else "CSM Jam"
                MainActivity.localServer?.let { server ->
                    server.sessionName = sessionName
                    server.onStateChanged = { SessionStore.requestSave(context, server, MainActivity.localFileServer, myIp) }
                    SessionStore.requestSave(context, server, MainActivity.localFileServer, myIp)
                }
                if (restore != null) toast("✦ Session wiederhergestellt")
                onReady()
                SessionDiscoveryManager.startAdvertising(
                    context = context,
                    sessionName = sessionName,
                    hostIp = myIp,
                    port = 8887,
                    getInfo = { Pair(MainActivity.localServer?.getParticipantCount() ?: 0, MainActivity.localServer?.getCurrentTrackTitle()) }
                )
            }
        }.start()
    }

    fun join(ip: String) {
        if (isConnecting) return
        isConnecting = true
        hostIpState.value = ip
        Thread {
            val ok = try { connect(ip) } catch (e: Exception) { e.printStackTrace(); false }
            ui {
                isConnecting = false
                if (ok) {
                    isConnected = true
                    MainActivity.isSessionActive = true
                    isHost = false
                    MainActivity.isHostMode = false
                    JamSessionService.start(context, "guest")
                } else {
                    toast("Keine Verbindung zu ${connectedHostIp.ifBlank { ip }} – seid ihr im selben WLAN? Gast-WLANs blockieren Geräte untereinander, dann Hotspot nutzen.", long = true)
                }
            }
        }.start()
    }

    /** Blocking: opens the socket to [ip] and waits up to 5 s for it. */
    private fun connect(ip: String): Boolean {
        val cleanIp = ip.trim().substringAfter("://").substringBefore(":").substringBefore("/").trim()
        // A typo like 192.168.192.168.1.5 used to hang 60 s on a nonsense address – fail right away instead
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(cleanIp)) return false
        connectedHostIp = cleanIp
        createClient(java.net.URI("ws://$cleanIp:8887"), previous = null)
        var waited = 0
        while (MainActivity.syncClient?.isOpen != true && waited < 10) {
            Thread.sleep(500)
            waited++
        }
        val open = MainActivity.syncClient?.isOpen == true
        if (open) MainActivity.syncClient?.requestState()
        return open
    }

    private fun createClient(uri: java.net.URI, previous: MusicSyncClient?) {
        val client = MusicSyncClient(
            serverUri = uri,
            username = username,
            onStateUpdated = { playing, track, startedAt, offset, repeat -> engine.onState(playing, track, startedAt, offset, repeat) },
            onListsUpdated = { _, _, _, _ -> },
            // Playback goes straight into the engine – independent of any UI being alive
            onPlayReceived = { trackUrl, startedAt, offset, crossfade, from -> engine.onPlay(trackUrl, startedAt, offset, crossfade, from) },
            onPauseReceived = { offset -> engine.onPause(offset) },
            onPrepareReceived = { trackUrl -> engine.onPrepare(trackUrl) },
            onTimeOffsetCalculated = { offset -> engine.serverTimeOffset = offset }
        )
        // After a reconnect every callback (voice, permissions, party light, …) moves over from the old client
        if (previous != null) client.copyCallbacksFrom(previous) else attach(client)
        client.onSyncStatsUpdated = { offsetMs, rtt ->
            engine.serverTimeOffset = offsetMs
            ui { engine.rttMs = rtt }
        }
        client.onPreloadReceived = { url -> engine.onPreload(url) }
        client.onCrossfadeChanged = { ms -> engine.setCrossfade(ms) }
        // Auto-calibration: music off, play our chirps on cue, take over the measured correction
        client.onCalibrationStart = { untilMs -> engine.muteUntil(untilMs - engine.serverTimeOffset) }
        client.onCalibrationChirp = { times -> engine.playCalibrationChirps(times) }
        client.onSyncTest = { enabled -> engine.setSyncTest(enabled) }
        client.onCalibrationApply = { delta ->
            engine.setCalibration(engine.calibrationMs + delta)
            toast("◈ Auto-Kalibrierung: ${if (delta > 0) "+" else ""}$delta ms")
        }
        client.onDisconnected = { _, _ ->
            val finalReason = client.finalCloseReason
            if (finalReason != null && !client.intentionalClose && MainActivity.isSessionActive) {
                // Kicked, declined or the host ended the session: reconnecting would be wrong
                ui { terminatedReasonState.value = finalReason }
            } else if (!client.intentionalClose && MainActivity.isSessionActive) {
                Thread {
                    try {
                        Thread.sleep(3000)
                        if (!client.intentionalClose && MainActivity.isSessionActive) createClient(uri, client)
                    } catch (_: InterruptedException) {}
                }.start()
            }
        }
        MainActivity.syncClient = client
        client.connect()
    }

    /** Server ended our membership: leave and say why. */
    fun handleTermination(reason: String) {
        terminatedReasonState.value = null
        if (!isConnected) return
        leave()
        toast(
            when (reason) {
                LocalMusicServer.CLOSE_KICKED -> "Du wurdest vom Host aus der Session entfernt"
                LocalMusicServer.CLOSE_DENIED -> "Der Host hat deine Anfrage abgelehnt"
                LocalMusicServer.CLOSE_SESSION_ENDED, LocalMusicServer.CLOSE_NO_SESSION -> "Der Host hat die Session beendet"
                else -> reason
            },
            long = true
        )
    }

    // ── Leaving ───────────────────────────────────────────────────────────

    /** Leaves the session and resets everything (button, kick, host ended the session). */
    fun leave() {
        // Leaving on purpose → nothing to resume
        MainActivity.localServer?.onStateChanged = null
        if (MainActivity.isHostMode) SessionStore.clear(context)
        stopTransmit()
        voicePlayback.stop()
        partyLight.stop()
        voiceRecorder.stopRecording()
        calibrator?.cancel()
        calibrationState.value = Calibration.Idle
        isPartyLightActive = false
        activeVoiceSpeaker = null
        awaitingApproval = false
        uploadStatus = null
        MainActivity.syncClient?.intentionalClose = true
        MainActivity.syncClient?.close()
        MainActivity.syncClient = null
        MainActivity.localServer?.resetSession()
        MainActivity.localFileServer?.reset()
        engine.stopSession()
        MainActivity.isSessionActive = false
        MainActivity.isHostMode = false
        isConnected = false
        isHost = false
        isConnecting = false
        isStartingHost = false
        connectedHostIp = ""
        queue.clear()
        participants.clear()
        playedLog.clear()
        joinRequests.clear()
        trackAddedBy.clear()
        SessionDiscoveryManager.stopAdvertising()
        JamSessionService.stop(context)
        // Own WLAN closes with the session – the phone goes back to its normal WLAN
        if (LocalHotspot.isActive) LocalHotspot.stop()
        fallbackTracks.clear()
        fallbackFolder = null
    }
}
