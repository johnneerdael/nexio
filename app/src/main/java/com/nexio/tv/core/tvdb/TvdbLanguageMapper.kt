package com.nexio.tv.core.tvdb

import java.util.Locale

object TvdbLanguageMapper {
    fun normalize(language: String?): String {
        val normalized = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?.lowercase(Locale.US)
            ?: return "eng"

        return when {
            normalized in setOf("eng", "en", "en-us", "en-gb") -> "eng"
            normalized in setOf("spa", "es", "es-es", "es-mx") -> "spa"
            normalized in setOf("fra", "fre", "fr", "fr-fr") -> "fra"
            normalized in setOf("deu", "ger", "de", "de-de") -> "deu"
            normalized in setOf("nld", "dut", "nl", "nl-nl") -> "nld"
            normalized in setOf("zho", "chi", "zh", "zh-cn", "zh-hans") -> "zho"
            normalized in setOf("zhtw", "zh-tw", "zh-hant") -> "zhtw"
            else -> "eng"
        }
    }
}
