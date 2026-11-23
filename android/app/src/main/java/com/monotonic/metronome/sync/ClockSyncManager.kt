package com.monotonic.metronome.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

data class ClockSyncState(
    val isSynced: Boolean = false,
    val isSyncing: Boolean = false,
    val offsetNs: Long = 0L,
    val offsetMs: Double = 0.0,
    val minRttMs: Double = 0.0,
    val medianRttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val sampleCount: Int = 0,
    val lastSyncMonotonicNs: Long = 0L
)

/**
 * Coordinates SNTP-style ping bursts over WebSocket to keep local monotonic time
 * aligned with the authoritative server clock.
 */
class ClockSyncManager(
    private val clock: MonotonicClock = SystemNanoTimeClock,
    private val sendPing: (seq: Int, t1: Long) -> Boolean,
    private val pingIntervalMs: Long = 75L,
    private val driftIntervalMs: Long = 20_000L,
    private val initialBurstSize: Int = 20,
    private val driftBurstSize: Int = 8,
    private val burstGraceWaitMs: Long = 500L,
    private val driftAlpha: Double = 0.25
) {
    private class ActiveBurst(val id: Int) {
        val pendingSeqs = HashMap<Int, Long>()
        val samples = ArrayList<SyncSample>()
        var allDispatched = false
        val allReceived = CompletableDeferred<Unit>()
    }

    private val _state = MutableStateFlow(ClockSyncState())
    val state: StateFlow<ClockSyncState> = _state.asStateFlow()

    private val seqGenerator = AtomicInteger(0)
    private val burstIdGenerator = AtomicInteger(0)
    private val burstLock = Any()
    private var activeBurst: ActiveBurst? = null
    private var sessionJob: Job? = null

    fun startSession(scope: CoroutineScope) {
        stop(resetOffset = true)
        sessionJob = scope.launch {
            runBurstAndUpdate(burstSize = initialBurstSize, useEma = false)
            while (isActive) {
                val waitMs = if (_state.value.isSynced) driftIntervalMs else 2_000L
                delay(waitMs)
                if (!isActive) break
                val alreadySynced = _state.value.isSynced
                runBurstAndUpdate(
                    burstSize = if (alreadySynced) driftBurstSize else initialBurstSize,
                    useEma = alreadySynced
                )
            }
        }
    }

    fun resyncNow(scope: CoroutineScope) {
        sessionJob?.cancel()
        synchronized(burstLock) {
            activeBurst = null
        }
        sessionJob = scope.launch {
            runBurstAndUpdate(burstSize = initialBurstSize, useEma = false)
            while (isActive) {
                val waitMs = if (_state.value.isSynced) driftIntervalMs else 2_000L
                delay(waitMs)
                if (!isActive) break
                val alreadySynced = _state.value.isSynced
                runBurstAndUpdate(
                    burstSize = if (alreadySynced) driftBurstSize else initialBurstSize,
                    useEma = alreadySynced
                )
            }
        }
    }

    fun stop(resetOffset: Boolean = true) {
        sessionJob?.cancel()
        sessionJob = null
        synchronized(burstLock) {
            activeBurst?.allReceived?.cancel()
            activeBurst = null
        }
        if (resetOffset) {
            _state.value = ClockSyncState()
        } else {
            _state.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * Called immediately from the WebSocket reader thread when `sync_pong` arrives.
     */
    fun onSyncPong(seq: Int, t1: Long, t2: Long, t3: Long, t4: Long) {
        synchronized(burstLock) {
            val burst = activeBurst ?: return
            val recordedT1 = burst.pendingSeqs.remove(seq) ?: return
            val sample = ClockSyncMath.computeSample(
                seq = seq,
                t1 = recordedT1,
                t2 = t2,
                t3 = t3,
                t4 = t4
            )
            if (sample != null) {
                burst.samples.add(sample)
            }
            if (burst.allDispatched && burst.pendingSeqs.isEmpty()) {
                burst.allReceived.complete(Unit)
            }
        }
    }

    private suspend fun runBurstAndUpdate(burstSize: Int, useEma: Boolean) {
        _state.update { it.copy(isSyncing = true) }
        val burst = ActiveBurst(id = burstIdGenerator.incrementAndGet())
        synchronized(burstLock) {
            activeBurst = burst
        }

        try {
            var dispatchedAny = false
            for (i in 0 until burstSize) {
                val seq = seqGenerator.incrementAndGet()
                val t1 = clock.nanoTime()
                synchronized(burstLock) {
                    if (activeBurst !== burst) return
                    burst.pendingSeqs[seq] = t1
                }
                val sent = sendPing(seq, t1)
                if (sent) {
                    dispatchedAny = true
                } else {
                    synchronized(burstLock) {
                        burst.pendingSeqs.remove(seq)
                    }
                }
                if (i < burstSize - 1) {
                    delay(pingIntervalMs)
                }
            }

            synchronized(burstLock) {
                if (activeBurst !== burst) return
                burst.allDispatched = true
                if (burst.pendingSeqs.isEmpty()) {
                    burst.allReceived.complete(Unit)
                }
            }

            if (dispatchedAny) {
                withTimeoutOrNull(burstGraceWaitMs) {
                    burst.allReceived.await()
                }
            }

            val samplesSnapshot: List<SyncSample> = synchronized(burstLock) {
                if (activeBurst === burst) {
                    activeBurst = null
                }
                burst.samples.toList()
            }

            val analysis = ClockSyncMath.analyzeBurst(samplesSnapshot)
            if (analysis != null) {
                _state.update { prev ->
                    val nextOffsetNs = if (useEma && prev.isSynced) {
                        ClockSyncMath.applyDriftEma(
                            currentOffsetNs = prev.offsetNs,
                            measuredOffsetNs = analysis.meanOffsetNs,
                            alpha = driftAlpha
                        )
                    } else {
                        analysis.meanOffsetNs
                    }
                    prev.copy(
                        isSynced = true,
                        isSyncing = false,
                        offsetNs = nextOffsetNs,
                        offsetMs = nextOffsetNs / 1_000_000.0,
                        minRttMs = analysis.minRttMs,
                        medianRttMs = analysis.medianRttMs,
                        jitterMs = analysis.jitterMs,
                        sampleCount = analysis.validSampleCount,
                        lastSyncMonotonicNs = clock.nanoTime()
                    )
                }
            } else {
                _state.update { it.copy(isSyncing = false) }
            }
        } finally {
            synchronized(burstLock) {
                if (activeBurst === burst) {
                    activeBurst = null
                }
            }
        }
    }
}
