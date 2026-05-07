package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

object AioStrictStreamParser {
    private val sizeRegex = Regex("""(?i)(\d+(?:\.\d+)?)\s?(KiB|MiB|GiB|TiB|KB|MB|GB|TB)""")
    private val packageSizeRegex = Regex("""(?i)📦\s*(\d+(?:\.\d+)?)\s?(KiB|MiB|GiB|TiB|KB|MB|GB|TB)""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val seedersRegex = Regex("""[👥👤]\s*(\d+)""")
    private val ageRegex = Regex("""(?i)(?:📅\s*)?(\d+[dhmy])\b""")
    private val filenameResolutionRegex = Regex("""\d{3,4}p""", RegexOption.IGNORE_CASE)
    private val filenameEpisodeRegex = Regex("""(?i)\bS\d{1,2}(?:E\d{1,2})?\b|\b\d{1,2}x\d{1,2}\b""")
    private val leadingEmojiRegex = Regex("""^[^\p{L}\p{N}]+""")
    private val leadingPrefixRegex = Regex("""^[^\s:]+:\s*""")
    private val webStreamrWarningRegex = Regex("""⚠️\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val cachedSymbols = listOf("⚡", "🚀", "cached")
    private val uncachedSymbols = listOf("⏳", "download", "uncached", "☁️")
    private val cachedDebridPlusMarkerRegex = Regex(
        """(?i)(?:^|[\s\[\(\|])(?:rd|pm|ad|dl|tb|ed|pk)\+(?:$|[\s\]\)\|])"""
    )
    private val genericIndexerEmojis = listOf("🌐", "⚙️", "🔗", "🔎", "🔍", "☁️")

    private fun aliasRegex(alias: String): Regex {
        return Regex("""(?i)(?:^|[\s(\[|/_-])${Regex.escape(alias)}(?=$|[\s)\]|/_+-])""")
    }

    private val servicePatterns = linkedMapOf(
        "RD" to listOf("realdebrid", "real-debrid", "rd"),
        "PM" to listOf("premiumize", "pm"),
        "AD" to listOf("alldebrid", "all-debrid"),
        "DL" to listOf("debridlink", "debrid-link", "dlink"),
        "TB" to listOf("torbox"),
        "ED" to listOf("easydebrid", "easy-debrid"),
        "PK" to listOf("pikpak")
    )

    fun parse(stream: Stream): ParsedStreamInfo {
        val name = stream.name.orEmpty()
        val description = (stream.description ?: stream.title).orEmpty()
        val filename = deriveFilename(stream, description)
        val folderName = deriveFolderName(stream.addonParserPreset, description)
        val fileParsed = filename?.let(AioStrictFileParser::parse)
        val folderParsed = folderName?.let(AioStrictFileParser::parse)
        var parsedFile = AioParserSupport.mergeParsedFiles(fileParsed, folderParsed)
        val sizeSourceDescription = if (stream.addonParserPreset == AddonParserPreset.STREMTHRU) {
            description.replace(packageSizeRegex, "")
        } else {
            description
        }
        val sizeMatches = sizeRegex.findAll(sizeSourceDescription).toList()
        val sizeBytes = stream.behaviorHints?.videoSize ?: sizeMatches.firstOrNull()?.toByteSize()
            ?: parseSizeBytes(name)
        val folderSizeCandidate = when (stream.addonParserPreset) {
            AddonParserPreset.STREMTHRU -> packageSizeRegex.find(description)?.toByteSize()
                ?: sizeMatches.getOrNull(1)?.toByteSize()
            else -> sizeMatches.getOrNull(1)?.toByteSize()
        }
        val folderSizeBytes = folderSizeCandidate
            ?.takeUnless { AioParserSupport.shouldDropNearEqualFolderSize(sizeBytes, it) }
        val durationMs = AioParserSupport.parseDuration(description)
        val bitrate = parseBitrate(description)
        val directDownloadServiceId = deriveDirectDownloadServiceId(stream)
        val serviceId = deriveServiceId(stream, description, filename, directDownloadServiceId)
        val cached = deriveCached(
            name = name,
            description = description,
            serviceId = serviceId,
            isDirectDebridDownload = directDownloadServiceId != null
        )
        val transportKind = deriveTransportKind(stream, serviceId, cached)
        val age = ageRegex.find(description)?.groupValues?.getOrNull(1)
        val ageHours = age?.let(AioParserSupport::parseAgeHours)
        val indexer = extractTextAfterEmoji(description, indexerEmojisFor(stream.addonParserPreset))
        val seeders = seedersRegex.find(description)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isPrivate = stream.addonParserPreset == AddonParserPreset.STREMTHRU && name.contains("🔑")
        val message = deriveMessage(stream, description)
        if (stream.addonParserPreset == AddonParserPreset.TORRENTIO) {
            parsedFile = applyTorrentioMultiSubs(parsedFile, description)
        }
        if (stream.addonParserPreset == AddonParserPreset.WEBSTREAMR) {
            parsedFile = applyWebStreamrResolutionOverride(parsedFile, name)
        }

        return ParsedStreamInfo(
            stream = stream,
            title = parsedFile?.title ?: fallbackTitle(stream, filename, description),
            filename = filename,
            folderName = folderName,
            sizeBytes = sizeBytes,
            folderSizeBytes = folderSizeBytes,
            resolution = parsedFile?.resolution,
            quality = parsedFile?.quality,
            encode = parsedFile?.encode,
            visualTags = parsedFile?.visualTags ?: emptyList(),
            audioTags = parsedFile?.audioTags ?: emptyList(),
            audioChannels = parsedFile?.audioChannels ?: emptyList(),
            languages = parsedFile?.languages ?: emptyList(),
            subtitles = parsedFile?.subtitles ?: emptyList(),
            year = parsedFile?.year ?: yearRegex.find(filename ?: description)?.value,
            seasons = parsedFile?.seasons ?: emptyList(),
            episodes = parsedFile?.episodes ?: emptyList(),
            seasonPack = parsedFile?.seasonPack == true,
            releaseGroup = parsedFile?.releaseGroup,
            container = parsedFile?.container,
            extension = parsedFile?.extension,
            network = parsedFile?.network,
            date = parsedFile?.date,
            editions = parsedFile?.editions ?: emptyList(),
            subbed = parsedFile?.subbed == true,
            dubbed = parsedFile?.dubbed == true,
            regraded = parsedFile?.regraded == true,
            repack = parsedFile?.repack == true,
            uncensored = parsedFile?.uncensored == true,
            unrated = parsedFile?.unrated == true,
            upscaled = parsedFile?.upscaled == true,
            serviceId = serviceId,
            isCached = cached,
            durationMs = durationMs,
            bitrate = bitrate,
            indexer = indexer,
            seeders = seeders,
            age = age,
            ageHours = ageHours,
            isPrivate = isPrivate,
            message = message,
            transportKind = transportKind,
            preservedMetadata = PreservedStreamMetadata(
                filename = stream.behaviorHints?.filename?.takeIf { it.isNotBlank() } ?: filename,
                videoHash = stream.behaviorHints?.videoHash,
                videoSize = stream.behaviorHints?.videoSize ?: sizeBytes,
                bingeGroup = stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun fallbackTitle(stream: Stream, filename: String?, description: String): String? {
        return filename?.let { AioStrictFileParser.parse(it).title }
            ?: stream.title
            ?: description.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun deriveFilename(stream: Stream, description: String): String? {
        stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        when (stream.addonParserPreset) {
            AddonParserPreset.STREMTHRU -> deriveStremThruFilename(description)?.let { return it }
            AddonParserPreset.WEBSTREAMR -> deriveWebStreamrFilename(stream, description)?.let { return it }
            AddonParserPreset.NEXIO_TORII,
            AddonParserPreset.NEXIO_NAGARE -> deriveStremThruFilename(description)?.let { return it }
            AddonParserPreset.TORRENTIO,
            AddonParserPreset.GENERIC -> Unit
        }
        val lines = description.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5)
        val candidate = lines.firstOrNull { line ->
            val parsed = AioStrictFileParser.parse(line)
            parsed.year != null ||
                parsed.seasons.isNotEmpty() ||
                parsed.episodes.isNotEmpty() ||
                yearRegex.containsMatchIn(line) ||
                filenameResolutionRegex.containsMatchIn(line) ||
                filenameEpisodeRegex.containsMatchIn(line)
        } ?: lines.firstOrNull()
        return candidate
            ?.trim()
            ?.replace(leadingPrefixRegex, "")
            ?.trim()
            ?.replace(leadingEmojiRegex, "")
            ?.let { normalizeFilenameCandidate(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun deriveStremThruFilename(description: String): String? {
        val lines = description.lines().map { it.trim() }.filter { it.isNotBlank() }
        return lines.firstNotNullOfOrNull { line ->
            when {
                "📄" in line -> line.substringAfter("📄").trim().takeIf { it.isNotBlank() }
                "📁" in line -> line.substringAfter("📁").trim().takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    private fun normalizeFilenameCandidate(candidate: String): String {
        return when {
            candidate.startsWith("📄 ") -> candidate.substringAfter("📄 ").trim()
            candidate.startsWith("📁 ") -> candidate.substringAfter("📁 ").trim()
            else -> candidate
        }
    }

    private fun deriveWebStreamrFilename(stream: Stream, description: String): String? {
        val firstLine = description.lines().firstOrNull()?.trim().orEmpty()
        val base = if (firstLine.contains("🔗")) null else firstLine.takeIf { it.isNotBlank() }
        val resolution = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(stream.name.orEmpty())?.value
        return listOfNotNull(base, resolution).joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    private fun deriveServiceId(
        stream: Stream,
        description: String,
        filename: String?,
        directDownloadServiceId: String?
    ): String? {
        stream.wrappedProviderId?.takeIf { it.isNotBlank() }?.let { return it }
        directDownloadServiceId?.let { return it }

        val descriptionSignals = description.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val normalizedLine = line.substringAfter("📄 ", line).substringAfter("📁 ", line).trim()
                filename?.equals(normalizedLine, ignoreCase = true) == true ||
                    normalizedLine.endsWith(".mkv", ignoreCase = true) ||
                    normalizedLine.endsWith(".mp4", ignoreCase = true) ||
                    normalizedLine.endsWith(".avi", ignoreCase = true)
            }
            .joinToString(" ")
        val lowered = listOfNotNull(
            stream.name?.takeIf { it.isNotBlank() },
            descriptionSignals.takeIf { it.isNotBlank() },
            stream.addonName.takeIf { it.isNotBlank() }
        ).joinToString(" ").lowercase(Locale.US)
        return servicePatterns.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias -> aliasRegex(alias).containsMatchIn(lowered) }
        }?.key
    }

    private fun deriveDirectDownloadServiceId(stream: Stream): String? {
        val host = runCatching {
            URI(stream.getStreamUrl().orEmpty()).host
        }.getOrNull()
            ?.trim()
            ?.lowercase(Locale.US)
            ?: return null

        return when {
            host == "download.real-debrid.com" || host.endsWith(".download.real-debrid.com") -> "RD"
            else -> null
        }
    }

    private fun deriveMessage(stream: Stream, description: String): String? {
        return when (stream.addonParserPreset) {
            AddonParserPreset.WEBSTREAMR -> {
                listOfNotNull(stream.name, description)
                    .firstNotNullOfOrNull { source ->
                        webStreamrWarningRegex.find(source)?.groupValues?.getOrNull(1)?.trim()
                    }
                    ?.takeIf { it.isNotBlank() }
            }

            else -> null
        }
    }

    private fun indexerEmojisFor(preset: AddonParserPreset): List<String> {
        return when (preset) {
            AddonParserPreset.STREMTHRU -> listOf("🔍")
            AddonParserPreset.WEBSTREAMR -> listOf("🔗")
            AddonParserPreset.NEXIO_TORII,
            AddonParserPreset.NEXIO_NAGARE -> listOf("📡")
            AddonParserPreset.TORRENTIO,
            AddonParserPreset.GENERIC -> genericIndexerEmojis
        }
    }

    private fun applyTorrentioMultiSubs(
        parsedFile: AioStrictParsedFile?,
        description: String
    ): AioStrictParsedFile? {
        if (parsedFile == null) return null
        val lastLine = description.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull() ?: return parsedFile
        val markerIndex = lastLine.indexOf("Multi Subs")
        if (markerIndex < 0) return parsedFile
        val segment = lastLine.substring(markerIndex)
        val subtitleLanguages = extractTorrentioSubtitleLanguages(segment)
            .distinct()
        if (subtitleLanguages.isEmpty()) return parsedFile.copy(languages = emptyList())
        return parsedFile.copy(
            languages = emptyList(),
            subtitles = (parsedFile.subtitles + subtitleLanguages).distinct()
        )
    }

    private fun extractTorrentioSubtitleLanguages(segment: String): List<String> {
        val aliases = linkedMapOf(
            "en" to "English",
            "eng" to "English",
            "fr" to "French",
            "fra" to "French",
            "it" to "Italian",
            "ita" to "Italian",
            "es" to "Spanish",
            "spa" to "Spanish",
            "de" to "German",
            "ger" to "German",
            "deu" to "German",
            "pt" to "Portuguese",
            "por" to "Portuguese",
            "jp" to "Japanese",
            "jpn" to "Japanese"
        )
        val tokens = segment.substringAfter("Multi Subs", "")
            .split(Regex("""[\s|,/_-]+"""))
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
        val fromCodes = tokens.mapNotNull { aliases[it] }
        if (fromCodes.isNotEmpty()) return fromCodes.distinct()
        return AioStrictFileParser.parse(segment).languages
            .filterNot { it.equals("Multi", ignoreCase = true) }
    }

    private fun applyWebStreamrResolutionOverride(
        parsedFile: AioStrictParsedFile?,
        name: String
    ): AioStrictParsedFile? {
        if (parsedFile == null) return null
        val rawResolution = Regex("""(\d+)p""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)
            ?: return parsedFile
        val numeric = rawResolution.toIntOrNull() ?: return parsedFile
        val closest = listOf(2160, 1440, 1080, 720, 576, 480, 360, 240, 144)
            .minByOrNull { kotlin.math.abs(it - numeric) }
            ?: return parsedFile
        return parsedFile.copy(resolution = "${closest}p")
    }

    private fun deriveCached(
        name: String,
        description: String,
        serviceId: String?,
        isDirectDebridDownload: Boolean
    ): Boolean? {
        val lowered = listOf(name, description).joinToString(" ").lowercase(Locale.US)
        return when {
            isDirectDebridDownload -> true
            uncachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> false
            cachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> true
            cachedDebridPlusMarkerRegex.containsMatchIn(lowered) -> true
            serviceId != null -> false
            else -> null
        }
    }

    private fun deriveTransportKind(stream: Stream, serviceId: String?, cached: Boolean?): StreamTransportKind {
        return when {
            cached == true -> StreamTransportKind.CACHED
            cached == false && serviceId != null -> StreamTransportKind.UNCACHED
            !stream.infoHash.isNullOrBlank() -> StreamTransportKind.P2P
            !stream.url.isNullOrBlank() -> StreamTransportKind.HTTP
            !stream.externalUrl.isNullOrBlank() -> StreamTransportKind.EXTERNAL
            !stream.ytId.isNullOrBlank() -> StreamTransportKind.YOUTUBE
            else -> StreamTransportKind.OTHER
        }
    }

    private fun parseSizeBytes(text: String): Long? {
        val match = sizeRegex.find(text) ?: return null
        return match.toByteSize()
    }

    private fun MatchResult.toByteSize(): Long? {
        val value = groupValues[1].toDoubleOrNull() ?: return null
        val unit = groupValues[2].uppercase(Locale.US)
        val multiplier = when (unit) {
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).roundToLong()
    }

    private fun deriveFolderName(preset: AddonParserPreset, description: String): String? {
        if (preset == AddonParserPreset.TORRENTIO) {
            return description.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?.takeIf { it.isNotBlank() }
        }
        return description.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("📁") }
            ?.substringAfter("📁")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTextAfterEmoji(text: String, emojis: List<String>): String? {
        return text.lineSequence().mapNotNull { line ->
            val emoji = emojis.firstOrNull { line.contains(it) } ?: return@mapNotNull null
            line.substringAfter(emoji).trim().takeIf { it.isNotBlank() }
        }.firstOrNull()
    }

    private fun parseBitrate(text: String): Long? {
        return text.lineSequence()
            .mapNotNull { line -> AioParserSupport.parseBitrate(line) }
            .firstOrNull()
    }
}
