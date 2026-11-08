package com.csm.jam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder

/**
 * Host side of the auto-calibration. Every device plays chirps at planned session times; the host
 * phone records them and measures how late each one arrives. Everything is measured relative to
 * the host's own chirp, so the host's mic latency and the clock mapping cancel out – what remains
 * is the real output delay difference (incl. Bluetooth) of each device.
 */
class AutoCalibrator(private val context: Context) {

    data class Slot(val participantId: String, val name: String, val atMs: Long, val isHost: Boolean)

    /** [deltaMs] = how much later than the host this device sounds; null = not heard. */
    data class Result(val participantId: String, val name: String, val deltaMs: Long?, val isHost: Boolean)

    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Blocking – run on a background thread. Returns null + error text if the host's own chirp wasn't heard. */
    @SuppressLint("MissingPermission")
    fun run(slots: List<Slot>, untilMs: Long, sessionNowMs: () -> Long): Pair<List<Result>?, String?> {
        val sr = ChirpSignal.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null to "Mikrofon nicht verfügbar"
        val record = try {
            AudioRecord(pickSource(), sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, sr))
        } catch (e: Exception) {
            return null to "Mikrofon nicht verfügbar"
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null to "Mikrofon nicht verfügbar"
        }

        // Record from now until the last chirp has surely arrived (Bluetooth can add ~0.5 s)
        val durationMs = (untilMs - sessionNowMs()).coerceIn(1000L, 60_000L) + 400
        val samples = FloatArray((sr * durationMs / 1000).toInt())
        val chunk = ShortArray(2048)
        var written = 0
        // Session time of sample 0 – refined below with the hardware capture timestamp
        var sample0SessionMs: Double
        try {
            record.startRecording()
            sample0SessionMs = sessionNowMs().toDouble()
            val ts = AudioTimestamp()
            var haveTimestamp = false
            while (written < samples.size && !cancelled) {
                val read = record.read(chunk, 0, minOf(chunk.size, samples.size - written))
                if (read <= 0) break
                for (i in 0 until read) samples[written + i] = chunk[i] / 32768f
                written += read
                if (!haveTimestamp && record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    // frame `ts.framePosition` was captured at `ts.nanoTime`
                    val frameSessionMs = JamClock.fromNanosExact(ts.nanoTime) + (sessionNowMs() - JamClock.nowMs())
                    sample0SessionMs = frameSessionMs - ts.framePosition * 1000.0 / sr
                    haveTimestamp = true
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        if (cancelled) return null to "Abgebrochen"

        val template = ChirpSignal.template(sr)
        val recorded = samples.copyOf(written)
        fun indexFor(sessionMs: Double) = ((sessionMs - sample0SessionMs) * sr / 1000.0).toInt()

        // Lag of every slot: arrival time – planned time
        val lagsById = LinkedHashMap<String, MutableList<Double>>()
        slots.forEach { slot ->
            val expected = indexFor(slot.atMs.toDouble())
            val detection = ChirpSignal.detect(
                recorded, template,
                expected - sr * 200 / 1000, expected + sr * 750 / 1000
            )
            val list = lagsById.getOrPut(slot.participantId) { mutableListOf() }
            if (detection != null && detection.confidence >= 8f) {
                list.add((detection.index - expected) * 1000.0 / sr)
            }
        }

        val hostSlot = slots.firstOrNull { it.isHost } ?: return null to "Kein Host im Plan"
        val hostLag = median(lagsById[hostSlot.participantId].orEmpty())
            ?: return null to "Den eigenen Ton nicht gehört – Lautstärke hoch und nochmal"

        val results = slots.distinctBy { it.participantId }.map { slot ->
            val lag = median(lagsById[slot.participantId].orEmpty())
            val delta = lag?.let { Math.round(it - hostLag) }?.takeIf { kotlin.math.abs(it) <= 1500 }
            Result(slot.participantId, slot.name, if (slot.isHost) 0L else delta, slot.isHost)
        }
        return results to null
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Least processed mic source the device offers (no AGC/noise suppression smearing the chirp). */
    private fun pickSource(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}
