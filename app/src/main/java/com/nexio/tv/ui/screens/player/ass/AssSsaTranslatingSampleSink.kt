package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import com.nexio.tv.data.repository.AssSsaEventFormat
import com.nexio.tv.data.repository.AssSsaEventRecord
import com.nexio.tv.data.repository.AutoTranslateDiagnosticsLogger
import com.nexio.tv.data.repository.AssSsaProtectedTranslationUnit
import com.nexio.tv.data.repository.AssSsaRisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AssSsaTranslatingSampleSink(
    private val downstream: AssSsaSampleSink,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val isPlaybackActive: () -> Boolean = { true },
    private val selectedTrackIdProvider: () -> Int? = { null },
    private val useSystemPromptTranslation: () -> Boolean,
    private val translate: suspend (List<AssSsaProtectedTranslationUnit>) -> Map<String, String>,
    private val translateRawAssSsa: suspend (String) -> String,
    private val diagnosticsLogger: AutoTranslateDiagnosticsLogger =
        AutoTranslateDiagnosticsLogger.disabled()
) : AssSsaSampleSink {
    private val trackFormats = linkedMapOf<Int, AssSsaEventFormat>()

    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        trackFormats[trackId] = AssSsaEventFormat.standardDialogue()
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

        if (!shouldTranslateTrack(trackId)) {
            diagnosticsLogger.log(
                "sample_emit_original reason=inactive_track track=$trackId selectedTrack=${selectedTrackIdProvider()}"
            )
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        if (!isPlaybackActive()) {
            diagnosticsLogger.log(
                "sample_emit_original reason=playback_inactive track=$trackId timeUs=$timeUs"
            )
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val text = data.decodeToString()
        if (useSystemPromptTranslation()) {
            diagnosticsLogger.log(
                "sample_translate_start mode=raw_ass track=$trackId timeUs=$timeUs bytes=${data.size} " +
                    "hash=${AutoTranslateDiagnosticsLogger.sha256Short(text)}"
            )
            diagnosticsLogger.logUnsafe("sample_raw_ass_source track=$trackId timeUs=$timeUs", text)
            scope.launch {
                if (!canEmitTranslatedSample(trackId)) {
                    diagnosticsLogger.log(
                        "sample_emit_original reason=playback_changed mode=raw_ass track=$trackId timeUs=$timeUs"
                    )
                    downstream.onSubtitleSample(trackId, timeUs, data)
                    return@launch
                }
                val translatedSample = runCatching {
                    translateRawAssSsa(text)
                }.onFailure { error ->
                    diagnosticsLogger.log(
                        "sample_translate_failed mode=raw_ass track=$trackId timeUs=$timeUs " +
                        "error=${error::class.simpleName}:${error.message}"
                    )
                }.getOrDefault(text)
                if (!canEmitTranslatedSample(trackId)) {
                    diagnosticsLogger.log(
                        "sample_emit_original reason=playback_changed mode=raw_ass track=$trackId timeUs=$timeUs"
                    )
                    downstream.onSubtitleSample(trackId, timeUs, data)
                    return@launch
                }
                diagnosticsLogger.log(
                    "sample_emit_${if (translatedSample == text) "original" else "translated"} mode=raw_ass " +
                        "track=$trackId timeUs=$timeUs sourceBytes=${data.size} translatedBytes=${translatedSample.toByteArray().size} " +
                        "sourceHash=${AutoTranslateDiagnosticsLogger.sha256Short(text)} " +
                        "translatedHash=${AutoTranslateDiagnosticsLogger.sha256Short(translatedSample)}"
                )
                diagnosticsLogger.logUnsafe("sample_raw_ass_output track=$trackId timeUs=$timeUs", translatedSample)
                downstream.onSubtitleSample(
                    trackId = trackId,
                    timeUs = timeUs,
                    data = translatedSample.toByteArray()
                )
            }
            return
        }

        val format = trackFormats[trackId] ?: AssSsaEventFormat.standardDialogue()
        val records = text.lineSequence()
            .mapNotNull { line -> AssSsaEventRecord.parseDialogueLine(line, format) }
            .toList()
        if (records.isEmpty()) {
            diagnosticsLogger.log(
                "sample_emit_original reason=no_dialogue_records track=$trackId timeUs=$timeUs bytes=${data.size}"
            )
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val unitsById = records.mapIndexed { index, record ->
            "evt_$index" to AssSsaProtectedTranslationUnit.fromText(
                id = "evt_$index",
                text = record.text
            )
        }
        scope.launch {
            val translatableUnits = unitsById
                .map { it.second }
                .filter { it.risk != AssSsaRisk.PreserveOnly && it.protectedText.isNotBlank() }
            diagnosticsLogger.log(
                "sample_translate_start mode=protected track=$trackId timeUs=$timeUs records=${records.size} " +
                    "units=${unitsById.size} translatable=${translatableUnits.size} " +
                    "preserveOnly=${unitsById.count { it.second.risk == AssSsaRisk.PreserveOnly }} " +
                    "bytes=${data.size} hash=${AutoTranslateDiagnosticsLogger.sha256Short(text)}"
            )
            diagnosticsLogger.logUnsafe("sample_protected_source track=$trackId timeUs=$timeUs", text)
            if (translatableUnits.isEmpty()) {
                diagnosticsLogger.log(
                    "sample_emit_original reason=no_translatable_units mode=protected track=$trackId timeUs=$timeUs"
                )
                downstream.onSubtitleSample(trackId, timeUs, data)
                return@launch
            }
            if (!canEmitTranslatedSample(trackId)) {
                diagnosticsLogger.log(
                    "sample_emit_original reason=playback_changed mode=protected track=$trackId timeUs=$timeUs"
                )
                downstream.onSubtitleSample(trackId, timeUs, data)
                return@launch
            }
            val translated = runCatching {
                translate(translatableUnits)
            }.onFailure { error ->
                diagnosticsLogger.log(
                    "sample_translate_failed mode=protected track=$trackId timeUs=$timeUs " +
                    "error=${error::class.simpleName}:${error.message}"
                )
            }.getOrDefault(emptyMap())
            if (!canEmitTranslatedSample(trackId)) {
                diagnosticsLogger.log(
                    "sample_emit_original reason=playback_changed mode=protected track=$trackId timeUs=$timeUs"
                )
                downstream.onSubtitleSample(trackId, timeUs, data)
                return@launch
            }
            val translatedLines = records.mapIndexed { index, record ->
                val unitId = "evt_$index"
                val unit = unitsById[index].second
                val translatedText = translated[unitId]
                val reconstructed = translatedText
                    ?.let { unit.reconstruct(it).getOrNull() }
                    ?: record.text
                record.withText(reconstructed).render()
            }
            downstream.onSubtitleSample(
                trackId = trackId,
                timeUs = timeUs,
                data = translatedLines.joinToString("\n").toByteArray()
            )
            val output = translatedLines.joinToString("\n")
            diagnosticsLogger.log(
                "sample_emit_translated mode=protected track=$trackId timeUs=$timeUs " +
                    "translatedItems=${translated.size} outputBytes=${output.toByteArray().size} " +
                    "outputHash=${AutoTranslateDiagnosticsLogger.sha256Short(output)}"
            )
            diagnosticsLogger.logUnsafe("sample_protected_output track=$trackId timeUs=$timeUs", output)
        }
    }

    override fun onFontAttachment(name: String, data: ByteArray) {
        downstream.onFontAttachment(name, data)
    }

    private fun shouldTranslateTrack(trackId: Int): Boolean {
        val selectedTrackId = selectedTrackIdProvider()
        return selectedTrackId == null || selectedTrackId == trackId
    }

    private fun canEmitTranslatedSample(trackId: Int): Boolean {
        return isEnabled() && isPlaybackActive() && shouldTranslateTrack(trackId)
    }
}
