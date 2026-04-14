package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal fun Format.isAssSsaFormat(): Boolean {
    if (sampleMimeType == MimeTypes.TEXT_VTT) return false
    if (sampleMimeType == MimeTypes.TEXT_SSA || sampleMimeType == "text/x-ass") return true

    val hasAssCodec = codecs
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim().lowercase(Locale.US) }
        ?.any { codec ->
            codec == MimeTypes.TEXT_SSA ||
                codec == "text/x-ass" ||
                codec == "s_text/ass" ||
                codec == "s_text/ssa" ||
                codec.endsWith("/x-ssa")
        } == true
    if (hasAssCodec) return true

    return initializationData.any { data -> hasAssSsaHeader(data) }
}

private fun hasAssSsaHeader(data: ByteArray): Boolean {
    return listOf(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_16LE,
        StandardCharsets.UTF_16BE,
    ).any { charset ->
        containsAssSsaHeader(data, charset)
    }
}

private fun containsAssSsaHeader(data: ByteArray, charset: Charset): Boolean {
    val preview = try {
        String(data, charset)
    } catch (_: Exception) {
        return false
    }
    return preview.contains("[Script Info]", ignoreCase = true) ||
        preview.contains("ScriptType:", ignoreCase = true)
}
