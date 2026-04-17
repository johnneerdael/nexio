package com.nexio.tv.core.player

import java.util.Locale

private val DOLBY_VISION_CODEC_PREFIXES = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

internal fun resolveDolbyVisionProfileFromCodecString(codecs: String?): Int? {
    val entries = codecs
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: return null

    for (entry in entries) {
        val parts = entry.split('.')
        if (parts.size < 2) continue
        val prefix = parts[0].lowercase(Locale.ROOT)
        if (prefix !in DOLBY_VISION_CODEC_PREFIXES) continue
        return parts[1].toIntOrNull()
    }
    return null
}
