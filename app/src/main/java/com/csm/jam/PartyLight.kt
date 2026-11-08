package com.csm.jam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
