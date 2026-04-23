package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTranslatingSampleSinkTest {
    @Test
    fun translatesDialogueSampleBeforeDelegatingToAssRenderer() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { false },
            translate = { units ->
                assertEquals(listOf("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry"), units.map { it.protectedText })
                mapOf("evt_0" to "Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos")
            },
            translateRawAssSsa = { error("raw path should not be used") }
        )

        sink.onTrackHeader(
            trackId = 4,
            headerData = "[Script Info]\nScriptType: v4.00+\n".toByteArray(),
            format = Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
                .build()
        )
        sink.onSubtitleSample(
            trackId = 4,
            timeUs = 1_000_000L,
            data = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,I am {\\i1}not{\\i0} angry".toByteArray()
        )

        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Ik ben {\\i1}niet{\\i0} boos",
            downstream.samples.single().decodeToString()
        )
    }

    @Test
    fun delegatesOriginalSampleWhenTranslationIsDisabled() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { false },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { true },
            translate = { emptyMap() },
            translateRawAssSsa = { error("raw path should not be used when disabled") }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello",
            downstream.samples.single().decodeToString()
        )
    }

    @Test
    fun fallsBackToOriginalSampleWhenProviderThrows() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { false },
            translate = { throw IllegalStateException("provider down") },
            translateRawAssSsa = { error("raw path should not be used") }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello",
            downstream.samples.single().decodeToString()
        )
    }

    @Test
    fun preserveOnlyDrawingSampleIsNotSentToProvider() = runTest {
        var providerCalls = 0
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { false },
            translate = {
                providerCalls += 1
                emptyMap()
            },
            translateRawAssSsa = { error("raw path should not be used") }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0{\\p0}".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(0, providerCalls)
        assertEquals(sample.decodeToString(), downstream.samples.single().decodeToString())
    }

    @Test
    fun signLikeSamplesTranslateThroughProtectedPathWithoutLosingFormatting() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        var protectedProviderCalls = 0
        var rawProviderCalls = 0
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { false },
            translate = {
                protectedProviderCalls += 1
                assertEquals(listOf("⟦ASS_000⟧My best friend?!"), it.map { unit -> unit.protectedText })
                mapOf("evt_0" to "⟦ASS_000⟧Mijn beste vriend?!")
            },
            translateRawAssSsa = { raw ->
                rawProviderCalls += 1
                raw
            }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 43_770_000L, data = sample.toByteArray())

        assertEquals(1, protectedProviderCalls)
        assertEquals(0, rawProviderCalls)
        assertEquals(
            "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            downstream.samples.single().decodeToString()
        )
    }

    @Test
    fun systemPromptModeTranslatesSignLikeSamplesThroughRawProvider() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}Sign text"
        val translatedSample = "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}Bordtekst"
        var protectedProviderCalls = 0
        var rawProviderCalls = 0
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { true },
            translate = {
                protectedProviderCalls += 1
                emptyMap()
            },
            translateRawAssSsa = { raw ->
                rawProviderCalls += 1
                assertEquals(sample, raw)
                translatedSample
            }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 43_770_000L, data = sample.toByteArray())

        assertEquals(1, rawProviderCalls)
        assertEquals(0, protectedProviderCalls)
        assertEquals(translatedSample, downstream.samples.single().decodeToString())
    }

    @Test
    fun systemPromptModeSendsUnclassifiedAssSampleThroughRawProvider() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = "Comment: this shape is still handled by the raw system prompt"
        val translatedSample = "Comment: deze vorm gaat nog steeds door de raw system prompt"
        var rawProviderCalls = 0
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { true },
            translate = { error("placeholder path should not be used") },
            translateRawAssSsa = { raw ->
                rawProviderCalls += 1
                assertEquals(sample, raw)
                translatedSample
            }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 43_770_000L, data = sample.toByteArray())

        assertEquals(1, rawProviderCalls)
        assertEquals(translatedSample, downstream.samples.single().decodeToString())
    }

    @Test
    fun systemPromptModeBatchesRawAssSampleBeforeDelegating() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = listOf(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello",
            "Dialogue: 0,0:00:03.00,0:00:05.00,Default,,0,0,0,,I am {\\i1}not{\\i0} angry"
        ).joinToString("\n")
        val translatedSample = listOf(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo",
            "Dialogue: 0,0:00:03.00,0:00:05.00,Default,,0,0,0,,Ik ben {\\i1}niet{\\i0} boos"
        ).joinToString("\n")
        var rawProviderCalls = 0
        var placeholderProviderCalls = 0
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { true },
            translate = {
                placeholderProviderCalls += 1
                emptyMap()
            },
            translateRawAssSsa = { raw ->
                rawProviderCalls += 1
                assertEquals(sample, raw)
                translatedSample
            }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())

        assertEquals(1, rawProviderCalls)
        assertEquals(0, placeholderProviderCalls)
        assertEquals(translatedSample, downstream.samples.single().decodeToString())
    }

    @Test
    fun systemPromptModeFallsBackToOriginalSampleWhenRawProviderThrows() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { null },
            useSystemPromptTranslation = { true },
            translate = { error("placeholder path should not be used") },
            translateRawAssSsa = { throw IllegalStateException("provider down") }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())

        assertEquals(sample, downstream.samples.single().decodeToString())
    }

    @Test
    fun delegatesOriginalSampleForNonSelectedTrack() = runTest {
        var providerCalls = 0
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { true },
            selectedTrackIdProvider = { 7 },
            useSystemPromptTranslation = { false },
            translate = {
                providerCalls += 1
                emptyMap()
            },
            translateRawAssSsa = { error("raw path should not be used") }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(0, providerCalls)
        assertEquals(sample.decodeToString(), downstream.samples.single().decodeToString())
    }

    @Test
    fun delegatesOriginalSampleWhenPlaybackIsInactive() = runTest {
        var providerCalls = 0
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            isPlaybackActive = { false },
            selectedTrackIdProvider = { 4 },
            useSystemPromptTranslation = { false },
            translate = {
                providerCalls += 1
                emptyMap()
            },
            translateRawAssSsa = { error("raw path should not be used") }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(0, providerCalls)
        assertEquals(sample.decodeToString(), downstream.samples.single().decodeToString())
    }

    private class RecordingAssSsaSampleSink : AssSsaSampleSink {
        val samples = mutableListOf<ByteArray>()

        override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) = Unit

        override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
            samples += data
        }
    }
}
