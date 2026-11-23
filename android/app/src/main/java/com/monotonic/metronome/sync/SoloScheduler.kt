package com.monotonic.metronome.sync

import kotlin.math.roundToLong

data class SoloTransportState(
    val isPlaying: Boolean = false,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val subdivision: Int = 1,
    val localStartTimeNs: Long = 0L,
    val stateVersion: Long = 1L
)

/**
 * Pure monotonic transport scheduler for offline Solo Mode.
 *
 * Uses [System.nanoTime] / [MonotonicClock] parity with a crisp 50ms local start buffer
 * and phase-preserving tempo/meter adjustments when playing.
 */
object SoloScheduler {
    const val MIN_BPM = 20.0
    const val MAX_BPM = 500.0
    const val MIN_BEATS_PER_BAR = 1
    const val MAX_BEATS_PER_BAR = 16
    val VALID_SUBDIVISIONS = setOf(1, 2, 3, 4)
    const val SOLO_LEAD_IN_NS = 50_000_000L

    fun play(state: SoloTransportState, nowNs: Long): SoloTransportState {
        if (state.isPlaying) return state
        return state.copy(
            isPlaying = true,
            localStartTimeNs = nowNs + SOLO_LEAD_IN_NS,
            stateVersion = state.stateVersion + 1L
        )
    }

    fun stop(state: SoloTransportState): SoloTransportState {
        if (!state.isPlaying && state.localStartTimeNs == 0L) return state
        return state.copy(
            isPlaying = false,
            localStartTimeNs = 0L,
            stateVersion = state.stateVersion + 1L
        )
    }

    fun updateTransport(
        state: SoloTransportState,
        nowNs: Long,
        bpm: Double = state.bpm,
        beatsPerBar: Int = state.beatsPerBar,
        subdivision: Int = state.subdivision
    ): SoloTransportState {
        val newBpm = (bpm.coerceIn(MIN_BPM, MAX_BPM) * 100.0).roundToLong() / 100.0
        val newBpb = beatsPerBar.coerceIn(MIN_BEATS_PER_BAR, MAX_BEATS_PER_BAR)
        val newSub = if (subdivision in VALID_SUBDIVISIONS) subdivision else state.subdivision

        if (newBpm == state.bpm && newBpb == state.beatsPerBar && newSub == state.subdivision) {
            return state
        }

        val nextStartNs = if (state.isPlaying && nowNs >= state.localStartTimeNs) {
            val oldBeatNs = 60_000_000_000.0 / state.bpm
            val elapsedNs = (nowNs - state.localStartTimeNs).coerceAtLeast(0L)
            val currentBeatNumber = (elapsedNs.toDouble() / oldBeatNs).toLong().coerceAtLeast(0L)
            val currentBeatStartNs = state.localStartTimeNs + (currentBeatNumber.toDouble() * oldBeatNs).roundToLong()
            val beatInBar = (currentBeatNumber % state.beatsPerBar.toLong()).toInt().coerceAtMost(newBpb - 1)
            val newBeatNs = 60_000_000_000.0 / newBpm
            // Keep virtualBeatNumber >= 1 bar so C++ aligns silently without re-triggering beat 0
            val virtualBeatNumber = beatInBar + newBpb
            currentBeatStartNs - (virtualBeatNumber.toDouble() * newBeatNs).roundToLong()
        } else {
            state.localStartTimeNs
        }

        return state.copy(
            bpm = newBpm,
            beatsPerBar = newBpb,
            subdivision = newSub,
            localStartTimeNs = nextStartNs,
            stateVersion = state.stateVersion + 1L
        )
    }
}
