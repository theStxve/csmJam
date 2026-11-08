package com.csm.jam

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ChirpSignalTest {

    private val sr = ChirpSignal.SAMPLE_RATE

    /** Simulated room recording: noise + attenuated chirp at [atSample] + a louder reflection 3 ms later. */
    private fun recording(atSample: Int, noise: Float = 0.05f, gain: Float = 0.2f): FloatArray {
        val rnd = Random(42)
        val samples = FloatArray(sr * 2) { (rnd.nextFloat() * 2 - 1) * noise }
        val chirp = ChirpSignal.template(sr)
        val reflection = atSample + sr * 3 / 1000
        chirp.forEachIndexed { i, v ->
            samples[atSample + i] += v * gain
            if (reflection + i < samples.size) samples[reflection + i] += v * gain * 1.3f
        }
        return samples
    }

    @Test
    fun findsTheDirectSoundToTheMillisecond() {
        val at = sr * 700 / 1000 + 17
        val d = ChirpSignal.detect(recording(at), ChirpSignal.template(sr), 0, sr * 2)
        assertNotNull(d)
        // The earlier direct path wins over the louder reflection, within 0.5 ms
        assertTrue("found ${d!!.index}, expected $at", abs(d.index - at) <= sr / 2000)
        assertTrue(d.confidence >= 8f)
    }

    @Test
    fun noiseAloneHasLowConfidence() {
        val rnd = Random(7)
        val noise = FloatArray(sr) { (rnd.nextFloat() * 2 - 1) * 0.1f }
        val d = ChirpSignal.detect(noise, ChirpSignal.template(sr), 0, sr)
        assertTrue(d == null || d.confidence < 8f)
    }
}
