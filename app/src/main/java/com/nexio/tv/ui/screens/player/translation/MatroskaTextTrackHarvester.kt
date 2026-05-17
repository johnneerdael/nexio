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
    val selectedInternalSubtitleIndex: Int,
    val sourceLanguage: String?,
    val sessionKey: TranslationTimelineSessionKey,
    val timelineStore: TranslatedSubtitleTimelineStore,
    val extractorOutput: ExtractorOutput
)

internal class TimelinePublishingMatroskaTextTrackSink(
    private val sessionKey: TranslationTimelineSessionKey,
    private val timelineStore: TranslatedSubtitleTimelineStore,
    private val sourceLanguage: String? = null
) : MatroskaTextTrackSink {
    var sampleCount: Int = 0
        private set

    override fun onSupportedTextTrack(
        trackId: Int,
        supportedTrackOrdinal: Int,
        format: Format
    ) = Unit

    override fun onSubtitleSample(sample: HarvestedMatroskaTextSample) {
        val text = sample.text.trim()
        if (text.isBlank()) return

        val cueGroup = CueGroup(
            listOf(Cue.Builder().setText(text).build()),
            sample.timeUs
        )
        timelineStore.putSourceCue(sessionKey, cueGroup)
        val sourceCue = timelineStore.registerMiss(sessionKey, cueGroup)
        if (sourceCue != null) {
            sampleCount += 1
            EmbeddedSubtitleHarvestDiagnostics.cueHarvested(
                session = sessionKey,
                cueKey = sourceCue.cueKey,
                sourceLanguage = sourceLanguage ?: sample.format.language
            )
        }
    }
}

internal class MatroskaTextTrackHarvester {
    suspend fun harvest(request: MatroskaTextTrackHarvestRequest): Int = withContext(Dispatchers.IO) {
        val sink = TimelinePublishingMatroskaTextTrackSink(
            sessionKey = request.sessionKey,
            timelineStore = request.timelineStore,
            sourceLanguage = request.sourceLanguage
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
                    request.selectedInternalSubtitleIndex
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
            sink.sampleCount
        } finally {
            dataSource.close()
            extractor.release()
        }
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
