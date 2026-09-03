@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.csm.jam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.content.ClipData
import android.content.ClipboardManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.MediaRecorder
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import org.json.JSONObject
import android.media.audiofx.Visualizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcEvent
import androidx.palette.graphics.Palette
import androidx.compose.ui.graphics.Brush
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

    data class SessionParticipant(
        val id: String,
        val name: String,
        val isHost: Boolean,
        val canMegaphone: Boolean,
        val canControl: Boolean
    )

    fun parseParticipant(raw: String): SessionParticipant {
        return try {
            if (raw.startsWith("{")) {
                val json = JSONObject(raw)
                SessionParticipant(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    isHost = json.optBoolean("isHost", false),
                    canMegaphone = json.optBoolean("canMegaphone", true),
                    canControl = json.optBoolean("canControl", false)
                )
            } else {
                val isHost = raw.contains("𓆩🜲𓆪") || raw.contains("Host")
                SessionParticipant(
                    id = raw,
                    name = raw,
                    isHost = isHost,
                    canMegaphone = true,
                    canControl = isHost
                )
            }
        } catch (_: Exception) {
            SessionParticipant(id = raw, name = raw, isHost = false, canMegaphone = true, canControl = false)
        }
    }

    class VoiceManager(
        private val onChunkReady: (ByteArray) -> Unit
    ) {
        private var audioRecord: AudioRecord? = null
        @Volatile private var isRecording = false
        private var recordThread: Thread? = null
        private val sampleRate = 16000
        private val channelConfig = AudioFormat.CHANNEL_IN_MONO
        private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        @Synchronized
        fun startRecording(context: Context): Boolean {
            if (isRecording) return true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            return try {
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufSize <= 0) return false
                val bufferSize = (minBufSize * 2).coerceAtLeast(4096)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    false
                } else {
                    record.startRecording()
                    audioRecord = record
                    isRecording = true
                    recordThread = Thread({
                        val buffer = ByteArray(minBufSize.coerceAtLeast(1024))
                        while (isRecording) {
                            val currentRecord = audioRecord ?: break
                            val read = currentRecord.read(buffer, 0, buffer.size)
                            if (read > 0 && isRecording) {
                                onChunkReady(buffer.copyOf(read))
                            } else if (read < 0) {
                                break
                            }
                        }
                    }, "VoiceRecordThread")
                    recordThread?.start()
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        @Synchronized
        fun stopRecording() {
            if (!isRecording && audioRecord == null) return
            isRecording = false
            val threadToJoin = recordThread
            val recordToRelease = audioRecord
            recordThread = null
            audioRecord = null

            try {
                recordToRelease?.stop()
            } catch (_: Exception) {}

            try {
                threadToJoin?.join(400)
            } catch (_: Exception) {}

            try {
                recordToRelease?.release()
            } catch (_: Exception) {}
        }
    }

    class VoicePlaybackManager {
        private var audioTrack: AudioTrack? = null
        private val sampleRate = 16000

        @Synchronized
        fun start() {
            stop()
            try {
                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufSize <= 0) return
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((minBufSize * 2).coerceAtLeast(4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.play()
                    audioTrack = track
                } else {
                    track.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @Synchronized
        fun write(data: ByteArray) {
            try {
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(data, 0, data.size)
                }
            } catch (_: Exception) {}
        }

        @Synchronized
        fun stop() {
            val track = audioTrack
            audioTrack = null
            try {
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.stop()
                    track.release()
                }
            } catch (_: Exception) {}
        }
    }

    enum class PartyLightMode(val label: String) {
        BEAT("Beat-Drop"),
        STROBE_FAST("Strobe Schnell"),
        STROBE_SLOW("Strobe Chill")
    }

    class PartyLightManager(
        private val context: Context,
        private val getPlayer: () -> ExoPlayer?,
        private val getIsPlaying: () -> Boolean
    ) {
        private var isRunning = false
        private var strobeJob: Job? = null
        private var visualizer: Visualizer? = null
        private var lastFlashTime = 0L
        @Volatile private var isTorchCurrentlyOn = false
        var mode: PartyLightMode = PartyLightMode.BEAT

        fun start(coroutineScope: CoroutineScope, currentMode: PartyLightMode = mode) {
            mode = currentMode
            if (isRunning) return
            isRunning = true

            val cameraManager = try {
                context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            } catch (_: Exception) { null } ?: return

            val cameraId = try {
                cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (_: Exception) { null } ?: return

            fun safeSetTorch(on: Boolean) {
                if (isTorchCurrentlyOn == on) return
                try {
                    cameraManager.setTorchMode(cameraId, on)
                    isTorchCurrentlyOn = on
                } catch (_: Exception) {}
            }

            var hasVisualizerEnergy = false

            // Hook Visualizer for beat mode
            val audioSessionId = getPlayer()?.audioSessionId ?: 0
            if (audioSessionId != 0 && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val range = Visualizer.getCaptureSizeRange()
                    visualizer = Visualizer(audioSessionId).apply {
                        captureSize = range[0]
                        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                                if (!isRunning || !getIsPlaying() || waveform == null || mode != PartyLightMode.BEAT) return
                                var sum = 0.0
                                for (b in waveform) {
                                    val sample = (b.toInt() and 0xFF) - 128
                                    sum += sample * sample
                                }
                                val rms = Math.sqrt(sum / waveform.size)
                                if (rms > 26.0) {
                                    hasVisualizerEnergy = true
                                    val now = System.currentTimeMillis()
                                    if (now - lastFlashTime > 160) {
                                        lastFlashTime = now
                                        coroutineScope.launch(Dispatchers.Main) {
                                            safeSetTorch(true)
                                            delay(50)
                                            safeSetTorch(false)
                                        }
                                    }
                                }
                            }
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                        }, Visualizer.getMaxCaptureRate() / 2, true, false)
                        enabled = true
                    }
                } catch (_: Exception) {
                    visualizer = null
                }
            }

            // Strobe & beat fallback loop
            strobeJob = coroutineScope.launch(Dispatchers.Main) {
                while (isActive && isRunning) {
                    val player = getPlayer()
                    val isPlaying = getIsPlaying() && (player != null && player.playbackState == androidx.media3.common.Player.STATE_READY && player.playWhenReady)
                    if (!isPlaying) {
                        safeSetTorch(false)
                        delay(80)
                        continue
                    }

                    when (mode) {
                        PartyLightMode.STROBE_FAST -> {
                            safeSetTorch(true)
                            delay(40)
                            safeSetTorch(false)
                            delay(80)
                        }
                        PartyLightMode.STROBE_SLOW -> {
                            safeSetTorch(true)
                            delay(60)
                            safeSetTorch(false)
                            delay(240)
                        }
                        PartyLightMode.BEAT -> {
                            if (!hasVisualizerEnergy || visualizer == null) {
                                val currentPos = player?.currentPosition ?: 0L
                                val beatPos = (currentPos % 480)
                                if (beatPos < 65) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastFlashTime > 180) {
                                        lastFlashTime = now
                                        safeSetTorch(true)
                                        delay(50)
                                        safeSetTorch(false)
                                    }
                                }
                            }
                            delay(25)
                        }
                    }
                }
                safeSetTorch(false)
            }
        }

        fun stop() {
            isRunning = false
            try {
                visualizer?.enabled = false
                visualizer?.release()
            } catch (_: Exception) {}
            visualizer = null
            strobeJob?.cancel()
            strobeJob = null
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, false)
                }
                isTorchCurrentlyOn = false
            } catch (_: Exception) {}
        }
    }

    internal var exoPlayer: ExoPlayer? = null
    private var serverTimeOffset: Long = 0L
    private var nfcAdapter: NfcAdapter? = null

    var isPlayingState by mutableStateOf(false)
    var lastStartedAt by mutableStateOf(0L)
    var lastPauseOffset by mutableStateOf(0L)
    var currentTrack by mutableStateOf<String?>(null)
    var pendingJoinIp by mutableStateOf<String?>(null)
    var currentSyncDiffMs by mutableStateOf(0L)
    var currentRttMs by mutableStateOf(0L)
    private var driftJob: Job? = null

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "csmjam" && data.host == "join") {
            val host = data.getQueryParameter("host")
            if (!host.isNullOrBlank()) {
                pendingJoinIp = host.trim()
            }
        }
    }

    private var lastHardSeekTime: Long = 0L

    private fun startDriftCorrectionLoop() {
        driftJob?.cancel()
        driftJob = lifecycleScope.launch {
            val recentDiffs = ArrayDeque<Long>()
            while (isActive) {
                delay(400)
                if (isPlayingState && lastStartedAt > 0L) {
                    val player = exoPlayer ?: continue
                    if (player.playbackState != androidx.media3.common.Player.STATE_READY) continue
                    if (!player.playWhenReady) continue

                    val nowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
                    val expectedElapsedMs = nowMs - lastStartedAt
                    if (expectedElapsedMs < 0) continue

                    val expectedPositionMs = lastPauseOffset + expectedElapsedMs
                    val currentPosMs = player.currentPosition
                    val rawDiffMs = currentPosMs - expectedPositionMs

                    recentDiffs.addLast(rawDiffMs)
                    if (recentDiffs.size > 3) recentDiffs.removeFirst()
                    val diffMs = recentDiffs.average().toLong()

                    currentSyncDiffMs = diffMs
                    val absDiff = kotlin.math.abs(diffMs)

                    when {
                        // Large desync (> 400ms): hard seek to snap back with 1.5s cooldown
                        absDiff > 400 -> {
                            val now = System.currentTimeMillis()
                            if (now - lastHardSeekTime > 1500) {
                                lastHardSeekTime = now
                                if (player.playbackParameters.speed != 1.0f) {
                                    player.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f)
                                }
                                player.seekTo(expectedPositionMs.coerceAtLeast(0L))
                            }
                        }
                        // Running ahead (> 20ms): gently slow down to 0.98x
                        diffMs > 20 -> {
                            if (player.playbackParameters.speed != 0.98f) {
                                player.playbackParameters = androidx.media3.common.PlaybackParameters(0.98f)
                            }
                        }
                        // Running behind (< -20ms): gently speed up to 1.02x
                        diffMs < -20 -> {
                            if (player.playbackParameters.speed != 1.02f) {
                                player.playbackParameters = androidx.media3.common.PlaybackParameters(1.02f)
                            }
                        }
                        // Synchronized (within [-20ms, +20ms]): lock to 1.0x normal speed
                        else -> {
                            if (player.playbackParameters.speed != 1.0f) {
                                player.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f)
                            }
                        }
                    }
                } else {
                    exoPlayer?.let { p ->
                        if (p.playbackParameters.speed != 1.0f) {
                            p.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    fun shareSession(hostAddress: String) {
        val cleanHost = hostAddress.trim().removePrefix("http://").removePrefix("ws://").substringBefore(":")
        val webLink = "http://$cleanHost:8081/join"
        val appLink = "csmjam://join?host=$cleanHost&port=8887"
        val shareText = """
            𓆩🜲𓆪 Tritt meiner CSM Jam Session bei!

            Im selben WLAN / Hotspot öffnen:
            $webLink

            Direktlink für die App:
            $appLink

            Host-IP: $cleanHost
        """.trimIndent()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val chooser = Intent.createChooser(sendIntent, "CSM Jam Session teilen")
        startActivity(chooser)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        handleIntent(intent)

        // Ensure status bar and navigation bar are dark #121212 (matching the app background)
        try {
            window.statusBarColor = android.graphics.Color.parseColor("#121212")
            window.navigationBarColor = android.graphics.Color.parseColor("#121212")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0, // 0 = light icons on dark status bar
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
        } catch (_: Exception) {}

        // NFC Quick-Join: init adapter (null on devices without NFC)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        try {
            // Deprecated Android Beam / NDEF push API - call safely via reflection or fallback
            nfcAdapter?.javaClass?.getMethod(
                "setNdefPushMessageCallback",
                NfcAdapter.CreateNdefMessageCallback::class.java,
                android.app.Activity::class.java
            )?.invoke(nfcAdapter, NfcAdapter.CreateNdefMessageCallback {
                if (localServer == null) return@CreateNdefMessageCallback null
                val hostIp = getRawIpAddress(this)
                if (hostIp == "Unbekannt") return@CreateNdefMessageCallback null
                val uri = "csmjam://join?host=$hostIp"
                NdefMessage(arrayOf(NdefRecord.createUri(uri)))
            }, this)
        } catch (_: Exception) {}

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

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // handleAudioFocus = true
            .setHandleAudioBecomingNoisy(true)
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
                        // Only run catch-up if playback was supposed to have ALREADY started in the past (elapsedMs > 150)!
                        // If elapsedMs <= 0, we are in the future countdown phase (e.g. seeking or track start),
                        // and ExoPlayer MUST NOT start playing or seek prematurely!
                        if (isPlayingState && lastStartedAt > 0) {
                            val nowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset.toLong()
                            val elapsedMs = nowMs - lastStartedAt

                            if (elapsedMs > 150) {
                                val targetPos = lastPauseOffset + elapsedMs
                                val currentPos = exoPlayer?.currentPosition ?: 0L

                                // Only seek if we are significantly out of sync (> 150ms)
                                // This avoids the infinite "seek-loop" because seeking itself
                                // triggers another STATE_READY event.
                                if (kotlin.math.abs(currentPos - targetPos) > 150) {
                                    exoPlayer?.seekTo(if (targetPos > 0) targetPos else 0)
                                }
                                if (exoPlayer?.playWhenReady != true) {
                                    exoPlayer?.playWhenReady = true
                                }
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
        // Fix memory leak: clear static reference to this Activity
        if (instance == this) instance = null
        driftJob?.cancel()
        driftJob = null
        SessionDiscoveryManager.stopDiscovery()
        // Only release player resources if no session is running.
        // When the user navigates away but the Jam is still active the MediaSession
        // and ExoPlayer must stay alive so the foreground service can keep the audio going.
        if (!isSessionActive) {
            activeMediaSession?.release()
            activeMediaSession = null
            exoPlayer?.release()
            exoPlayer = null
        }
        // We do NOT stop the servers or service here.
        // Session only stops when the user explicitly clicks "Session verlassen".
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

        val prefs = getSharedPreferences("CSM_Prefs", Context.MODE_PRIVATE)
        val initialUsername = prefs.getString("username", "")?.takeIf { it.isNotBlank() } ?: "Gast"
        var globalUsername by remember { mutableStateOf(initialUsername) }

        // Switch from ModeSelection to OFFLINE if a deep link was clicked
        LaunchedEffect(pendingJoinIp) {
            if (!pendingJoinIp.isNullOrBlank() && appMode == AppMode.NONE) {
                appMode = AppMode.OFFLINE
            }
        }

        // QR-Code Scanner Launcher (ZXing Embedded, 100% offline)
        val qrScannerLauncher = rememberLauncherForActivityResult(
            contract = ScanContract()
        ) { result ->
            val content = result.contents
            if (!content.isNullOrBlank()) {
                val host = parseHostFromQr(content)
                if (host != null) {
                    pendingJoinIp = host
                    appMode = AppMode.OFFLINE
                } else {
                    Toast.makeText(this@MainActivity, "Ungültiger CSM Jam QR-Code", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (appMode == AppMode.NONE) {
            ModeSelectionScreen(
                onOffline = { name -> globalUsername = name; appMode = AppMode.OFFLINE },
                onQrScan = { name ->
                    globalUsername = name
                    val options = ScanOptions().apply {
                        setPrompt("CSM Jam QR-Code scannen")
                        setBeepEnabled(false)
                        setOrientationLocked(true) // Hochformat (Portrait) fixieren
                        setCameraId(0) // Rückkamera
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    }
                    qrScannerLauncher.launch(options)
                }
            )
            return
        }

        // Android 13+ (API 33+) runtime permission for foreground service media notification
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { /* permission result handled by OS */ }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // ---- OFFLINE MODE ----
        var isConnected by remember { mutableStateOf(isSessionActive) }
        var isHost by remember { mutableStateOf(isHostMode) }
        var hostIp by remember { mutableStateOf("192.168.") }
        var discoveredSessions by remember { mutableStateOf<List<DiscoveredSession>>(emptyList()) }

        DisposableEffect(isConnected) {
            if (!isConnected) {
                SessionDiscoveryManager.startDiscovery(this@MainActivity) { list ->
                    runOnUiThread { discoveredSessions = list }
                }
            } else {
                SessionDiscoveryManager.stopDiscovery()
            }
            onDispose {
                SessionDiscoveryManager.stopDiscovery()
            }
        }
        
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

        val voicePlaybackManager = remember { VoicePlaybackManager() }
        val partyLightManager = remember { PartyLightManager(this@MainActivity, { exoPlayer }, { isPlayingState }) }
        var isPartyLightActive by remember { mutableStateOf(false) }
        var partyLightTapCount by remember { mutableStateOf(0) }
        var partyLightUnlocked by remember { mutableStateOf(false) }
        var partyLightMode by remember { mutableStateOf(PartyLightMode.BEAT) }
        var showPartyLightModeDialog by remember { mutableStateOf(false) }

        // ── Dynamic Album-Art Gradient ─────────────────────────────────────
        val defaultGradient = listOf(Color(0xFF0D1B2A), Color(0xFF121212))
        var themeGradient by remember { mutableStateOf(defaultGradient) }

        // ── Battery Saver Mode ────────────────────────────────────────────
        var isBatterySaverMode by remember { mutableStateOf(false) }

        // ── Session Wrapped dialog ────────────────────────────────────────
        var showSessionWrappedDialog by remember { mutableStateOf(false) }

        // ── Fallback/Autoplay Folder ──────────────────────────────────────
        var fallbackFolderUri by remember { mutableStateOf<android.net.Uri?>(null) }
        val fallbackTracks = remember { mutableStateListOf<android.net.Uri>() }
        val fallbackFolderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                fallbackFolderUri = uri
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Enumerate MP3/FLAC/OGG files inside the chosen folder
                fallbackTracks.clear()
                try {
                    val docId = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, uri)
                    docId?.listFiles()?.forEach { file ->
                        val name = file.name?.lowercase() ?: ""
                        if (file.isFile && (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".m4a"))) {
                            fallbackTracks.add(file.uri)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                // Register with server
                if (fallbackTracks.isNotEmpty()) {
                    syncClient?.sendRegisterFallback(true)
                }
            }
        }

        var sessionStartTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
        var totalSongsPlayed by remember { mutableStateOf(0) }
        val trackAddedByMap = remember { mutableStateMapOf<String, String>() }

        var wifiInfo by remember { mutableStateOf(getWifiNetworkInfo(this@MainActivity)) }
        var currentWallClockMs by remember { mutableStateOf(System.currentTimeMillis()) }

        // Periodic timer to update session duration and wifi info
        LaunchedEffect(isConnected) {
            while (isActive && isConnected) {
                currentWallClockMs = System.currentTimeMillis()
                wifiInfo = getWifiNetworkInfo(this@MainActivity)
                delay(1000)
            }
        }

        // ── Gradient extraction: fires whenever the artwork URL or battery saver changes ──
        val currentArtworkUrl = trackArtworks[currentTrack]
        LaunchedEffect(currentArtworkUrl, isBatterySaverMode) {
            if (isBatterySaverMode || currentArtworkUrl.isNullOrEmpty()) {
                themeGradient = if (isBatterySaverMode) listOf(Color.Black, Color.Black) else defaultGradient
                return@LaunchedEffect
            }
            // Load bitmap on IO thread, extract Palette, update state on main thread
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val loader = coil.ImageLoader(this@MainActivity)
                    val req = coil.request.ImageRequest.Builder(this@MainActivity)
                        .data(currentArtworkUrl)
                        .allowHardware(false) // Palette needs a software bitmap
                        .size(128, 128)       // Small for speed
                        .build()
                    val result = (loader.execute(req) as? coil.request.SuccessResult)?.drawable
                    val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        Palette.from(bitmap).generate { palette ->
                            val dark1 = palette?.darkVibrantSwatch?.rgb
                                ?: palette?.vibrantSwatch?.rgb
                                ?: palette?.darkMutedSwatch?.rgb
                                ?: 0xFF0D1B2A.toInt()
                            val dark2 = palette?.darkMutedSwatch?.rgb
                                ?: palette?.mutedSwatch?.rgb
                                ?: 0xFF121212.toInt()
                            themeGradient = listOf(
                                Color(dark1).copy(alpha = 1f),
                                Color(dark2).copy(alpha = 1f)
                            )
                        }
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.Main) { themeGradient = defaultGradient }
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) { themeGradient = defaultGradient }
                }
            }
        }

        // ── Status Bar / Navigation Bar sync with current Theme Gradient & Battery Saver ──
        LaunchedEffect(themeGradient, isBatterySaverMode) {
            try {
                val targetColor = if (isBatterySaverMode) {
                    android.graphics.Color.BLACK
                } else {
                    val c = themeGradient.firstOrNull() ?: defaultGradient.first()
                    android.graphics.Color.argb(
                        255,
                        (c.red * 255).toInt(),
                        (c.green * 255).toInt(),
                        (c.blue * 255).toInt()
                    )
                }
                window.statusBarColor = targetColor
                window.navigationBarColor = if (isBatterySaverMode) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#121212")
            } catch (_: Exception) {}
        }

        var activeVoiceSpeaker by remember { mutableStateOf<String?>(null) }
        var myCanMegaphone by remember { mutableStateOf(false) }

        var isMegaphoneTransmitting by remember { mutableStateOf(false) }
        val voiceRecordingManager = remember {
            VoiceManager { chunk ->
                syncClient?.sendVoiceChunk(chunk)
            }
        }

        val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                isMegaphoneTransmitting = true
                syncClient?.sendVoiceStart(globalUsername)
                voiceRecordingManager.startRecording(this@MainActivity)
            } else {
                Toast.makeText(this@MainActivity, "Mikrofon-Berechtigung erforderlich für Megafon", Toast.LENGTH_SHORT).show()
            }
        }

        // Re-attach to existing session if active
        LaunchedEffect(isConnected) {
            if (isConnected && syncClient != null) {
                val client = syncClient ?: return@LaunchedEffect
                client.onListsUpdated = { q, p, titles, arts ->
                    runOnUiThread {
                        queue.clear(); queue.addAll(q)
                        participants.clear(); participants.addAll(p)
                        trackTitles.clear(); trackTitles.putAll(titles)
                        trackArtworks.clear(); trackArtworks.putAll(arts)
                    }
                }
                client.onStateUpdated = { playing, track, startedAtMs, pauseOffsetMs, repeatSingle ->
                    runOnUiThread {
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
                }
                client.onPlayReceived = { trackUrl, startedAtMs, pauseOffsetMs ->
                    val receivedAtMs = System.currentTimeMillis()
                    runOnUiThread {
                        currentTrack = trackUrl
                        lastStartedAt = startedAtMs
                        lastPauseOffset = pauseOffsetMs
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
                    runOnUiThread { handlePrepare(trackUrl) }
                }
                client.onTimeOffsetCalculated = { offset ->
                    serverTimeOffset = offset
                }
                client.onSyncStatsUpdated = { offsetMs, rttMs ->
                    runOnUiThread {
                        serverTimeOffset = offsetMs
                        currentRttMs = rttMs
                    }
                }
                client.onGuestPermissionsChanged = { allow ->
                    runOnUiThread { guestsCanControl = allow }
                }
                client.onVoiceStart = { speaker ->
                    runOnUiThread {
                        activeVoiceSpeaker = speaker
                        exoPlayer?.volume = 0.15f
                        voicePlaybackManager.start()
                    }
                }
                client.onVoiceChunk = { data ->
                    voicePlaybackManager.write(data)
                }
                client.onVoiceEnd = {
                    runOnUiThread {
                        activeVoiceSpeaker = null
                        voicePlaybackManager.stop()
                        exoPlayer?.volume = 1.0f
                    }
                }
                client.onPartyLightChanged = { enabled ->
                    runOnUiThread {
                        isPartyLightActive = enabled
                        if (enabled) {
                            partyLightManager.start(lifecycleScope, partyLightMode)
                        } else {
                            partyLightManager.stop()
                        }
                    }
                }
                client.onSessionStatsUpdated = { startMs, totalPlayed, addedByMap ->
                    runOnUiThread {
                        if (startMs > 0L) sessionStartTimeMs = startMs
                        totalSongsPlayed = totalPlayed
                        trackAddedByMap.clear()
                        trackAddedByMap.putAll(addedByMap)
                    }
                }
                client.onMyPermissionsChanged = { canMega, canCtrl ->
                    runOnUiThread {
                        myCanMegaphone = canMega
                        if (canCtrl) guestsCanControl = true
                    }
                }
                client.onFallbackRequested = {
                    // Server wants a fallback track from this client
                    val tracks = fallbackTracks.toList()
                    if (tracks.isNotEmpty()) {
                        val pick = tracks.random()
                        val myIp = getRawIpAddress(this@MainActivity)
                        val hostResult = localFileServer?.hostFile(pick)
                        if (hostResult != null) {
                            val fileId = hostResult.first
                            val hasArt = hostResult.second
                            val fileName = getFileName(this@MainActivity, pick)
                            val title = getAudioTitle(this@MainActivity, pick) ?: fileName.removeSuffix(".mp3")
                            val trackUrl = "http://$myIp:8081/$fileId/${Uri.encode(fileName)}"
                            val artUrl = if (hasArt) "http://$myIp:8081/art/$fileId" else null
                            syncClient?.sendAddTrack(trackUrl, title, artUrl, globalUsername + " (Autoplay)")
                        }
                    }
                }
                // Request fresh state to populate UI
                client.requestState()
                // Start continuous drift-correction loop
                startDriftCorrectionLoop()
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

        // FIX: Use OpenMultipleDocuments instead of GetMultipleContents so that we can call
        // takePersistableUriPermission. Without this, the content:// URIs become invalid when
        // the background service (NanoHTTPD worker thread) tries to stream them later, causing
        // a SecurityException and silent playback failure for guests.
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->
            val myIp = getRawIpAddress(this@MainActivity)
            var added = 0
            uris.forEach { uri ->
                // Persist read permission so the URI stays valid across process boundaries
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}

                val fileName = getFileName(this@MainActivity, uri)
                val title = getAudioTitle(this@MainActivity, uri) ?: fileName.removeSuffix(".mp3")
                val hostResult = localFileServer?.hostFile(uri)
                if (hostResult != null) {
                    val fileId = hostResult.first
                    val hasArt = hostResult.second
                    val remoteUrl = "http://$myIp:8081/$fileId/${Uri.encode(fileName)}"
                    val artUrl = if (hasArt) "http://$myIp:8081/art/$fileId" else null
                    syncClient?.sendAddTrack(remoteUrl, title, artUrl, globalUsername)
                    added++
                }
            }
            if (added > 0) {
                Toast.makeText(this@MainActivity, "$added Track(s) hinzugefügt!", Toast.LENGTH_SHORT).show()
            }
        }


                // Lambda for connecting as guest
                var isConnectingGuest by remember(isConnected) { mutableStateOf(false) }
                val performConnectGuest: (String) -> Unit = { targetIp ->
                    if (!isConnectingGuest) {
                        isConnectingGuest = true
                        hostIp = targetIp
                        connectToHost(
                            ip = targetIp,
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
                            isConnectingGuest = false
                            if (connected) {
                                isConnected = true
                                isSessionActive = true
                                isHost = false
                                isHostMode = false
                                JamSessionService.start(this@MainActivity, "guest")
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Verbindung fehlgeschlagen – IP & WLAN prüfen!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // Deep link auto-join: if a link was clicked, automatically join the target host
                LaunchedEffect(pendingJoinIp) {
                    val targetIp = pendingJoinIp ?: return@LaunchedEffect
                    pendingJoinIp = null

                    if (isConnected && hostIp == targetIp) {
                        Toast.makeText(this@MainActivity, "Bereits mit dieser Session verbunden", Toast.LENGTH_SHORT).show()
                        return@LaunchedEffect
                    }
                    if (isConnected) {
                        syncClient?.intentionalClose = true
                        syncClient?.close()
                        syncClient = null
                        localServer?.resetSession()
                        localFileServer?.reset()
                        SessionDiscoveryManager.stopAdvertising()
                        exoPlayer?.stop()
                        isConnected = false
                        isSessionActive = false
                        isHost = false
                        isHostMode = false
                    }

                    performConnectGuest(targetIp)
                }

                if (!isConnected) {
                    androidx.activity.compose.BackHandler { appMode = AppMode.NONE }
                    // ----- CONNECT SCREEN -----
                    val connectScrollState = androidx.compose.foundation.rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(connectScrollState)
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Lokales WLAN", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("\"Offline\" / LAN Modus", fontSize = 14.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(36.dp))

                        var isHostingStarted by remember(isConnected) { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (isHostingStarted) return@Button
                                isHostingStarted = true
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
                                        val myIp = getRawIpAddress(this@MainActivity)
                                        val sessionName = if (globalUsername.isNotBlank() && globalUsername != "Gast") "$globalUsername's Jam" else "CSM Jam"
                                        SessionDiscoveryManager.startAdvertising(
                                            context = this@MainActivity,
                                            sessionName = sessionName,
                                            hostIp = myIp,
                                            port = 8887,
                                            getInfo = {
                                                Pair(localServer?.getParticipantCount() ?: 0, localServer?.getCurrentTrackTitle())
                                            }
                                        )
                                    } else {
                                        isHostingStarted = false
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Fehler beim Starten des Host-Servers!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isHostingStarted,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Text(if (isHostingStarted) "Server startet..." else "Session Hosten (Lokaler Server)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(28.dp))

                        // --- VERFÜGBARE SESSIONS (AUTO-DISCOVERY) ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Sensors, 
                                    contentDescription = null, 
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Verfügbare Sessions", 
                                    fontSize = 17.sp, 
                                    fontWeight = FontWeight.Bold, 
                                    color = Color.White
                                )
                            }

                            IconButton(
                                onClick = {
                                    SessionDiscoveryManager.startDiscovery(this@MainActivity) { list ->
                                        runOnUiThread { discoveredSessions = list }
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Refresh, 
                                    contentDescription = "Aktualisieren", 
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (discoveredSessions.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "Suche nach Sessions im WLAN...",
                                        fontSize = 13.sp,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                discoveredSessions.forEach { session ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { performConnectGuest(session.hostIp) },
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(14.dp)
                                                .fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Filled.MusicNote,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = session.sessionName,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val infoText = buildString {
                                                        append(session.hostIp)
                                                        if (session.participantsCount > 0) {
                                                            append(" • ")
                                                            append("${session.participantsCount} Teilnehmer")
                                                        }
                                                        if (!session.currentTrack.isNullOrBlank()) {
                                                            append(" • ◈ ")
                                                            append(session.currentTrack)
                                                        }
                                                    }
                                                    Text(
                                                        text = infoText,
                                                        fontSize = 12.sp,
                                                        color = Color.LightGray,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { performConnectGuest(session.hostIp) },
                                                enabled = !isConnectingGuest,
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Text(if (isConnectingGuest) "Verbindet..." else "Beitreten", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // --- MANUELLE EINGABE (FALLBACK) ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
                            Text(
                                " oder manuell per IP ", 
                                fontSize = 12.sp, 
                                color = Color.Gray
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color.DarkGray)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

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
                            onClick = { performConnectGuest(hostIp) }, 
                            enabled = !isConnectingGuest,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Text(if (isConnectingGuest) "Verbindet..." else "Per IP Beitreten", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(28.dp))
                        TextButton(onClick = { appMode = AppMode.NONE }) {
                            Text("← Zurück zur Modus-Auswahl", color = Color.Gray)
                        }
                    }
                } else {
            // ----- MAIN SESSION UI -----
            val bgGradient = Brush.verticalGradient(themeGradient)
            Scaffold(
                containerColor = Color.Transparent,
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
                            onExpandClick = { showFullPlayer = true },
                            themeGradient = themeGradient
                        )

                    }
                }
            ) { paddingValues ->

                var showShareMenuDialog by remember { mutableStateOf(false) }
                var showQrCodeDialog by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
                val coroutineScope = rememberCoroutineScope()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgGradient)
                        .padding(paddingValues)
                ) {
                    // ── Tab Bar ──────────────────────────────────────────────
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                            text = { Text("Warteschlange", fontSize = 13.sp) },
                            icon = { Icon(Icons.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = pagerState.currentPage == 1,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                            text = { Text("Session", fontSize = 13.sp) },
                            icon = { Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    // ── Swipeable Pager ───────────────────────────────────────
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { page ->
                        if (page == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Spacer(modifier = Modifier.height(12.dp))

                                // Speech Announcement Banner
                                androidx.compose.animation.AnimatedVisibility(visible = activeVoiceSpeaker != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFFE53935).copy(alpha = 0.95f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "$activeVoiceSpeaker macht eine Durchsage...",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }

                            // Megaphone Push-to-Talk Button
                            if (isHost || myCanMegaphone) {
                                val megaphoneInteractionSource = remember { MutableInteractionSource() }
                                val isMegaphonePressed by megaphoneInteractionSource.collectIsPressedAsState()

                                LaunchedEffect(isMegaphonePressed) {
                                    if (isMegaphonePressed) {
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            isMegaphoneTransmitting = true
                                            syncClient?.sendVoiceStart(globalUsername)
                                            voiceRecordingManager.startRecording(this@MainActivity)
                                        } else {
                                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else if (isMegaphoneTransmitting) {
                                        isMegaphoneTransmitting = false
                                        voiceRecordingManager.stopRecording()
                                        syncClient?.sendVoiceEnd()
                                    }
                                }

                                Button(
                                    onClick = {},
                                    interactionSource = megaphoneInteractionSource,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(27.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isMegaphoneTransmitting) Color(0xFFE53935) else Color(0xFF203A43),
                                        contentColor = Color.White
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                                ) {
                                    Icon(
                                        if (isMegaphoneTransmitting) Icons.Filled.Mic else Icons.Filled.Campaign,
                                        contentDescription = null,
                                        tint = if (isMegaphoneTransmitting) Color.Yellow else Color(0xFF1DB954),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (isMegaphoneTransmitting) "Live spricht... (Loslassen zum Beenden)" else "Megafon (Gedrückt halten zum Sprechen)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Add Song Button
                            if (isHost || guestsCanControl) {
                                Button(
                                    onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("MP3 vom Gerät hinzufügen", color = Color.White)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
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
                                                                onDragStopped = { syncClient?.sendReorderQueue(0, 0) }
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
                        }

                    // ── Tab 1: Session ────────────────────────────────────────
                    } else {
                        var selectedParticipantForManagement by remember { mutableStateOf<SessionParticipant?>(null) }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Header: Session title, status label (easter egg), party light, share
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isHost) "𓆩🜲𓆪 Host Session" else "◇ Guest Session",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Status: Verbunden",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 14.sp,
                                        modifier = Modifier.clickable {
                                            val newCount = partyLightTapCount + 1
                                            if (newCount >= 5) {
                                                partyLightTapCount = 0
                                                partyLightUnlocked = !partyLightUnlocked
                                                if (!partyLightUnlocked && isPartyLightActive) {
                                                    isPartyLightActive = false
                                                    partyLightManager.stop()
                                                    syncClient?.sendSetPartyLight(false)
                                                }
                                                val msg = if (partyLightUnlocked) "Party-Licht freigeschaltet! ✨" else "Party-Licht versteckt."
                                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                            } else {
                                                partyLightTapCount = newCount
                                            }
                                        }
                                    )
                                    if (isHost) {
                                        Text(
                                            "Deine IP (für Gäste): ${getRawIpAddress(this@MainActivity)}",
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (partyLightUnlocked) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                val nextState = !isPartyLightActive
                                                if (isHost) {
                                                    syncClient?.sendSetPartyLight(nextState)
                                                } else {
                                                    isPartyLightActive = nextState
                                                    if (nextState) partyLightManager.start(lifecycleScope, partyLightMode) else partyLightManager.stop()
                                                }
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = if (isPartyLightActive) Color(0xFFFFD600) else Color.White.copy(alpha = 0.15f),
                                                contentColor = if (isPartyLightActive) Color.Black else Color.White
                                            )
                                        ) {
                                            Icon(
                                                if (isPartyLightActive) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                                contentDescription = "Party-Licht",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    if (isHost || guestsCanControl) {
                                        FilledTonalButton(
                                            onClick = { showShareMenuDialog = true },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Icon(Icons.Filled.Share, contentDescription = "Session teilen", modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Teilen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // ── WLAN & Netzwerk Statuscard ──
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF1E1E1E),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val wifiIcon = when (wifiInfo.level) {
                                            4 -> Icons.Filled.Wifi
                                            3 -> Icons.Filled.Wifi
                                            2 -> Icons.Filled.Wifi2Bar
                                            1 -> Icons.Filled.Wifi1Bar
                                            else -> Icons.Filled.WifiOff
                                        }
                                        Icon(
                                            wifiIcon,
                                            contentDescription = "WLAN Signal",
                                            tint = Color(0xFF1DB954),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column {
                                            Text(wifiInfo.ssid, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            val pingText = if (currentRttMs > 0) "Ping: ${currentRttMs}ms" else "Verbunden"
                                            Text(pingText, color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }

                                    // Force Sync Action button
                                    FilledTonalButton(
                                        onClick = {
                                            syncClient?.sendForceSync()
                                            Toast.makeText(this@MainActivity, "Sync neu getaktet", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Filled.Sync, contentDescription = "Force Sync", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Neu takten", fontSize = 12.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // ── Session Statistiken ──
                            val elapsedSeconds = ((currentWallClockMs - sessionStartTimeMs).coerceAtLeast(0L) / 1000L)
                            val durationHours = elapsedSeconds / 3600
                            val durationMins = (elapsedSeconds % 3600) / 60
                            val durationSecs = elapsedSeconds % 60
                            val durationStr = if (durationHours > 0) {
                                String.format("%d Std. %02d Min.", durationHours, durationMins)
                            } else {
                                String.format("%d Min. %02d Sek.", durationMins, durationSecs)
                            }

                            // Calculate Top DJ
                            val userTrackCounts = mutableMapOf<String, Int>()
                            trackAddedByMap.values.forEach { userName ->
                                userTrackCounts[userName] = (userTrackCounts[userName] ?: 0) + 1
                            }
                            val topDjEntry = userTrackCounts.maxByOrNull { it.value }
                            val topDjText = if (topDjEntry != null) "${topDjEntry.key} (${topDjEntry.value} Tracks)" else "Noch kein DJ"

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF1E1E1E),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("Session Statistiken", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("Laufzeit", color = Color.Gray, fontSize = 11.sp)
                                            Text(durationStr, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Column {
                                            Text("Gespielt", color = Color.Gray, fontSize = 11.sp)
                                            Text("$totalSongsPlayed Songs", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Column {
                                            Text("Top DJ", color = Color.Gray, fontSize = 11.sp)
                                            Text(topDjText, color = Color(0xFF64B5F6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }

                            // ── Party-Licht Einstellungen (falls freigeschaltet) ──
                            if (partyLightUnlocked) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF262114),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Filled.FlashOn, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(20.dp))
                                            Column {
                                                Text("Party-Licht Modus", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                Text("Modus: ${partyLightMode.label}", color = Color(0xFFFFD600), fontSize = 11.sp)
                                            }
                                        }
                                        FilledTonalButton(
                                            onClick = { showPartyLightModeDialog = true },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFFFD600).copy(alpha = 0.2f), contentColor = Color(0xFFFFD600))
                                        ) {
                                            Text("Ändern", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // ── Akku-Sparmodus Toggle ─────────────────────────────
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isBatterySaverMode) Color(0xFF0A2010) else Color(0xFF1E1E1E),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null,
                                            tint = if (isBatterySaverMode) Color(0xFF4CAF50) else Color.Gray,
                                            modifier = Modifier.size(20.dp))
                                        Column {
                                            Text("Akku-Sparmodus", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("AMOLED-Schwarz, keine Animationen", color = Color.Gray, fontSize = 11.sp)
                                        }
                                    }
                                    Switch(
                                        checked = isBatterySaverMode,
                                        onCheckedChange = { isBatterySaverMode = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50), checkedTrackColor = Color(0xFF1B5E20))
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // ── Autoplay / Fallback-Ordner ────────────────────────
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF1E1E1E),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(20.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Autoplay-Ordner", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (fallbackFolderUri != null) "${fallbackTracks.size} Song(s) bereit (Round-Robin)" else "Kein Ordner gewählt",
                                                color = if (fallbackFolderUri != null) Color(0xFF64B5F6) else Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { fallbackFolderLauncher.launch(null) },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF64B5F6).copy(alpha = 0.2f), contentColor = Color(0xFF64B5F6))
                                        ) { Text(if (fallbackFolderUri != null) "Ändern" else "Wählen", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    }
                                    if (fallbackFolderUri != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("Wenn die Warteschlange fast leer ist, wird automatisch ein Song von dir hinzugefügt.", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))


                            // Guest control toggle (host only)
                            if (isHost) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Gäste dürfen steuern", color = Color.White, fontSize = 14.sp)
                                        Text(
                                            if (guestsCanControl) "Steuerung, Songs hinzufügen & Teilen für Gäste erlaubt" else "Nur Host kontrolliert & teilt die Session",
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
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Participants list
                            Text("Teilnehmer (${participants.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))

                            participants.forEach { rawParticipant ->
                                val participant = parseParticipant(rawParticipant)
                                val canManage = isHost && !participant.isHost

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (canManage) Color(0xFF1E2F38) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable(enabled = canManage) {
                                            selectedParticipantForManagement = participant
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            participant.name,
                                            color = if (participant.isHost) Color(0xFF1DB954) else Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = if (participant.isHost) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (participant.canMegaphone) {
                                                Icon(Icons.Filled.Campaign, contentDescription = "Megafon", tint = Color(0xFF1DB954), modifier = Modifier.size(16.dp))
                                            }
                                            if (participant.canControl) {
                                                Icon(Icons.Filled.MusicNote, contentDescription = "Co-DJ", tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                                            }
                                            if (canManage) {
                                                Icon(Icons.Filled.Settings, contentDescription = "Gast verwalten", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Leave session button → shows Session Wrapped summary first
                            val doLeaveSession = {
                                voicePlaybackManager.stop()
                                partyLightManager.stop()
                                voiceRecordingManager.stopRecording()
                                exoPlayer?.volume = 1.0f
                                isPartyLightActive = false
                                activeVoiceSpeaker = null
                                syncClient?.intentionalClose = true
                                syncClient?.close()
                                syncClient = null
                                localServer?.resetSession()
                                localFileServer?.reset()
                                exoPlayer?.stop()
                                isSessionActive = false
                                isHostMode = false
                                isConnected = false
                                isHost = false
                                isPlayingState = false
                                currentTrack = null
                                lastStartedAt = 0L
                                lastPauseOffset = 0L
                                currentSyncDiffMs = 0L
                                currentRttMs = 0L
                                driftJob?.cancel()
                                driftJob = null
                                queue.clear()
                                participants.clear()
                                SessionDiscoveryManager.stopAdvertising()
                                JamSessionService.stop(this@MainActivity)
                                showSessionWrappedDialog = false
                                fallbackTracks.clear()
                                fallbackFolderUri = null
                                themeGradient = defaultGradient
                                isBatterySaverMode = false
                            }
                            OutlinedButton(
                                onClick = { showSessionWrappedDialog = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Session verlassen")
                            }

                            // ── Session Wrapped Dialog ───────────────────────────────
                            if (showSessionWrappedDialog) {
                                val durationMs = currentWallClockMs - sessionStartTimeMs
                                val durationMins = (durationMs / 60000).toInt()
                                val durationSecs = ((durationMs % 60000) / 1000).toInt()
                                val topDj = trackAddedByMap.values
                                    .groupingBy { it }.eachCount()
                                    .maxByOrNull { it.value }?.key ?: "—"
                                val topArtist = trackTitles.values
                                    .mapNotNull { t -> t.substringAfterLast(" - ", "").takeIf { it.isNotBlank() } }
                                    .groupingBy { it }.eachCount()
                                    .maxByOrNull { it.value }?.key ?: "—"

                                AlertDialog(
                                    onDismissRequest = { showSessionWrappedDialog = false },
                                    containerColor = themeGradient.firstOrNull()?.copy(alpha = 1f)?.let {
                                        Color(it.red * 0.8f, it.green * 0.8f, it.blue * 0.8f)
                                    } ?: Color(0xFF1A1A2E),
                                    title = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                            Text("✦ Session Wrapped ✦", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center)
                                        }
                                    },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            // Duration
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Filled.Timer, contentDescription = null, tint = Color(0xFF1DB954))
                                                Column {
                                                    Text("Session-Dauer", color = Color.Gray, fontSize = 11.sp)
                                                    Text("${durationMins}m ${durationSecs}s", color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                            // Songs played
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = Color(0xFF1DB954))
                                                Column {
                                                    Text("Gespielte Songs", color = Color.Gray, fontSize = 11.sp)
                                                    Text("$totalSongsPlayed Songs", color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                            // Top DJ
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFD700))
                                                Column {
                                                    Text("Top-DJ", color = Color.Gray, fontSize = 11.sp)
                                                    Text(topDj, color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                            // Top Artist
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color(0xFF64B5F6))
                                                Column {
                                                    Text("Meist gespielter Künstler", color = Color.Gray, fontSize = 11.sp)
                                                    Text(topArtist, color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = { doLeaveSession() },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Session verlassen") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSessionWrappedDialog = false }) {
                                            Text("Weiterfeiern", color = Color(0xFF1DB954))
                                        }
                                    }
                                )
                            }
                        }

                        // Participant management dialog
                        if (selectedParticipantForManagement != null) {
                            val p = selectedParticipantForManagement!!
                            var canMegaphone by remember(p.id) { mutableStateOf(p.canMegaphone) }
                            var canControl by remember(p.id) { mutableStateOf(p.canControl) }

                            AlertDialog(
                                onDismissRequest = { selectedParticipantForManagement = null },
                                title = {
                                    Text("Gast verwalten: ${p.name.removePrefix("◇ ").trim()}", fontWeight = FontWeight.Bold)
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Megafon erlauben", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Text("Erlaubt dem Gast, Durchsagen über alle Lautsprecher zu machen", color = Color.Gray, fontSize = 12.sp)
                                            }
                                            Switch(
                                                checked = canMegaphone,
                                                onCheckedChange = {
                                                    canMegaphone = it
                                                    syncClient?.sendUpdateParticipantPermissions(p.id, canMegaphone = it, canControl = canControl)
                                                }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Co-DJ Rechte", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Text("Erlaubt Queue-Steuerung, Shuffeln und Song-Reihenfolge", color = Color.Gray, fontSize = 12.sp)
                                            }
                                            Switch(
                                                checked = canControl,
                                                onCheckedChange = {
                                                    canControl = it
                                                    syncClient?.sendUpdateParticipantPermissions(p.id, canMegaphone = canMegaphone, canControl = it)
                                                }
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                syncClient?.sendKickParticipant(p.id)
                                                selectedParticipantForManagement = null
                                                Toast.makeText(this@MainActivity, "${p.name} gekickt", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.fillMaxWidth().height(44.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                        ) {
                                            Icon(Icons.Filled.PersonRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Aus Session kicken")
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { selectedParticipantForManagement = null }) {
                                        Text("Fertig")
                                    }
                                }
                            )
                        }
                    }
                }

                // ── Party-Light Mode Dialog ──────────────────────────────────
                if (showPartyLightModeDialog) {
                    AlertDialog(
                        onDismissRequest = { showPartyLightModeDialog = false },
                        title = { Text("Party-Licht Modus wählen", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PartyLightMode.values().forEach { modeOption ->
                                    val isSelected = partyLightMode == modeOption
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) Color(0xFFFFD600).copy(alpha = 0.2f) else Color(0xFF2C2C2C),
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            partyLightMode = modeOption
                                            partyLightManager.mode = modeOption
                                            if (isPartyLightActive) {
                                                partyLightManager.stop()
                                                partyLightManager.start(lifecycleScope, modeOption)
                                            }
                                            showPartyLightModeDialog = false
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(modeOption.label, color = if (isSelected) Color(0xFFFFD600) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                val desc = when (modeOption) {
                                                    PartyLightMode.BEAT -> "Flasht passend zum Bass-Peak der Musik"
                                                    PartyLightMode.STROBE_FAST -> "Schnelles Club-Stroboskop"
                                                    PartyLightMode.STROBE_SLOW -> "Ruhiges, chilliges Blinken"
                                                }
                                                Text(desc, color = Color.Gray, fontSize = 11.sp)
                                            }
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = {
                                                    partyLightMode = modeOption
                                                    partyLightManager.mode = modeOption
                                                    if (isPartyLightActive) {
                                                        partyLightManager.stop()
                                                        partyLightManager.start(lifecycleScope, modeOption)
                                                    }
                                                    showPartyLightModeDialog = false
                                                },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFD600))
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showPartyLightModeDialog = false }) {
                                Text("Schließen")
                            }
                        }
                    )
                }

                // ── Shared Dialogs (beide Tabs) ───────────────────────────────
                if (showShareMenuDialog) {
                    val targetHostIp = if (isHost) getRawIpAddress(this@MainActivity) else hostIp
                    val cleanHost = targetHostIp.trim().removePrefix("http://").removePrefix("ws://").substringBefore(":")
                    val qrLink = "csmjam://join?host=$cleanHost&port=8887"

                    AlertDialog(
                        onDismissRequest = { showShareMenuDialog = false },
                        title = { Text("Session teilen", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Wie möchtest du deine Session teilen?", fontSize = 14.sp, color = Color.LightGray)

                                Button(
                                    onClick = { showShareMenuDialog = false; showQrCodeDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("QR-Code auf Display anzeigen", fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = { showShareMenuDialog = false; shareSession(targetHostIp) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Text / Link teilen (WhatsApp etc.)", fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("CSM Jam Link", qrLink)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(this@MainActivity, "Link in Zwischenablage kopiert!", Toast.LENGTH_SHORT).show()
                                        showShareMenuDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Link kopieren", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showShareMenuDialog = false }) { Text("Abbrechen") }
                        }
                    )
                }

                if (showQrCodeDialog) {
                    val targetHostIp = if (isHost) getRawIpAddress(this@MainActivity) else hostIp
                    val cleanHost = targetHostIp.trim().removePrefix("http://").removePrefix("ws://").substringBefore(":")
                    val qrLink = "csmjam://join?host=$cleanHost&port=8887"
                    val qrBitmap = remember(qrLink) { generateQrCodeBitmap(qrLink, 600) }

                    AlertDialog(
                        onDismissRequest = { showQrCodeDialog = false },
                        title = {
                            Text("QR-Code zum Beitreten", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (qrBitmap != null) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.size(240.dp).padding(8.dp)) {
                                        Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "CSM Jam QR-Code", modifier = Modifier.fillMaxSize())
                                    }
                                } else {
                                    Text("Fehler beim Erzeugen des QR-Codes", color = Color.Red)
                                }
                                Text(
                                    "Host: $cleanHost:8887",
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Mitspieler können diesen Code direkt auf dem Startbildschirm mit dem Scanner-Icon scannen.",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showQrCodeDialog = false }) { Text("Schließen") }
                        }
                    )
                }

            } // Closes Scaffold content
        } // Closes else (connected session UI)

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
                    // 1. Re-measure NTP offset from scratch
                    syncClient?.pingServer(resetHistory = true)
                    // 2. Tell the server to re-broadcast a fresh PLAY timestamp to all clients
                    syncClient?.sendForceSync()
                    // 3. Restart drift-correction loop so it picks up fresh timing immediately
                    startDriftCorrectionLoop()
                },
                onCollapseClick = { showFullPlayer = false },
                isHost = isHost || guestsCanControl,
                isRepeatSingle = isRepeatSingle,
                onToggleRepeat = { syncClient?.sendSetRepeatSingle(!isRepeatSingle) },
                onSeek = { seekToMs -> syncClient?.sendSeekAction(seekToMs) },
                onShuffle = { syncClient?.sendShuffle() },
                themeGradient = themeGradient
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
        onExpandClick: () -> Unit,
        themeGradient: List<Color> = listOf(Color(0xFF282828), Color(0xFF1A1A1A))
    ) {
        val cardBg = themeGradient.firstOrNull()?.copy(alpha = 0.85f) ?: Color(0xFF282828)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { onExpandClick() },
            colors = CardDefaults.cardColors(containerColor = cardBg),
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
        isOnlineMode: Boolean = false,
        themeGradient: List<Color> = listOf(Color(0xFF0D1B2A), Color(0xFF121212))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(themeGradient))
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

                // NOTE: Calibration seek is handled by the LaunchedEffect in AppContent
                // (with 50ms debounce). Having a second LaunchedEffect here competing for
                // exoPlayer.seekTo caused audio stutter loops – removed.

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
                        // Subtle sync status — only shows drift detail when noticeably out of sync
                        val absDrift = kotlin.math.abs(currentSyncDiffMs)
                        val syncLabel = when {
                            !isPlaying -> "Pausiert"
                            absDrift <= 25 -> "Synchron"
                            absDrift <= 100 -> "${if (currentSyncDiffMs > 0) "+" else ""}${currentSyncDiffMs}ms"
                            else -> "${if (currentSyncDiffMs > 0) "+" else ""}${currentSyncDiffMs}ms — wird korrigiert"
                        }
                        val syncColor = when {
                            !isPlaying -> Color(0xFF888888)
                            absDrift <= 25 -> Color(0xFF555555)
                            else -> Color(0xFF888888)
                        }
                        Text(
                            syncLabel,
                            color = syncColor,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Bar
                var currentPositionMs by remember { mutableStateOf(0L) }
                var currentDurationMs by remember { mutableStateOf(1L) } 
                
                var isDraggingSlider by remember { mutableStateOf(false) }
                var sliderPositionMs by remember { mutableStateOf(0f) }
                var pendingSeekPositionMs by remember { mutableStateOf<Float?>(null) }

                LaunchedEffect(isPlaying, exoPlayer) {
                    while (true) {
                        exoPlayer?.let {
                            if (it.playbackState == androidx.media3.common.Player.STATE_READY || it.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                                currentPositionMs = it.currentPosition
                                currentDurationMs = if (it.duration > 0) it.duration else 1L
                                if (pendingSeekPositionMs != null && kotlin.math.abs(it.currentPosition - pendingSeekPositionMs!!.toLong()) < 600) {
                                    pendingSeekPositionMs = null
                                }
                            }
                        }
                        kotlinx.coroutines.delay(100)
                    }
                }

                LaunchedEffect(pendingSeekPositionMs) {
                    if (pendingSeekPositionMs != null) {
                        kotlinx.coroutines.delay(1200)
                        pendingSeekPositionMs = null
                    }
                }

                val displayMs = when {
                    isDraggingSlider -> sliderPositionMs
                    pendingSeekPositionMs != null -> pendingSeekPositionMs!!
                    else -> currentPositionMs.toFloat()
                }
                
                fun formatTime(ms: Long): String {
                    if (ms < 0) return "0:00"
                    val sec = ms / 1000
                    val m = sec / 60
                    val s = sec % 60
                    return String.format("%d:%02d", m, s)
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Slider(
                        value = displayMs.coerceIn(0f, currentDurationMs.toFloat()),
                        valueRange = 0f..currentDurationMs.toFloat(),
                        onValueChange = { newVal ->
                            isDraggingSlider = true
                            sliderPositionMs = newVal
                            pendingSeekPositionMs = newVal
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
                        // Skip Previous – FIX: was disabled because currentIndex was always 0.
                        // Now enabled whenever the host can control; the server handles
                        // both "restart current track" and "go to previous track from history".
                        IconButton(
                            onClick = onSkipPrev,
                            modifier = Modifier.size(56.dp),
                            enabled = isHost
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Vorheriger",
                                tint = if (isHost) Color.White else Color.DarkGray,
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
                            // FIX: Always pass the canonical trackUrl (HTTP URL from server) to handlePrepare.
                            // handlePrepare will internally resolve the local content:// URI for playback,
                            // but uses trackUrl as the ExoPlayer mediaId so ACTION_TRACK_READY always
                            // matches the server's pendingPlayTrackUrl.
                            runOnUiThread { handlePrepare(trackUrl) }
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
                    client.onSyncStatsUpdated = { offsetMs, rttMs ->
                        runOnUiThread {
                            serverTimeOffset = offsetMs
                            currentRttMs = rttMs
                        }
                    }
                    // Start continuous drift-correction loop
                    startDriftCorrectionLoop()
                    
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
        if (startedAtMs == 0L) {
            player.seekTo(if (pauseOffsetMs > 0) pauseOffsetMs else 0)
            player.playWhenReady = true
            return
        }

        val actualNowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset
        val waitTimeMs = startedAtMs - actualNowMs

        if (waitTimeMs > 0) {
            // Pre-seek to the exact start position NOW while we still have plenty of buffer time.
            // ExoPlayer fully decodes and buffers before we fire play.
            // At fire-time we only flip playWhenReady – NO seekTo! – avoiding decoder flush lag.
            player.seekTo(pauseOffsetMs.coerceAtLeast(0L))
            player.playWhenReady = false

            // FIX: Replace CPU-burning busy-wait (100% CPU for final ~15ms) with a
            // Handler.postDelayed on the main looper. Precision is still ~1–2ms on modern
            // Android because the main looper is already running at display frame rate (~60 Hz ≈ 16ms).
            // The handler fires on the UI thread so no runOnUiThread wrapper is needed.
            val mainHandler = android.os.Handler(mainLooper)
            mainHandler.postDelayed({
                if (player.currentMediaItem?.mediaId == trackUrl &&
                    lastStartedAt == startedAtMs &&
                    isPlayingState) {
                    // Only flip play – no seekTo here to avoid decoder flush
                    player.playWhenReady = true
                }
            }, waitTimeMs.coerceAtLeast(0L))
        } else {
            // Late join: seek to the correct catch-up position and start immediately
            val latestNowMs = System.currentTimeMillis() + serverTimeOffset + calibrationOffset
            val elapsedMs = latestNowMs - startedAtMs
            val exactTrackPositionMs = pauseOffsetMs + elapsedMs
            val targetPos = if (player.duration > 0) exactTrackPositionMs.coerceIn(0L, player.duration) else exactTrackPositionMs.coerceAtLeast(0L)
            player.seekTo(targetPos)
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

                    // Priority: hotspot/AP interfaces (and 192.168.43.x) first, then WLAN, then everything else
                    val priority = when {
                        ip.startsWith("192.168.43.") -> -1
                        ifName.contains("ap") || ifName.contains("softap") || ifName.contains("swlan") || ifName.contains("tether") -> 0
                        ifName.startsWith("wlan") -> 1
                        ifName.startsWith("eth") || ifName.contains("usb") || ifName.contains("rndis") -> 2
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

    data class WifiNetworkInfo(val ssid: String, val level: Int) // level 0..4

    private fun getWifiNetworkInfo(context: Context): WifiNetworkInfo {
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

    // =========================================================================
    // QR CODE HELPERS
    // =========================================================================

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

    // =========================================================================
    // MODE SELECTION SCREEN
    // =========================================================================

    @Composable
    fun ModeSelectionScreen(
        onOffline: (String) -> Unit,
        onQrScan: (String) -> Unit
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

                // Row with Join Button and QR Scanner Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onOffline(username.trim().ifEmpty { "Gast ${System.currentTimeMillis() % 100}" }) },
                        modifier = Modifier
                            .weight(1f)
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Jam Session Betreten", 
                            fontSize = 16.sp, 
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // QR-Code Scan Button
                    FilledTonalIconButton(
                        onClick = { onQrScan(username.trim().ifEmpty { "Gast ${System.currentTimeMillis() % 100}" }) },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF203A43),
                            contentColor = Color(0xFF1DB954)
                        )
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = "QR-Code scannen und beitreten",
                            modifier = Modifier.size(30.dp)
                        )
                    }
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
