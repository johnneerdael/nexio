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
    fun translatesDialogueSampleThroughSegmentSurfaces() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            translate = { surfaces ->
                assertEquals(listOf(listOf("I am", "not", "angry")), surfaces.map { it.segments })
                mapOf("evt_0" to listOf("Ik ben", "niet", "boos"))
            }
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
    fun translatesCommentSampleWhenItMatchesEventFormat() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            translate = { surfaces ->
                assertEquals(listOf("evt_0"), surfaces.map { it.id })
                mapOf("evt_0" to listOf("Bordtekst"))
            }
        )
        val sample = "Comment: 0,0:00:01.00,0:00:03.00,Default,SIGN,0,0,0,,Sign text"

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample.toByteArray())

        assertEquals(
            "Comment: 0,0:00:01.00,0:00:03.00,Default,SIGN,0,0,0,,Bordtekst",
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
            translate = { emptyMap() }
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
            translate = { throw IllegalStateException("provider down") }
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
            translate = {
                providerCalls += 1
                emptyMap()
            }
        )
        val sample = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0{\\p0}".toByteArray()

        sink.onSubtitleSample(trackId = 4, timeUs = 1_000_000L, data = sample)

        assertEquals(0, providerCalls)
        assertEquals(sample.decodeToString(), downstream.samples.single().decodeToString())
    }

    @Test
    fun signLikeSamplesTranslateThroughSegmentSurfacesWithoutLosingFormatting() = runTest {
        val downstream = RecordingAssSsaSampleSink()
        val sample = "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        var providerCalls = 0
        val sink = AssSsaTranslatingSampleSink(
            downstream = downstream,
            scope = CoroutineScope(Dispatchers.Unconfined),
            isEnabled = { true },
            translate = {
                providerCalls += 1
                assertEquals(listOf(listOf("My best friend?!")), it.map { surface -> surface.segments })
                mapOf("evt_0" to listOf("Mijn beste vriend?!"))
            }
        )

        sink.onSubtitleSample(trackId = 4, timeUs = 43_770_000L, data = sample.toByteArray())

        assertEquals(1, providerCalls)
        assertEquals(
            "Dialogue: 0,0:00:43.77,0:00:45.65,Default,SIGN,0,0,0,,{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            downstream.samples.single().decodeToString()
        )
    }

    private class RecordingAssSsaSampleSink : AssSsaSampleSink {
        val samples = mutableListOf<ByteArray>()

        override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) = Unit

        override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
            samples += data
        }

        override fun onFontAttachment(name: String, data: ByteArray) = Unit
    }
}
