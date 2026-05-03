@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.csm.jam

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicTextField
import sh.calvin.reorderable.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import java.net.URI

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    companion object {
        var localServer: LocalMusicServer? = null
        var localFileServer: LocalFileServer? = null
        var syncClient: MusicSyncClient? = null
                var isSessionActive: Boolean = false
        var isHostMode: Boolean = false
                var calibrationOffset: Float = 0f
        var activeMediaSession: MediaSession? = null  // exposed for JamSessionService notification
        var instance: MainActivity? = null
    }

    enum class AppMode { NONE, OFFLINE }

    internal var exoPlayer: ExoPlayer? = null
    private var serverTimeOffset: Long = 0L

    var isPlayingState by mutableStateOf(false)
    var lastStartedAt by mutableStateOf(0L)
    var lastPauseOffset by mutableStateOf(0L)
    var currentTrack by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                50000, // maxBufferMs
                250,   // bufferForPlaybackMs - Extremely fast ready state (250ms)!
                500    // bufferForPlaybackAfterRebufferMs
            )
            .build()
        
        // Create a custom DataSource factory that includes User-Agent and Consent Cookies 
        // to prevent YouTube from returning HTML/403 pages instead of audio streams.
        // We MUST use a YouTube Android User-Agent because yt-dlp signs the URL for the Android client
        // (the URL contains &c=ANDROID) and YouTube will reject mismatches with 403 Forbidden.
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        val currentTrack = exoPlayer?.currentMediaItem?.mediaId
                        if (currentTrack != null) {
                            syncClient?.sendTrackReady(currentTrack)
                        }
                        
                        // LATE CATCH-UP LOGIC:
                        // If we just became ready but the server says we should already be playing,
                        // check if we are behind and seek to the correct position.
                        // This fixes sync issues after skipping or slow buffering.
                        if (isPlayingState && lastStartedAt > 0) {
                            val nowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
                            val elapsedMs = nowMs - lastStartedAt
                            val targetPos = lastPauseOffset + elapsedMs
                            val currentPos = exoPlayer?.currentPosition ?: 0L
                            
                            // Only seek if we are significantly out of sync (> 150ms)
                            // This avoids the infinite "seek-loop" because seeking itself
                            // triggers another STATE_READY event.
                            if (kotlin.math.abs(currentPos - targetPos) > 150) {
                                exoPlayer?.seekTo(if (targetPos > 0) targetPos else 0)
                                exoPlayer?.playWhenReady = true
                            }
                        }
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        // Track finished playing – tell the server to advance the queue
                        val finishedTrack = exoPlayer?.currentMediaItem?.mediaId
                        if (finishedTrack != null) {
                            syncClient?.sendTrackFinished(finishedTrack)
                        }
                    }
                }
            }
        })

        // Wrap ExoPlayer in a ForwardingPlayer so the MediaSession believes
        // "Next" and "Previous" are always available, causing the system 
        // Media Style notification (and lockscreen) to actually show those buttons.
        val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(exoPlayer!!) {
            override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                syncClient?.sendSkipNext()
            }
            
            override fun seekToNextMediaItem() {
                syncClient?.sendSkipNext()
            }

            override fun seekToPrevious() {
                syncClient?.sendSkipPrev()
            }
            
            override fun seekToPreviousMediaItem() {
                syncClient?.sendSkipPrev()
            }
        }

        // Build MediaSession – hooks up lockscreen controls and Bluetooth headset events
        activeMediaSession = androidx.media3.session.MediaSession.Builder(this, forwardingPlayer).build()

        // Lokalen HTTP Server für eigene MP3s als Singleton starten
        if (localFileServer == null) {
            localFileServer = LocalFileServer(applicationContext, 8081)
            try {
                localFileServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1DB954), // Spotify Green Outline / Buttons
                    background = Color(0xFF121212),
                    surface = Color(0xFF282828),
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMediaSession?.release()
        activeMediaSession = null
        exoPlayer?.release()
        // We do NOT stop the servers or service here anymore.
        // This allows the host to leave the app and return without killing the session.
        // Session only stops when the user explicitly clicks "Leave Session".
    }

    @Composable
    fun AppContent() {
        // Mode selector: show on fresh launch, skip if returning to active session
        var appMode by remember {
            mutableStateOf(when {
                isSessionActive -> AppMode.OFFLINE
                else -> AppMode.NONE
            })
        }

        // Cleanup when switching modes
        LaunchedEffect(appMode) {
            if (appMode == AppMode.NONE) {
                if (!isSessionActive) {
                    syncClient?.intentionalClose = true
                    syncClient?.close()
                    syncClient = null
                }
            }
        }

        var globalUsername by remember { mutableStateOf("Gast") }

        if (appMode == AppMode.NONE) {
            ModeSelectionScreen(
                onOffline = { name -> globalUsername = name; appMode = AppMode.OFFLINE }
            )
            return
        }



        // ---- OFFLINE MODE (existing code below, unchanged) ----
        var isConnected by remember { mutableStateOf(isSessionActive) }
        var isHost by remember { mutableStateOf(isHostMode) }
        var hostIp by remember { mutableStateOf("192.168.") }
        
        var calibrationOffsetMs by remember { mutableStateOf(calibrationOffset) }
        
        // Persist calibration back to static
        LaunchedEffect(calibrationOffsetMs) {
            calibrationOffset = calibrationOffsetMs
            
            // REAL-TIME SYNC DURING DRAG:
            // If we are currently playing, adjust the seek position immediately
            // so the user can hear the calibration change in real-time.
            if (isPlayingState && lastStartedAt > 0) {
                val nowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
                val elapsedMs = nowMs - lastStartedAt
                val targetPos = lastPauseOffset + elapsedMs
                exoPlayer?.seekTo(if (targetPos > 0) targetPos else 0)
            }
        }
        
        var showFullPlayer by remember { mutableStateOf(false) }
        
        val queue = remember { mutableStateListOf<String>() }
        val participants = remember { mutableStateListOf<String>() }
        val trackTitles = remember { mutableStateMapOf<String, String>() }
        val trackArtworks = remember { mutableStateMapOf<String, String>() }

        var guestsCanControl by remember { mutableStateOf(false) }
        var isRepeatSingle by remember { mutableStateOf(false) }

        var pendingDownloadUrl by remember { mutableStateOf<String?>(null) }
        val saveFileLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("audio/mpeg")
        ) { uri ->
            uri?.let { destUri ->
                pendingDownloadUrl?.let { url ->
                    downloadAndSave(this@MainActivity, url, destUri)
                }
            }
        }

        // Re-attach to existing session if active
        LaunchedEffect(isConnected) {
            if (isConnected && syncClient != null) {
                val client = syncClient ?: return@LaunchedEffect
                client.onListsUpdated = { q, p, titles, arts ->
                    queue.clear(); queue.addAll(q)
                    participants.clear(); participants.addAll(p)
                    trackTitles.clear(); trackTitles.putAll(titles)
                    trackArtworks.clear(); trackArtworks.putAll(arts)
                }
                client.onStateUpdated = { playing, track, startedAtMs, pauseOffsetMs, repeatSingle ->
                    if (track != null) currentTrack = track
                    isPlayingState = playing
                    lastStartedAt = startedAtMs
                    lastPauseOffset = pauseOffsetMs
                    isRepeatSingle = repeatSingle
                    val resolvedTitle = if (track != null) {
                        (trackTitles[track] ?: extractFileNameFromUrl(track)).toString()
                    } else "Warten auf Song..."
                    JamSessionService.updateTrack(this@MainActivity, if (isHostMode) "host" else "guest", resolvedTitle, playing)
                }
                client.onPlayReceived = { trackUrl, startedAtMs, pauseOffsetMs ->
                    val receivedAtMs = System.currentTimeMillis()
                    runOnUiThread {
                        // REMOVED: if (trackUrl != currentTrack || lastStartedAt != startedAtMs || lastPauseOffset != pauseOffsetMs)
                        // We must always process a PLAY command to ensure sync, 
                        // even if currentTrack was already updated by onStateUpdated.
                        currentTrack = trackUrl
                        lastStartedAt = startedAtMs
                        lastPauseOffset = pauseOffsetMs
                        
                        // Fresh sync for offline mode
                        syncClient?.pingServer(true)
                        
                        // For LAN mode we don't sync headers yet, but we'll pass null
                        handlePlay(trackUrl, startedAtMs, pauseOffsetMs, receivedAtMs, calibrationOffset.toLong(), null) 
                    }
                }
                client.onPauseReceived = { offset ->
                    runOnUiThread { 
                        isPlayingState = false
                        lastPauseOffset = offset
                        handlePause(offset) 
                    }
                }
                client.onPrepareReceived = { trackUrl ->
                    handlePrepare(trackUrl)
                }
                client.onTimeOffsetCalculated = { offset ->
                    serverTimeOffset = offset
                }
                client.onGuestPermissionsChanged = { allow ->
                    runOnUiThread { guestsCanControl = allow }
                }
                // Request fresh state to populate UI
                client.requestState()
            }
        }


        // Helper: get display title for a URL
        fun titleFor(url: String?) = trackTitles[url] ?: extractFileNameFromUrl(url)

        fun resolveArtwork(url: String?): Any? {
            if (url == null) return null
            if (isHostMode && url.contains("/art/")) {
                val fileId = url.substringAfterLast("/")
                val localBytes = localFileServer?.getArtwork(fileId)
                if (localBytes != null) return localBytes
            }
            return url
        }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris: List<Uri> ->
            val myIp = getRawIpAddress(this@MainActivity)
            var added = 0
            uris.forEach { uri ->
                val fileName = getFileName(this@MainActivity, uri)
                val title = getAudioTitle(this@MainActivity, uri) ?: fileName.removeSuffix(".mp3")
                val hostResult = localFileServer?.hostFile(uri)
                if (hostResult != null) {
                    val fileId = hostResult.first
                    val hasArt = hostResult.second
                    val remoteUrl = "http://$myIp:8081/$fileId/${Uri.encode(fileName)}"
                    val artUrl = if (hasArt) "http://$myIp:8081/art/$fileId" else null
                    syncClient?.sendAddTrack(remoteUrl, title, artUrl)
                    added++
                }

            }
            if (added > 0) {
                Toast.makeText(this@MainActivity, "$added Track(s) hinzugefügt!", Toast.LENGTH_SHORT).show()
            }
        }


                if (!isConnected) {
                    androidx.activity.compose.BackHandler { appMode = AppMode.NONE }
                    // ----- CONNECT SCREEN -----
                    val connectScrollState = androidx.compose.foundation.rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(connectScrollState)
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Lokales WLAN", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("\"Offline\" / LAN Modus", fontSize = 14.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        Button(
                            onClick = {
                                startHosting(
                                    username = globalUsername,
                                    onListsUpdated = { q: List<String>, p: List<String>, titles: Map<String, String>, arts: Map<String, String> ->
                                        queue.clear(); queue.addAll(q)
                                        participants.clear(); participants.addAll(p)
                                        trackTitles.clear(); trackTitles.putAll(titles)
                                        trackArtworks.clear(); trackArtworks.putAll(arts)
                                    },
                                    onStateUpdated = { playing: Boolean, track: String?, startedAtMs: Long, pauseOffsetMs: Long, _: Boolean ->
                                        if (track != null) currentTrack = track
                                        isPlayingState = playing
                                        lastStartedAt = startedAtMs
                                        lastPauseOffset = pauseOffsetMs
                                        val resolvedTitle = if (track != null) {
                                            (trackTitles[track] ?: extractFileNameFromUrl(track)).toString()
                                        } else "Warten auf Song..."
                                        JamSessionService.updateTrack(this@MainActivity, "host", resolvedTitle, playing)
                                    },
                                    getCalibrationOffset = { calibrationOffsetMs.toLong() }
                                ) { connected: Boolean ->
                                    if (connected) {
                                        isHost = true
                                        isHostMode = true
                                        isConnected = true
                                        isSessionActive = true
                                        JamSessionService.start(this@MainActivity, "host")
                                    }
                                }
                            }, 
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Text("Session Hosten (Lokaler Server)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        OutlinedTextField(
                            value = hostIp,
                            onValueChange = { hostIp = it },
                            label = { Text("Host IP-Adresse") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                connectToHost(
                                    ip = hostIp,
                                    username = globalUsername,
                                    onListsUpdated = { q: List<String>, p: List<String>, titles: Map<String, String>, arts: Map<String, String> ->
                                        queue.clear()
                                        queue.addAll(q)
                                        participants.clear()
                                        participants.addAll(p)
                                        trackTitles.clear()
                                        trackTitles.putAll(titles)
                                        trackArtworks.clear()
                                        trackArtworks.putAll(arts)
                                    },
                                    onStateUpdated = { playing: Boolean, track: String?, startedAtMs: Long, pauseOffsetMs: Long, _: Boolean ->
                                        if (track != null) currentTrack = track
                                        isPlayingState = playing
                                        lastStartedAt = startedAtMs
                                        lastPauseOffset = pauseOffsetMs
                                        val resolvedTitle = if (track != null) {
                                            (trackTitles[track] ?: extractFileNameFromUrl(track)).toString()
                                        } else "Warten auf Song..."
                                        JamSessionService.updateTrack(this@MainActivity, "guest", resolvedTitle, playing)
                                    },
                                    getCalibrationOffset = { calibrationOffsetMs.toLong() }
                                ) { connected: Boolean ->
                                    if (connected) {
                                        isConnected = true
                                        isSessionActive = true
                                        isHost = false
                                        isHostMode = false
                                        JamSessionService.start(this@MainActivity, "guest")
                                    }
                                }
                            }, 
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Text("Session Beitreten", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        TextButton(onClick = { appMode = AppMode.NONE }) {
                            Text("← Zurück zur Modus-Auswahl", color = Color.Gray)
                        }
                    }
                } else {
            // ----- MAIN SESSION UI -----
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (!currentTrack.isNullOrEmpty()) {
                        MiniPlayer(
                            trackName = titleFor(currentTrack),
                            isPlaying = isPlayingState,
                            hasNext = queue.size > 1,
                            artworkUrl = resolveArtwork(trackArtworks[currentTrack]),
                            onPlayPauseClick = {
                                if (isPlayingState) syncClient?.sendPauseAction()
                                else syncClient?.sendPlayAction(null)
                            },
                            onSkipNext = { syncClient?.sendSkipNext() },
                            onExpandClick = { showFullPlayer = true }
                        )

                    }
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(if (isHost) "👑 Host Session" else "👤 Guest Session", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Status: Verbunden", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    if (isHost) {
                        Text("Deine IP (für Gäste): ${getRawIpAddress(this@MainActivity)}", color = Color.Gray, fontSize = 12.sp)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Guest permissions toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Gäste dürfen steuern", color = Color.White, fontSize = 14.sp)
                                Text(
                                    if (guestsCanControl) "Play/Pause/Skip & Songs hinzufügen erlaubt" else "Nur Host kontrolliert die Session",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = guestsCanControl,
                                onCheckedChange = { allow ->
                                    guestsCanControl = allow
                                    syncClient?.sendSetGuestPermissions(allow)
                                }
                            )
                        }
                    }

                    
                    Spacer(modifier = Modifier.height(24.dp))

                    if (isHost || guestsCanControl) {
                        Button(
                            onClick = { filePickerLauncher.launch("audio/*") },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("MP3 vom Gerät hinzufügen", color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    } else {
                        Text(
                            "🔒 Nur der Host kann Songs hinzufügen",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Text("Warteschlange", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (queue.isEmpty()) {
                        Text("Noch keine Songs in der Warteschlange.", color = Color.Gray, fontSize = 14.sp)
                    }
                    val listState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyColumnState(listState) { from, to ->
                        val item = queue.removeAt(from.index)
                        queue.add(to.index, item)
                        syncClient?.sendReorderQueue(from.index, to.index)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(queue, key = { _, url -> url }) { index, trackUrl ->
                            ReorderableItem(reorderableState, key = trackUrl) { isDragging ->
                                val displayName = trackTitles[trackUrl] ?: extractFileNameFromUrl(trackUrl)
                                val isCurrent = trackUrl == currentTrack
                                val canControl = isHost || guestsCanControl
                                val elevation by animateFloatAsState(if (isDragging) 12f else 0f)

                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { dismissValue ->
                                        if (dismissValue == SwipeToDismissBoxValue.EndToStart && canControl && !isCurrent) {
                                            syncClient?.sendRemoveTrack(trackUrl)
                                            true
                                        } else false
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        val color = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                            Color(0xFFD32F2F) else Color.Transparent
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(vertical = 4.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(color),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Entfernen",
                                                tint = Color.White,
                                                modifier = Modifier.padding(end = 16.dp)
                                            )
                                        }
                                    }
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .graphicsLayer { shadowElevation = elevation },
                                        colors = CardDefaults.cardColors(containerColor = if (isCurrent) MaterialTheme.colorScheme.surface else Color(0xFF1E1E1E)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val artUrl = trackArtworks[trackUrl]
                                            Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color.DarkGray),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (artUrl != null) {
                                                AsyncImage(
                                                    model = artUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Filled.MusicNote,
                                                    contentDescription = null,
                                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = displayName.toString(),
                                            fontSize = 15.sp,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (canControl) {
                                            Icon(
                                                Icons.Filled.DragHandle,
                                                contentDescription = "Ziehen zum sortieren",
                                                tint = Color(0xFF555555),
                                                modifier = Modifier.size(20.dp).draggableHandle(
                                                    onDragStopped = { syncClient?.sendReorderQueue(0, 0) /* Server sync is already done in rememberReorderableLazyColumnState */ }
                                                )
                                            )
                                        }
                                        if (!isHost) {
                                            IconButton(
                                                onClick = {
                                                    pendingDownloadUrl = trackUrl
                                                    val fileName = (trackTitles[trackUrl] ?: extractFileNameFromUrl(trackUrl)).toString()
                                                    saveFileLauncher.launch("$fileName.mp3")
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Filled.Download,
                                                    contentDescription = "Download",
                                                    tint = Color.Gray,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }


                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Teilnehmer (${participants.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                        items(participants) { p ->
                            Text(p, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = {
                            // Disconnect everything – mark as intentional so auto-reconnect doesn't fire
                            syncClient?.intentionalClose = true
                            syncClient?.close()
                            syncClient = null
                            
                            // Keep the local server running to avoid port binding issues on reconnect.
                            // Just reset its state and clear all old clients.
                            localServer?.resetSession()
                            localFileServer?.reset()
                            // Do not stop or null out localFileServer either, keep the HTTP singleton alive.

                            
                            exoPlayer?.stop()
                            
                            // Reset Session Statics
                            isSessionActive = false
                            isHostMode = false
                            
                            // Reset UI State
                            isConnected = false
                            isHost = false
                            queue.clear()
                            participants.clear()
                            
                            JamSessionService.stop(this@MainActivity)
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Session verlassen")
                    }
                } // Closes Column
            } // Closes Scaffold content
            

        // ----- FULL SCREEN PLAYER -----
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            androidx.activity.compose.BackHandler(enabled = showFullPlayer) {
                showFullPlayer = false
            }
            FullScreenPlayer(
                trackName = titleFor(currentTrack),
                isPlaying = isPlayingState,
                currentIndex = queue.indexOf(currentTrack).coerceAtLeast(0),
                totalTracks = queue.size,
                artworkUrl = resolveArtwork(trackArtworks[currentTrack]),
                calibrationOffsetMs = calibrationOffsetMs,
                onCalibrationChange = { newOffset ->
                    calibrationOffsetMs = newOffset
                },
                onPlayPauseClick = {
                    if (isPlayingState) syncClient?.sendPauseAction()
                    else syncClient?.sendPlayAction(null)
                },
                onSkipNext = { syncClient?.sendSkipNext() },
                onSkipPrev = { syncClient?.sendSkipPrev() },
                onForceSync = {
                    syncClient?.pingServer(true)
                    syncClient?.sendForceSync()
                    // Apply calibration locally immediately if playing
                    if (isPlayingState && !currentTrack.isNullOrEmpty()) {
                        handlePlay(currentTrack!!, lastStartedAt, lastPauseOffset, System.currentTimeMillis(), calibrationOffsetMs.toLong())
                    }
                },
                onCollapseClick = { showFullPlayer = false },
                isHost = isHost || guestsCanControl,
                isRepeatSingle = isRepeatSingle,
                onToggleRepeat = { syncClient?.sendSetRepeatSingle(!isRepeatSingle) },
                onSeek = { seekToMs -> syncClient?.sendSeekAction(seekToMs) },
                onShuffle = { syncClient?.sendShuffle() }
            )
        }
    }
}

    @Composable
    fun MiniPlayer(
        trackName: String,
        isPlaying: Boolean,
        hasNext: Boolean = false,
        artworkUrl: Any? = null,
        onPlayPauseClick: () -> Unit,
        onSkipNext: () -> Unit = {},
        onExpandClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { onExpandClick() },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art placeholder
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(Color(0xFF1DB954), Color(0xFF0D7A3A))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkUrl != null) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                val parts = trackName.split(" - ", limit = 2)
                val finalTitle = parts[0]
                val finalArtist = parts.getOrNull(1) ?: "CSM Jam Sync"

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = finalTitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = finalArtist,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play/Pause
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Skip Next
                if (hasNext) {
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Weiter",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun FullScreenPlayer(
        trackName: String,
        isPlaying: Boolean,
        currentIndex: Int = 0,
        totalTracks: Int = 1,
        isHost: Boolean = false,
        artworkUrl: Any? = null,
        calibrationOffsetMs: Float = 0f,
        onCalibrationChange: (Float) -> Unit = {},
        onPlayPauseClick: () -> Unit,
        onSkipNext: () -> Unit = {},
        onSkipPrev: () -> Unit = {},
        onForceSync: () -> Unit = {},
        onCollapseClick: () -> Unit,
        isRepeatSingle: Boolean = false,
        onToggleRepeat: () -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onShuffle: () -> Unit = {},
        isOnlineMode: Boolean = false
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212)
        ) {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var showSyncDialog by remember { mutableStateOf(false) }

                // Top bar: collapse + title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapseClick) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Schließen", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("JETZT LÄUFT", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        if (totalTracks > 1) {
                            Text("${currentIndex + 1} / $totalTracks", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = { showSyncDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Sync & Einstellungen", tint = Color.Gray, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Album art
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Color(0xFF1DB954).copy(alpha = 0.3f), Color(0xFF121212))
                            )
                        )
                        .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkUrl != null) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF1DB954).copy(alpha = 0.5f),
                            modifier = Modifier.size(100.dp)
                        )
                    }
                }


                Spacer(modifier = Modifier.height(32.dp))

                // Debounced Calibration seeking to prevent MediaCodec crashes
                var lastAppliedCalibration by remember { mutableStateOf(calibrationOffsetMs) }
                LaunchedEffect(calibrationOffsetMs) {
                    if (calibrationOffsetMs != lastAppliedCalibration) {
                        kotlinx.coroutines.delay(50) // Faster debounce for live feel
                        if (isPlaying) {
                            val diff = calibrationOffsetMs - lastAppliedCalibration
                            val currentPos = exoPlayer?.currentPosition ?: 0L
                            // Positive diff: increasing offset makes audio play EARLIER (seek forward)
                            // to compensate for output delay (e.g. Bluetooth)
                            exoPlayer?.seekTo(currentPos + diff.toLong())
                        }
                        lastAppliedCalibration = calibrationOffsetMs
                    }
                }

                val parts = trackName.split(" - ", limit = 2)
                val finalTitle = parts[0]
                val finalArtist = parts.getOrNull(1)

                // Track name + sync badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = finalTitle,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (finalArtist != null) {
                            Text(
                                text = finalArtist,
                                color = Color.LightGray,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlaying) Color(0xFF1DB954) else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isPlaying) "Synced · Alle Geräte" else "Pausiert",
                                color = if (isPlaying) Color(0xFF1DB954) else Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Bar
                var currentPositionMs by remember { mutableStateOf(0L) }
                var currentDurationMs by remember { mutableStateOf(1L) } 
                
                var isDraggingSlider by remember { mutableStateOf(false) }
                var sliderPositionMs by remember { mutableStateOf(0f) }

                LaunchedEffect(isPlaying, exoPlayer) {
                    while (true) {
                        exoPlayer?.let {
                            if (it.playbackState == androidx.media3.common.Player.STATE_READY || it.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                                currentPositionMs = it.currentPosition
                                currentDurationMs = if (it.duration > 0) it.duration else 1L
                            }
                        }
                        kotlinx.coroutines.delay(100)
                    }
                }

                val displayMs = if (isDraggingSlider) sliderPositionMs else currentPositionMs.toFloat()
                
                fun formatTime(ms: Long): String {
                    if (ms < 0) return "0:00"
                    val sec = ms / 1000
                    val m = sec / 60
                    val s = sec % 60
                    return String.format("%d:%02d", m, s)
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Slider(
                        value = displayMs,
                        valueRange = 0f..currentDurationMs.toFloat(),
                        onValueChange = { newVal ->
                            isDraggingSlider = true
                            sliderPositionMs = newVal
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            onSeek(sliderPositionMs.toLong())
                        },
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF1DB954),
                            activeTrackColor = Color(0xFF1DB954),
                            inactiveTrackColor = Color.DarkGray
                        ),
                        enabled = isHost
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(displayMs.toLong()), color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text(formatTime(currentDurationMs), color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Controls row
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Repeat Single Toggle (left aligned)
                    IconButton(
                        onClick = onToggleRepeat,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(56.dp),
                        enabled = isHost
                    ) {
                        Text(
                            "1x",
                            color = if (isRepeatSingle) Color(0xFF1DB954) else Color.DarkGray,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Main Media Controls (centered)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Skip Previous
                        IconButton(
                            onClick = onSkipPrev,
                            modifier = Modifier.size(56.dp),
                            enabled = isHost && currentIndex > 0
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Vorheriger",
                                tint = if (isHost && currentIndex > 0) Color.White else Color.DarkGray,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Play/Pause main button
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isHost) MaterialTheme.colorScheme.primary
                                    else Color(0xFF1DB954).copy(alpha = 0.5f)
                                )
                                .clickable(enabled = isHost) { onPlayPauseClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        // Skip Next
                        IconButton(
                            onClick = onSkipNext,
                            modifier = Modifier.size(56.dp),
                            enabled = isHost
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Nächster",
                                tint = if (isHost) Color.White else Color.DarkGray,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Shuffle Button (right aligned)
                    IconButton(
                        onClick = onShuffle,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(56.dp),
                        enabled = isHost
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                if (!isHost) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Steuerung nur für den Host verfügbar",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                if (showSyncDialog) {
                    AlertDialog(
                        onDismissRequest = { showSyncDialog = false },
                        title = { Text("Sync Tuning & Kalibrierung", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Audio Offset – local per-device adjustment (works in both online and offline)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Audio Offset", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        var offsetInput by remember(calibrationOffsetMs) { mutableStateOf(calibrationOffsetMs.toInt().toString()) }
                                        BasicTextField(
                                            value = offsetInput,
                                            onValueChange = {
                                                offsetInput = it
                                                val newVal = it.replace(Regex("[^0-9\\-]"), "").toIntOrNull()
                                                if (newVal != null) onCalibrationChange(newVal.toFloat())
                                            },
                                            textStyle = TextStyle(
                                                color = Color(0xFF1DB954),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.End
                                            ),
                                            modifier = Modifier.width(60.dp).padding(end = 4.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1DB954))
                                        )
                                        Text("ms", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Slider(
                                    value = calibrationOffsetMs,
                                    onValueChange = onCalibrationChange,
                                    valueRange = -1000f..1000f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF1DB954),
                                        activeTrackColor = Color(0xFF1DB954),
                                        inactiveTrackColor = Color.DarkGray
                                    )
                                )
                                Text(
                                    "Lokaler Offset für dein Gerät (z.B. für Bluetooth-Verzögerung). Jeder stellt seinen eigenen ein.",
                                    color = Color.Gray, fontSize = 10.sp, lineHeight = 14.sp
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                InfiniteKnob(
                                    value = calibrationOffsetMs.toInt(),
                                    onValueChange = { onCalibrationChange(it.toFloat()) },
                                    modifier = Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 8.dp),
                                    sensitivity = 2f
                                )

                                // Force Sync button available in all modes
                                Spacer(modifier = Modifier.height(24.dp))
                                var showSyncToast by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { onForceSync(); showSyncToast = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF1DB954),
                                        containerColor = Color(0xFF1DB954).copy(alpha = 0.08f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1DB954).copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Force Sync starten", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (showSyncToast) {
                                    LaunchedEffect(Unit) {
                                        kotlinx.coroutines.delay(2000)
                                        showSyncToast = false
                                    }
                                    Text("Kalibrierung angewendet...", color = Color(0xFF1DB954), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSyncDialog = false }) {
                                Text("Schließen", color = Color(0xFF1DB954))
                            }
                        },
                        containerColor = Color(0xFF282828),
                        textContentColor = Color.LightGray
                    )
                }
            }
        }
    }

    private fun extractFileNameFromUrl(url: String?): String {
        if (url == null) return "Nichts"
        val decoded = Uri.decode(url)
        return decoded.substringAfterLast("/").removeSuffix(".mp3")
    }

    private fun getAudioTitle(context: Context, uri: Uri): String? {
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

    private fun getFileName(context: Context, uri: Uri): String {
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

    private fun startHosting(
        username: String,
        onListsUpdated: (List<String>, List<String>, Map<String, String>, Map<String, String>) -> Unit,
        onStateUpdated: (Boolean, String?, Long, Long, Boolean) -> Unit,
        getCalibrationOffset: () -> Long,
        onResult: (Boolean) -> Unit
    ) {
        Thread {
            try {
                // If server already running, reuse it — don't try to rebind port
                if (localServer == null) {
                    localServer = LocalMusicServer(8887)
                    localServer?.start()
                    // Wait briefly for server socket to bind
                    Thread.sleep(400)
                }
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Server läuft auf Port 8887", Toast.LENGTH_SHORT).show()
                }
                
                // Connect to own server via localhost — with real UI callbacks!
                connectToHost("127.0.0.1", username, onListsUpdated, onStateUpdated, getCalibrationOffset, onResult)
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Server-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
                localServer = null
                runOnUiThread { onResult(false) }
            }
        }.start()
    }

    private fun connectToHost(
        ip: String,
        username: String,
        onListsUpdated: (List<String>, List<String>, Map<String, String>, Map<String, String>) -> Unit,
        onStateUpdated: (Boolean, String?, Long, Long, Boolean) -> Unit,
        getCalibrationOffset: () -> Long,
        onResult: (Boolean) -> Unit
    ) {
        // Run in background to prevent blocking
        Thread {
            try {
                var cleanIp = ip.trim()
                if (cleanIp.contains("://")) {
                    cleanIp = cleanIp.substringAfter("://")
                }
                cleanIp = cleanIp.substringBefore(":").trim()
                
                val uri = URI("ws://$cleanIp:8887")
                
                // Closure to create and bind a fresh client
                fun createNewClient() {
                    val client = MusicSyncClient(
                        serverUri = uri,
                        username = username,
                        onStateUpdated = { isPlaying, trackUrl, startedAtMs, pauseOffsetMs, isRepeatSingle ->
                            runOnUiThread { onStateUpdated(isPlaying, trackUrl, startedAtMs, pauseOffsetMs, isRepeatSingle) }
                            // NOTE: Do NOT call handlePlay here. onStateUpdated fires on initial connect
                            // with stale/zero startedAtMs which causes ExoPlayer to seek past track end.
                            // onPlayReceived (below) handles actual play commands correctly.
                        },
                        onListsUpdated = { q, p, titles, arts ->
                            runOnUiThread { onListsUpdated(q, p, titles, arts) }
                        },
                        onPrepareReceived = { trackUrl ->
                            var playUriStr = trackUrl
                            try {
                                val uriObj = android.net.Uri.parse(trackUrl)
                                val segments = uriObj.pathSegments
                                if (segments.isNotEmpty()) {
                                    val localUri = localFileServer?.getFileUri(segments[0])
                                    if (localUri != null) {
                                        playUriStr = localUri.toString()
                                    }
                                }
                            } catch (e: Exception) {}
                            runOnUiThread { handlePrepare(playUriStr) }
                        },
                        onPlayReceived = { trackUrl, startedAtMs, pauseOffsetMs ->
                            val receivedAtMs = System.currentTimeMillis()
                            runOnUiThread { 
                                handlePlay(trackUrl, startedAtMs, pauseOffsetMs, receivedAtMs, getCalibrationOffset()) 
                            }
                        },
                        onPauseReceived = { offset ->
                            runOnUiThread { handlePause(offset) }
                        },
                        onTimeOffsetCalculated = { offset ->
                            serverTimeOffset = offset
                        }
                    )
                    
                    client.onDisconnected = { reason, remote ->
                        if (!client.intentionalClose && isSessionActive) {
                            println("SyncClient disconnected. Starting reconnection loop...")
                            Thread {
                                var attempt = 0
                                while (!client.intentionalClose && isSessionActive && attempt < 20) {
                                    attempt++
                                    println("Reconnection attempt $attempt...")
                                    try {
                                        Thread.sleep(3000)
                                        if (client.intentionalClose || !isSessionActive) break
                                        createNewClient()
                                        break
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }.start()
                        }
                    }
                    
                    syncClient = client
                    client.connect()
                }

                createNewClient()
                
                // Wait to see if we connected
                var waitAttempts = 0
                while (syncClient?.isOpen == false && waitAttempts < 10) {
                    Thread.sleep(500)
                    waitAttempts++
                }
                
                val result = syncClient?.isOpen ?: false
                if (result) {
                    syncClient?.requestState()
                }
                runOnUiThread { onResult(result) }
                
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { onResult(false) }
            }
        }.start()
    }

    private fun handlePlay(trackUrl: String, startedAtMs: Long, pauseOffsetMs: Long, receivedAtMs: Long = System.currentTimeMillis(), calibrationOffset: Long = 0L, httpHeaders: Map<String, String>? = null) {
        val player = exoPlayer ?: return

        // 1. If we are the one hosting the file, play the local content Uri directly!
        var playUriStr = trackUrl
        try {
            val uri = android.net.Uri.parse(trackUrl)
            val segments = uri.pathSegments
            if (segments.isNotEmpty()) {
                val fileId = segments[0]
                val localUri = localFileServer?.getFileUri(fileId)
                if (localUri != null) {
                    playUriStr = localUri.toString()
                }
            }
        } catch (e: Exception) {}


        // Führe PLAY sofort aus! ExoPlayer streamt die MP3 on the fly von playUriStr (HTTP-URL oder lokaler Pfad).
        // Kein Warten mehr auf den kompletten Download vor dem Start.
        executePlay(player, trackUrl, playUriStr, startedAtMs, pauseOffsetMs, receivedAtMs, calibrationOffset, httpHeaders)
    }

    private fun executePlay(player: androidx.media3.common.Player, trackUrl: String, playUri: String, startedAtMs: Long, pauseOffsetMs: Long, receivedAtMs: Long = System.currentTimeMillis(), calibrationOffset: Long = 0L, httpHeaders: Map<String, String>? = null) {

        val needsLoad = player.currentMediaItem?.mediaId != trackUrl
        if (needsLoad) {
            val mediaItem = MediaItem.Builder().setUri(playUri).setMediaId(trackUrl).build()
            
            player.setMediaItem(mediaItem)
            
            player.prepare()
        }

        // Guard: startedAtMs=0 is an invalid/uninitialized timestamp.
        // Playing from pauseOffset directly instead of calculating an insane seek position.
        if (startedAtMs == 0L) {

            player.seekTo(if (pauseOffsetMs > 0) pauseOffsetMs else 0)
            player.playWhenReady = true
            return
        }

        // IMPORTANT: Use actual current time for seek calculation, NOT the stale receivedAtMs.
        // yt-dlp resolution can take 10-12 seconds, so receivedAtMs is outdated by the time
        // we actually call executePlay. Using System.currentTimeMillis() avoids seeking past end.
        val actualNowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
        val waitTimeMs = startedAtMs - actualNowMs


        if (waitTimeMs > 0) {
            // Future play: server gave us a future timestamp so we all start at the same moment
            player.seekTo(pauseOffsetMs)
            player.playWhenReady = false
            
            Thread {
                try {
                    if (waitTimeMs > 40) {
                        Thread.sleep(waitTimeMs - 20)
                    }
                    // Busy-wait for sub-millisecond precision
                    while (System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong() < startedAtMs) {
                        // Tight loop
                    }
                    runOnUiThread {
                        if (player.currentMediaItem?.mediaId == trackUrl) {
                            // Recalculate NOW to compensate for any UI-thread scheduling delay.
                            // The host has more UI work (server + client), so this delay can be
                            // significant (10–100ms). Without this, the host always plays behind.
                            val nowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
                            val schedulingDelayMs = nowMs - startedAtMs
                            val correctedPosition = pauseOffsetMs + if (schedulingDelayMs > 0) schedulingDelayMs else 0L
                            player.seekTo(correctedPosition)
                            player.playWhenReady = true
                        }
                    }
                } catch (e: Exception) {}
            }.start()
        } else {
            // Late play: seek to catch up based on the most current time possible
            val latestNowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
            val elapsedMs = latestNowMs - startedAtMs
            val exactTrackPositionMs = pauseOffsetMs + elapsedMs
            player.seekTo(if (exactTrackPositionMs >= 0) exactTrackPositionMs else 0)
            player.playWhenReady = true
        }
    }

    private fun handlePrepare(trackUrl: String) {
        // The server asks us to PREPARE this track. It will wait for us to report 
        // ACTION_TRACK_READY (which happens automatically via the ExoPlayer listener in onCreate)
        var playUriStr = trackUrl
        try {
            val uriObj = android.net.Uri.parse(trackUrl)
            val segments = uriObj.pathSegments
            if (segments.isNotEmpty()) {
                val localUri = localFileServer?.getFileUri(segments[0])
                if (localUri != null) {
                    playUriStr = localUri.toString()
                }
            }
        } catch (e: Exception) {}

        runOnUiThread {
            val player = exoPlayer ?: return@runOnUiThread
            val needsLoad = player.currentMediaItem?.mediaId != trackUrl
            if (needsLoad) {
                val mediaItem = MediaItem.Builder().setUri(playUriStr).setMediaId(trackUrl).build()
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = false

                // Trigger sync during preparation so it's fresh for PLAY
                
                    syncClient?.pingServer(true)
                
            } else if (player.playbackState == androidx.media3.common.Player.STATE_READY || player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                // Already prepared and ready, instantly send the ready signal!
                syncClient?.sendTrackReady(trackUrl)
            }
        }
    }

    private fun handlePause(pauseOffsetMs: Long) {
        val player = exoPlayer ?: return
        player.pause()
        if (pauseOffsetMs >= 0) {
            player.seekTo(pauseOffsetMs)
        }
    }


    private fun getLocalIpAddress(context: Context): String {
        val resultList = mutableListOf<String>()
        
        // Show ALL non-loopback IPv4 (no filters — works for WLAN, Hotspot, USB-Tethering, etc)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val ifName = iface.name.lowercase()
                // Skip purely virtual/mobile interfaces - but keep wlan, ap, swlan, softap, tether, eth, usb
                val isMobileData = ifName.startsWith("rmnet") && !ifName.contains("usb")
                val isDummy = ifName.startsWith("dummy") || ifName == "lo"
                if (isMobileData || isDummy) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        resultList.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return if (resultList.isNotEmpty()) {
            resultList.distinct().joinToString("  |  ")
        } else {
            "Unbekannt"
        }
    }

    // Returns a single plain IP suitable for use in URLs.
    // Works 100% offline — no WifiManager needed.
    // WifiManager.connectionInfo returns 0 in hotspot/tethering mode so we can't rely on it.
    private fun getRawIpAddress(context: Context): String {
        val candidates = mutableListOf<Pair<Int, String>>() // (priority, ip)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "Unbekannt"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val ifName = iface.name.lowercase()

                // Skip mobile data interfaces
                if (ifName.startsWith("rmnet") && !ifName.contains("usb")) continue
                if (ifName.startsWith("dummy")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress || addr !is java.net.Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip == "0.0.0.0") continue

                    // Priority: hotspot/AP interfaces first, then WLAN, then everything else
                    val priority = when {
                        ifName.contains("ap") || ifName.contains("softap") || ifName.contains("swlan") -> 0
                        ifName.startsWith("wlan") -> 1
                        ifName.startsWith("eth") || ifName.contains("usb") -> 2
                        else -> 3
                    }
                    candidates.add(priority to ip)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return candidates.minByOrNull { it.first }?.second ?: "Unbekannt"
    }
    // =========================================================================
    // MODE SELECTION SCREEN
    // =========================================================================

    @Composable
    fun ModeSelectionScreen(
        onOffline: (String) -> Unit
    ) {
        val prefs = getSharedPreferences("CSM_Prefs", Context.MODE_PRIVATE)
        var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Static Logo
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Color(0xFF1DB954).copy(alpha = 0.4f), Color.Transparent)
                            ),
                            shape = RoundedCornerShape(70.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MusicNote, 
                        contentDescription = null, 
                        tint = Color(0xFF1DB954), 
                        modifier = Modifier.size(64.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    "CSM Jam", 
                    fontSize = 42.sp, 
                    fontWeight = FontWeight.ExtraBold, 
                    color = Color.White,
                    letterSpacing = 2.sp
                )
                Text(
                    "Local Network Audio Sync", 
                    fontSize = 14.sp, 
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(56.dp))

                // Sleek Username Input
                OutlinedTextField(
                    value = username,
                    onValueChange = { 
                        username = it
                        prefs.edit().putString("username", it.trim()).apply()
                    },
                    placeholder = { Text("Dein Anzeigename", color = Color.Gray) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF1DB954)
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Premium Action Button
                Button(
                    onClick = { onOffline(username.trim().ifEmpty { "Gast ${System.currentTimeMillis() % 100}" }) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor = Color.Black
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 2.dp
                    )
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Jam Session Betreten", 
                        fontSize = 18.sp, 
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Verbindet alle Geräte im gleichen WLAN.\nKein Internet benötigt.", 
                    fontSize = 12.sp, 
                    color = Color.White.copy(alpha = 0.5f), 
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }

    @Composable
    fun InfiniteKnob(
        value: Int,
        onValueChange: (Int) -> Unit,
        modifier: Modifier = Modifier,
        sensitivity: Float = 1f
    ) {
        var accumulatedDelta by remember { mutableStateOf(0f) }
        val currentOnValueChange by rememberUpdatedState(onValueChange)
        val currentValue by rememberUpdatedState(value)
        Box(
            modifier = modifier
                .background(Color(0xFF282828), RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        accumulatedDelta -= dragAmount * sensitivity
                        if (kotlin.math.abs(accumulatedDelta) >= 10f) {
                            val steps = (accumulatedDelta / 10f).toInt()
                            currentOnValueChange(currentValue + steps)
                            accumulatedDelta -= steps * 10f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Offset: $value ms", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("(Hoch/Runter wischen)", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }

    private fun downloadAndSave(context: Context, sourceUrl: String, destUri: Uri) {
        Thread {
            try {
                val url = java.net.URL(sourceUrl)
                val connection = url.openConnection()
                connection.connect()
                val input = connection.getInputStream()
                val output = context.contentResolver.openOutputStream(destUri)
                if (output != null) {
                    input.copyTo(output)
                    output.close()
                }
                input.close()
                runOnUiThread {
                    Toast.makeText(context, "Download abgeschlossen!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}