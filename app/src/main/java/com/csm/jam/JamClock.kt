package com.csm.jam

/**
 * Session clock in wall-clock-like milliseconds that never jumps.
 * System.currentTimeMillis() can be moved by NTP/carrier time updates in the middle of a party,
 * which would shift every scheduled start time at once. This clock only ever moves forward smoothly.
 */
object JamClock {
    private val baseWallMs = System.currentTimeMillis()
    private val baseNanos = System.nanoTime()

    fun nowMs(): Long = baseWallMs + (System.nanoTime() - baseNanos) / 1_000_000L

    /** Converts a System.nanoTime() stamp (e.g. an audio hardware timestamp) to this clock, sub-ms exact. */
    fun fromNanosExact(nanoTime: Long): Double = baseWallMs + (nanoTime - baseNanos) / 1_000_000.0
}
