package com.csm.jam

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The auto-calibration signal: a 40 ms linear sweep 2→8 kHz with a Hann window.
 * A sweep correlates into one very sharp peak (≈0.2 ms wide) even in a noisy room,
 * which is what makes the arrival time measurable to the millisecond.
 */
object ChirpSignal {
    const val SAMPLE_RATE = 48_000
    const val CHIRP_MS = 40
    private const val F0 = 2_000.0
    private const val F1 = 8_000.0

    fun template(sampleRate: Int = SAMPLE_RATE): FloatArray {
        val n = sampleRate * CHIRP_MS / 1000
        val duration = n.toDouble() / sampleRate
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (F0 * t + (F1 - F0) * t * t / (2 * duration))
            val window = 0.5 * (1 - cos(2 * PI * i / (n - 1)))
            (sin(phase) * window).toFloat()
        }
    }

    /** 16-bit mono WAV of [totalMs] silence with a chirp starting at each of [chirpOffsetsMs]. */
    fun writeWav(file: File, chirpOffsetsMs: List<Long>, totalMs: Long) {
        val total = (SAMPLE_RATE * totalMs / 1000).toInt()
        val pcm = ShortArray(total)
        val chirp = template()
        chirpOffsetsMs.forEach { offset ->
            val start = (SAMPLE_RATE * offset / 1000).toInt()
            for (i in chirp.indices) {
                val idx = start + i
                if (idx in 0 until total) pcm[idx] = (chirp[i] * 0.9f * Short.MAX_VALUE).toInt().toShort()
            }
        }
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val dataBytes = total * 2
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }
            f.write(header.array())
            val body = java.nio.ByteBuffer.allocate(dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { body.putShort(it) }
            f.write(body.array())
        }
    }

    /** Length of the click-test loop; clicks every [CLICK_INTERVAL_MS], accented every second. */
    const val CLICK_LOOP_MS = 4000L
    const val CLICK_INTERVAL_MS = 500L

    /**
     * Click-test loop: sharp 12 ms clicks every 500 ms, a higher accented click on every full second.
     * Sharp attack + short decay so two devices a few ms apart are clearly heard as a "flam".
     */
    fun writeClickWav(file: File) {
        val total = (SAMPLE_RATE * CLICK_LOOP_MS / 1000).toInt()
        val pcm = ShortArray(total)
        val clickLen = SAMPLE_RATE * 12 / 1000
        var t = 0L
        while (t < CLICK_LOOP_MS) {
            val accent = t % 1000L == 0L
            val freq = if (accent) 2_000.0 else 1_200.0
            val start = (SAMPLE_RATE * t / 1000).toInt()
            for (i in 0 until clickLen) {
                val env = kotlin.math.exp(-i / (clickLen / 5.0))
                val v = sin(2 * PI * freq * i / SAMPLE_RATE) * env * (if (accent) 0.95 else 0.7)
                pcm[start + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            t += CLICK_INTERVAL_MS
        }
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val dataBytes = total * 2
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }
            f.write(header.array())
            val body = java.nio.ByteBuffer.allocate(dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { body.putShort(it) }
            f.write(body.array())
        }
    }

    /** @param index sample where the chirp starts, @param confidence peak height relative to the noise floor */
    data class Detection(val index: Int, val confidence: Float)

    /**
     * Matched filter: finds where [template] starts inside samples[from, to).
     * Takes the earliest strong peak, not simply the highest – in a room the direct sound comes
     * first and reflections can be louder.
     */
    fun detect(samples: FloatArray, template: FloatArray, from: Int, to: Int): Detection? {
        val n = template.size
        val start = max(0, from)
        val end = min(samples.size - n, to)
        if (end <= start) return null
        val corr = FloatArray(end - start)
        var best = 0f
        var bestIdx = -1
        for (i in start until end) {
            var acc = 0f
            for (j in 0 until n) acc += samples[i + j] * template[j]
            val v = abs(acc)
            corr[i - start] = v
            if (v > best) {
                best = v
                bestIdx = i
            }
        }
        if (bestIdx < 0 || best <= 0f) return null
        val floor = corr.copyOf().also { it.sort() }[corr.size / 2] + 1e-9f
        // Direct path: earliest peak within 6 ms before the maximum that is at least 60 % as strong
        val lookBack = SAMPLE_RATE * 6 / 1000
        var direct = bestIdx
        for (i in max(start, bestIdx - lookBack) until bestIdx) {
            if (corr[i - start] >= best * 0.6f) {
                direct = i
                break
            }
        }
        return Detection(direct, best / floor)
    }
}
