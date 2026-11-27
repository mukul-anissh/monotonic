package com.monotonic.metronome.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketReconnectTest {

    @Test
    fun testDelayForAttemptBackoffAndBounds() {
        val policy = WebSocketReconnectPolicy()
        assertNull(policy.delayForAttempt(0))
        assertEquals(1_000L, policy.delayForAttempt(1))
        assertEquals(2_000L, policy.delayForAttempt(2))
        assertEquals(4_000L, policy.delayForAttempt(3))
        assertEquals(4_000L, policy.delayForAttempt(4))
        assertEquals(4_000L, policy.delayForAttempt(5))
        assertNull(policy.delayForAttempt(6))
    }

    @Test
    fun testReconnectExhaustsAfterFiveAttemptsWithExactSchedule() = runTest {
        val connectTimestamps = mutableListOf<Long>()
        val states = mutableListOf<ReconnectUiState>()
        var exhaustedReason: String? = null
        var exhaustedCalled = false

        lateinit var controller: RoomReconnectController
        controller = RoomReconnectController(
            scope = this,
            attemptTimeoutMs = 10_000L,
            onStateChanged = { states.add(it) },
            onAttemptConnect = {
                connectTimestamps.add(testScheduler.currentTime)
                controller.onAttemptFailed("attempt-${connectTimestamps.size}-failed")
            },
            onReconnectExhausted = { reason ->
                exhaustedCalled = true
                exhaustedReason = reason
            }
        )

        assertTrue(controller.startIfIdle(ReconnectTarget("ws://192.168.1.10:8000/ws", "ABC123"), "init"))
        assertFalse(controller.startIfIdle(ReconnectTarget("ws://192.168.1.10:8000/ws", "ABC123"), "dup"))
        assertTrue(controller.isReconnecting)

        // Total backoff = 1s + 2s + 4s + 4s + 4s = 15s
        advanceTimeBy(16_000L)
        runCurrent()

        assertEquals(listOf(1_000L, 3_000L, 7_000L, 11_000L, 15_000L), connectTimestamps)
        assertTrue(exhaustedCalled)
        assertEquals("attempt-5-failed", exhaustedReason)
        assertFalse(controller.isReconnecting)
        assertEquals(listOf(1, 2, 3, 4, 5), states.filter { it.isReconnecting }.map { it.attempt })
        assertEquals(ReconnectUiState(isReconnecting = false, attempt = 0, maxAttempts = 5), states.last())
    }

    @Test
    fun testReconnectSucceedsOnThirdAttemptAndStopsRetrying() = runTest {
        val connectAttempts = mutableListOf<Int>()
        val states = mutableListOf<ReconnectUiState>()
        var exhaustedCalled = false

        lateinit var controller: RoomReconnectController
        controller = RoomReconnectController(
            scope = this,
            attemptTimeoutMs = 10_000L,
            onStateChanged = { states.add(it) },
            onAttemptConnect = {
                val attempt = connectAttempts.size + 1
                connectAttempts.add(attempt)
                if (attempt < 3) {
                    controller.onAttemptFailed("down")
                } else {
                    controller.onAttemptSucceeded()
                }
            },
            onReconnectExhausted = {
                exhaustedCalled = true
            }
        )

        assertTrue(controller.startIfIdle(ReconnectTarget("ws://192.168.1.10:8000/ws", "XYZ789")))
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(listOf(1, 2, 3), connectAttempts)
        assertFalse(exhaustedCalled)
        assertFalse(controller.isReconnecting)
        assertEquals(ReconnectUiState(isReconnecting = false, attempt = 0, maxAttempts = 5), states.last())
    }

    @Test
    fun testCancelAbortsPendingBackoffWithoutExhaustion() = runTest {
        var connectCount = 0
        var exhaustedCalled = false
        val states = mutableListOf<ReconnectUiState>()

        val controller = RoomReconnectController(
            scope = this,
            onStateChanged = { states.add(it) },
            onAttemptConnect = { connectCount++ },
            onReconnectExhausted = { exhaustedCalled = true }
        )

        assertTrue(controller.startIfIdle(ReconnectTarget("ws://192.168.1.10:8000/ws", "ROOM99")))
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(0, connectCount)

        controller.cancel()
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(0, connectCount)
        assertFalse(exhaustedCalled)
        assertFalse(controller.isReconnecting)
        assertEquals(ReconnectUiState(isReconnecting = false, attempt = 0, maxAttempts = 5), states.last())
    }

    @Test
    fun testAttemptTimeoutAdvancesToNextAttempt() = runTest {
        var attempts = 0
        var exhaustedCalled = false

        lateinit var controller: RoomReconnectController
        controller = RoomReconnectController(
            scope = this,
            attemptTimeoutMs = 5_000L,
            onStateChanged = {},
            onAttemptConnect = {
                attempts++
                if (attempts == 2) {
                    controller.onAttemptSucceeded()
                }
            },
            onReconnectExhausted = { exhaustedCalled = true }
        )

        assertTrue(controller.startIfIdle(ReconnectTarget("ws://192.168.1.10:8000/ws", "TIME01")))
        // 1s delay -> attempt 1 -> 5s timeout -> 2s delay -> attempt 2 (succeeds) = 8s
        advanceTimeBy(9_000L)
        runCurrent()

        assertEquals(2, attempts)
        assertFalse(exhaustedCalled)
        assertFalse(controller.isReconnecting)
    }
}
