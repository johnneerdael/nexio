package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import com.nexio.tv.data.repository.AssSsaEventFormat
import com.nexio.tv.data.repository.AssSsaEventRecord
import com.nexio.tv.data.repository.AssSsaProtectedTranslationUnit
import com.nexio.tv.data.repository.AssSsaTextTokenizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AssSsaTranslatingSampleSink(
    private val downstream: AssSsaSampleSink,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val translate: suspend (List<AssSsaProtectedTranslationUnit>) -> Map<String, String>
) : AssSsaSampleSink {
    private val trackFormats = linkedMapOf<Int, AssSsaEventFormat>()

    override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        trackFormats[trackId] = AssSsaEventFormat.standardDialogue()
        downstream.onTrackHeader(trackId, headerData, format)
    }

    override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        if (!isEnabled()) {
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val text = data.decodeToString()
        val format = trackFormats[trackId] ?: AssSsaEventFormat.standardDialogue()
        val records = text.lineSequence()
            .mapNotNull { line -> AssSsaEventRecord.parseDialogueLine(line, format) }
            .toList()
        if (records.isEmpty()) {
            downstream.onSubtitleSample(trackId, timeUs, data)
            return
        }

        val unitsById = records.mapIndexed { index, record ->
            "evt_$index" to AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize(record.text)
            )
        }
        scope.launch {
            val translated = runCatching {
                translate(unitsById.map { it.second })
            }.getOrDefault(emptyMap())
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
        }
    }

    override fun onFontAttachment(name: String, data: ByteArray) {
        downstream.onFontAttachment(name, data)
    }
}
