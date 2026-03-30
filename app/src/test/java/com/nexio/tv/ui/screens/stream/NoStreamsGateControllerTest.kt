package com.nexio.tv.ui.screens.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoStreamsGateControllerTest {

    @Test
    fun `gate only fires after configured delay`() = runTest {
        var fireCount = 0
        val controller = NoStreamsGateController(
            scope = this,
            delayMs = 10_000L,
            onGateElapsed = { fireCount += 1 }
        )

        controller.restart()
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(0, fireCount)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, fireCount)
    }

    @Test
    fun `gate cancel prevents empty state from surfacing`() = runTest {
        var fireCount = 0
        val controller = NoStreamsGateController(
            scope = this,
            delayMs = 10_000L,
            onGateElapsed = { fireCount += 1 }
        )

        controller.restart()
        advanceTimeBy(5_000L)
        runCurrent()
        controller.cancel()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(0, fireCount)
    }

    @Test
    fun `gate restart resets timer for retries and fresh searches`() = runTest {
        var fireCount = 0
        val controller = NoStreamsGateController(
            scope = this,
            delayMs = 10_000L,
            onGateElapsed = { fireCount += 1 }
        )

        controller.restart()
        advanceTimeBy(9_000L)
        runCurrent()
        controller.restart()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, fireCount)

        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals(1, fireCount)
    }
}
