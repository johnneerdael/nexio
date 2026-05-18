package com.nexio.tv.ui.screens.player.translation

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.Mp4TextTrackSampleTable
import androidx.media3.extractor.mp4.Mp4TextTrackSampleTableListener
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface Mp4ExtractorFactory {
    fun create(textTrackSampleTableListener: Mp4TextTrackSampleTableListener?): Extractor
}

internal fun interface Mp4ExtractorInputOpener {
    fun open(
        uri: Uri,
        headers: Map<String, String>,
        position: Long
    ): Mp4ExtractorInputHandle
}

internal data class Mp4ExtractorInputHandle(
    val input: ExtractorInput,
    val close: () -> Unit
)

internal class Mp4TextTrackHarvester(
    private val extractorFactory: Mp4ExtractorFactory = Mp4ExtractorFactory {
            textTrackSampleTableListener ->
        Mp4Extractor(
            DefaultSubtitleParserFactory(),
            Mp4Extractor.FLAG_READ_TEXT_TRACKS_ONLY,
            null,
            textTrackSampleTableListener
        )
    },
    private val inputOpener: Mp4ExtractorInputOpener = DefaultMp4ExtractorInputOpener,
    private val subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : EmbeddedSubtitleTrackHarvester {
    override val container: EmbeddedSubtitleContainer = EmbeddedSubtitleContainer.MP4

    override suspend fun harvest(
        request: EmbeddedSubtitleTrackHarvestRequest
    ): EmbeddedSubtitleTrackHarvestResult = withContext(Dispatchers.IO) {
        val startedMs = System.currentTimeMillis()
        val publisher = TimelinePublishingTextCueSink(
            sessionKey = request.sessionKey,
            container = container,
            timelineStore = request.timelineStore,
            sourceLanguage = request.sourceLanguage
        )
        var sampleTables: ImmutableList<Mp4TextTrackSampleTable>? = null
        val extractor = extractorFactory.create(
            Mp4TextTrackSampleTableListener { exportedTables ->
                sampleTables = exportedTables
            }
        )
        val initialSeekTimeUs = (request.initialPositionMs * 1000L).coerceAtLeast(0L)
        var seekMap: SeekMap? = null
        var initialSeekApplied = initialSeekTimeUs == 0L
        var sampleTablePathTried = false
        extractor.init(
            Mp4TextExtractorOutput(
                delegate = request.extractorOutput,
                selectedSupportedTextTrackOrdinalProvider = {
                    request.selectedSupportedTextOrdinal
                },
                shouldPublishCueGroups = { initialSeekApplied },
                onSeekMap = { map -> seekMap = map },
                onCueGroup = { cueGroup, format ->
                    publisher.publish(cueGroup, format.language)
                }
            )
        )

        val uri = Uri.parse(request.streamUrl)
        val positionHolder = PositionHolder()
        var inputPosition = 0L
        var inputHandle: Mp4ExtractorInputHandle? = null
        var reads = 0L
        var extractorSeeks = 0L
        var inputOpens = 0L
        var inputOpenMs = 0L
        var lastInputPosition = 0L
        var lastPendingSeekPosition = -1L
        var lastReadResult = Extractor.RESULT_CONTINUE
        var lastReadProgressLogMs = startedMs
        var lastReadProgressHarvested = -1

        fun openInput(position: Long): Mp4ExtractorInputHandle {
            val openStartedMs = System.currentTimeMillis()
            return inputOpener.open(uri, request.headers, position).also {
                inputOpens += 1
                inputOpenMs += System.currentTimeMillis() - openStartedMs
                lastInputPosition = position
            }
        }

        fun logReadProgress(force: Boolean = false) {
            val nowMs = System.currentTimeMillis()
            val harvested = publisher.sampleCount
            if (
                !force &&
                harvested == lastReadProgressHarvested &&
                nowMs - lastReadProgressLogMs < MP4_READ_PROGRESS_LOG_INTERVAL_MS
            ) {
                return
            }
            EmbeddedSubtitleHarvestDiagnostics.mp4HarvestReadProgress(
                session = request.sessionKey,
                requestedTimeUs = initialSeekTimeUs,
                reads = reads,
                extractorSeeks = extractorSeeks,
                inputOpens = inputOpens,
                lastInputPosition = lastInputPosition,
                pendingSeekPosition = lastPendingSeekPosition,
                harvested = harvested,
                lastCueTimeUs = publisher.lastCueTimeUs,
                elapsedMs = nowMs - startedMs,
                openMs = inputOpenMs,
                lastReadResult = lastReadResult
            )
            lastReadProgressLogMs = nowMs
            lastReadProgressHarvested = harvested
        }

        try {
            inputHandle = openInput(inputPosition)
            var readResult = Extractor.RESULT_CONTINUE
            while (readResult != Extractor.RESULT_END_OF_INPUT) {
                ensureActive()
                val currentInputHandle = checkNotNull(inputHandle)
                readResult = extractor.read(currentInputHandle.input, positionHolder)
                reads += 1
                lastReadResult = readResult
                if (readResult == Extractor.RESULT_SEEK) {
                    currentInputHandle.close()
                    inputHandle = null
                    inputPosition = positionHolder.position
                    lastPendingSeekPosition = inputPosition
                    extractorSeeks += 1
                    inputHandle = openInput(inputPosition)
                    readResult = Extractor.RESULT_CONTINUE
                } else if (!initialSeekApplied) {
                    val map = seekMap
                    if (map != null && map.isSeekable) {
                        val seekPoint = map.getSeekPoints(initialSeekTimeUs).first
                        if (seekPoint.position != Long.MAX_VALUE) {
                            currentInputHandle.close()
                            inputHandle = null
                            inputPosition = seekPoint.position
                            lastPendingSeekPosition = inputPosition
                            extractorSeeks += 1
                            extractor.seek(inputPosition, initialSeekTimeUs)
                            inputHandle = openInput(inputPosition)
                        }
                        EmbeddedSubtitleHarvestDiagnostics.initialSeekApplied(
                            session = request.sessionKey,
                            container = container,
                            requestedTimeUs = initialSeekTimeUs,
                            seekTimeUs = seekPoint.timeUs,
                            seekPosition = seekPoint.position,
                            seekable = true
                        )
                        initialSeekApplied = true
                        readResult = Extractor.RESULT_CONTINUE
                        logReadProgress(force = true)
                    } else if (map != null) {
                        EmbeddedSubtitleHarvestDiagnostics.initialSeekApplied(
                            session = request.sessionKey,
                            container = container,
                            requestedTimeUs = initialSeekTimeUs,
                            seekTimeUs = 0L,
                            seekPosition = 0L,
                            seekable = false
                        )
                        initialSeekApplied = true
                        logReadProgress(force = true)
                    }
                }
                logReadProgress()
                val exportedTables = sampleTables
                if (exportedTables != null && !sampleTablePathTried) {
                    sampleTablePathTried = true
                    val sampleTableResult = harvestFromSampleTable(
                        request = request,
                        publisher = publisher,
                        uri = uri,
                        tables = exportedTables,
                        initialSeekTimeUs = initialSeekTimeUs,
                        startedMs = startedMs
                    )
                    if (sampleTableResult != null) {
                        return@withContext sampleTableResult
                    }
                }
            }
            logReadProgress(force = true)
            EmbeddedSubtitleTrackHarvestResult(
                container = container,
                harvested = publisher.sampleCount,
                durationMs = System.currentTimeMillis() - startedMs
            )
        } finally {
            inputHandle?.close()
            extractor.release()
        }
    }

    private fun harvestFromSampleTable(
        request: EmbeddedSubtitleTrackHarvestRequest,
        publisher: TimelinePublishingTextCueSink,
        uri: Uri,
        tables: ImmutableList<Mp4TextTrackSampleTable>,
        initialSeekTimeUs: Long,
        startedMs: Long
    ): EmbeddedSubtitleTrackHarvestResult? {
        if (tables.isEmpty()) {
            return EmbeddedSubtitleTrackHarvestResult(
                container = container,
                harvested = 0,
                durationMs = System.currentTimeMillis() - startedMs
            )
        }
        val selectedTable = tableForSelectedOrdinal(tables, request.selectedSupportedTextOrdinal)
            ?: return null
        val format = selectedTable.format
        if (!subtitleParserFactory.supportsFormat(format)) {
            return null
        }

        val firstSampleIndex = firstSampleIndexAtOrAfter(selectedTable, initialSeekTimeUs)
        val ranges = coalescedSampleRanges(selectedTable, firstSampleIndex)
        EmbeddedSubtitleHarvestDiagnostics.mp4SampleTableHarvestStarted(
            session = request.sessionKey,
            selectedOrdinal = selectedTable.textTrackOrdinal,
            trackId = selectedTable.trackId,
            sampleMimeType = format.sampleMimeType,
            totalSamples = selectedTable.sampleCount,
            startSampleIndex = firstSampleIndex,
            ranges = ranges.size,
            bytesPlanned = ranges.sumOf { it.length.toLong() }
        )

        val parser = subtitleParserFactory.create(format)
        var inputOpens = 0
        var bytesRead = 0L
        var nextProgressSample = firstSampleIndex
        var lastProgressLogMs = startedMs
        val rangeCount = ranges.size
        for (rangeIndex in ranges.indices) {
            val range = ranges[rangeIndex]
            val handle = inputOpener.open(uri, request.headers, range.startOffset)
            inputOpens += 1
            try {
                val rangeBytes = ByteArray(range.length)
                handle.input.readFully(rangeBytes, 0, rangeBytes.size)
                bytesRead += rangeBytes.size.toLong()
                for (sampleIndex in range.firstSampleIndex until range.lastSampleIndexExclusive) {
                    val sampleOffset = selectedTable.offsets[sampleIndex]
                    val sampleSize = selectedTable.sizes[sampleIndex]
                    val sampleStart = (sampleOffset - range.startOffset).toInt()
                    publishParsedSample(
                        parser = parser,
                        format = format,
                        data = rangeBytes,
                        offset = sampleStart,
                        size = sampleSize,
                        sampleTimeUs = selectedTable.timestampsUs[sampleIndex],
                        publisher = publisher
                    )
                    nextProgressSample = sampleIndex + 1
                }
            } finally {
                handle.close()
            }

            val nowMs = System.currentTimeMillis()
            if (
                rangeIndex == rangeCount - 1 ||
                nextProgressSample - firstSampleIndex >= 100 ||
                nowMs - lastProgressLogMs >= MP4_SAMPLE_TABLE_PROGRESS_LOG_INTERVAL_MS
            ) {
                EmbeddedSubtitleHarvestDiagnostics.mp4SampleTableHarvestProgress(
                    session = request.sessionKey,
                    selectedOrdinal = selectedTable.textTrackOrdinal,
                    nextSampleIndex = nextProgressSample,
                    totalSamples = selectedTable.sampleCount,
                    rangesRead = rangeIndex + 1,
                    totalRanges = rangeCount,
                    inputOpens = inputOpens,
                    bytesRead = bytesRead,
                    harvested = publisher.sampleCount,
                    elapsedMs = nowMs - startedMs
                )
                lastProgressLogMs = nowMs
            }
        }

        return EmbeddedSubtitleTrackHarvestResult(
            container = container,
            harvested = publisher.sampleCount,
            durationMs = System.currentTimeMillis() - startedMs
        )
    }

    private fun publishParsedSample(
        parser: SubtitleParser,
        format: Format,
        data: ByteArray,
        offset: Int,
        size: Int,
        sampleTimeUs: Long,
        publisher: TimelinePublishingTextCueSink
    ) {
        parser.parse(
            data,
            offset,
            size,
            SubtitleParser.OutputOptions.allCues()
        ) { cuesWithTiming ->
            if (cuesWithTiming.cues.isEmpty()) return@parse
            val cueTimeUs = when {
                cuesWithTiming.startTimeUs == C.TIME_UNSET -> sampleTimeUs
                format.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE ->
                    sampleTimeUs + cuesWithTiming.startTimeUs
                else -> cuesWithTiming.startTimeUs + format.subsampleOffsetUs
            }
            publisher.publish(CueGroup(cuesWithTiming.cues, cueTimeUs), format.language)
        }
    }

    private fun tableForSelectedOrdinal(
        tables: ImmutableList<Mp4TextTrackSampleTable>,
        selectedOrdinal: Int
    ): Mp4TextTrackSampleTable? {
        for (i in tables.indices) {
            val table = tables[i]
            if (table.textTrackOrdinal == selectedOrdinal) return table
        }
        return null
    }

    private fun firstSampleIndexAtOrAfter(
        table: Mp4TextTrackSampleTable,
        timeUs: Long
    ): Int {
        for (i in table.timestampsUs.indices) {
            if (table.timestampsUs[i] >= timeUs) return i
        }
        return table.sampleCount
    }

    private fun coalescedSampleRanges(
        table: Mp4TextTrackSampleTable,
        firstSampleIndex: Int
    ): List<Mp4TextSampleRange> {
        val ranges = mutableListOf<Mp4TextSampleRange>()
        var rangeStart = -1L
        var rangeEnd = -1L
        var firstIndex = firstSampleIndex
        var previousIndex = firstSampleIndex
        for (sampleIndex in firstSampleIndex until table.sampleCount) {
            val sampleStart = table.offsets[sampleIndex]
            val sampleEnd = sampleStart + table.sizes[sampleIndex]
            if (rangeStart < 0L) {
                rangeStart = sampleStart
                rangeEnd = sampleEnd
                firstIndex = sampleIndex
                previousIndex = sampleIndex
                continue
            }
            val gap = sampleStart - rangeEnd
            val mergedLength = sampleEnd - rangeStart
            if (gap > MP4_SAMPLE_TABLE_MAX_RANGE_GAP_BYTES ||
                mergedLength > MP4_SAMPLE_TABLE_MAX_RANGE_BYTES
            ) {
                ranges += Mp4TextSampleRange(rangeStart, rangeEnd, firstIndex, previousIndex + 1)
                rangeStart = sampleStart
                rangeEnd = sampleEnd
                firstIndex = sampleIndex
            } else {
                rangeEnd = maxOf(rangeEnd, sampleEnd)
            }
            previousIndex = sampleIndex
        }
        if (rangeStart >= 0L) {
            ranges += Mp4TextSampleRange(rangeStart, rangeEnd, firstIndex, previousIndex + 1)
        }
        return ranges
    }
}

private data class Mp4TextSampleRange(
    val startOffset: Long,
    val endOffsetExclusive: Long,
    val firstSampleIndex: Int,
    val lastSampleIndexExclusive: Int
) {
    val length: Int
        get() = (endOffsetExclusive - startOffset).toInt()
}

private object DefaultMp4ExtractorInputOpener : Mp4ExtractorInputOpener {
    override fun open(
        uri: Uri,
        headers: Map<String, String>,
        position: Long
    ): Mp4ExtractorInputHandle {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .createDataSource()
        try {
            val remainingLength = dataSource.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
            val length = mp4ExtractorInputLength(
                position = position,
                remainingLength = remainingLength
            )
            return Mp4ExtractorInputHandle(
                input = DefaultExtractorInput(dataSource, position, length),
                close = { dataSource.close() }
            )
        } catch (error: Throwable) {
            dataSource.close()
            throw error
        }
    }
}

internal fun mp4ExtractorInputLength(position: Long, remainingLength: Long): Long {
    if (remainingLength == C.LENGTH_UNSET.toLong()) return C.LENGTH_UNSET.toLong()
    return position + remainingLength
}

private const val MP4_READ_PROGRESS_LOG_INTERVAL_MS = 5_000L
private const val MP4_SAMPLE_TABLE_PROGRESS_LOG_INTERVAL_MS = 5_000L
private const val MP4_SAMPLE_TABLE_MAX_RANGE_GAP_BYTES = 8 * 1024 * 1024L
private const val MP4_SAMPLE_TABLE_MAX_RANGE_BYTES = 64 * 1024 * 1024L
