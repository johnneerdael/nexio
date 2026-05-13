package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.repository.AssSsaEventFormat
import com.nexio.tv.data.repository.AssSsaEventRecord
import com.nexio.tv.data.repository.AssSsaSegmentSurfaceParser
import com.nexio.tv.data.repository.AssSsaSurfaceParseResult
import com.nexio.tv.data.repository.AssSsaTranslationSurface
import com.nexio.tv.data.repository.AutoTranslateDiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    private val translationTimeoutMs: Long = SAMPLE_TRANSLATION_TIMEOUT_MS
) : AssSsaSampleSink {
    private val trackFormats = linkedMapOf<Int, TrackEventFormats>()
    private val inFlightTranslations = mutableMapOf<String, Deferred<Map<String, List<String>>>>()

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

        val surfacesByIndex = records.mapIndexedNotNull { index, record ->
            val id = "evt_$index"
            when (val result = AssSsaSegmentSurfaceParser.parse(id, record.text)) {
                is AssSsaSurfaceParseResult.Translatable -> index to result.surface
                is AssSsaSurfaceParseResult.PreserveOnly -> null
            }
        }
        scope.launch {
            val surfaces = surfacesByIndex.map { it.second }
            diagnosticsLogger.log(
                "sample_translate_start mode=ass_segment track=$trackId timeUs=$timeUs records=${records.size} " +
                    "surfaces=${surfaces.size} bytes=${data.size} hash=${AutoTranslateDiagnosticsLogger.sha256Short(text)}"
            )
            if (surfaces.isEmpty()) {
                diagnosticsLogger.log(
                    "sample_emit_original reason=no_translatable_surfaces mode=ass_segment track=$trackId timeUs=$timeUs"
                )
                downstream.onSubtitleSample(trackId, timeUs, data)
                return@launch
            }
            val translationKey = AutoTranslateDiagnosticsLogger.sha256Short(text)
            val translationDeferred = translationDeferredFor(translationKey, surfaces)
            val translated = runCatching {
                withTimeoutOrNull(translationTimeoutMs) {
                    translationDeferred.await()
                }
            }.onFailure { error ->
                diagnosticsLogger.log(
                    "sample_translate_failed mode=ass_segment track=$trackId timeUs=$timeUs " +
                        "error=${error::class.simpleName}:${error.message}"
                )
            }.getOrNull()
            if (translated == null) {
                diagnosticsLogger.log(
                    "sample_emit_original reason=translation_timeout mode=ass_segment track=$trackId " +
                        "timeUs=$timeUs timeoutMs=$translationTimeoutMs bytes=${data.size}"
                )
                downstream.onSubtitleSample(trackId, timeUs, data)
                return@launch
            }
            val surfaceByIndex = surfacesByIndex.toMap()
            val translatedLines = records.mapIndexed { index, record ->
                val surface = surfaceByIndex[index]
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
            downstream.onSubtitleSample(trackId = trackId, timeUs = timeUs, data = output.toByteArray())
            diagnosticsLogger.log(
                "sample_emit_translated mode=ass_segment track=$trackId timeUs=$timeUs " +
                    "translatedItems=${translated.size} outputBytes=${output.toByteArray().size} " +
                    "outputHash=${AutoTranslateDiagnosticsLogger.sha256Short(output)}"
            )
            diagnosticsLogger.logUnsafe("sample_ass_segment_output track=$trackId timeUs=$timeUs", output)
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
