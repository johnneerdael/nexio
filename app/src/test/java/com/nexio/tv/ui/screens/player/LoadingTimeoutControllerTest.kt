package com.nexio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTimeoutControllerTest {

    @Test
    fun `initial phase fires retry after 120 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            scope = this
        )
        backgroundScope.launch { controller.events.collect { events.add(it) } }

        controller.start()
        advanceTimeBy(119_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)

        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test
    fun `mid-stream phase fires retry after 60 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            scope = this
        )
        backgroundScope.launch { controller.events.collect { events.add(it) } }

        controller.start()
        advanceTimeBy(59_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)

        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test
    fun `second timeout after retry fires error`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            scope = this
        )
        backgroundScope.launch { controller.events.collect { events.add(it) } }

        controller.start()
        advanceTimeBy(60_001)
        controller.start()
        advanceTimeBy(60_001)

        assertEquals(listOf(LoadingTimeoutEvent.Retry, LoadingTimeoutEvent.Error), events)
    }

    @Test
    fun `cancel before timeout produces no events`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            scope = this
        )
        backgroundScope.launch { controller.events.collect { events.add(it) } }

        controller.start()
        advanceTimeBy(60_000)
        controller.cancel()
        advanceTimeBy(120_000)

        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
    }
}
