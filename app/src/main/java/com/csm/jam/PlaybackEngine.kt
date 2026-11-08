package com.csm.jam

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything that makes the local device play in sync with the session timeline.
 *
 * One instance per process (the session outlives the Activity – it keeps running in the
 * foreground service), so a re-created Activity never ends up with a second player.
 * Two ExoPlayers alternate: normally the active one plays a gapless [current, next] playlist,
 * with crossfade enabled the next track starts on the other player and both are faded.
 * All public methods may be called from any thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackEngine private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: PlaybackEngine? = null

        fun get(context: Context): PlaybackEngine = instance ?: synchronized(this) {
            instance ?: PlaybackEngine(context.applicationContext).also { instance = it }
        }

        /** The engine if it was created already (for the notification service). */
        fun peek(): PlaybackEngine? = instance

        private const val FADE_STEP_MS = 40L
    }

    // ── Session timeline (observable by Compose) ──────────────────────────
    var isPlaying by mutableStateOf(false)
    var currentTrack by mutableStateOf<String?>(null)
    var startedAtMs by mutableStateOf(0L)
    var pauseOffsetMs by mutableStateOf(0L)
    var isRepeatSingle by mutableStateOf(false)
        private set
    var syncDiffMs by mutableStateOf(0L)
        private set
    var rttMs by mutableStateOf(0L)
    /** Host option, pushed by the server – the same for everybody. */
    var crossfadeMs by mutableStateOf(0)
        private set
    /** Per-device output offset (+ = play earlier), persisted per device. */
    var calibrationMs by mutableStateOf(0f)
        private set
    /** Host started the click test: everybody plays the same click beat instead of music. */
    var syncTestActive by mutableStateOf(false)
        private set
    /** The player that currently carries the session timeline (changes with every crossfade). */
    var activePlayerState by mutableStateOf<ExoPlayer?>(null)
        private set

    @Volatile var serverTimeOffset: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("CSM_Prefs", Context.MODE_PRIVATE)

    private val players: Array<ExoPlayer>
    private var activeIdx = 0
    val activePlayer: ExoPlayer get() = players[activeIdx]
    private val idlePlayer: ExoPlayer get() = players[1 - activeIdx]
    private var fadingPlayer: ExoPlayer? = null
    private var fadeJob: Job? = null
    private val fadeGain = HashMap<ExoPlayer, Float>()
    private val lastMediaId = HashMap<ExoPlayer, String?>()
    private var duckFactor = 1f
    private var mutedUntilMs = 0L
    private var lastPreloadUrl: String? = null

    // Drift correction state
    private var lastHardSeekTime = 0L
    private var seekCompensationMs = 60L
    private var awaitingSeekResult = false

    private var chirpPlayer: ExoPlayer? = null
    private var clickPlayer: ExoPlayer? = null
    private var clickAlignJob: Job? = null
    private var offsetJumpPending: Runnable? = null
    private var focusRequest: AudioFocusRequest? = null

    val mediaSession: MediaSession

    init {
        players = arrayOf(buildPlayer(), buildPlayer())
        players.forEach { p ->
            fadeGain[p] = 1f
            p.addListener(listenerFor(p))
        }
        mediaSession = MediaSession.Builder(context, forwardingFor(players[0])).build()
        activePlayerState = players[0]
        calibrationMs = prefs.getFloat("calibration_ms", 0f)
        startDriftLoop()
    }

    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 250, 500)
            .build()
        val attrs = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        // Audio focus is handled once by the engine – two players requesting it would pause each other
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
    }

    /** Lock screen / headset buttons control the session, not just this device. */
    private fun forwardingFor(player: ExoPlayer) = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() { MainActivity.syncClient?.sendSkipNext() }
        override fun seekToNextMediaItem() { MainActivity.syncClient?.sendSkipNext() }
        override fun seekToPrevious() { MainActivity.syncClient?.sendSkipPrev() }
        override fun seekToPreviousMediaItem() { MainActivity.syncClient?.sendSkipPrev() }
    }

    private fun listenerFor(p: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val id = p.currentMediaItem?.mediaId ?: return
                    // Also for preloaded tracks: the server learns their length early
                    MainActivity.syncClient?.sendTrackReady(id, p.duration.coerceAtLeast(0L))
                    if (p !== activePlayer) return
                    // Late catch-up: only for the track the server timeline refers to and only if
                    // playback should already be running (during the countdown nothing may start early)
                    if (id == currentTrack && isPlaying && startedAtMs > 0) {
                        val elapsed = syncedNowMs() - startedAtMs
                        if (elapsed > 150) {
                            val target = pauseOffsetMs + elapsed
                            if (abs(p.currentPosition - target) > 150) hardSeek(p, target)
                            if (!p.playWhenReady) p.playWhenReady = true
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    // Last item ended without a follow-up – tell the server to advance the queue
                    if (p === activePlayer) p.currentMediaItem?.mediaId?.let { MainActivity.syncClient?.sendTrackFinished(it) }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = lastMediaId[p]
            lastMediaId[p] = mediaItem?.mediaId
            if (p !== activePlayer) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                // Gapless switch into the preloaded track happened on its own
                if (previous != null) MainActivity.syncClient?.sendTrackFinished(previous)
                handler.post {
                    trimPlaylist(p)
                    // The new item is usually already buffered, so STATE_READY won't fire again
                    val id = p.currentMediaItem?.mediaId ?: return@post
                    if (p.playbackState == Player.STATE_READY) MainActivity.syncClient?.sendTrackReady(id, p.duration.coerceAtLeast(0L))
                }
            }
        }
    }

    // ── Clock ─────────────────────────────────────────────────────────────

    /** "Now" on the server timeline, including this device's manual offset. */
    fun syncedNowMs(): Long = JamClock.nowMs() + serverTimeOffset + calibrationMs.toLong()

    /**
     * Changes this device's output offset. Manual changes take effect right away: once the user
     * stops adjusting (short debounce, so dragging doesn't seek on every tick) the music jumps to
     * the new position instead of gliding there over several seconds.
     */
    fun setCalibration(ms: Float, jump: Boolean = true) = main {
        val old = calibrationMs
        calibrationMs = (Math.round(ms.coerceIn(-2000f, 2000f))).toFloat()
        prefs.edit().putFloat("calibration_ms", calibrationMs).apply()
        if (!jump || calibrationMs == old) return@main
        offsetJumpPending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            offsetJumpPending = null
            jumpToTimeline()
            realignClicks()
        }
        offsetJumpPending = r
        handler.postDelayed(r, 250)
    }

    /** Puts the active player exactly where the timeline (incl. offset) says – used after manual offset changes. */
    private fun jumpToTimeline() {
        val p = activePlayer
        if (!isPlaying || startedAtMs <= 0L || !p.isPlaying || p.currentMediaItem?.mediaId != currentTrack) return
        val expected = pauseOffsetMs + (syncedNowMs() - startedAtMs)
        if (expected < 0 || (p.duration > 0 && expected > p.duration)) return
        if (abs(p.currentPosition - expected) > 15) hardSeek(p, expected)
    }

    // ── Volume: crossfade gain × megaphone ducking × calibration mute ────

    private fun applyVolumes() {
        val muted = JamClock.nowMs() < mutedUntilMs || syncTestActive
        players.forEach { p -> p.volume = if (muted) 0f else (fadeGain[p] ?: 1f) * duckFactor }
    }

    /** Megaphone announcement: music down to 15 %. */
    fun setDucked(ducked: Boolean) = main {
        duckFactor = if (ducked) 0.15f else 1f
        applyVolumes()
    }

    /** Silence the music (auto-calibration listens with the mic) until [untilMs] on the session clock. */
    fun muteUntil(untilMs: Long) = main {
        mutedUntilMs = untilMs
        applyVolumes()
        handler.postDelayed({ applyVolumes() }, (untilMs - JamClock.nowMs()).coerceAtLeast(0L) + 50)
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // A party keeps playing – focus changes from other apps are ignored on purpose
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }

    // ── Server messages ───────────────────────────────────────────────────

    fun onState(playing: Boolean, track: String?, startedAt: Long, pauseOffset: Long, repeatSingle: Boolean) = main {
        if (track != null) currentTrack = track
        isPlaying = playing
        startedAtMs = startedAt
        pauseOffsetMs = pauseOffset
        isRepeatSingle = repeatSingle
        // Repeat-one loops inside the player → gapless, the server only re-times it
        activePlayer.repeatMode = if (repeatSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (playing) requestFocus()
    }

    fun setCrossfade(ms: Int) = main {
        crossfadeMs = ms.coerceIn(0, 12_000)
        applyPreload()
    }

    fun onPreload(trackUrl: String?) = main {
        lastPreloadUrl = trackUrl
        applyPreload()
    }

    fun onPrepare(trackUrl: String) = main {
        // The server waits for ACTION_TRACK_READY (sent by the player listener once buffered)
        stopFade()
        val player = activePlayer
        val idx = indexOfTrack(player, trackUrl)
        when {
            idleHas(trackUrl) -> {
                // Preloaded on the other player (crossfade mode) – make that one active
                swapActive()
                activePlayer.playWhenReady = false
                activePlayer.seekTo(0L)
                player.stop()
                player.clearMediaItems()
                reportReadySoon(trackUrl)
            }
            idx == -1 -> {
                player.setMediaItem(buildMediaItem(trackUrl))
                player.prepare()
                player.playWhenReady = false
                applyPreload()
                // One extra clock sample so the upcoming PLAY uses a fresh offset (history is kept)
                MainActivity.syncClient?.pingServer(false)
            }
            idx != player.currentMediaItemIndex -> {
                // Already preloaded as next item – jump there, buffer is kept
                player.playWhenReady = false
                player.seekTo(idx, 0L)
                trimPlaylist(player)
                reportReadySoon(trackUrl)
            }
            else -> reportReadySoon(trackUrl)
        }
    }

    fun onPause(offsetMs: Long) = main {
        isPlaying = false
        pauseOffsetMs = offsetMs
        stopFade()
        activePlayer.pause()
        if (offsetMs >= 0) activePlayer.seekTo(offsetMs)
    }

    fun onPlay(trackUrl: String, startedAt: Long, pauseOffset: Long, crossfade: Int = 0, fromTrackUrl: String? = null) = main {
        currentTrack = trackUrl
        startedAtMs = startedAt
        pauseOffsetMs = pauseOffset
        isPlaying = true
        requestFocus()
        play(trackUrl, startedAt, pauseOffset, crossfade, fromTrackUrl)
    }

    /** Leaving the session: everything stops, the players stay around for the next session. */
    fun stopSession() = main {
        stopFade()
        players.forEach { it.stop(); it.clearMediaItems(); it.playbackParameters = PlaybackParameters(1f) }
        releaseChirpPlayer()
        stopClicks()
        syncTestActive = false
        isPlaying = false
        currentTrack = null
        startedAtMs = 0L
        pauseOffsetMs = 0L
        syncDiffMs = 0L
        rttMs = 0L
        lastPreloadUrl = null
        duckFactor = 1f
        mutedUntilMs = 0L
        crossfadeMs = 0
        applyVolumes()
        abandonFocus()
    }

    /** Forget the drift history (e.g. after a manual re-sync). */
    fun resync() = main { awaitingSeekResult = false }

    // ── Core playback ─────────────────────────────────────────────────────

    /** Own files are played straight from storage, everything else is streamed from the host. */
    private fun buildMediaItem(trackUrl: String): MediaItem {
        var playUri = trackUrl
        try {
            val segments = Uri.parse(trackUrl).pathSegments
            if (segments.isNotEmpty()) MainActivity.localFileServer?.getFileUri(segments[0])?.let { playUri = it.toString() }
        } catch (_: Exception) {}
        return MediaItem.Builder().setUri(playUri).setMediaId(trackUrl).build()
    }

    private fun indexOfTrack(player: ExoPlayer, trackUrl: String): Int =
        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackUrl } ?: -1

    private fun idleHas(trackUrl: String) =
        fadingPlayer == null && idlePlayer.mediaItemCount > 0 && idlePlayer.currentMediaItem?.mediaId == trackUrl

    /** Keeps the playlist at [current, next] – finished items are dropped. */
    private fun trimPlaylist(player: ExoPlayer) {
        val idx = player.currentMediaItemIndex
        if (idx > 0) player.removeMediaItems(0, idx)
    }

    private fun swapActive() {
        activeIdx = 1 - activeIdx
        val p = activePlayer
        fadeGain[p] = 1f
        p.repeatMode = if (isRepeatSingle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        mediaSession.player = forwardingFor(p)
        activePlayerState = p
        applyVolumes()
    }

    /**
     * Without crossfade the follow-up track sits behind the current one in the same playlist
     * (ExoPlayer switches gaplessly). With crossfade it is buffered on the other player instead.
     */
    private fun applyPreload() {
        val url = lastPreloadUrl
        val player = activePlayer
        if (player.mediaItemCount == 0) return
        val cur = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val wantInPlaylist = if (crossfadeMs > 0) null else url?.takeIf { it != currentId }

        if (!(wantInPlaylist != null && player.mediaItemCount == cur + 2 && player.getMediaItemAt(cur + 1).mediaId == wantInPlaylist)) {
            if (player.mediaItemCount > cur + 1) player.removeMediaItems(cur + 1, player.mediaItemCount)
            if (wantInPlaylist != null) player.addMediaItem(buildMediaItem(wantInPlaylist))
        }

        if (fadingPlayer != null) return // the other player is still fading out – preload afterwards
        val idle = idlePlayer
        val wantOnIdle = if (crossfadeMs > 0) url?.takeIf { it != currentId } else null
        if (wantOnIdle == null) {
            if (idle.mediaItemCount > 0) { idle.stop(); idle.clearMediaItems() }
        } else if (idle.currentMediaItem?.mediaId != wantOnIdle) {
            idle.setMediaItem(buildMediaItem(wantOnIdle))
            idle.prepare()
            idle.playWhenReady = false
            fadeGain[idle] = 0f
            applyVolumes()
        }
    }

    private fun play(trackUrl: String, startedAt: Long, pauseOffset: Long, crossfade: Int, fromTrackUrl: String?) {
        val player = activePlayer
        val waitTimeMs = startedAt - syncedNowMs()
        val currentId = player.currentMediaItem?.mediaId

        // Crossfade: the next track starts on the other player while the current one keeps playing
        if (crossfade > 0 && pauseOffset == 0L && fromTrackUrl != null && currentId == fromTrackUrl &&
            player.isPlaying && trackUrl != currentId) {
            val idle = idlePlayer
            if (fadingPlayer == null) {
                if (idle.currentMediaItem?.mediaId != trackUrl) {
                    idle.setMediaItem(buildMediaItem(trackUrl))
                    idle.prepare()
                }
                idle.playWhenReady = false
                idle.seekTo(0L)
                fadeGain[idle] = 0f
                applyVolumes()
                if (waitTimeMs > 0) {
                    handler.postDelayed({ startCrossfade(trackUrl, startedAt, crossfade) }, waitTimeMs)
                } else {
                    startCrossfade(trackUrl, startedAt, crossfade)
                }
                return
            }
        }

        val idx = indexOfTrack(player, trackUrl)
        // Seamless hand-over announced ahead of time: the follow-up track is preloaded behind the one
        // that is still playing. ExoPlayer switches gaplessly exactly at its end – touch nothing.
        if (startedAt > 0 && waitTimeMs > 0 && pauseOffset == 0L && player.isPlaying && player.duration > 0) {
            val remaining = player.duration - player.currentPosition
            val endsOnCue = abs(remaining - waitTimeMs) < 1500
            if (endsOnCue && currentId != trackUrl && idx == player.currentMediaItemIndex + 1) {
                // Safety net for a device that runs behind: force the switch shortly after the planned start
                handler.postDelayed({ ensureSwitched(trackUrl, startedAt) }, waitTimeMs + 350)
                return
            }
            if (endsOnCue && currentId == trackUrl && player.repeatMode == Player.REPEAT_MODE_ONE) return
        }

        stopFade()
        when {
            idx == -1 && idleHas(trackUrl) -> {
                swapActive()
                player.stop()
                player.clearMediaItems()
            }
            idx == -1 -> {
                player.setMediaItem(buildMediaItem(trackUrl))
                player.prepare()
                applyPreload()
            }
            idx != player.currentMediaItemIndex -> {
                player.seekTo(idx, pauseOffset.coerceAtLeast(0L))
                trimPlaylist(player)
            }
        }
        val p = activePlayer
        if (p.playbackState == Player.STATE_IDLE) p.prepare()

        // Guard: startedAt=0 is an invalid/uninitialized timestamp.
        if (startedAt == 0L) {
            p.seekTo(pauseOffset.coerceAtLeast(0L))
            p.playWhenReady = true
            return
        }

        if (waitTimeMs > 0) {
            // Pre-seek now while there's time to buffer; at the start moment only playWhenReady flips
            p.seekTo(pauseOffset.coerceAtLeast(0L))
            p.playWhenReady = false
            handler.postDelayed({
                if (p === activePlayer && p.currentMediaItem?.mediaId == trackUrl && startedAtMs == startedAt && isPlaying) {
                    p.playWhenReady = true
                }
            }, waitTimeMs)
        } else {
            // Late join: jump to the right spot and start immediately
            val target = pauseOffset + (syncedNowMs() - startedAt)
            val clamped = if (p.duration > 0) target.coerceIn(0L, p.duration) else target.coerceAtLeast(0L)
            // Already running in sync (e.g. right after a seamless transition) – a seek would only cause a hiccup
            val inSync = p.currentMediaItem?.mediaId == trackUrl && p.isPlaying && abs(p.currentPosition - clamped) < 120
            if (!inSync) {
                if (p.isPlaying) hardSeek(p, clamped) else p.seekTo(clamped)
            }
            p.playWhenReady = true
        }
    }

    /** Fallback if the gapless switch didn't happen on time (slow device, buffer underrun). */
    private fun ensureSwitched(trackUrl: String, startedAt: Long) {
        val player = activePlayer
        if (startedAtMs != startedAt || !isPlaying) return
        if (player.currentMediaItem?.mediaId == trackUrl) return
        val target = (syncedNowMs() - startedAt).coerceAtLeast(0L)
        val idx = indexOfTrack(player, trackUrl)
        if (idx >= 0) {
            player.seekTo(idx, target)
        } else {
            player.setMediaItem(buildMediaItem(trackUrl), target)
            player.prepare()
        }
        player.playWhenReady = true
        trimPlaylist(player)
    }

    private fun startCrossfade(trackUrl: String, startedAt: Long, crossfade: Int) {
        if (startedAtMs != startedAt || !isPlaying || fadingPlayer != null) return
        val old = activePlayer
        val incoming = idlePlayer
        if (incoming.currentMediaItem?.mediaId != trackUrl) return
        val elapsed = (syncedNowMs() - startedAt).coerceAtLeast(0L)
        if (elapsed > 40) incoming.seekTo(elapsed)
        incoming.playWhenReady = true
        fadeGain[incoming] = 0f
        fadingPlayer = old
        swapActive()
        fadeGain[incoming] = 0f
        old.repeatMode = Player.REPEAT_MODE_OFF
        setSpeed(old, 1f)

        val total = (crossfade - elapsed).coerceAtLeast(FADE_STEP_MS)
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val start = JamClock.nowMs()
            while (isActive) {
                val t = ((JamClock.nowMs() - start).toFloat() / total).coerceIn(0f, 1f)
                // Equal-power curve: no loudness dip in the middle of the fade
                fadeGain[incoming] = sin(t * Math.PI.toFloat() / 2f)
                fadeGain[old] = cos(t * Math.PI.toFloat() / 2f)
                applyVolumes()
                if (t >= 1f) break
                delay(FADE_STEP_MS)
            }
            finishFade()
        }
    }

    private fun finishFade() {
        val old = fadingPlayer ?: return
        fadingPlayer = null
        old.stop()
        old.clearMediaItems()
        fadeGain[old] = 1f
        fadeGain[activePlayer] = 1f
        applyVolumes()
        applyPreload()
    }

    private fun stopFade() {
        fadeJob?.cancel()
        fadeJob = null
        finishFade()
    }

    private fun reportReadySoon(trackUrl: String) {
        handler.postDelayed({
            val p = activePlayer
            val state = p.playbackState
            if (p.currentMediaItem?.mediaId == trackUrl && (state == Player.STATE_READY || state == Player.STATE_ENDED)) {
                MainActivity.syncClient?.sendTrackReady(trackUrl, p.duration.coerceAtLeast(0L))
            }
        }, 120)
    }

    // ── Drift correction ──────────────────────────────────────────────────

    private fun setSpeed(player: ExoPlayer, speed: Float) {
        if (abs(player.playbackParameters.speed - speed) > 0.0005f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    private fun hardSeek(player: ExoPlayer, positionMs: Long) {
        setSpeed(player, 1f)
        player.seekTo((positionMs + seekCompensationMs).coerceAtLeast(0L))
        lastHardSeekTime = JamClock.nowMs()
        awaitingSeekResult = true
    }

    /**
     * Keeps this device on the shared timeline. Small offsets are pulled in smoothly by a speed
     * change proportional to the error (inaudible, no pitch shift), only big jumps are seeked.
     */
    private fun startDriftLoop() {
        scope.launch {
            val recentDiffs = ArrayDeque<Long>()
            var measuredTrack: String? = null
            while (isActive) {
                delay(250)
                val player = activePlayer
                val onTimeline = isPlaying && startedAtMs > 0L &&
                    player.playbackState == Player.STATE_READY && player.isPlaying &&
                    // During a seamless hand-over the timeline already belongs to the next track
                    player.currentMediaItem?.mediaId == currentTrack
                if (!onTimeline) {
                    recentDiffs.clear()
                    setSpeed(player, 1f)
                    continue
                }
                if (player.currentMediaItem?.mediaId != measuredTrack) {
                    measuredTrack = player.currentMediaItem?.mediaId
                    recentDiffs.clear()
                }
                val elapsed = syncedNowMs() - startedAtMs
                if (elapsed < 0) continue
                val expected = pauseOffsetMs + elapsed
                if (player.duration > 0 && expected > player.duration) continue
                // Let a seek settle before measuring again
                if (JamClock.nowMs() - lastHardSeekTime < 700) continue

                val raw = player.currentPosition - expected
                if (awaitingSeekResult) {
                    awaitingSeekResult = false
                    seekCompensationMs = (seekCompensationMs - raw / 2).coerceIn(0L, 300L)
                }
                recentDiffs.addLast(raw)
                if (recentDiffs.size > 5) recentDiffs.removeFirst()
                // Median ignores single outliers from position-reporting jitter
                val diff = recentDiffs.sorted()[recentDiffs.size / 2]
                syncDiffMs = diff
                val absDiff = abs(diff)

                when {
                    absDiff > 250 && recentDiffs.size >= 2 -> {
                        if (JamClock.nowMs() - lastHardSeekTime > 2000) {
                            recentDiffs.clear()
                            hardSeek(player, expected)
                        }
                    }
                    absDiff <= 6 -> setSpeed(player, 1f)
                    else -> {
                        // 25 ms ahead → 1 % slower; capped at ±4 %; quantised so the speed isn't changed constantly
                        val correction = (diff / 2500f).coerceIn(-0.04f, 0.04f)
                        setSpeed(player, Math.round((1f - correction) / 0.004f) * 0.004f)
                    }
                }
            }
        }
    }

    // ── Auto-calibration: play chirps exactly at the given session times ──

    /**
     * Plays the calibration chirps through the same output path and timing logic as the music,
     * so whatever offset the host measures is exactly what the music needs.
     */
    fun playCalibrationChirps(chirpTimesMs: List<Long>) = main {
        if (chirpTimesMs.isEmpty()) return@main
        releaseChirpPlayer()
        val first = chirpTimesMs.min()
        val leadMs = 1500L
        val file = File(context.cacheDir, "calib_chirp.wav")
        ChirpSignal.writeWav(file, chirpTimesMs.map { it - first + leadMs }, totalMs = (chirpTimesMs.max() - first) + leadMs + 600)
        val startAt = first - leadMs

        val player = buildPlayer()
        chirpPlayer = player
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = false
        val wait = startAt - syncedNowMs()
        handler.postDelayed({ if (chirpPlayer === player) player.playWhenReady = true }, wait.coerceAtLeast(0L))

        // Align during the silent lead-in: big errors are seeked, the rest is trimmed with the speed
        // (pitch doesn't matter in silence). Back to exactly 1.0× before the first chirp.
        scope.launch {
            var lastSeek = 0L
            while (isActive && chirpPlayer === player) {
                delay(40)
                val expected = syncedNowMs() - startAt
                if (expected < 0 || !player.isPlaying) continue
                if (expected > leadMs - 250) {
                    setSpeed(player, 1f)
                    break
                }
                if (JamClock.nowMs() - lastSeek < 250) continue
                val diff = player.currentPosition - expected
                if (abs(diff) > 40) {
                    setSpeed(player, 1f)
                    player.seekTo((expected + seekCompensationMs).coerceAtLeast(0L))
                    lastSeek = JamClock.nowMs()
                } else {
                    setSpeed(player, (1f - diff / 250f).coerceIn(0.85f, 1.15f))
                }
            }
        }
        handler.postDelayed({ if (chirpPlayer === player) releaseChirpPlayer() },
            (chirpTimesMs.max() - syncedNowMs()).coerceAtLeast(0L) + 1500)
    }

    // ── Click test: same beat on every device, so offsets can be tuned by ear ──

    fun setSyncTest(enabled: Boolean) = main {
        if (enabled == syncTestActive) return@main
        syncTestActive = enabled
        applyVolumes()
        if (enabled) startClicks() else stopClicks()
    }

    private fun startClicks() {
        stopClicks()
        val file = File(context.cacheDir, "sync_clicks.wav")
        if (!file.exists()) ChirpSignal.writeClickWav(file)
        val player = buildPlayer()
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        clickPlayer = player
        // The loop position must equal (session time mod loop length) – exactly like the music it uses
        // this device's offset, so turning the offset moves the clicks.
        clickAlignJob = scope.launch {
            var lastSeek = 0L
            while (isActive && clickPlayer === player) {
                delay(50)
                if (!player.isPlaying || player.playbackState != Player.STATE_READY) continue
                val loop = ChirpSignal.CLICK_LOOP_MS
                val expected = Math.floorMod(syncedNowMs(), loop)
                var diff = player.currentPosition - expected
                if (diff > loop / 2) diff -= loop
                if (diff < -loop / 2) diff += loop
                if (abs(diff) > 20) {
                    if (JamClock.nowMs() - lastSeek > 400) {
                        setSpeed(player, 1f)
                        player.seekTo(Math.floorMod(expected + seekCompensationMs, loop))
                        lastSeek = JamClock.nowMs()
                    }
                } else {
                    setSpeed(player, if (abs(diff) <= 2) 1f else (1f - diff / 400f).coerceIn(0.95f, 1.05f))
                }
            }
        }
    }

    private fun realignClicks() {
        val player = clickPlayer ?: return
        val expected = Math.floorMod(syncedNowMs() + seekCompensationMs, ChirpSignal.CLICK_LOOP_MS)
        player.seekTo(expected)
    }

    private fun stopClicks() {
        clickAlignJob?.cancel()
        clickAlignJob = null
        clickPlayer?.release()
        clickPlayer = null
    }

    private fun releaseChirpPlayer() {
        chirpPlayer?.release()
        chirpPlayer = null
    }
}
