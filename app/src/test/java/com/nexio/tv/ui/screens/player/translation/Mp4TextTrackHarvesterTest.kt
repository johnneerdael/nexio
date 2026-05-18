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
import androidx.media3.extractor.mp4.Mp4TextTrackSampleTable
import androidx.media3.extractor.mp4.Mp4TextTrackSampleTableListener
import androidx.media3.extractor.text.CueEncoder
import com.google.common.collect.ImmutableList
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
            extractorFactory = { _ -> extractor },
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
            extractorFactory = { _ -> extractor },
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

    @Test
    fun harvestUsesSampleTablePathForAllAheadSamples() = runTest {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val sampleOne = tx3gSample("Opening")
        val sampleTwo = tx3gSample("Current")
        val sampleBytes = ByteArray(128)
        sampleOne.copyInto(sampleBytes, destinationOffset = 0)
        sampleTwo.copyInto(sampleBytes, destinationOffset = 32)
        val table = Mp4TextTrackSampleTable(
            0,
            7,
            Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_TX3G)
                .setLanguage("en")
                .build(),
            2,
            longArrayOf(100L, 132L),
            intArrayOf(sampleOne.size, sampleTwo.size),
            longArrayOf(10_000L, 20_000L),
            intArrayOf(C.BUFFER_FLAG_KEY_FRAME, C.BUFFER_FLAG_KEY_FRAME),
            30_000L
        )
        val openedPositions = mutableListOf<Long>()
        val harvester = Mp4TextTrackHarvester(
            extractorFactory = { listener -> SampleTableFakeExtractor(listener, table) },
            inputOpener = { _, _, position ->
                openedPositions += position
                Mp4ExtractorInputHandle(
                    input = ByteArrayExtractorInput(sampleBytes, position - 100L),
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
                initialPositionMs = 15,
                sourceLanguage = "en",
                sessionKey = session,
                timelineStore = store,
                extractorOutput = NoOpExtractorOutput
            )
        )

        assertEquals(1, result.harvested)
        assertEquals(listOf(0L, 132L), openedPositions)
        assertEquals(20_000L, store.pendingBackfill(session).single().cueGroup.presentationTimeUs)
        assertEquals(1, store.stats(session).sourceCueCount)
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

    private class SampleTableFakeExtractor(
        private val listener: Mp4TextTrackSampleTableListener?,
        private val table: Mp4TextTrackSampleTable
    ) : Extractor {
        private var readCount = 0

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) = Unit

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (readCount++ > 0) return Extractor.RESULT_END_OF_INPUT
            listener?.onTextTrackSampleTables(ImmutableList.of(table))
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private object FixedSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            return SeekMap.SeekPoints(SeekPoint(timeUs, 123_456L))
        }
    }

    private class ByteArrayExtractorInput(
        private val bytes: ByteArray,
        startPosition: Long
    ) : ExtractorInput {
        private var position = startPosition.toInt()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val readLength = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + readLength)
            position += readLength
            return readLength
        }

        override fun readFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean
        ): Boolean {
            if (position + length > bytes.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            bytes.copyInto(target, offset, position, position + length)
            position += length
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val skipped = minOf(length, bytes.size - position)
            position += skipped
            return if (skipped == 0) C.RESULT_END_OF_INPUT else skipped
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (position + length > bytes.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            position += length
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int = read(target, offset, length)
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            readFully(target, offset, length, allowEndOfInput)
        override fun peekFully(target: ByteArray, offset: Int, length: Int) =
            readFully(target, offset, length)
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
            skipFully(length, allowEndOfInput)
        override fun advancePeekPosition(length: Int) = skipFully(length)
        override fun resetPeekPosition() = Unit
        override fun getPeekPosition(): Long = position.toLong()
        override fun getPosition(): Long = position.toLong()
        override fun getLength(): Long = bytes.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Nothing {
            throw e
        }
    }

    private fun tx3gSample(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return ByteArray(textBytes.size + 2).also { sample ->
            sample[0] = ((textBytes.size ushr 8) and 0xFF).toByte()
            sample[1] = (textBytes.size and 0xFF).toByte()
            textBytes.copyInto(sample, destinationOffset = 2)
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
