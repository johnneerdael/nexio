package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParserAheadSubtitleQueueTest {
    @Test
    fun `enqueue returns immediately and drains on worker scope`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val diagnostics = RecordingDiagnostics()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            maxQueuedCues = 10,
            playbackPositionUsProvider = { 250L },
            diagnostics = diagnostics,
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )

        queue.enqueue(
            Format.Builder().setLanguage("es").build(),
            CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        )

        assertEquals(0, drained.size)
        scope.advanceUntilIdle()

        assertEquals(1, drained.size)
        assertEquals(1_000L, drained.single().presentationTimeUs)
        assertEquals(1, diagnostics.enqueuedEvents.size)
        assertEquals(1_000L, diagnostics.enqueuedEvents.single().cueTimeUs)
        assertEquals(250L, diagnostics.enqueuedEvents.single().playbackPositionUs)
    }

    @Test
    fun `duplicate cue text at same start is dropped`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val diagnostics = RecordingDiagnostics()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            diagnostics = diagnostics,
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )
        val format = Format.Builder().setLanguage("es").build()
        val cues = CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)

        queue.enqueue(format, cues)
        queue.enqueue(format, cues)
        scope.advanceUntilIdle()

        assertEquals(1, drained.size)
        assertEquals(1, diagnostics.duplicateDrops)
    }

    @Test
    fun `blank and bitmap cues are ignored`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )

        queue.enqueue(
            Format.Builder().setLanguage("es").build(),
            CuesWithTiming(listOf(Cue.Builder().setText("   ").build()), 1_000L, 2_000L)
        )
        scope.advanceUntilIdle()

        assertTrue(drained.isEmpty())
    }

    @Test
    fun `disabled queue rejects parser cues`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            isEnabledProvider = { false },
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )

        queue.enqueue(
            Format.Builder().setLanguage("es").build(),
            CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        )
        scope.advanceUntilIdle()

        assertTrue(drained.isEmpty())
    }

    @Test
    fun `playback position provider failure does not escape parser enqueue`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val drained = mutableListOf<CueGroup>()
        val diagnostics = RecordingDiagnostics()
        val queue = ParserAheadSubtitleQueue(
            scope = scope,
            playbackPositionUsProvider = { error("wrong thread") },
            diagnostics = diagnostics,
            enqueueForTranslation = { _, cueGroup -> drained += cueGroup }
        )

        queue.enqueue(
            Format.Builder().setLanguage("es").build(),
            CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        )
        scope.advanceUntilIdle()

        assertEquals(1, drained.size)
        assertEquals(0L, diagnostics.enqueuedEvents.single().playbackPositionUs)
    }

    private class RecordingDiagnostics : ParserAheadSubtitleDiagnostics {
        val enqueuedEvents = mutableListOf<ParserAheadSubtitleDiagnostics.EnqueueEvent>()
        var duplicateDrops = 0

        override fun onEnqueued(event: ParserAheadSubtitleDiagnostics.EnqueueEvent) {
            enqueuedEvents += event
        }

        override fun onDuplicateDrop() {
            duplicateDrops += 1
        }
    }
}
