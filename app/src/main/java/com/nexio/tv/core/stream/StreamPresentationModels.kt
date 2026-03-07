package com.nexio.tv.core.stream

import androidx.compose.runtime.Immutable
import com.nexio.tv.domain.model.Stream
import java.util.Locale
import kotlin.math.roundToLong

@Immutable
data class StreamFeatureFlags(
    val uniformFormattingEnabled: Boolean = false,
    val groupAcrossAddonsEnabled: Boolean = false,
    val deduplicateGroupedStreamsEnabled: Boolean = false,
    val filterWebDolbyVisionStreamsEnabled: Boolean = false,
    val filterEpisodeMismatchStreamsEnabled: Boolean = false,
    val filterMovieYearMismatchStreamsEnabled: Boolean = false
)

@Immutable
data class StreamRequestContext(
    val contentType: String? = null,
    val title: String? = null,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null
)

enum class StreamTransportKind {
    CACHED,
    UNCACHED,
    P2P,
    HTTP,
    YOUTUBE,
    EXTERNAL,
    OTHER
}

@Immutable
data class ParsedStreamInfo(
    val stream: Stream,
    val title: String?,
    val filename: String?,
    val sizeBytes: Long?,
    val resolution: String?,
    val quality: String?,
    val encode: String?,
    val visualTags: List<String>,
    val audioTags: List<String>,
    val audioChannels: List<String>,
    val languages: List<String>,
    val year: String?,
    val seasons: List<Int>,
    val episodes: List<Int>,
    val releaseGroup: String?,
    val serviceId: String?,
    val isCached: Boolean?,
    val durationMs: Long?,
    val transportKind: StreamTransportKind
) {
    val isDolbyVision: Boolean get() = visualTags.any { it == "DV" }
    val isWebDl: Boolean get() = quality == "WEB-DL"

    val normalizedFilenameKey: String? by lazy(LazyThreadSafetyMode.NONE) {
        filename
            ?.lowercase(Locale.US)
            ?.replace(Regex("""\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|m2ts)$"""), "")
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    val normalizedTitleKey: String? by lazy(LazyThreadSafetyMode.NONE) {
        title
            ?.lowercase(Locale.US)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    val roundedSizeBucket: Long? by lazy(LazyThreadSafetyMode.NONE) {
        sizeBytes?.takeIf { it > 0L }?.let { size ->
            val bucketSize = 512L * 1024L * 1024L
            ((size + (bucketSize / 2L)) / bucketSize) * bucketSize
        }
    }

    val smartDetectKey: String? by lazy(LazyThreadSafetyMode.NONE) {
        val parts = buildList {
            normalizedTitleKey?.let { add("title=$it") }
            year?.let { add("year=$it") }
            resolution?.let { add("res=$it") }
            quality?.let { add("quality=$it") }
            encode?.let { add("encode=$it") }
            if (visualTags.isNotEmpty()) add("visual=${visualTags.sorted().joinToString(",")}")
            if (audioTags.isNotEmpty()) add("audio=${audioTags.sorted().joinToString(",")}")
            if (audioChannels.isNotEmpty()) add("channels=${audioChannels.sorted().joinToString(",")}")
            if (seasons.isNotEmpty()) add("season=${seasons.sorted().joinToString(",")}")
            if (episodes.isNotEmpty()) add("episode=${episodes.sorted().joinToString(",")}")
            roundedSizeBucket?.let { add("size=$it") }
            releaseGroup?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { add("group=$it") }
        }
        if (parts.size < 4 || normalizedTitleKey == null) null else parts.joinToString("|")
    }

    val exactDuplicateKey: String by lazy(LazyThreadSafetyMode.NONE) {
        normalizedFilenameKey?.let { return@lazy "filename:$it" }
        stream.behaviorHints?.videoHash
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?.let { return@lazy "videohash:$it" }
        if (!stream.infoHash.isNullOrBlank()) {
            return@lazy "hash:${stream.infoHash.lowercase(Locale.US)}:${stream.fileIdx ?: 0}"
        }
        stream.getStreamUrl()
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?.let { return@lazy "url:$it" }
        smartDetectKey?.let { return@lazy "smart:$it" }
        listOfNotNull(
            normalizedTitleKey,
            roundedSizeBucket?.toString(),
            resolution,
            quality,
            encode
        ).joinToString("|")
    }

    val hasUsablePlaybackTarget: Boolean
        get() = !stream.getStreamUrl().isNullOrBlank() || !stream.infoHash.isNullOrBlank() || !stream.ytId.isNullOrBlank()

    val serviceRetentionKey: String? by lazy(LazyThreadSafetyMode.NONE) {
        if (!hasUsablePlaybackTarget) return@lazy null
        serviceId?.let { return@lazy "${transportKind.name}:$it" }
        when (transportKind) {
            StreamTransportKind.CACHED,
            StreamTransportKind.UNCACHED -> null
            else -> transportKind.name
        }
    }
}

@Immutable
data class StreamCardModel(
    val stream: Stream,
    val parsed: ParsedStreamInfo,
    val title: String,
    val subtitle: String?,
    val detailLines: List<String>
)

@Immutable
data class OrganizedStreams(
    val items: List<StreamCardModel>,
    val availableAddons: List<String>,
    val selectedAddonFilter: String?,
    val showAddonFilters: Boolean
)

object StreamPresentationEngine {
    fun organize(
        streams: List<Stream>,
        availableAddons: List<String>,
        selectedAddonFilter: String?,
        flags: StreamFeatureFlags,
        requestContext: StreamRequestContext = StreamRequestContext()
    ): OrganizedStreams {
        val parsed = streams.map { stream ->
            val parsedInfo = AioStyleStreamParser.parse(stream)
            StreamCardModel(
                stream = stream,
                parsed = parsedInfo,
                title = buildTitle(stream, parsedInfo, flags.uniformFormattingEnabled),
                subtitle = buildSubtitle(stream, parsedInfo, flags.uniformFormattingEnabled),
                detailLines = buildDetailLines(stream, parsedInfo, flags.uniformFormattingEnabled)
            )
        }

        val filteredByRequest = parsed.filterNot { item ->
            shouldFilterForRequest(
                parsed = item.parsed,
                requestContext = requestContext,
                flags = flags
            )
        }

        val filteredByDv = if (flags.filterWebDolbyVisionStreamsEnabled) {
            filteredByRequest.filterNot { it.parsed.isWebDl && it.parsed.isDolbyVision }
        } else {
            filteredByRequest
        }

        if (!flags.groupAcrossAddonsEnabled) {
            val effectiveFilter = selectedAddonFilter?.takeIf { addon ->
                availableAddons.contains(addon)
            }
            val visibleItems = if (effectiveFilter == null) {
                filteredByDv
            } else {
                filteredByDv.filter { it.stream.addonName == effectiveFilter }
            }
            return OrganizedStreams(
                items = visibleItems,
                availableAddons = availableAddons,
                selectedAddonFilter = effectiveFilter,
                showAddonFilters = true
            )
        }

        val groupedItems = if (flags.deduplicateGroupedStreamsEnabled) {
            deduplicate(filteredByDv)
        } else {
            filteredByDv
        }.sortedWith(
            compareByDescending<StreamCardModel> { it.parsed.sizeBytes ?: -1L }
                .thenByDescending { resolutionRank(it.parsed.resolution) }
                .thenByDescending { it.parsed.isCached == true }
                .thenBy { it.stream.addonName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )

        return OrganizedStreams(
            items = groupedItems,
            availableAddons = emptyList(),
            selectedAddonFilter = null,
            showAddonFilters = false
        )
    }

    private fun deduplicate(items: List<StreamCardModel>): List<StreamCardModel> {
        if (items.size < 2) return items

        val disjointSet = IntDisjointSet(items.size)
        val keyToIndexes = LinkedHashMap<String, MutableList<Int>>()

        items.forEachIndexed { index, item ->
            dedupeKeys(item).forEach { key ->
                keyToIndexes.getOrPut(key) { mutableListOf() }.add(index)
            }
        }

        keyToIndexes.values.forEach { indexes ->
            if (indexes.size < 2) return@forEach
            val anchor = indexes.first()
            indexes.drop(1).forEach { candidate ->
                disjointSet.union(anchor, candidate)
            }
        }

        val clusters = LinkedHashMap<Int, MutableList<StreamCardModel>>()
        items.forEachIndexed { index, item ->
            val root = disjointSet.find(index)
            clusters.getOrPut(root) { mutableListOf() }.add(item)
        }

        return clusters.values.flatMap { cluster ->
            if (cluster.size == 1) {
                cluster
            } else {
                selectDeduplicatedCluster(cluster)
            }
        }
    }

    private fun dedupeKeys(item: StreamCardModel): Set<String> {
        val parsed = item.parsed
        val stream = item.stream
        return buildSet {
            parsed.normalizedFilenameKey?.let { add("filename:$it") }
            stream.behaviorHints?.videoHash
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?.let { add("videohash:$it") }
            if (!stream.infoHash.isNullOrBlank()) {
                add("hash:${stream.infoHash.lowercase(Locale.US)}:${stream.fileIdx ?: 0}")
            }
            parsed.smartDetectKey?.let { add("smart:$it") }
            if (parsed.normalizedTitleKey != null && parsed.roundedSizeBucket != null) {
                add("title-size:${parsed.normalizedTitleKey}:${parsed.roundedSizeBucket}")
            }
            stream.getStreamUrl()
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?.let { add("url:$it") }
        }
    }

    private fun dedupePriority(item: StreamCardModel): Int {
        val parsed = item.parsed
        return when {
            parsed.isCached == true -> 0
            parsed.transportKind == StreamTransportKind.UNCACHED -> 1
            parsed.transportKind == StreamTransportKind.P2P -> 2
            parsed.transportKind == StreamTransportKind.HTTP -> 3
            parsed.transportKind == StreamTransportKind.EXTERNAL -> 4
            parsed.transportKind == StreamTransportKind.YOUTUBE -> 5
            else -> 5
        }
    }

    private fun selectDeduplicatedCluster(cluster: List<StreamCardModel>): List<StreamCardModel> {
        val exactCollapsed = cluster
            .groupBy { it.parsed.exactDuplicateKey }
            .values
            .map { pickBestRepresentative(it) }

        val cached = exactCollapsed.filter { it.parsed.transportKind == StreamTransportKind.CACHED && it.parsed.hasUsablePlaybackTarget }
        if (cached.isNotEmpty()) {
            return retainPerService(cached)
        }

        val uncached = exactCollapsed.filter { it.parsed.transportKind == StreamTransportKind.UNCACHED && it.parsed.hasUsablePlaybackTarget }
        if (uncached.isNotEmpty()) {
            return retainPerService(uncached)
        }

        val p2p = exactCollapsed.filter { it.parsed.transportKind == StreamTransportKind.P2P && it.parsed.hasUsablePlaybackTarget }
        if (p2p.isNotEmpty()) {
            return listOf(pickBestRepresentative(p2p))
        }

        val http = exactCollapsed.filter {
            it.parsed.transportKind == StreamTransportKind.HTTP ||
                it.parsed.transportKind == StreamTransportKind.EXTERNAL
        }
        if (http.isNotEmpty()) {
            return listOf(pickBestRepresentative(http))
        }

        val youtube = exactCollapsed.filter { it.parsed.transportKind == StreamTransportKind.YOUTUBE }
        if (youtube.isNotEmpty()) {
            return listOf(pickBestRepresentative(youtube))
        }

        return listOf(pickBestRepresentative(exactCollapsed))
    }

    private fun retainPerService(items: List<StreamCardModel>): List<StreamCardModel> {
        val withKnownService = items.filter { it.parsed.serviceRetentionKey != null }
        val source = if (withKnownService.isNotEmpty()) withKnownService else items
        return source
            .groupBy { it.parsed.serviceRetentionKey ?: "unknown" }
            .values
            .map { pickBestRepresentative(it) }
    }

    private fun pickBestRepresentative(items: List<StreamCardModel>): StreamCardModel {
        return items.minWithOrNull(
            compareBy<StreamCardModel> { dedupePriority(it) }
                .thenBy { addonPriority(it) }
                .thenByDescending { it.parsed.hasUsablePlaybackTarget }
                .thenByDescending { it.parsed.sizeBytes ?: -1L }
                .thenByDescending { resolutionRank(it.parsed.resolution) }
                .thenByDescending { metadataRichness(it.parsed) }
                .thenBy { it.title.lowercase(Locale.US) }
        ) ?: items.first()
    }

    private fun addonPriority(item: StreamCardModel): String {
        return item.stream.addonName.lowercase(Locale.US)
    }

    private fun metadataRichness(parsed: ParsedStreamInfo): Int {
        var score = 0
        if (!parsed.audioTags.isNullOrEmpty()) score += parsed.audioTags.size
        if (!parsed.visualTags.isNullOrEmpty()) score += parsed.visualTags.size
        if (!parsed.audioChannels.isNullOrEmpty()) score += parsed.audioChannels.size
        if (parsed.quality != null) score += 1
        if (parsed.encode != null) score += 1
        if (parsed.releaseGroup != null) score += 1
        return score
    }

    private fun buildTitle(stream: Stream, parsed: ParsedStreamInfo, uniform: Boolean): String {
        if (!uniform) {
            return stream.getDisplayName()
        }
        val baseTitle = parsed.title?.takeIf { it.isNotBlank() }
            ?: parsed.filename?.substringBeforeLast('.')
            ?: stream.getDisplayName()
        val resolutionLabel = when (parsed.resolution) {
            "2160p" -> "4K"
            "1440p" -> "2K"
            "1080p" -> "FHD"
            "720p" -> "HD"
            "576p", "480p" -> "SD"
            else -> null
        }
        val cacheMarker = when (parsed.isCached) {
            true -> " ⚡"
            false -> " ❌"
            null -> ""
        }
        return listOfNotNull(baseTitle, resolutionLabel)
            .joinToString(" | ")
            .plus(cacheMarker)
            .trim()
    }

    private fun buildSubtitle(stream: Stream, parsed: ParsedStreamInfo, uniform: Boolean): String? {
        if (!uniform) {
            return stream.getDisplayDescription()?.takeIf { it != stream.getDisplayName() }
        }

        return parsed.serviceId?.let { service ->
            buildString {
                append(service)
                if (parsed.releaseGroup != null) {
                    append(" • ")
                    append(parsed.releaseGroup)
                }
            }
        } ?: parsed.releaseGroup
    }

    private fun buildDetailLines(stream: Stream, parsed: ParsedStreamInfo, uniform: Boolean): List<String> {
        if (!uniform) {
            return emptyList()
        }

        val lines = mutableListOf<String>()

        val introParts = mutableListOf<String>()
        parsed.year?.let { introParts += "🎬 $it" }
        formatSeasonEpisode(parsed)?.let { introParts += it }
        parsed.durationMs?.takeIf { it > 0 }?.let { introParts += "⏱️ ${formatDuration(it)}" }
        if (introParts.isNotEmpty()) {
            lines += introParts.joinToString(" | ")
        }

        val videoParts = mutableListOf<String>()
        parsed.quality?.let { videoParts += qualityLabel(it) }
        parsed.encode?.let { videoParts += "🎞️ ${stylizeCodec(it)}" }
        if (parsed.visualTags.isNotEmpty()) {
            videoParts += parsed.visualTags.joinToString(" | ")
        }
        if (videoParts.isNotEmpty()) {
            lines += videoParts.joinToString(" | ")
        }

        val audioParts = mutableListOf<String>()
        if (parsed.audioTags.isNotEmpty()) {
            audioParts += "🎧 ${parsed.audioTags.joinToString(" | ") { stylizeAudioTag(it) }}"
        }
        if (parsed.audioChannels.isNotEmpty()) {
            audioParts += "🔊 ${parsed.audioChannels.joinToString(" | ")}"
        }
        if (audioParts.isNotEmpty()) {
            lines += audioParts.joinToString(" | ")
        }

        val packageParts = mutableListOf<String>()
        parsed.sizeBytes?.takeIf { it > 0 }?.let { packageParts += "📦 ${formatBytes(it)}" }
        if (parsed.languages.isNotEmpty()) {
            packageParts += parsed.languages.joinToString(" • ") { languageCodeOrName(it) }
        }
        if (packageParts.isNotEmpty()) {
            lines += packageParts.joinToString(" | ")
        }

        parsed.filename?.takeIf { it.isNotBlank() }?.let { lines += it }

        return lines
    }

    private fun formatSeasonEpisode(parsed: ParsedStreamInfo): String? {
        val seasons = parsed.seasons
        val episodes = parsed.episodes
        if (seasons.isEmpty() && episodes.isEmpty()) return null
        if (seasons.size == 1 && episodes.isNotEmpty()) {
            val season = seasons.first().toString().padStart(2, '0')
            return "🎬 ${episodes.joinToString(" • ") { episode ->
                "S${season}E${episode.toString().padStart(2, '0')}"
            }}"
        }
        if (seasons.isNotEmpty() && episodes.size == seasons.size) {
            return "🎬 ${seasons.zip(episodes).joinToString(" • ") { (season, episode) ->
                "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            }}"
        }
        if (seasons.isNotEmpty() && episodes.isNotEmpty()) {
            return "🎬 ${seasons.joinToString(",") { "S${it.toString().padStart(2, '0')}" }} | ${
                episodes.joinToString(",") { "E${it.toString().padStart(2, '0')}" }
            }"
        }
        if (seasons.isNotEmpty()) {
            return seasons.joinToString(" ") { "S${it.toString().padStart(2, '0')}" }
        }
        return episodes.joinToString(" ") { "E${it.toString().padStart(2, '0')}" }
    }

    private fun qualityLabel(quality: String): String {
        return when (quality) {
            "BluRay REMUX" -> "💎 REMUX"
            "BluRay" -> "📀 BLURAY"
            "WEB-DL" -> "🖥 WEB-DL"
            "WEBRip" -> "💻 WEBRIP"
            "HDRip" -> "💿 HDRIP"
            "HC HD-Rip" -> "💽 HC HD-RIP"
            "DVDRip" -> "💾 DVDRIP"
            "HDTV" -> "📺 HDTV"
            else -> quality
        }
    }

    private fun stylizeCodec(codec: String): String {
        return when (codec) {
            "AV1" -> "AV1"
            "HEVC" -> "HEVC"
            "AVC" -> "AVC"
            else -> codec
        }
    }

    private fun stylizeAudioTag(tag: String): String {
        return tag
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%dh:%02dm:%02ds", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%dm:%02ds", minutes, seconds)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        val formatted = if (value >= 10 || unitIndex == 0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$formatted ${units[unitIndex]}"
    }

    private fun languageCodeOrName(language: String): String {
        return when (language.lowercase(Locale.US)) {
            "english" -> "EN"
            "dutch" -> "NL"
            "spanish" -> "ES"
            "french" -> "FR"
            "german" -> "DE"
            "italian" -> "IT"
            "japanese" -> "JA"
            "korean" -> "KO"
            "chinese" -> "ZH"
            "portuguese" -> "PT"
            else -> language.uppercase(Locale.US).take(3)
        }
    }

    private fun resolutionRank(resolution: String?): Int {
        return when (resolution) {
            "2160p" -> 9
            "1440p" -> 8
            "1080p" -> 7
            "720p" -> 6
            "576p" -> 5
            "480p" -> 4
            "360p" -> 3
            "240p" -> 2
            "144p" -> 1
            else -> 0
        }
    }

    private fun shouldFilterForRequest(
        parsed: ParsedStreamInfo,
        requestContext: StreamRequestContext,
        flags: StreamFeatureFlags
    ): Boolean {
        return shouldFilterEpisodeMismatch(parsed, requestContext, flags) ||
            shouldFilterMovieYearMismatch(parsed, requestContext, flags)
    }

    private fun shouldFilterEpisodeMismatch(
        parsed: ParsedStreamInfo,
        requestContext: StreamRequestContext,
        flags: StreamFeatureFlags
    ): Boolean {
        if (!flags.filterEpisodeMismatchStreamsEnabled) return false

        val requestSeason = requestContext.season ?: return false
        val requestEpisode = requestContext.episode ?: return false
        if (requestSeason <= 0 || requestEpisode <= 0) return false

        if (parsed.seasons.isEmpty() && parsed.episodes.isEmpty()) {
            if (requestSeason == 0) return false
            if (matchesEpisodeTitleOnly(parsed, requestContext.episodeTitle)) return false
            return looksStandaloneMovieCandidate(parsed)
        }

        if (parsed.seasons.isNotEmpty() && requestSeason !in parsed.seasons) {
            return true
        }
        if (parsed.episodes.isNotEmpty() && requestEpisode !in parsed.episodes) {
            return true
        }
        return false
    }

    private fun shouldFilterMovieYearMismatch(
        parsed: ParsedStreamInfo,
        requestContext: StreamRequestContext,
        flags: StreamFeatureFlags
    ): Boolean {
        if (!flags.filterMovieYearMismatchStreamsEnabled) return false

        val requestYear = requestContext.year?.toIntOrNull() ?: return false
        val parsedYear = parsed.year?.toIntOrNull() ?: return false
        if (requestYear == parsedYear) return false

        val isMovieRequest = requestContext.contentType.equals("movie", ignoreCase = true)
        if (isMovieRequest) {
            return true
        }

        val isEpisodeRequest = (requestContext.season ?: 0) > 0 && (requestContext.episode ?: 0) > 0
        if (!isEpisodeRequest) {
            return false
        }

        return parsed.seasons.isEmpty() && parsed.episodes.isEmpty()
    }

    private fun matchesEpisodeTitleOnly(
        parsed: ParsedStreamInfo,
        episodeTitle: String?
    ): Boolean {
        val expected = normalizeMatchKey(episodeTitle).takeIf { it.isNotBlank() } ?: return false
        val parsedTitle = normalizeMatchKey(parsed.title)
        val parsedFilename = normalizeMatchKey(parsed.filename)
        return parsedTitle.contains(expected) || parsedFilename.contains(expected)
    }

    private fun normalizeMatchKey(value: String?): String {
        return value
            ?.lowercase(Locale.US)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
            .orEmpty()
    }

    private fun looksStandaloneMovieCandidate(parsed: ParsedStreamInfo): Boolean {
        if (parsed.year != null) return true
        val rawText = listOfNotNull(parsed.title, parsed.filename).joinToString(" ")
        if (rawText.isBlank()) return false
        return Regex("""\b(19|20)\d{2}\b""").containsMatchIn(rawText)
    }
}

private object AioStyleStreamParser {
    private val seasonEpisodeTokenRegex = Regex("""(?i)^s\d{1,2}e\d{1,2}$""")
    private val seasonOnlyTokenRegex = Regex("""(?i)^s\d{1,2}$""")
    private val episodeOnlyTokenRegex = Regex("""(?i)^e\d{1,2}$""")

    private fun tokenRegex(pattern: String): Regex {
        return Regex("""(?i)(?:^|[\s(\[._-])(?:$pattern)(?=$|[\s)\].,_-])""")
    }

    private fun aliasRegex(alias: String): Regex {
        return Regex("""(?i)(?:^|[\s(\[|/_-])${Regex.escape(alias)}(?=$|[\s)\]|/_-])""")
    }

    private val resolutionPatterns = linkedMapOf(
        "2160p" to tokenRegex("""4k|2160p|uhd"""),
        "1440p" to tokenRegex("""1440p|2k|qhd"""),
        "1080p" to tokenRegex("""1080p|fhd"""),
        "720p" to tokenRegex("""720p|hd"""),
        "576p" to tokenRegex("""576p"""),
        "480p" to tokenRegex("""480p|sd""")
    )

    private val qualityPatterns = linkedMapOf(
        "BluRay REMUX" to tokenRegex("""(?:bd|br|uhd)?remux"""),
        "BluRay" to tokenRegex("""bluray|blu-ray|bdrip|brrip"""),
        "WEB-DL" to tokenRegex("""web[ ._-]?dl"""),
        "WEBRip" to tokenRegex("""web[ ._-]?rip"""),
        "HDRip" to tokenRegex("""hd[ ._-]?rip"""),
        "HC HD-Rip" to tokenRegex("""hc[ ._-]?hd[ ._-]?rip"""),
        "DVDRip" to tokenRegex("""dvd[ ._-]?rip"""),
        "HDTV" to tokenRegex("""hdtv""")
    )

    private val visualTagPatterns = linkedMapOf(
        "HDR10+" to tokenRegex("""hdr10\+?"""),
        "HDR10" to tokenRegex("""hdr10"""),
        "HDR" to tokenRegex("""hdr"""),
        "DV" to tokenRegex("""dovi|dolby[ ._-]?vision|dv"""),
        "IMAX" to tokenRegex("""imax""")
    )

    private val audioTagPatterns = linkedMapOf(
        "Atmos" to tokenRegex("""atmos"""),
        "TrueHD" to tokenRegex("""true[ ._-]?hd"""),
        "DTS-HD MA" to tokenRegex("""dts[ ._-]?hd[ ._-]?ma"""),
        "DTS-HD" to tokenRegex("""dts[ ._-]?hd"""),
        "DTS-ES" to tokenRegex("""dts[ ._-]?es"""),
        "DTS:X" to tokenRegex("""dts[ .:_-]?x"""),
        "DTS" to tokenRegex("""dts"""),
        "DD+" to tokenRegex("""dd\+|eac3|dolby[ ._-]?digital[ ._-]?plus"""),
        "DD" to tokenRegex("""dd|ac3|dolby[ ._-]?digital"""),
        "FLAC" to tokenRegex("""flac"""),
        "OPUS" to tokenRegex("""opus"""),
        "AAC" to tokenRegex("""aac""")
    )

    private val audioChannelPatterns = linkedMapOf(
        "7.1" to tokenRegex("""7[ ._-]?1(?:ch)?"""),
        "6.1" to tokenRegex("""6[ ._-]?1(?:ch)?"""),
        "5.1" to tokenRegex("""5[ ._-]?1(?:ch)?"""),
        "2.0" to tokenRegex("""2[ ._-]?0(?:ch)?""")
    )

    private val encodePatterns = linkedMapOf(
        "HEVC" to tokenRegex("""hevc|x265|h265"""),
        "AVC" to tokenRegex("""avc|x264|h264"""),
        "AV1" to tokenRegex("""av1""")
    )

    private val languagePatterns = linkedMapOf(
        "English" to tokenRegex("""(?:english|eng)(?![ ._-]?sub)"""),
        "Dutch" to tokenRegex("""(?:dutch|nl)(?![ ._-]?sub)"""),
        "Spanish" to tokenRegex("""(?:spanish|spa|esp)(?![ ._-]?sub)"""),
        "French" to tokenRegex("""(?:french|fra|fre|vf|vff)(?![ ._-]?sub)"""),
        "German" to tokenRegex("""(?:german|deu|ger)(?![ ._-]?sub)"""),
        "Italian" to tokenRegex("""(?:italian|ita)(?![ ._-]?sub)"""),
        "Japanese" to tokenRegex("""(?:japanese|jpn|jap)(?![ ._-]?sub)"""),
        "Multi" to tokenRegex("""multi"""),
        "Dual Audio" to tokenRegex("""dual[ ._-]?audio""")
    )

    private val sizeRegex = Regex("""(?i)(\d+(?:\.\d+)?)\s?(KB|MB|GB|TB)""")
    private val durationRegex = Regex("""(?i)(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val seasonEpisodeRegex = Regex("""(?i)\bS(\d{1,2})(?:E(\d{1,2}))?\b""")
    private val altEpisodeRegex = Regex("""(?i)\b(\d{1,2})x(\d{1,2})\b""")
    private val releaseGroupRegex = Regex("""-(?!\d+$|S\d+|\d+x|ep?\d+)([A-Za-z0-9][A-Za-z0-9._-]{1,})$""")
    private val cachedSymbols = listOf("⚡", "🚀", "cached", "+")
    private val uncachedSymbols = listOf("⏳", "download", "uncached", "☁️")

    private val servicePatterns = linkedMapOf(
        "RD" to listOf("realdebrid", "real-debrid", "rd"),
        "PM" to listOf("premiumize", "pm"),
        "AD" to listOf("alldebrid", "all-debrid", "ad"),
        "DL" to listOf("debridlink", "debrid-link", "dl"),
        "TB" to listOf("torbox", "tb"),
        "ED" to listOf("easydebrid", "easy-debrid", "ed"),
        "PK" to listOf("pikpak", "pk")
    )

    fun parse(stream: Stream): ParsedStreamInfo {
        val name = stream.name.orEmpty()
        val description = (stream.description ?: stream.title).orEmpty()
        val filename = deriveFilename(stream, description)
        val parseSource = listOfNotNull(filename, description, name, stream.title).joinToString(" ")

        val resolution = matchFirst(parseSource, resolutionPatterns)
        val quality = matchFirst(parseSource, qualityPatterns)
        val encode = matchFirst(parseSource, encodePatterns)
        val visualTags = matchMany(parseSource, visualTagPatterns)
        val audioTags = matchMany(parseSource, audioTagPatterns)
        val audioChannels = matchMany(parseSource, audioChannelPatterns)
        val languages = matchMany(parseSource, languagePatterns)
        val year = yearRegex.find(parseSource)?.value
        val releaseGroup = filename
            ?.substringBeforeLast('.')
            ?.let { releaseGroupRegex.find(it)?.groupValues?.getOrNull(1) }
        val title = deriveTitle(filename ?: stream.title ?: stream.name)
        val seasonsEpisodes = deriveSeasonEpisode(filename ?: parseSource)
        val serviceId = deriveServiceId(stream, parseSource)
        val cached = deriveCached(parseSource)
        val sizeBytes = stream.behaviorHints?.videoSize ?: parseSizeBytes(description) ?: parseSizeBytes(name)
        val durationMs = parseDuration(description)
        val transportKind = deriveTransportKind(stream, serviceId, cached)

        return ParsedStreamInfo(
            stream = stream,
            title = title,
            filename = filename,
            sizeBytes = sizeBytes,
            resolution = resolution,
            quality = quality,
            encode = encode,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            languages = languages,
            year = year,
            seasons = seasonsEpisodes.first,
            episodes = seasonsEpisodes.second,
            releaseGroup = releaseGroup,
            serviceId = serviceId,
            isCached = cached,
            durationMs = durationMs,
            transportKind = transportKind
        )
    }

    private fun deriveFilename(stream: Stream, description: String): String? {
        stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val lines = description.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5)
        val candidate = lines.firstOrNull { line ->
            yearRegex.containsMatchIn(line) ||
                resolutionPatterns.values.any { it.containsMatchIn(line) } ||
                qualityPatterns.values.any { it.containsMatchIn(line) } ||
                seasonEpisodeRegex.containsMatchIn(line) ||
                altEpisodeRegex.containsMatchIn(line)
        } ?: lines.firstOrNull()
        return candidate
            ?.replace(Regex("""^[^\p{L}\p{N}]+"""), "")
            ?.substringAfter("📄 ", candidate)
            ?.substringAfter("📁 ", candidate)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun deriveTitle(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutExtension = value.substringBeforeLast('.', value)
        val truncated = withoutExtension
            .replace(Regex("""(?i)\b(?:2160p|1440p|1080p|720p|576p|480p|bluray|blu-ray|remux|web[- ._]?dl|webrip|hdrip|hdtv|x265|h265|hevc|x264|h264|avc|av1|dovi|dolby[ ._-]?vision|hdr10\+?|hdr|atmos|truehd|dts(?:[- ._]?hd(?:[- ._]?ma)?)?|dd\+?|aac|flac|opus)\b.*$"""), "")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return truncated.takeIf { it.isNotBlank() }?.split(' ')
            ?.joinToString(" ") { token ->
                when {
                    seasonEpisodeTokenRegex.matches(token) -> token.uppercase(Locale.US)
                    seasonOnlyTokenRegex.matches(token) -> token.uppercase(Locale.US)
                    episodeOnlyTokenRegex.matches(token) -> token.uppercase(Locale.US)
                    else -> token.lowercase(Locale.US)
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                }
            }
    }

    private fun deriveSeasonEpisode(text: String): Pair<List<Int>, List<Int>> {
        seasonEpisodeRegex.find(text)?.let { match ->
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            return listOfNotNull(season) to listOfNotNull(episode)
        }
        altEpisodeRegex.find(text)?.let { match ->
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            return listOfNotNull(season) to listOfNotNull(episode)
        }
        return emptyList<Int>() to emptyList()
    }

    private fun deriveServiceId(stream: Stream, parseSource: String): String? {
        val lowered = buildString {
            append(parseSource)
            append(' ')
            append(stream.addonName)
        }.lowercase(Locale.US)
        return servicePatterns.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias ->
                aliasRegex(alias).containsMatchIn(lowered)
            }
        }?.key
    }

    private fun deriveCached(parseSource: String): Boolean? {
        val lowered = parseSource.lowercase(Locale.US)
        return when {
            uncachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> false
            cachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> true
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
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.US)
        val multiplier = when (unit) {
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0
            "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).roundToLong()
    }

    private fun parseDuration(text: String): Long? {
        val match = durationRegex.find(text) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues.getOrNull(3)?.toLongOrNull()
        val totalSeconds = if (third != null) {
            first * 3600L + second * 60L + third
        } else {
            first * 60L + second
        }
        return totalSeconds * 1000L
    }

    private fun matchFirst(text: String, patterns: Map<String, Regex>): String? {
        return patterns.entries.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.key
    }

    private fun matchMany(text: String, patterns: Map<String, Regex>): List<String> {
        return patterns.entries
            .filter { (_, regex) -> regex.containsMatchIn(text) }
            .map { it.key }
    }
}

private class IntDisjointSet(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size)

    fun find(value: Int): Int {
        if (parent[value] != value) {
            parent[value] = find(parent[value])
        }
        return parent[value]
    }

    fun union(left: Int, right: Int) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot == rightRoot) return

        when {
            rank[leftRoot] < rank[rightRoot] -> parent[leftRoot] = rightRoot
            rank[leftRoot] > rank[rightRoot] -> parent[rightRoot] = leftRoot
            else -> {
                parent[rightRoot] = leftRoot
                rank[leftRoot] += 1
            }
        }
    }
}
