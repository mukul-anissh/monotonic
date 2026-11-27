package com.monotonic.metronome.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded exponential backoff schedule for automatic room WebSocket recovery.
 * Attempts 1..5 use delays: 1000 ms, 2000 ms, 4000 ms, 4000 ms, 4000 ms.
 */
data class WebSocketReconnectPolicy(
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 4_000L,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    fun delayForAttempt(attemptNumber: Int): Long? {
        if (attemptNumber < 1 || attemptNumber > maxAttempts) return null
        var delayMs = initialDelayMs
        repeat(attemptNumber - 1) {
            delayMs = (delayMs * 2L).coerceAtMost(maxDelayMs)
        }
        return delayMs.coerceAtMost(maxDelayMs)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}

data class ReconnectTarget(
    val serverUrl: String,
    val roomCode: String
)

data class ReconnectUiState(
    val isReconnecting: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = WebSocketReconnectPolicy.DEFAULT_MAX_ATTEMPTS
)

/**
 * Coordinates automatic room rejoin attempts using [WebSocketReconnectPolicy].
 */
class RoomReconnectController(
    private val scope: CoroutineScope,
    private val policy: WebSocketReconnectPolicy = WebSocketReconnectPolicy(),
    private val attemptTimeoutMs: Long = 10_000L,
    private val onStateChanged: (ReconnectUiState) -> Unit,
    private val onAttemptConnect: (ReconnectTarget) -> Unit,
    private val onReconnectExhausted: (String?) -> Unit
) {
    private sealed interface AttemptResult {
        data object Succeeded : AttemptResult
        data class Failed(val reason: String?) : AttemptResult
        data object Aborted : AttemptResult
    }

    private val lock = Any()
    private var reconnectJob: Job? = null
    private var currentAttemptDeferred: CompletableDeferred<AttemptResult>? = null

    @Volatile
    private var activeReconnecting: Boolean = false

    val isReconnecting: Boolean
        get() = activeReconnecting

    fun startIfIdle(target: ReconnectTarget, initialError: String? = null): Boolean {
        synchronized(lock) {
            if (activeReconnecting) return false
            activeReconnecting = true
            reconnectJob = scope.launch {
                runReconnectLoop(target, initialError)
            }
            return true
        }
    }

    fun onAttemptSucceeded() {
        var shouldEmitReset = false
        synchronized(lock) {
            val deferred = currentAttemptDeferred
            if (deferred != null) {
                deferred.complete(AttemptResult.Succeeded)
            } else if (activeReconnecting) {
                activeReconnecting = false
                reconnectJob?.cancel()
                reconnectJob = null
                shouldEmitReset = true
            }
        }
        if (shouldEmitReset) {
            onStateChanged(ReconnectUiState(isReconnecting = false, attempt = 0, maxAttempts = policy.maxAttempts))
        }
    }

    fun onAttemptFailed(reason: String?) {
        synchronized(lock) {
            currentAttemptDeferred?.complete(AttemptResult.Failed(reason))
        }
    }

    fun cancel() {
        var wasActive = false
        synchronized(lock) {
            wasActive = activeReconnecting
            activeReconnecting = false
            val deferred = currentAttemptDeferred
            currentAttemptDeferred = null
            deferred?.complete(AttemptResult.Aborted)
            reconnectJob?.cancel()
            reconnectJob = null
        }
        if (wasActive) {
            onStateChanged(ReconnectUiState(isReconnecting = false, attempt = 0, maxAttempts = policy.maxAttempts))
        }
    }

    private suspend fun runReconnectLoop(target: ReconnectTarget, initialError: String?) {
        var lastError = initialError
        var recoveredOrAborted = false

        try {
            for (attempt in 1..policy.maxAttempts) {
                val delayMs = policy.delayForAttempt(attempt) ?: break
                if (!activeReconnecting) {
                    recoveredOrAborted = true
                    return
                }
                onStateChanged(
                    ReconnectUiState(
                        isReconnecting = true,
                        attempt = attempt,
                        maxAttempts = policy.maxAttempts
                    )
                )
                delay(delayMs)

                val deferred = CompletableDeferred<AttemptResult>()
                val abortedBeforeConnect = synchronized(lock) {
                    if (!activeReconnecting) {
                        true
                    } else {
                        currentAttemptDeferred = deferred
                        false
                    }
                }
                if (abortedBeforeConnect) {
                    recoveredOrAborted = true
                    return
                }

                onAttemptConnect(target)

                val result = withTimeoutOrNull(attemptTimeoutMs) {
                    deferred.await()
                } ?: AttemptResult.Failed("Reconnect timed out")

                synchronized(lock) {
                    if (currentAttemptDeferred === deferred) {
                        currentAttemptDeferred = null
                    }
                }

                when (result) {
                    AttemptResult.Succeeded,
                    AttemptResult.Aborted -> {
                        recoveredOrAborted = true
                        return
                    }

                    is AttemptResult.Failed -> {
                        lastError = result.reason ?: lastError
                    }
                }
            }
        } finally {
            val wasStillActive = synchronized(lock) {
                val prev = activeReconnecting
                activeReconnecting = false
                currentAttemptDeferred = null
                reconnectJob = null
                prev
            }
            if (wasStillActive || recoveredOrAborted) {
                onStateChanged(
                    ReconnectUiState(
                        isReconnecting = false,
                        attempt = 0,
                        maxAttempts = policy.maxAttempts
                    )
                )
            }
        }

        if (!recoveredOrAborted) {
            onReconnectExhausted(lastError)
        }
    }
}
