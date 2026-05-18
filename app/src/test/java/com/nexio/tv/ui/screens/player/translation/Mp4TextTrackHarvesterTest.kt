package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import java.io.EOFException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Mp4TextTrackHarvesterTest {
    @Test
    fun harvestPublishesSelectedMedia3CueSamples() = runTest {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val extractor = FakeMp4TextExtractor()
        val harvester = Mp4TextTrackHarvester(
            extractorFactory = { extractor },
            inputOpener = { _, _, _ ->
                Mp4ExtractorInputHandle(
                    input = EmptyExtractorInput,
                    close = {}
                )
            }
        )

        store.beginSession(session)
        val result = harvester.harvest(
            EmbeddedSubtitleTrackHarvestRequest(
                streamUrl = "https://example.test/movie.mp4",
                headers = mapOf("Authorization" to "Bearer token"),
                selectedSupportedTextOrdinal = 0,
                sourceLanguage = "en",
                sessionKey = session,
                timelineStore = store,
                extractorOutput = NoOpExtractorOutput
            )
        )

        assertEquals(EmbeddedSubtitleContainer.MP4, result.container)
        assertEquals(1, result.harvested)
        assertEquals(1, store.stats(session).sourceCueCount)
        assertEquals(1, store.stats(session).pendingBackfillCount)
    }

    private fun session(): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = "stream",
            trackKey = "track",
            targetLanguage = "nl",
            settingsKey = "settings"
        )
    }

    private class FakeMp4TextExtractor : Extractor {
        private lateinit var output: ExtractorOutput
        private var readCount = 0

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (readCount > 0) return Extractor.RESULT_END_OF_INPUT
            readCount += 1

            val track = output.track(1, C.TRACK_TYPE_TEXT)
            val sample = CueEncoder().encode(
                listOf(Cue.Builder().setText("Hello").build()),
                2_000L
            )
            track.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setLanguage("en")
                    .build()
            )
            track.sampleData(
                ParsableByteArray(sample),
                sample.size,
                TrackOutput.SAMPLE_DATA_PART_MAIN
            )
            track.sampleMetadata(
                10_000L,
                C.BUFFER_FLAG_KEY_FRAME,
                sample.size,
                0,
                null
            )
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private object EmptyExtractorInput : ExtractorInput {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun readFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean
        ): Boolean {
            if (allowEndOfInput) return false
            throw EOFException()
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            throw EOFException()
        }

        override fun skip(length: Int): Int = C.RESULT_END_OF_INPUT

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (allowEndOfInput) return false
            throw EOFException()
        }

        override fun skipFully(length: Int) {
            throw EOFException()
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun peekFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean
        ): Boolean {
            if (allowEndOfInput) return false
            throw EOFException()
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            throw EOFException()
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (allowEndOfInput) return false
            throw EOFException()
        }

        override fun advancePeekPosition(length: Int) {
            throw EOFException()
        }

        override fun resetPeekPosition() = Unit
        override fun getPeekPosition(): Long = 0L
        override fun getPosition(): Long = 0L
        override fun getLength(): Long = C.LENGTH_UNSET.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Nothing {
            throw e
        }
    }
}
