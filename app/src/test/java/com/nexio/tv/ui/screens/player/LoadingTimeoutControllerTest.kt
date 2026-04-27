package com.nexio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTimeoutControllerTest {

    @Test fun `initial phase fires retry after 120 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(119_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)
        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test fun `mid-stream phase fires retry after 60 seconds`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(59_999)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
        advanceTimeBy(2)
        assertEquals(listOf(LoadingTimeoutEvent.Retry), events)
    }

    @Test fun `second timeout after retry fires Error`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.MidStream,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(60_001)        // first ceiling -> Retry
        controller.start()           // caller restarts after retry
        advanceTimeBy(60_001)        // second ceiling -> Error
        assertEquals(listOf(LoadingTimeoutEvent.Retry, LoadingTimeoutEvent.Error), events)
    }

    @Test fun `cancel before timeout produces no events`() = runTest {
        val events = mutableListOf<LoadingTimeoutEvent>()
        val controller = LoadingTimeoutController(
            phase = LoadingPhase.Initial,
            onEvent = events::add,
            scope = this
        )
        controller.start()
        advanceTimeBy(60_000)
        controller.cancel()
        advanceTimeBy(120_000)
        assertEquals(emptyList<LoadingTimeoutEvent>(), events)
    }
}
