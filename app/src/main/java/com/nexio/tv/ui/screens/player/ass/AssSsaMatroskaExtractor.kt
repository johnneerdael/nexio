package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.IOException
import java.lang.reflect.Field

internal class AssSsaMatroskaExtractor(
    private val sink: AssSsaSampleSink,
    private val extractorOutputField: Field? = defaultExtractorOutputField
) : MatroskaExtractor(
    if (extractorOutputField != null) {
        AssNoOpSubtitleParserFactory()
    } else {
        DefaultSubtitleParserFactory()
    },
    if (extractorOutputField != null) FLAG_EMIT_RAW_SUBTITLE_DATA else 0
) {
    private var currentAttachmentName: String? = null
    private var currentAttachmentMimeType: String? = null
    private var missingExtractorOutputFieldLogged = false

    override fun getElementType(id: Int): Int {
        return when (id) {
            ID_ATTACHMENTS, ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_FILE_NAME, ID_FILE_MIME_TYPE -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
            else -> super.getElementType(id)
        }
    }

    override fun isLevel1Element(id: Int): Boolean {
        return super.isLevel1Element(id) || id == ID_ATTACHMENTS
    }

    @Throws(ParserException::class)
    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_EBML -> wrapExtractorOutputIfPossible()
            ID_ATTACHED_FILE -> clearAttachment()
        }
        super.startMasterElement(id, contentPosition, contentSize)
    }

    @Throws(ParserException::class)
    override fun endMasterElement(id: Int) {
        if (id == ID_ATTACHED_FILE) {
            clearAttachment()
            return
        }
        super.endMasterElement(id)
    }

    @Throws(ParserException::class)
    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_FILE_NAME -> currentAttachmentName = value
            ID_FILE_MIME_TYPE -> currentAttachmentMimeType = value
            else -> super.stringElement(id, value)
        }
    }

    @Throws(IOException::class)
    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (id != ID_FILE_DATA) {
            super.binaryElement(id, contentSize, input)
            return
        }

        val name = currentAttachmentName
        val mimeType = currentAttachmentMimeType
        if (name != null && mimeType in FONT_MIME_TYPES) {
            val data = ByteArray(contentSize)
            input.readFully(data, 0, contentSize)
            sink.onFontAttachment(name, data)
        } else {
            input.skipFully(contentSize)
        }
    }

    internal fun wrapExtractorOutputForTesting() {
        wrapExtractorOutputIfPossible()
    }

    private fun wrapExtractorOutputIfPossible() {
        val field = extractorOutputField
        if (field == null) {
            logMissingExtractorOutputFieldOnce()
            return
        }
        val current = field.get(this) as? ExtractorOutput ?: return
        if (current is AssSsaExtractorOutput) return
        field.set(this, AssSsaExtractorOutput(current, sink))
    }

    private fun logMissingExtractorOutputFieldOnce() {
        if (missingExtractorOutputFieldLogged) return
        missingExtractorOutputFieldLogged = true
        runCatching {
            Log.w(
                TAG,
                "MatroskaExtractor extractorOutput field unavailable; using Media3 subtitle parsing"
            )
        }
    }

    private fun clearAttachment() {
        currentAttachmentName = null
        currentAttachmentMimeType = null
    }

    companion object {
        private const val TAG = "AssSsaMatroskaExtractor"

        private const val ID_EBML = 0x1A45DFA3
        private const val ID_ATTACHMENTS = 0x1941A469
        private const val ID_ATTACHED_FILE = 0x61A7
        private const val ID_FILE_NAME = 0x466E
        private const val ID_FILE_MIME_TYPE = 0x4660
        private const val ID_FILE_DATA = 0x465C

        private val FONT_MIME_TYPES = setOf(
            "font/ttf",
            "font/otf",
            "font/sfnt",
            "font/woff",
            "font/woff2",
            "application/font-sfnt",
            "application/font-woff",
            "application/x-truetype-font",
            "application/vnd.ms-opentype",
            "application/x-font-ttf",
        )

        private val defaultExtractorOutputField = runCatching {
            MatroskaExtractor::class.java.getDeclaredField("extractorOutput").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
}
