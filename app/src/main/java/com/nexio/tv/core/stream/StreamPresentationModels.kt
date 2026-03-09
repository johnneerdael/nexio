package com.nexio.tv.core.stream

import androidx.compose.runtime.Immutable
import com.nexio.tv.domain.model.AddonParserPreset
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
    val showAddonFilters: Boolean,
    val diagnostics: StreamFilteringDiagnostics = StreamFilteringDiagnostics()
)

@Immutable
data class StreamFilteringDiagnostics(
    val inputCount: Int = 0,
    val droppedEpisodeMismatchCount: Int = 0,
    val droppedMovieYearMismatchCount: Int = 0,
    val droppedWebDolbyVisionCount: Int = 0,
    val droppedDeduplicateCount: Int = 0,
    val dedupeMixedCachedUncachedClusterCount: Int = 0,
    val dedupeCachedDroppedForUncachedClusterCount: Int = 0,
    val droppedAddonFilterCount: Int = 0,
    val finalPresentedCount: Int = 0
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

        var droppedEpisodeMismatchCount = 0
        var droppedMovieYearMismatchCount = 0

        val filteredByRequest = parsed.filterNot { item ->
            val dropEpisode = shouldFilterEpisodeMismatch(
                parsed = item.parsed,
                requestContext = requestContext,
                flags = flags
            )
            if (dropEpisode) {
                droppedEpisodeMismatchCount += 1
                return@filterNot true
            }

            val dropYear = shouldFilterMovieYearMismatch(
                parsed = item.parsed,
                requestContext = requestContext,
                flags = flags
            )
            if (dropYear) {
                droppedMovieYearMismatchCount += 1
            }
            dropYear
        }

        var droppedWebDolbyVisionCount = 0
        val filteredByDv = if (flags.filterWebDolbyVisionStreamsEnabled) {
            filteredByRequest.filterNot {
                val shouldDrop = it.parsed.isWebDl && it.parsed.isDolbyVision
                if (shouldDrop) droppedWebDolbyVisionCount += 1
                shouldDrop
            }
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
            val droppedAddonFilterCount = if (effectiveFilter == null) 0 else (filteredByDv.size - visibleItems.size)
            val diagnostics = StreamFilteringDiagnostics(
                inputCount = parsed.size,
                droppedEpisodeMismatchCount = droppedEpisodeMismatchCount,
                droppedMovieYearMismatchCount = droppedMovieYearMismatchCount,
                droppedWebDolbyVisionCount = droppedWebDolbyVisionCount,
                droppedDeduplicateCount = 0,
                dedupeMixedCachedUncachedClusterCount = 0,
                dedupeCachedDroppedForUncachedClusterCount = 0,
                droppedAddonFilterCount = droppedAddonFilterCount,
                finalPresentedCount = visibleItems.size
            )
            return OrganizedStreams(
                items = visibleItems,
                availableAddons = availableAddons,
                selectedAddonFilter = effectiveFilter,
                showAddonFilters = true,
                diagnostics = diagnostics
            )
        }

        val dedupeResult = if (flags.deduplicateGroupedStreamsEnabled) {
            deduplicate(filteredByDv)
        } else {
            DeduplicationResult(items = filteredByDv)
        }
        val groupedPreSortItems = dedupeResult.items
        val droppedDeduplicateCount = if (flags.deduplicateGroupedStreamsEnabled) {
            (filteredByDv.size - groupedPreSortItems.size).coerceAtLeast(0)
        } else {
            0
        }
        val groupedItems = groupedPreSortItems.sortedWith(
            compareBy<StreamCardModel> { cacheStateRank(it.parsed.isCached) }
                .thenByDescending { resolutionRank(it.parsed.resolution) }
                .thenByDescending { it.parsed.sizeBytes ?: -1L }
                .thenBy { it.stream.addonName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
        val diagnostics = StreamFilteringDiagnostics(
            inputCount = parsed.size,
            droppedEpisodeMismatchCount = droppedEpisodeMismatchCount,
            droppedMovieYearMismatchCount = droppedMovieYearMismatchCount,
            droppedWebDolbyVisionCount = droppedWebDolbyVisionCount,
            droppedDeduplicateCount = droppedDeduplicateCount,
            dedupeMixedCachedUncachedClusterCount = dedupeResult.mixedCachedUncachedClusterCount,
            dedupeCachedDroppedForUncachedClusterCount = dedupeResult.cachedDroppedForUncachedClusterCount,
            droppedAddonFilterCount = 0,
            finalPresentedCount = groupedItems.size
        )

        return OrganizedStreams(
            items = groupedItems,
            availableAddons = emptyList(),
            selectedAddonFilter = null,
            showAddonFilters = false,
            diagnostics = diagnostics
        )
    }

    private fun deduplicate(items: List<StreamCardModel>): DeduplicationResult {
        if (items.size < 2) return DeduplicationResult(items = items)

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

        var mixedCachedUncachedClusterCount = 0
        var cachedDroppedForUncachedClusterCount = 0

        val deduplicatedItems = clusters.values.flatMap { cluster ->
            if (cluster.size == 1) {
                cluster
            } else {
                val hasCachedCandidate = cluster.any {
                    it.parsed.transportKind == StreamTransportKind.CACHED && it.parsed.hasUsablePlaybackTarget
                }
                val hasUncachedCandidate = cluster.any {
                    it.parsed.transportKind == StreamTransportKind.UNCACHED && it.parsed.hasUsablePlaybackTarget
                }
                if (hasCachedCandidate && hasUncachedCandidate) {
                    mixedCachedUncachedClusterCount += 1
                }

                val selected = selectDeduplicatedCluster(cluster)
                val selectedHasCached = selected.any {
                    it.parsed.transportKind == StreamTransportKind.CACHED && it.parsed.hasUsablePlaybackTarget
                }
                val selectedHasUncached = selected.any {
                    it.parsed.transportKind == StreamTransportKind.UNCACHED && it.parsed.hasUsablePlaybackTarget
                }
                if (hasCachedCandidate && !selectedHasCached && selectedHasUncached) {
                    cachedDroppedForUncachedClusterCount += 1
                }

                selected
            }
        }

        return DeduplicationResult(
            items = deduplicatedItems,
            mixedCachedUncachedClusterCount = mixedCachedUncachedClusterCount,
            cachedDroppedForUncachedClusterCount = cachedDroppedForUncachedClusterCount
        )
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

        if (exactCollapsed.isEmpty()) return emptyList()
        return exactCollapsed
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
        val storyMarker = when {
            parsed.seasons.isNotEmpty() && parsed.episodes.isNotEmpty() ->
                "🎬 ${parsed.seasons.zip(parsed.episodes).joinToString(" • ") { (season, episode) ->
                    "S${season.toString().padStart(2, '0')} - E${episode.toString().padStart(2, '0')}"
                }}"
            parsed.year != null -> "🎬 ${parsed.year}"
            else -> null
        }
        val cacheMarker = when (parsed.isCached) {
            true -> "⚡️"
            false -> "❌️"
            null -> null
        }
        val resolutionLabel = resolutionLabel(parsed.resolution)
        return listOfNotNull(baseTitle, storyMarker, cacheMarker, resolutionLabel)
            .joinToString(" ")
            .trim()
    }

    private fun buildSubtitle(stream: Stream, parsed: ParsedStreamInfo, uniform: Boolean): String? {
        if (!uniform) {
            return stream.getDisplayDescription()?.takeIf { it != stream.getDisplayName() }
        }
        return null
    }

    private fun buildDetailLines(stream: Stream, parsed: ParsedStreamInfo, uniform: Boolean): List<String> {
        if (!uniform) {
            return emptyList()
        }

        val lines = mutableListOf<String>()

        parsed.durationMs?.takeIf { it > 0 }?.let { lines += "⏱️ ${formatDuration(it)}" }

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
            "DVDRip" -> "💾 DVD RIP"
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
        return when (tag) {
            "Atmos" -> "Atmos"
            "TrueHD" -> "TrueHD"
            "DTS-HD MA" -> "DTS-HD MA"
            "DTS-HD" -> "DTS-HD"
            "DTS-ES" -> "DTS-ES"
            "DTS" -> "DTS"
            "DD+" -> "DD+"
            "DD" -> "DD"
            "FLAC" -> "FLAC"
            "OPUS" -> "OPUS"
            "AAC" -> "AAC"
            else -> tag
        }
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
            "multi" -> "MULTI"
            "dual audio" -> "DUAL"
            else -> language.uppercase(Locale.US)
        }
    }

    private fun resolutionLabel(resolution: String?): String? {
        return when (resolution) {
            "2160p" -> "4K"
            "1440p" -> "2K"
            "1080p" -> "FHD"
            "720p" -> "HD"
            "576p", "480p" -> "SD"
            else -> null
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

    private fun cacheStateRank(isCached: Boolean?): Int {
        return when (isCached) {
            true -> 0
            null -> 1
            false -> 2
        }
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

private data class DeduplicationResult(
    val items: List<StreamCardModel>,
    val mixedCachedUncachedClusterCount: Int = 0,
    val cachedDroppedForUncachedClusterCount: Int = 0
)

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
        "BluRay REMUX" to tokenRegex("""(?:bd|br|b|uhd)?remux"""),
        "BluRay" to tokenRegex("""bluray|blu-ray|bdrip|brrip"""),
        "WEB-DL" to tokenRegex("""web[ ._-]?(dl)?(?![ ._-]?rip)"""),
        "WEBRip" to tokenRegex("""web[ ._-]?rip"""),
        "HDRip" to tokenRegex("""hd[ ._-]?rip|web[ ._-]?dl[ ._-]?rip"""),
        "HC HD-Rip" to tokenRegex("""hc|hc[ ._-]?hd[ ._-]?rip"""),
        "DVDRip" to tokenRegex("""dvd[ ._-]?rip"""),
        "HDTV" to tokenRegex("""hdtv""")
    )

    private val visualTagPatterns = linkedMapOf(
        "HDR10+" to tokenRegex("""hdr[ ._-]?10[ ._-]?(?:plus|\+|p)"""),
        "HDR10" to tokenRegex("""hdr[ ._-]?10(?![ ._-]?(?:plus|\+|p))"""),
        "HDR" to tokenRegex("""hdr(?![ ._-]?10)"""),
        "DV" to tokenRegex("""dovi|do?(?:lby)?[ ._-]?vi?(?:sion)?|dv"""),
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
        "DD+" to tokenRegex("""ddp(?:5[ ._-]?1|7[ ._-]?1|2[ ._-]?0)?|dd\+|e[ ._-]?ac[ ._-]?3|dolby[ ._-]?digital[ ._-]?plus"""),
        "DD" to tokenRegex("""(?<!e[ ._-]?)ac[ ._-]?3|dolby[ ._-]?digital|dd(?!p|\+)"""),
        "FLAC" to tokenRegex("""flac"""),
        "OPUS" to tokenRegex("""opus"""),
        "AAC" to tokenRegex("""aac""")
    )

    private val audioChannelPatterns = linkedMapOf(
        "7.1" to tokenRegex("""(?:d(?:olby)?[ ._-]?d(?:igital)?[ ._-]?(?:(?:p(?:lus)?|\+)a?)?)?7[ ._-]?1(?:ch)?"""),
        "6.1" to tokenRegex("""(?:d(?:olby)?[ ._-]?d(?:igital)?[ ._-]?(?:(?:p(?:lus)?|\+)a?)?)?6[ ._-]?1(?:ch)?"""),
        "5.1" to tokenRegex("""(?:d(?:olby)?[ ._-]?d(?:igital)?[ ._-]?(?:(?:p(?:lus)?|\+)a?)?)?5[ ._-]?1(?:ch)?"""),
        "2.0" to tokenRegex("""(?:d(?:olby)?[ ._-]?d(?:igital)?)?2[ ._-]?0(?:ch)?""")
    )

    private val encodePatterns = linkedMapOf(
        "HEVC" to tokenRegex("""hevc|x265|h265"""),
        "AVC" to tokenRegex("""avc|x264|h264"""),
        "AV1" to tokenRegex("""av1""")
    )

    private val languagePatterns = linkedMapOf(
        "English" to tokenRegex("""(?:english|eng)(?![ ._-]?sub(?:title)?s?)"""),
        "Dutch" to tokenRegex("""(?:dutch|nl)(?![ ._-]?sub)"""),
        "Spanish" to tokenRegex("""(?:spanish|spa|esp)(?![ ._-]?sub)"""),
        "French" to tokenRegex("""(?:french|fra|fre|fr|vf|vff|vfi|vf2|vfq|truefrench)(?![ ._-]?sub(?:title)?s?)"""),
        "German" to tokenRegex("""(?:german|deu|ger)(?![ ._-]?sub)"""),
        "Italian" to tokenRegex("""(?:italian|ita)(?![ ._-]?sub)"""),
        "Japanese" to tokenRegex("""(?:japanese|jpn|jap)(?![ ._-]?sub)"""),
        "Multi" to tokenRegex("""multi"""),
        "Dual Audio" to tokenRegex("""dual[ ._-]?(?:audio|lang(?:uage)?)""")
    )

    private val sizeRegex = Regex("""(?i)(\d+(?:\.\d+)?)\s?(KB|MB|GB|TB)""")
    private val durationRegex = Regex("""(?i)(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val seasonEpisodeRegex = Regex("""(?i)\bS(\d{1,2})(?:E(\d{1,2}))?\b""")
    private val altEpisodeRegex = Regex("""(?i)\b(\d{1,2})x(\d{1,2})\b""")
    private val yearTokenRegex = Regex("""^(?:19|20)\d{2}$""")
    private val releaseGroupRegex = Regex("""-([A-Za-z0-9][A-Za-z0-9._-]*)$""", RegexOption.IGNORE_CASE)
    private val cachedSymbols = listOf("⚡", "🚀", "cached")
    private val uncachedSymbols = listOf("⏳", "download", "uncached", "☁️")
    private val cachedDebridPlusMarkerRegex = Regex(
        pattern = """(?i)(?:^|[\s\[\(\|])(?:rd|pm|ad|dl|tb|ed|pk)\+(?:$|[\s\]\)\|])"""
    )

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
        val parseSource = listOfNotNull(filename, description, name, stream.title).joinToString(" ")

        val resolution = matchFirst(parseSource, resolutionPatterns)
        val quality = matchFirst(parseSource, qualityPatterns)
        val encode = matchFirst(parseSource, encodePatterns)
        val visualTags = matchMany(parseSource, visualTagPatterns)
        val audioTags = matchMany(parseSource, audioTagPatterns)
        val audioChannels = matchMany(parseSource, audioChannelPatterns)
        val languages = reorderLanguages(matchMany(parseSource, languagePatterns))
        val year = yearRegex.find(parseSource)?.value
        val filenameWithoutExtension = filename?.substringBeforeLast('.', filename)
        val releaseGroup = filenameWithoutExtension
            ?.let { releaseGroupRegex.find(it)?.groupValues?.getOrNull(1) }
        val title = deriveTitle(filename ?: stream.title ?: stream.name, year)
        val seasonsEpisodes = deriveSeasonEpisode(filename ?: parseSource)
        val serviceId = deriveServiceId(stream, description, filename)
        val cached = deriveCached(name, description)
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
        when (stream.addonParserPreset) {
            AddonParserPreset.STREMTHRU -> deriveStremThruFilename(description)?.let { return it }
            AddonParserPreset.WEBSTREAMR -> deriveWebStreamrFilename(stream, description)?.let { return it }
            AddonParserPreset.TORRENTIO,
            AddonParserPreset.GENERIC -> Unit
        }
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

    private fun deriveWebStreamrFilename(stream: Stream, description: String): String? {
        val firstLine = description.lines().firstOrNull()?.trim().orEmpty()
        val base = if (firstLine.contains("🔗")) null else firstLine.takeIf { it.isNotBlank() }
        val resolution = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(stream.name.orEmpty())?.value
        return listOfNotNull(base, resolution).joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    private fun deriveTitle(raw: String?, detectedYear: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutExtension = value.substringBeforeLast('.', value)
        val tokens = withoutExtension
            .replace(Regex("""[\[\](){}]"""), " ")
            .split(Regex("""[.\s_\-]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val stopIndex = tokens.indexOfFirst { token ->
            isTitleBoundaryToken(token, detectedYear)
        }.let { index -> if (index == -1) tokens.size else index }
        val titleTokens = tokens.take(stopIndex).ifEmpty { tokens.take(1) }
        return titleTokens.joinToString(" ") { token ->
            token.lowercase(Locale.US)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }.trim().takeIf { it.isNotBlank() }
    }

    private fun isTitleBoundaryToken(token: String, detectedYear: String?): Boolean {
        val normalized = token.trim().trim('(', ')', '[', ']', '{', '}')
        if (normalized.isBlank()) return false
        if (seasonEpisodeTokenRegex.matches(normalized)) return true
        if (seasonOnlyTokenRegex.matches(normalized)) return true
        if (episodeOnlyTokenRegex.matches(normalized)) return true
        if (yearTokenRegex.matches(normalized) && (detectedYear == null || normalized == detectedYear)) return true
        val text = " $normalized "
        return resolutionPatterns.values.any { it.containsMatchIn(text) } ||
            qualityPatterns.values.any { it.containsMatchIn(text) } ||
            encodePatterns.values.any { it.containsMatchIn(text) } ||
            visualTagPatterns.values.any { it.containsMatchIn(text) } ||
            audioTagPatterns.values.any { it.containsMatchIn(text) } ||
            audioChannelPatterns.values.any { it.containsMatchIn(text) } ||
            languagePatterns.values.any { it.containsMatchIn(text) }
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

    private fun deriveServiceId(stream: Stream, description: String, filename: String?): String? {
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
            aliases.any { alias ->
                aliasRegex(alias).containsMatchIn(lowered)
            }
        }?.key
    }

    private fun deriveCached(name: String, description: String): Boolean? {
        val lowered = listOf(name, description).joinToString(" ").lowercase(Locale.US)
        return when {
            uncachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> false
            cachedSymbols.any { lowered.contains(it.lowercase(Locale.US)) } -> true
            cachedDebridPlusMarkerRegex.containsMatchIn(lowered) -> true
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

    private fun reorderLanguages(languages: List<String>): List<String> {
        if (languages.isEmpty()) return languages
        return languages
            .distinct()
            .sortedWith(
                compareBy<String> { language ->
                    when (language) {
                        "Multi" -> 0
                        "Dual Audio" -> 1
                        else -> 2
                    }
                }.thenBy { it }
            )
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
