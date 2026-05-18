package com.nexio.tv.ui.screens.player.translation

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface Mp4ExtractorFactory {
    fun create(): Extractor
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
        Mp4Extractor(
            DefaultSubtitleParserFactory(),
            Mp4Extractor.FLAG_READ_TEXT_TRACKS_ONLY
        )
    },
    private val inputOpener: Mp4ExtractorInputOpener = DefaultMp4ExtractorInputOpener
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
        val extractor = extractorFactory.create()
        val initialSeekTimeUs = (request.initialPositionMs * 1000L).coerceAtLeast(0L)
        var seekMap: SeekMap? = null
        var initialSeekApplied = initialSeekTimeUs == 0L
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
