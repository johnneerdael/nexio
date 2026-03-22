package com.nexio.tv.core.stream

import java.util.Locale
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

internal object AioParserSupport {
    private val umlautMap = mapOf(
        'Ä' to "Ae",
        'ä' to "ae",
        'Ö' to "Oe",
        'ö' to "oe",
        'Ü' to "Ue",
        'ü' to "ue",
        'ß' to "ss"
    )

    private val durationRegex = Regex(
        """(?i)(?<![^\s\[(_\-,.])(?:(\d+)h[:\s]?(\d+)m[:\s]?(\d+)s|(\d+)h[:\s]?(\d+)m|(\d+)m[:\s]?(\d+)s|(\d+)h|(\d+)m|(\d+)s)(?=[\s)\]_.\-,]|$)"""
    )
    private val bitrateRegex = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*(bps|kbps|mbps|gbps|tbps)\b""")

    fun normaliseTitle(title: String): String {
        return title
            .map { umlautMap[it] ?: it.toString() }
            .joinToString("")
            .replace("&", "and")
            .normalize()
            .replace(Regex("""[\u0300-\u036f]"""), "")
            .replace(Regex("""[^\p{L}\p{N}+]+"""), "")
            .lowercase(Locale.US)
    }

    fun cleanTitle(title: String): String {
        var cleaned = title
            .map { umlautMap[it] ?: it.toString() }
            .joinToString("")
            .normalize()
        listOf("♪", "♫", "★", "☆", "♡", "♥", "-").forEach { token ->
            cleaned = cleaned.replace(token, " ")
        }
        return cleaned
            .replace("&", "and")
            .replace(Regex("""[\u0300-\u036f]"""), "")
            .replace(Regex("""[^\p{L}\p{N}\s]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.US)
            .trim()
    }

    fun parseDuration(text: String, outputSeconds: Boolean = false): Long? {
        val match = durationRegex.find(text) ?: return null
        val hours = (match.groupValues.getOrNull(1) ?: match.groupValues.getOrNull(4) ?: match.groupValues.getOrNull(8))
            .orEmpty()
            .toLongOrNull() ?: 0L
        val minutes = (match.groupValues.getOrNull(2)
            ?: match.groupValues.getOrNull(5)
            ?: match.groupValues.getOrNull(6)
            ?: match.groupValues.getOrNull(9))
            .orEmpty()
            .toLongOrNull() ?: 0L
        val seconds = (match.groupValues.getOrNull(3)
            ?: match.groupValues.getOrNull(7)
            ?: match.groupValues.getOrNull(10))
            .orEmpty()
            .toLongOrNull() ?: 0L
        val totalMs = (hours * 3600L + minutes * 60L + seconds) * 1000L
        return if (outputSeconds) floor(totalMs / 1000.0).toLong() else totalMs
    }

    fun parseAgeHours(ageString: String): Int? {
        val match = Regex("""^(\d+)([a-zA-Z])$""").find(ageString.trim()) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.US)) {
            "d" -> value * 24
            "h" -> value
            "m" -> value / 60
            "y" -> value * 24 * 365
            else -> null
        }
    }

    fun parseBitrate(text: String): Long? {
        val match = bitrateRegex.find(text)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].lowercase(Locale.US)) {
                "bps" -> 1.0
                "kbps" -> 1_000.0
                "mbps" -> 1_000_000.0
                "gbps" -> 1_000_000_000.0
                "tbps" -> 1_000_000_000_000.0
                else -> return null
            }
            return (value * multiplier).roundToLong()
        }
        val trimmed = text.trim()
        return trimmed.toDoubleOrNull()?.roundToLong()
    }

    fun mergeParsedFiles(
        fileParsed: AioStrictParsedFile?,
        folderParsed: AioStrictParsedFile?
    ): AioStrictParsedFile? {
        if (fileParsed == null && folderParsed == null) return null

        fun <T> arrayMerge(first: List<T>, second: List<T>): List<T> {
            return linkedSetOf<T>().apply {
                addAll(first)
                addAll(second)
            }.toList()
        }

        fun <T> arrayFallback(vararg arrays: List<T>): List<T> {
            return arrays.firstOrNull { it.isNotEmpty() } ?: emptyList()
        }

        val richest = richerParsedFile(fileParsed, folderParsed)
        val seasons = arrayFallback(fileParsed?.seasons.orEmpty(), folderParsed?.seasons.orEmpty())
        val episodes = arrayFallback(fileParsed?.episodes.orEmpty(), folderParsed?.episodes.orEmpty())

        return AioStrictParsedFile(
            resolution = fileParsed?.resolution ?: folderParsed?.resolution,
            quality = fileParsed?.quality ?: folderParsed?.quality,
            languages = arrayMerge(folderParsed?.languages.orEmpty(), fileParsed?.languages.orEmpty()),
            subtitles = arrayMerge(folderParsed?.subtitles.orEmpty(), fileParsed?.subtitles.orEmpty()),
            encode = fileParsed?.encode ?: folderParsed?.encode,
            audioChannels = arrayMerge(folderParsed?.audioChannels.orEmpty(), fileParsed?.audioChannels.orEmpty()),
            audioTags = arrayMerge(folderParsed?.audioTags.orEmpty(), fileParsed?.audioTags.orEmpty()),
            visualTags = arrayMerge(folderParsed?.visualTags.orEmpty(), fileParsed?.visualTags.orEmpty()),
            releaseGroup = fileParsed?.releaseGroup ?: folderParsed?.releaseGroup,
            title = richest?.title ?: folderParsed?.title ?: fileParsed?.title,
            year = fileParsed?.year ?: folderParsed?.year,
            subbed = fileParsed?.subbed == true || folderParsed?.subbed == true,
            dubbed = fileParsed?.dubbed == true || folderParsed?.dubbed == true,
            editions = arrayMerge(folderParsed?.editions.orEmpty(), fileParsed?.editions.orEmpty()),
            regraded = fileParsed?.regraded == true || folderParsed?.regraded == true,
            repack = fileParsed?.repack == true || folderParsed?.repack == true,
            uncensored = fileParsed?.uncensored == true || folderParsed?.uncensored == true,
            unrated = fileParsed?.unrated == true || folderParsed?.unrated == true,
            upscaled = fileParsed?.upscaled == true || folderParsed?.upscaled == true,
            network = fileParsed?.network ?: folderParsed?.network,
            container = fileParsed?.container ?: folderParsed?.container,
            extension = fileParsed?.extension ?: folderParsed?.extension,
            seasons = seasons,
            volumes = arrayMerge(folderParsed?.volumes.orEmpty(), fileParsed?.volumes.orEmpty()),
            episodes = episodes,
            date = fileParsed?.date ?: folderParsed?.date,
            seasonPack = folderParsed?.seasonPack == true || fileParsed?.seasonPack == true
        )
    }

    private fun richerParsedFile(
        fileParsed: AioStrictParsedFile?,
        folderParsed: AioStrictParsedFile?
    ): AioStrictParsedFile? {
        if (fileParsed == null) return folderParsed
        if (folderParsed == null) return fileParsed

        fun richness(parsed: AioStrictParsedFile): Int {
            var score = 0
            if (parsed.title != null) score += 1
            if (parsed.year != null) score += 1
            if (parsed.seasons.isNotEmpty()) score += 1
            if (parsed.episodes.isNotEmpty()) score += 1
            if (parsed.resolution != null) score += 1
            if (parsed.quality != null) score += 1
            if (parsed.encode != null) score += 1
            if (parsed.releaseGroup != null) score += 1
            if (parsed.editions.isNotEmpty()) score += 1
            if (parsed.regraded) score += 1
            if (parsed.repack) score += 1
            if (parsed.uncensored) score += 1
            if (parsed.unrated) score += 1
            if (parsed.upscaled) score += 1
            if (parsed.network != null) score += 1
            if (parsed.container != null) score += 1
            if (parsed.extension != null) score += 1
            if (parsed.visualTags.isNotEmpty()) score += 1
            if (parsed.audioTags.isNotEmpty()) score += 1
            if (parsed.audioChannels.isNotEmpty()) score += 1
            if (parsed.languages.isNotEmpty()) score += 1
            if (parsed.subtitles.isNotEmpty()) score += 1
            if (parsed.seasonPack) score += 1
            return score
        }

        return if (richness(fileParsed) >= richness(folderParsed)) fileParsed else folderParsed
    }

    fun shouldDropNearEqualFolderSize(size: Long?, folderSize: Long?): Boolean {
        if (size == null || folderSize == null || size <= 0L) return false
        return abs(size - folderSize).toDouble() / size.toDouble() < 0.05
    }
}

private fun String.normalize(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
