package com.monotonic.metronome.sync

import java.math.BigInteger
import kotlin.math.roundToLong
import kotlin.math.sqrt

data class SyncSample(
    val seq: Int,
    val t1: Long,
    val t2: Long,
    val t3: Long,
    val t4: Long,
    val rttNs: Long,
    val offsetNs: Long
)

data class BurstAnalysis(
    val meanOffsetNs: Long,
    val minRttMs: Double,
    val medianRttMs: Double,
    val jitterMs: Double,
    val validSampleCount: Int,
    val keptSampleCount: Int
)

/**
 * Pure SNTP clock offset math, outlier filtering, and drift smoothing.
 *
 * Sign convention:
 * - `rtt_ns = (t4 - t1) - (t3 - t2)`
 * - `offset_ns = ((t2 - t1) + (t3 - t4)) / 2`
 * - `server_time_ns ≈ local_time_ns + offset_ns`
 * - `local_start_time_ns = start_server_time_ns - offset_ns`
 */
object ClockSyncMath {

    private const val DEFAULT_MAX_VALID_RTT_NS = 10_000_000_000L // 10 seconds
    private const val NS_PER_MS = 1_000_000.0

    fun computeSample(
        seq: Int,
        t1: Long,
        t2: Long,
        t3: Long,
        t4: Long,
        maxValidRttNs: Long = DEFAULT_MAX_VALID_RTT_NS
    ): SyncSample? {
        if (t4 < t1 || t3 < t2) return null

        val clientSpan = t4 - t1
        val serverSpan = t3 - t2
        val rttNs = clientSpan - serverSpan
        if (rttNs < 0L || rttNs > maxValidRttNs) return null

        // Use BigInteger so huge monotonic uptime differences never overflow 64-bit signed Long
        val d1 = BigInteger.valueOf(t2).subtract(BigInteger.valueOf(t1))
        val d2 = BigInteger.valueOf(t3).subtract(BigInteger.valueOf(t4))
        val offsetNs = d1.add(d2).divide(BigInteger.TWO).longValueExact()

        return SyncSample(
            seq = seq,
            t1 = t1,
            t2 = t2,
            t3 = t3,
            t4 = t4,
            rttNs = rttNs,
            offsetNs = offsetNs
        )
    }

    /**
     * Sorts valid samples by lowest RTT, discards the slowest 50% (congestion spikes),
     * and computes the mean offset and jitter (std dev) of the fastest 50%.
     */
    fun analyzeBurst(samples: List<SyncSample>): BurstAnalysis? {
        val valid = samples.filter { it.rttNs >= 0L }
        if (valid.isEmpty()) return null

        val sorted = valid.sortedBy { it.rttNs }
        val keepCount = maxOf(1, sorted.size / 2)
        val kept = sorted.subList(0, keepCount)

        val baseOffset = kept[0].offsetNs
        val meanDelta = kept.sumOf { (it.offsetNs - baseOffset).toDouble() } / kept.size.toDouble()
        val meanOffsetNs = baseOffset + meanDelta.roundToLong()

        val minRttMs = sorted.first().rttNs / NS_PER_MS
        val medianRttNs = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].rttNs.toDouble()
        } else {
            val mid = sorted.size / 2
            (sorted[mid - 1].rttNs.toDouble() + sorted[mid].rttNs.toDouble()) / 2.0
        }
        val medianRttMs = medianRttNs / NS_PER_MS

        val varianceNsSq = kept.sumOf { sample ->
            val diff = (sample.offsetNs - meanOffsetNs).toDouble()
            diff * diff
        } / kept.size.toDouble()
        val jitterMs = sqrt(varianceNsSq) / NS_PER_MS

        return BurstAnalysis(
            meanOffsetNs = meanOffsetNs,
            minRttMs = minRttMs,
            medianRttMs = medianRttMs,
            jitterMs = jitterMs,
            validSampleCount = valid.size,
            keptSampleCount = kept.size
        )
    }

    /**
     * Exponential moving average update for silent quartz drift correction.
     */
    fun applyDriftEma(
        currentOffsetNs: Long,
        measuredOffsetNs: Long,
        alpha: Double = 0.25
    ): Long {
        val delta = measuredOffsetNs - currentOffsetNs
        return currentOffsetNs + (alpha * delta.toDouble()).roundToLong()
    }

    fun serverToLocalTimeNs(serverTimeNs: Long, offsetNs: Long): Long {
        return serverTimeNs - offsetNs
    }
}
