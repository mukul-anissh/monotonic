package com.monotonic.metronome.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloSchedulerTest {

    @Test
    fun testPlaySchedules50msLeadInAndStopClears() {
        val initial = SoloTransportState()
        val nowNs = 10_000_000_000L

        val playing = SoloScheduler.play(initial, nowNs)
        assertTrue(playing.isPlaying)
        assertEquals(nowNs + 50_000_000L, playing.localStartTimeNs)
        assertEquals(initial.stateVersion + 1L, playing.stateVersion)

        // Repeated play while playing is a no-op
        assertEquals(playing, SoloScheduler.play(playing, nowNs + 1_000_000L))

        val stopped = SoloScheduler.stop(playing)
        assertFalse(stopped.isPlaying)
        assertEquals(0L, stopped.localStartTimeNs)
        assertEquals(playing.stateVersion + 1L, stopped.stateVersion)
    }

    @Test
    fun testBpm500AndMeterBoundsClamping() {
        val initial = SoloTransportState()
        val nowNs = 5_000_000_000L

        val upper = SoloScheduler.updateTransport(
            state = initial,
            nowNs = nowNs,
            bpm = 650.0,
            beatsPerBar = 24,
            subdivision = 3
        )
        assertEquals(500.0, upper.bpm, 0.001)
        assertEquals(16, upper.beatsPerBar)
        assertEquals(3, upper.subdivision)

        val lower = SoloScheduler.updateTransport(
            state = upper,
            nowNs = nowNs,
            bpm = 5.0,
            beatsPerBar = 0,
            subdivision = 4
        )
        assertEquals(20.0, lower.bpm, 0.001)
        assertEquals(1, lower.beatsPerBar)
        assertEquals(4, lower.subdivision)

        // Invalid subdivision retains existing valid subdivision
        val invalidSub = SoloScheduler.updateTransport(
            state = lower,
            nowNs = nowNs,
            subdivision = 5
        )
        assertEquals(4, invalidSub.subdivision)
    }

    @Test
    fun testMidPlayback500BpmTransitionPreservesBeatPhase() {
        val t0 = 100_000_000_000L
        val playing = SoloScheduler.play(
            SoloTransportState(bpm = 120.0, beatsPerBar = 4, subdivision = 1),
            t0
        )
        val startNs = playing.localStartTimeNs // t0 + 50ms

        // 1 beat = 500ms at 120 BPM. Advance 1100ms past start -> 100ms into Beat 2 (beatIdx = 2)
        val currentBeatStartNs = startNs + 1_000_000_000L
        val nowNs = currentBeatStartNs + 100_000_000L

        val updated = SoloScheduler.updateTransport(
            state = playing,
            nowNs = nowNs,
            bpm = 500.0
        )
        assertEquals(500.0, updated.bpm, 0.001)
        assertTrue(updated.localStartTimeNs < nowNs)

        // At 500 BPM, 1 beat = 120_000_000 ns (120 ms).
        // Next beat boundary should fall cleanly at currentBeatStartNs + 120ms, on beat index 3.
        val nextBeatNs = currentBeatStartNs + 120_000_000L
        val elapsedAtNextBeatNs = nextBeatNs - updated.localStartTimeNs
        val beatDurationNs = 120_000_000L
        assertEquals(0L, elapsedAtNextBeatNs % beatDurationNs)
        val beatNumber = elapsedAtNextBeatNs / beatDurationNs
        assertTrue(beatNumber >= 4L)
        assertEquals(3L, beatNumber % 4L)
    }
}
