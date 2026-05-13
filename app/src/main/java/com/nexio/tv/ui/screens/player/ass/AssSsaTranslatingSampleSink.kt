package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.repository.AssSsaEventFormat
import com.nexio.tv.data.repository.AssSsaEventRecord
import com.nexio.tv.data.repository.AssSsaSegmentSurfaceParser
import com.nexio.tv.data.repository.AssSsaSurfaceParseResult
import com.nexio.tv.data.repository.AssSsaTranslationAction
import com.nexio.tv.data.repository.AssSsaTranslationPlanConfig
import com.nexio.tv.data.repository.AssSsaTranslationPlanner
import com.nexio.tv.data.repository.AssSsaTranslationSurface
import com.nexio.tv.data.repository.AutoTranslateDiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val ASS_SAMPLE_TIME_PATTERN = Regex("""\d+:\d{2}:\d{2}[:.]\d{2}""")
private const val SAMPLE_TRANSLATION_TIMEOUT_MS = 5_000L

internal class AssSsaTranslatingSampleSink(
    private val downstream: AssSsaSampleSink,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val translate: suspend (List<AssSsaTranslationSurface>) -> Map<String, List<String>>,
    private val diagnosticsLogger: AutoTranslateDiagnosticsLogger =
        AutoTranslateDiagnosticsLogger.disabled(),
    private val translationTimeoutMs: Long = SAMPLE_TRANSLATION_TIMEOUT_MS,
    private val liveBatchWindowMs: Long = 250L,
    private val maxLiveBatchTranslations: Int = 3
) : AssSsaSampleSink {
    private val trackFormats = linkedMapOf<Int, TrackEventFormats>()
    private val inFlightTranslations = mutableMapOf<String, Deferred<Map<String, List<String>>>>()
    private val pendingBatches = mutableMapOf<Long, MutableList<PendingLiveSample>>()

    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        val dialogueFormat = headerData.decodeToString()
            .lineSequence()
            .mapNotNull(AssSsaEventFormat::parse)
            .lastOrNull()
            ?: AssSsaEventFormat.standardDialogue()
        trackFormats[trackId] = TrackEventFormats(
            dialogueFormat = dialogueFormat,
            prefixedRawSampleFormat = if (format.isEmbeddedAssSsaSampleTrack()) {
                AssSsaEventFormat.prefixedMatroskaAss()
            } else {
                null
            },
            rawSampleFormat = if (format.isEmbeddedAssSsaSampleTrack()) {
                AssSsaEventFormat.matroskaAss()
            } else {
                dialogueFormat
            }
        )
        downstream.onTrackHeader(trackId, headerData, format)
    }

    override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        if (!isEnabled()) {
            diagnosticsLogger.log(
                "sample_emit_original reason=disabled track=$trackId timeUs=$timeUs bytes=${data.size} " +
                    "hash=${AutoTranslateDiagnosticsLogger.sha256Short(data.decodeToString())}"
            )
            diagnosticsLogger.logUnsafe("sample_original_disabled track=$trackId timeUs=$timeUs", data.decodeToString())
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val text = data.decodeToString()
        val formats = trackFormats[trackId] ?: TrackEventFormats.default()
        val records = text.lineSequence()
            .mapNotNull { line -> line.parseAssSsaSampleRecord(formats) }
            .toList()
        if (records.isEmpty()) {
            diagnosticsLogger.log(
                "sample_emit_original reason=no_event_records track=$trackId timeUs=$timeUs bytes=${data.size}"
            )
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val pending = PendingLiveSample(
            trackId = trackId,
            timeUs = timeUs,
            data = data,
            text = text,
            records = records
        )
        if (liveBatchWindowMs <= 0L) {
            scope.launch { processLiveBatch(listOf(pending), batchedIds = false) }
            return
        }

        val batchKey = timeUs / (liveBatchWindowMs * 1000L)
        val shouldSchedule = synchronized(pendingBatches) {
            val bucket = pendingBatches.getOrPut(batchKey) { mutableListOf() }
            bucket += pending
            bucket.size == 1
        }
        if (shouldSchedule) {
            scope.launch {
                delay(liveBatchWindowMs)
                val samples = synchronized(pendingBatches) {
                    pendingBatches.remove(batchKey).orEmpty()
                }
                processLiveBatch(samples, batchedIds = true)
            }
        }
    }

    override fun onFontAttachment(name: String, data: ByteArray) {
        downstream.onFontAttachment(name, data)
    }

    private fun translationDeferredFor(
        key: String,
        surfaces: List<AssSsaTranslationSurface>
    ): Deferred<Map<String, List<String>>> {
        synchronized(inFlightTranslations) {
            inFlightTranslations[key]?.takeIf { !it.isCancelled }?.let { return it }
            val created = scope.async { translate(surfaces) }
            inFlightTranslations[key] = created
            created.invokeOnCompletion {
                synchronized(inFlightTranslations) {
                    if (inFlightTranslations[key] === created) {
                        inFlightTranslations.remove(key)
                    }
                }
            }
            return created
        }
    }

    private suspend fun processLiveBatch(samples: List<PendingLiveSample>, batchedIds: Boolean) {
        if (samples.isEmpty()) return
        val allRecords = samples.flatMapIndexed { sampleIndex, sample ->
            sample.records.mapIndexed { recordIndex, record -> IndexedLiveRecord(sampleIndex, recordIndex, record) }
        }
        val flatRecords = allRecords.map { it.record }
        val plan = AssSsaTranslationPlanner.plan(
            records = flatRecords,
            config = AssSsaTranslationPlanConfig(
                liveWindowMs = liveBatchWindowMs.takeIf { it > 0L },
                maxTranslatePerWindow = if (liveBatchWindowMs > 0L) maxLiveBatchTranslations else Int.MAX_VALUE,
                budgetPreferPreserveStyles = setOf("signs")
            )
        )
        val surfacesByFlatIndex = flatRecords.mapIndexedNotNull { flatIndex, record ->
            if (plan.actions[flatIndex] !is AssSsaTranslationAction.Translate) return@mapIndexedNotNull null
            val indexed = allRecords[flatIndex]
            val id = if (batchedIds) {
                "evt_${indexed.sampleIndex}_${indexed.recordIndex}"
            } else {
                "evt_${indexed.recordIndex}"
            }
            when (val result = AssSsaSegmentSurfaceParser.parse(id, record.text)) {
                is AssSsaSurfaceParseResult.Translatable -> flatIndex to result.surface
                is AssSsaSurfaceParseResult.PreserveOnly -> null
            }
        }.toMap()
        val preserveCount = plan.actions.count { it is AssSsaTranslationAction.Preserve }
        val duplicateCount = plan.actions.count { it is AssSsaTranslationAction.DuplicateOf }
        samples.forEach { sample ->
            diagnosticsLogger.log(
                "sample_ass_classified track=${sample.trackId} timeUs=${sample.timeUs} " +
                    "records=${sample.records.size} translate=${surfacesByFlatIndex.size} " +
                    "preserve=$preserveCount duplicate=$duplicateCount"
            )
        }
        val surfaces = surfacesByFlatIndex.values.toList()
        val first = samples.first()
        diagnosticsLogger.log(
            "sample_translate_start mode=ass_segment track=${first.trackId} timeUs=${first.timeUs} " +
                "records=${flatRecords.size} surfaces=${surfaces.size} " +
                "bytes=${samples.sumOf { it.data.size }} " +
                "hash=${AutoTranslateDiagnosticsLogger.sha256Short(samples.joinToString("\n") { it.text })}"
        )
        if (surfaces.isEmpty()) {
            samples.forEach { sample ->
                diagnosticsLogger.log(
                    "sample_emit_original reason=no_translatable_surfaces mode=ass_segment " +
                        "track=${sample.trackId} timeUs=${sample.timeUs}"
                )
                downstream.onSubtitleSample(sample.trackId, sample.timeUs, sample.data)
            }
            return
        }

        val translationKey = AutoTranslateDiagnosticsLogger.sha256Short(samples.joinToString("\n") { it.text })
        val translated = runCatching {
            withTimeoutOrNull(translationTimeoutMs) {
                translationDeferredFor(translationKey, surfaces).await()
            }
        }.onFailure { error ->
            diagnosticsLogger.log(
                "sample_translate_failed mode=ass_segment track=${first.trackId} timeUs=${first.timeUs} " +
                    "error=${error::class.simpleName}:${error.message}"
            )
        }.getOrNull()
        if (translated == null) {
            samples.forEach { sample ->
                diagnosticsLogger.log(
                    "sample_emit_original reason=translation_timeout mode=ass_segment track=${sample.trackId} " +
                        "timeUs=${sample.timeUs} timeoutMs=$translationTimeoutMs bytes=${sample.data.size}"
                )
                downstream.onSubtitleSample(sample.trackId, sample.timeUs, sample.data)
            }
            return
        }

        var flatCursor = 0
        samples.forEach { sample ->
            val translatedLines = sample.records.mapIndexed { recordIndex, record ->
                val flatIndex = flatCursor + recordIndex
                val canonicalIndex = when (val action = plan.actions[flatIndex]) {
                    is AssSsaTranslationAction.Translate -> action.canonicalIndex
                    is AssSsaTranslationAction.DuplicateOf -> action.canonicalIndex
                    is AssSsaTranslationAction.Preserve -> null
                }
                val surface = canonicalIndex?.let { surfacesByFlatIndex[it] }
                val translatedText = surface
                    ?.let {
                        translated[it.id]?.let { segments ->
                            runCatching { it.recomposeOrThrow(segments) }.getOrNull()
                        }
                    }
                    ?: record.text
                record.withText(translatedText).render()
            }
            val output = translatedLines.joinToString("\n")
            downstream.onSubtitleSample(trackId = sample.trackId, timeUs = sample.timeUs, data = output.toByteArray())
            diagnosticsLogger.log(
                "sample_emit_translated mode=ass_segment track=${sample.trackId} timeUs=${sample.timeUs} " +
                    "translatedItems=${translated.size} outputBytes=${output.toByteArray().size} " +
                    "outputHash=${AutoTranslateDiagnosticsLogger.sha256Short(output)}"
            )
            diagnosticsLogger.logUnsafe("sample_ass_segment_output track=${sample.trackId} timeUs=${sample.timeUs}", output)
            flatCursor += sample.records.size
        }
    }

    private data class PendingLiveSample(
        val trackId: Int,
        val timeUs: Long,
        val data: ByteArray,
        val text: String,
        val records: List<AssSsaEventRecord>
    )

    private data class IndexedLiveRecord(
        val sampleIndex: Int,
        val recordIndex: Int,
        val record: AssSsaEventRecord
    )

    private data class TrackEventFormats(
        val dialogueFormat: AssSsaEventFormat,
        val prefixedRawSampleFormat: AssSsaEventFormat?,
        val rawSampleFormat: AssSsaEventFormat
    ) {
        companion object {
            fun default(): TrackEventFormats {
                val dialogueFormat = AssSsaEventFormat.standardDialogue()
                return TrackEventFormats(
                    dialogueFormat = dialogueFormat,
                    prefixedRawSampleFormat = null,
                    rawSampleFormat = dialogueFormat
                )
            }
        }
    }

    private fun String.parseAssSsaSampleRecord(formats: TrackEventFormats): AssSsaEventRecord? {
        val trimmed = trimStart()
        return if (trimmed.startsWith("Dialogue:", ignoreCase = true) ||
            trimmed.startsWith("Comment:", ignoreCase = true)
        ) {
            formats.prefixedRawSampleFormat
                ?.let { parsePrefixedRawAssSsaSampleRecord(it) }
                ?: AssSsaEventRecord.parseDialogueLine(this, formats.dialogueFormat)
        } else {
            parseRawAssSsaSampleRecord(formats.rawSampleFormat)
        }
    }

    private fun String.parsePrefixedRawAssSsaSampleRecord(format: AssSsaEventFormat): AssSsaEventRecord? {
        val record = AssSsaEventRecord.parseDialogueLine(this, format) ?: return null
        val start = record.field("Start").orEmpty()
        val end = record.field("End").orEmpty()
        val readOrder = record.field("ReadOrder").orEmpty()
        val layer = record.field("Layer").orEmpty()
        return if (start.looksLikeAssSampleTime() &&
            end.looksLikeAssSampleTime() &&
            readOrder.trim().toIntOrNull() != null &&
            layer.trim().toIntOrNull() != null
        ) {
            record
        } else {
            null
        }
    }

    private fun String.parseRawAssSsaSampleRecord(format: AssSsaEventFormat): AssSsaEventRecord? {
        if (format.textIndex < 0) return null
        val values = split(',', limit = format.fields.size)
        if (values.size <= format.textIndex) return null
        return AssSsaEventRecord(
            kind = "Sample",
            prefix = "",
            format = format,
            values = values
        )
    }

    private fun String.looksLikeAssSampleTime(): Boolean {
        return ASS_SAMPLE_TIME_PATTERN.matches(trim())
    }

    private fun Format.isEmbeddedAssSsaSampleTrack(): Boolean {
        return (sampleMimeType == MimeTypes.TEXT_SSA || sampleMimeType == "text/x-ass") &&
            (containerMimeType == MimeTypes.VIDEO_MATROSKA || containerMimeType == MimeTypes.VIDEO_WEBM)
    }
}
