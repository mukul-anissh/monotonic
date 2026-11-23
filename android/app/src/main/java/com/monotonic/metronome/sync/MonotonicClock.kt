package com.monotonic.metronome.sync

/**
 * Abstraction over a strictly monotonic nanosecond clock.
 *
 * Production uses [SystemNanoTimeClock], which maps directly to POSIX `CLOCK_MONOTONIC`
 * on Android and matches the native C++ Oboe engine's `clock_gettime(CLOCK_MONOTONIC)`.
 */
fun interface MonotonicClock {
    fun nanoTime(): Long
}

object SystemNanoTimeClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
