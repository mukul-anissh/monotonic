package com.monotonic.metronome.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClockSyncMathTest {

    @Test
    fun testSntpSignConventionPositiveAndNegativeOffset() {
        // Case 1: Server monotonic clock is +42_500_000_000 ns (42.5s) ahead of client
        val trueOffsetPos = 42_500_000_000L
        val t1Pos = 100_000_000_000L
        val t2Pos = t1Pos + trueOffsetPos + 1_500_000L // 1.5ms uplink
        val t3Pos = t2Pos + 100_000L // 0.1ms server processing
        val t4Pos = (t3Pos - trueOffsetPos) + 1_500_000L // 1.5ms downlink

        val samplePos = ClockSyncMath.computeSample(1, t1Pos, t2Pos, t3Pos, t4Pos)
        assertNotNull(samplePos)
        assertEquals(3_000_000L, samplePos!!.rttNs)
        assertEquals(trueOffsetPos, samplePos.offsetNs)

        val serverStartPos = 200_000_000_000L
        val localStartPos = ClockSyncMath.serverToLocalTimeNs(serverStartPos, samplePos.offsetNs)
        assertEquals(serverStartPos - trueOffsetPos, localStartPos)
        assertEquals(serverStartPos, localStartPos + samplePos.offsetNs)

        // Case 2: Server monotonic clock is behind client (-120_000_000_000 ns)
        val trueOffsetNeg = -120_000_000_000L
        val t1Neg = 500_000_000_000L
        val t2Neg = t1Neg + trueOffsetNeg + 2_000_000L
        val t3Neg = t2Neg + 50_000L
        val t4Neg = (t3Neg - trueOffsetNeg) + 2_000_000L

        val sampleNeg = ClockSyncMath.computeSample(2, t1Neg, t2Neg, t3Neg, t4Neg)
        assertNotNull(sampleNeg)
        assertEquals(4_000_000L, sampleNeg!!.rttNs)
        assertEquals(trueOffsetNeg, sampleNeg.offsetNs)

        // Case 3: Huge multi-week uptime difference (no 64-bit overflow)
        val hugeOffset = 2_000_000_000_000_000L
        val t1Huge = 5_000_000_000_000_000L
        val t2Huge = t1Huge + hugeOffset + 1_000_000L
        val t3Huge = t2Huge + 50_000L
        val t4Huge = (t3Huge - hugeOffset) + 1_000_000L
        val sampleHuge = ClockSyncMath.computeSample(3, t1Huge, t2Huge, t3Huge, t4Huge)
        assertNotNull(sampleHuge)
        assertEquals(hugeOffset, sampleHuge!!.offsetNs)
    }

    @Test
    fun testMalformedAndNegativeRttRejected() {
        // t4 < t1
        assertNull(ClockSyncMath.computeSample(1, 1000L, 2000L, 2100L, 900L))
        // t3 < t2
        assertNull(ClockSyncMath.computeSample(2, 1000L, 2500L, 2400L, 2000L))
        // Negative RTT: (t4 - t1) < (t3 - t2)
        assertNull(ClockSyncMath.computeSample(3, 1000L, 2000L, 2600L, 1500L))
        // Excessive RTT (> 10s)
        assertNull(ClockSyncMath.computeSample(4, 0L, 1_000L, 2_000L, 15_000_000_000L))
    }

    @Test
    fun testOutlierFilteringDiscardsSlowest50Percent() {
        val trueOffset = 15_000_000_000L
        val samples = mutableListOf<SyncSample>()

        // 10 clean low-RTT samples (4ms..5.8ms) with sub-ms symmetric jitter
        for (i in 0 until 10) {
            val uplinkNs = 2_000_000L + (i * 100_000L)
            val downlinkNs = 2_000_000L + ((9 - i) * 100_000L)
            val t1 = 1_000_000_000L + i * 75_000_000L
            val t2 = t1 + trueOffset + uplinkNs
            val t3 = t2 + 40_000L
            val t4 = (t3 - trueOffset) + downlinkNs
            samples.add(ClockSyncMath.computeSample(i, t1, t2, t3, t4)!!)
        }

        // 10 congested asymmetric spike samples (80ms..200ms uplink spike biasing offset by +40ms+)
        for (i in 10 until 20) {
            val uplinkNs = 80_000_000L + (i - 10) * 12_000_000L
            val downlinkNs = 2_000_000L
            val t1 = 1_000_000_000L + i * 75_000_000L
            val t2 = t1 + trueOffset + uplinkNs
            val t3 = t2 + 40_000L
            val t4 = (t3 - trueOffset) + downlinkNs
            samples.add(ClockSyncMath.computeSample(i, t1, t2, t3, t4)!!)
        }

        // Interleave samples before analysis
        val shuffled = samples.shuffled(java.util.Random(42))
        val analysis = ClockSyncMath.analyzeBurst(shuffled)
        assertNotNull(analysis)
        assertEquals(20, analysis!!.validSampleCount)
        assertEquals(10, analysis.keptSampleCount)

        // Mean offset must recover trueOffset within 0.5 ms despite 10 badly skewed spikes
        val errorNs = abs(analysis.meanOffsetNs - trueOffset)
        assertTrue("Expected offset error < 500_000ns, got $errorNs", errorNs < 500_000L)
        assertEquals(4.9, analysis.minRttMs, 0.05)
        assertTrue("Median RTT should reflect all 20 samples", analysis.medianRttMs > 40.0)
        assertTrue("Filtered jitter should be well under 1ms", analysis.jitterMs < 1.0)
    }

    @Test
    fun testDriftSmoothingEmaAlpha025() {
        val current = 1_000_000_000L
        val measured = 1_010_000_000L // +10ms jump

        val step1 = ClockSyncMath.applyDriftEma(current, measured, alpha = 0.25)
        assertEquals(1_002_500_000L, step1) // +2.5ms

        val step2 = ClockSyncMath.applyDriftEma(step1, measured, alpha = 0.25)
        assertEquals(1_004_375_000L, step2) // +1.875ms

        var smoothed = current
        repeat(20) {
            smoothed = ClockSyncMath.applyDriftEma(smoothed, measured, alpha = 0.25)
        }
        assertTrue(abs(smoothed - measured) < 50_000L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testClockSyncManagerInitialBurstAndDriftLoop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var simulatedLocalNs = 5_000_000_000L
        var simulatedServerOffsetNs = 250_000_000_000L
        val clock = MonotonicClock { simulatedLocalNs + testScheduler.currentTime * 1_000_000L }

        lateinit var manager: ClockSyncManager
        var pingCounter = 0
        manager = ClockSyncManager(
            clock = clock,
            sendPing = { seq, t1 ->
                pingCounter++
                // Alternate between fast symmetric 2ms pings and occasional 60ms asymmetric spikes
                val isSpike = (pingCounter % 4 == 0)
                val uplinkNs = if (isSpike) 60_000_000L else 1_000_000L
                val downlinkNs = 1_000_000L
                val t2 = t1 + simulatedServerOffsetNs + uplinkNs
                val t3 = t2 + 50_000L
                val t4 = (t3 - simulatedServerOffsetNs) + downlinkNs
                manager.onSyncPong(seq, t1, t2, t3, t4)
                true
            },
            pingIntervalMs = 75L,
            driftIntervalMs = 20_000L,
            initialBurstSize = 20,
            driftBurstSize = 8,
            burstGraceWaitMs = 200L,
            driftAlpha = 0.25
        )

        manager.startSession(this)
        runCurrent()
        // Advance past initial 20 * 75ms burst (1425ms)
        advanceTimeBy(1_600L)
        runCurrent()

        val initialState = manager.state.value
        assertTrue("Manager should be synced after initial burst", initialState.isSynced)
        assertEquals(20, initialState.sampleCount)
        assertEquals(simulatedServerOffsetNs, initialState.offsetNs)
        assertEquals(2.0, initialState.minRttMs, 0.01)
        assertEquals(0.0, initialState.jitterMs, 0.01)

        // Introduce +4ms quartz clock drift before the 20s mini-burst
        simulatedServerOffsetNs += 4_000_000L
        advanceTimeBy(20_600L)
        runCurrent()

        val driftedState = manager.state.value
        assertEquals(8, driftedState.sampleCount)
        // EMA with alpha=0.25 moves +1.0ms on the first drift burst (250_001_000_000L)
        assertEquals(250_001_000_000L, driftedState.offsetNs)

        manager.stop()
    }
}
