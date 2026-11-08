package com.csm.jam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/** Records the megaphone (16 kHz mono PCM16) and hands every chunk to [onChunkReady]. */
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

/**
 * Plays the megaphone PCM stream (16 kHz mono). Called directly on the WebSocket thread so
 * start → chunks → stop keep their order; nothing is lost while the UI thread is busy.
 */
class VoicePlaybackManager {
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 16000
    // ~150 ms jitter buffer: WLAN delivers chunks in bursts, starting immediately would stutter
    private val prebufferBytes = sampleRate * 2 * 150 / 1000
    private var bufferedBytes = 0
    private var playing = false
    @Volatile var lastChunkAtMs: Long = 0L
        private set

    @Synchronized
    fun start() {
        releaseNow()
        lastChunkAtMs = System.currentTimeMillis()
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
                .setBufferSizeInBytes(maxOf(minBufSize * 4, sampleRate * 2)) // up to 1 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
                bufferedBytes = 0
                playing = false
            } else {
                track.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun write(data: ByteArray) {
        lastChunkAtMs = System.currentTimeMillis()
        val track = audioTrack ?: return
        try {
            // Never block the socket thread – it also carries the music sync messages
            track.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
            if (!playing) {
                bufferedBytes += data.size
                if (bufferedBytes >= prebufferBytes) {
                    track.play()
                    playing = true
                }
            }
        } catch (_: Exception) {}
    }

    /** Lets the buffered rest play out (no cut-off last word), then frees the track. */
    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            if (!playing && bufferedBytes > 0) track.play() // very short message never reached the prebuffer
            track.stop() // in stream mode this finishes the written data first
        } catch (_: Exception) {}
        Thread {
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            try { track.release() } catch (_: Exception) {}
        }.start()
    }

    private fun releaseNow() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (_: Exception) {}
    }
}
