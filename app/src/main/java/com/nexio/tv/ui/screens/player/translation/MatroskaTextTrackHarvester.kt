package com.nexio.tv.ui.screens.player.translation

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class MatroskaTextTrackHarvestRequest(
    val streamUrl: String,
    val headers: Map<String, String>,
    val selectedSupportedSubRipOrdinal: Int,
    val sourceLanguage: String?,
    val sessionKey: TranslationTimelineSessionKey,
    val timelineStore: TranslatedSubtitleTimelineStore,
    val extractorOutput: ExtractorOutput
)

internal class TimelinePublishingMatroskaTextTrackSink(
    private val cueSink: TimelinePublishingTextCueSink
) : MatroskaTextTrackSink {
    val sampleCount: Int
        get() = cueSink.sampleCount

    override fun onSupportedTextTrack(
        trackId: Int,
        supportedTrackOrdinal: Int,
        format: Format
    ) = Unit

    override fun onSubtitleSample(sample: HarvestedMatroskaTextSample) {
        val cueGroup = CueGroup(
            listOf(Cue.Builder().setText(sample.text.trim()).build()),
            sample.timeUs
        )
        cueSink.publish(cueGroup, sample.format.language)
    }
}

internal class MatroskaTextTrackHarvester : EmbeddedSubtitleTrackHarvester {
    override val container: EmbeddedSubtitleContainer = EmbeddedSubtitleContainer.MATROSKA

    override suspend fun harvest(
        request: EmbeddedSubtitleTrackHarvestRequest
    ): EmbeddedSubtitleTrackHarvestResult = withContext(Dispatchers.IO) {
        val startedMs = System.currentTimeMillis()
        val sink = TimelinePublishingMatroskaTextTrackSink(
            cueSink = TimelinePublishingTextCueSink(
                sessionKey = request.sessionKey,
                container = container,
                timelineStore = request.timelineStore,
                sourceLanguage = request.sourceLanguage
            )
        )
        val extractor = MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED,
            MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
        )
        extractor.init(
            MatroskaTextExtractorOutput(
                delegate = request.extractorOutput,
                sink = sink,
                selectedSupportedTrackOrdinalProvider = {
                    request.selectedSupportedTextOrdinal
                }
            )
        )

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
        val dataSource = dataSourceFactory.createDataSource()
        val uri = Uri.parse(request.streamUrl)
        var inputPosition = 0L
        val positionHolder = PositionHolder()

        try {
            var input = openInput(dataSource, uri, inputPosition)
            var readResult = Extractor.RESULT_CONTINUE
            while (readResult != Extractor.RESULT_END_OF_INPUT) {
                ensureActive()
                readResult = extractor.read(input, positionHolder)
                if (readResult == Extractor.RESULT_SEEK) {
                    dataSource.close()
                    inputPosition = positionHolder.position
                    input = openInput(dataSource, uri, inputPosition)
                    readResult = Extractor.RESULT_CONTINUE
                }
            }
            EmbeddedSubtitleTrackHarvestResult(
                container = container,
                harvested = sink.sampleCount,
                durationMs = System.currentTimeMillis() - startedMs
            )
        } finally {
            dataSource.close()
            extractor.release()
        }
    }

    // Compatibility bridge for the current runtime call site. Remove in Task 6.
    suspend fun harvest(request: MatroskaTextTrackHarvestRequest): Int {
        return harvest(
            EmbeddedSubtitleTrackHarvestRequest(
                streamUrl = request.streamUrl,
                headers = request.headers,
                selectedSupportedTextOrdinal = request.selectedSupportedSubRipOrdinal,
                sourceLanguage = request.sourceLanguage,
                sessionKey = request.sessionKey,
                timelineStore = request.timelineStore,
                extractorOutput = request.extractorOutput
            )
        ).harvested
    }

    private fun openInput(
        dataSource: DefaultHttpDataSource,
        uri: Uri,
        position: Long
    ): DefaultExtractorInput {
        val remainingLength = dataSource.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
        val length = matroskaExtractorInputLength(
            position = position,
            remainingLength = remainingLength
        )
        return DefaultExtractorInput(dataSource, position, length)
    }
}

internal fun matroskaExtractorInputLength(position: Long, remainingLength: Long): Long {
    if (remainingLength == C.LENGTH_UNSET.toLong()) return C.LENGTH_UNSET.toLong()
    return position + remainingLength
}
