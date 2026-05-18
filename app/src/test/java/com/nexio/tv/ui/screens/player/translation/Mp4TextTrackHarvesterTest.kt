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
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
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

    @Test
    fun harvestSeeksToInitialPlaybackPositionBeforePublishingSamples() = runTest {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val extractor = SeekableFakeMp4TextExtractor()
        val openedPositions = mutableListOf<Long>()
        val harvester = Mp4TextTrackHarvester(
            extractorFactory = { extractor },
            inputOpener = { _, _, position ->
                openedPositions += position
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
                headers = emptyMap(),
                selectedSupportedTextOrdinal = 0,
                initialPositionMs = 815_000L,
                sourceLanguage = "en",
                sessionKey = session,
                timelineStore = store,
                extractorOutput = NoOpExtractorOutput
            )
        )

        assertEquals(listOf(0L, 123_456L), openedPositions)
        assertEquals(815_000_000L, extractor.seekTimeUs)
        assertEquals(123_456L, extractor.seekPosition)
        assertEquals(1, result.harvested)
        assertEquals(1, store.stats(session).sourceCueCount)
        assertEquals(815_000_000L, store.pendingBackfill(session).single().cueGroup.presentationTimeUs)
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

    private class SeekableFakeMp4TextExtractor : Extractor {
        private lateinit var output: ExtractorOutput
        private var readCount = 0
        var seekPosition: Long? = null
            private set
        var seekTimeUs: Long? = null
            private set

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            when (readCount++) {
                0 -> {
                    output.seekMap(FixedSeekMap)
                    publishSample(timeUs = 10_000L, text = "Opening")
                    return Extractor.RESULT_CONTINUE
                }
                1 -> {
                    publishSample(timeUs = 815_000_000L, text = "Current")
                    return Extractor.RESULT_CONTINUE
                }
                else -> return Extractor.RESULT_END_OF_INPUT
            }
        }

        override fun seek(position: Long, timeUs: Long) {
            this.seekPosition = position
            this.seekTimeUs = timeUs
        }

        override fun release() = Unit

        private fun publishSample(timeUs: Long, text: String) {
            val track = output.track(1, C.TRACK_TYPE_TEXT)
            val sample = CueEncoder().encode(
                listOf(Cue.Builder().setText(text).build()),
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
                timeUs,
                C.BUFFER_FLAG_KEY_FRAME,
                sample.size,
                0,
                null
            )
        }
    }

    private object FixedSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            return SeekMap.SeekPoints(SeekPoint(timeUs, 123_456L))
        }
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
