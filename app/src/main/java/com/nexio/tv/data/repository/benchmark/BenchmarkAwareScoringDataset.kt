package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson
import android.content.ContextWrapper
import com.nexio.tv.core.player.DolbyVisionAutoPlayGate
import com.nexio.tv.core.player.DolbyVisionProfileProbe
import com.nexio.tv.core.player.DolbyVisionProfileProbeResult
import com.nexio.tv.core.player.DolbyVisionProfileProbeStatus
import com.nexio.tv.core.stream.AioStrictStreamParser
import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.PreservedStreamMetadata
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import java.nio.file.Files
import java.nio.file.Path

data class BenchmarkAwareScoringDataset(
    val datasetVersion: Int = 1,
    val scenarios: List<BenchmarkAwareScoringScenario> = emptyList()
) {
    fun toJson(gson: Gson = Gson()): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String, gson: Gson = Gson()): BenchmarkAwareScoringDataset {
            return gson.fromJson(json, BenchmarkAwareScoringDataset::class.java)
        }

        fun fromPath(path: Path, gson: Gson = Gson()): BenchmarkAwareScoringDataset {
            return fromJson(String(Files.readAllBytes(path)), gson)
        }
    }
}

data class BenchmarkAwareScoringScenario(
    val id: String,
    val name: String,
    val request: BenchmarkAwareScoringScenarioRequest,
    val benchmarks: List<DebridBenchmarkResult>,
    val streams: List<BenchmarkAwareScoringScenarioStream>,
    val expectedWinnerStreamKey: String? = null,
    val acceptableWinnerKeys: List<String> = emptyList(),
    val autoplayExpectation: BenchmarkAwareScoringScenarioAutoPlayExpectation? = null,
    val preferredPairs: List<BenchmarkAwareScoringPreferencePair> = emptyList(),
    val notes: String? = null
) {
    fun toShadowRequestContext(): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = request.requestId ?: id,
            videoId = request.videoId,
            contentType = request.contentType,
            title = request.title,
            season = request.season,
            episode = request.episode,
            runtimeMinutes = request.runtimeMinutes
        )
    }

    fun toBenchmarkSessionMap(): Map<DebridBenchmarkProvider, DebridBenchmarkResult> {
        return benchmarks.associateBy { it.provider }
    }

    fun toStreamCards(): List<StreamCardModel> = streams.map { it.toStreamCardModel() }

    fun resolveAutoPlayWinnerStreamKey(
        selected: ShadowStreamDecision?,
        selectedNonDolbyVisionFallback: ShadowStreamDecision?,
        winners: List<ShadowStreamDecision> = emptyList()
    ): String? {
        val expectation = autoplayExpectation ?: return selected?.streamKey
        val cards = toStreamCards()
        val selectedItem = selected?.streamKey?.let { streamKey ->
            val baseKey = streamKey.substringBefore('|')
            cards.firstOrNull { it.stream.wrappedOriginalStreamKey == streamKey || it.stream.wrappedOriginalStreamKey == baseKey || it.parsed.exactDuplicateKey == streamKey || it.parsed.exactDuplicateKey == baseKey }
        } ?: return null
        val fallbackCandidateItems = winners.mapNotNull { decision ->
            val baseKey = decision.streamKey.substringBefore('|')
            cards.firstOrNull { it.stream.wrappedOriginalStreamKey == decision.streamKey || it.stream.wrappedOriginalStreamKey == baseKey || it.parsed.exactDuplicateKey == decision.streamKey || it.parsed.exactDuplicateKey == baseKey }
        }
        val urlToStreamKey = cards.associate { it.stream.getStreamUrl() to it.stream.wrappedOriginalStreamKey }
        val gate = DolbyVisionAutoPlayGate(
            probe = object : DolbyVisionProfileProbe {
                override suspend fun probe(
                    context: android.content.Context,
                    url: String,
                    headers: Map<String, String>?,
                    filename: String?
                ): DolbyVisionProfileProbeResult {
                    val streamKey = urlToStreamKey[url] ?: selectedItem.stream.wrappedOriginalStreamKey
                    return expectation.probeByStreamKey[streamKey]
                        ?.toProbeResult()
                        ?: DolbyVisionProfileProbeResult.unknown()
                }
            }
        )
        val resolved = kotlinx.coroutines.runBlocking {
            gate.resolve(
                context = ContextWrapper(null),
                playbackInfo = selectedItem.toPlaybackInfo(request, fallbackCandidateItems),
                autoPlay = true,
                displaySupportsDolbyVision = expectation.displaySupportsDolbyVision
            )
        }
        return resolved.playbackInfo.streamKey
    }

    fun expectedResolvedWinnerStreamKey(): String? {
        return autoplayExpectation?.expectedResolvedWinnerStreamKey ?: expectedWinnerStreamKey
    }

    fun acceptableResolvedWinnerKeys(): List<String> {
        return autoplayExpectation?.acceptableResolvedWinnerKeys?.ifEmpty {
            autoplayExpectation.expectedResolvedWinnerStreamKey?.let(::listOf).orEmpty()
        } ?: acceptableWinnerKeys
    }
}

data class BenchmarkAwareScoringScenarioRequest(
    val requestId: String? = null,
    val videoId: String,
    val contentType: String,
    val title: String? = null,
    val originalLanguage: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null
)

data class BenchmarkAwareScoringPreferencePair(
    val preferredStreamKey: String,
    val otherStreamKey: String,
    val reason: String? = null
)

data class BenchmarkAwareScoringScenarioAutoPlayExpectation(
    val displaySupportsDolbyVision: Boolean = false,
    val expectedResolvedWinnerStreamKey: String? = null,
    val acceptableResolvedWinnerKeys: List<String> = emptyList(),
    val probeByStreamKey: Map<String, BenchmarkAwareScoringScenarioProbeOutcome> = emptyMap()
)

data class BenchmarkAwareScoringScenarioProbeOutcome(
    val status: String,
    val profileLabel: String? = null,
    val profileNumber: Int? = null,
    val error: String? = null
) {
    fun toProbeResult(): DolbyVisionProfileProbeResult {
        return when (DolbyVisionProfileProbeStatus.valueOf(status)) {
            DolbyVisionProfileProbeStatus.DETECTED -> DolbyVisionProfileProbeResult.detected(
                profileLabel = profileLabel ?: "dv_profile_${profileNumber ?: 0}",
                profileNumber = profileNumber ?: 0
            )
            DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> DolbyVisionProfileProbeResult.notDolbyVision()
            DolbyVisionProfileProbeStatus.UNKNOWN -> DolbyVisionProfileProbeResult.unknown()
            DolbyVisionProfileProbeStatus.FAILED -> DolbyVisionProfileProbeResult.failed(error)
        }
    }
}

data class BenchmarkAwareScoringScenarioStream(
    val streamKey: String,
    val providerId: String,
    val resolution: String? = null,
    val quality: String? = null,
    val encode: String? = null,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val useRealParser: Boolean = false,
    val visualTags: List<String>? = null,
    val audioTags: List<String>? = null,
    val audioChannels: List<String>? = null,
    val languages: List<String>? = null,
    val title: String? = "Example",
    val description: String? = "Example description",
    val filename: String? = null,
    val releaseGroup: String? = "GROUP",
    val addonName: String? = "Dataset Addon"
) {
    fun toStreamCardModel(): StreamCardModel {
        val effectiveFilename = filename ?: "$streamKey.mkv"
        val effectiveTitle = title?.takeIf { it.isNotBlank() } ?: "Example"
        val effectiveDescription = description?.takeIf { it.isNotBlank() } ?: "Example description"
        val effectiveAddonName = addonName?.takeIf { it.isNotBlank() } ?: "Dataset Addon"
        val effectiveAudioChannels = audioChannels?.takeIf { it.isNotEmpty() }
        val effectiveLanguages = languages?.takeIf { it.isNotEmpty() }
        val stream = Stream(
            name = effectiveFilename,
            title = effectiveTitle,
            description = effectiveDescription,
            url = "https://example.com/$streamKey.mkv",
            ytId = null,
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = sizeBytes,
                filename = effectiveFilename
            ),
            addonName = effectiveAddonName,
            addonLogo = null,
            wrappedProviderId = providerId,
            wrappedOriginalStreamKey = streamKey
        )
        val effectiveVisualTags = visualTags?.toList()
        val effectiveAudioTags = audioTags?.toList()
        val parserSeed = stream.copy(
            name = stream.name ?: effectiveFilename,
            title = effectiveTitle,
            description = effectiveDescription.ifBlank { effectiveFilename },
            behaviorHints = stream.behaviorHints?.copy(filename = effectiveFilename, videoSize = sizeBytes)
        )
        val parserParsed = if (useRealParser) AioStrictStreamParser.parse(parserSeed) else null
        val parsed = ParsedStreamInfo(
            stream = stream,
            title = parserParsed?.title ?: effectiveTitle,
            filename = effectiveFilename,
            sizeBytes = sizeBytes,
            resolution = resolution ?: parserParsed?.resolution,
            quality = quality ?: parserParsed?.quality,
            encode = encode ?: parserParsed?.encode,
            visualTags = if (!effectiveVisualTags.isNullOrEmpty()) effectiveVisualTags else parserParsed?.visualTags ?: emptyList(),
            audioTags = if (!effectiveAudioTags.isNullOrEmpty()) effectiveAudioTags else parserParsed?.audioTags ?: emptyList(),
            audioChannels = effectiveAudioChannels ?: parserParsed?.audioChannels ?: emptyList(),
            languages = effectiveLanguages ?: parserParsed?.languages ?: emptyList(),
            subtitles = parserParsed?.subtitles ?: emptyList(),
            year = parserParsed?.year ?: "2026",
            seasons = parserParsed?.seasons ?: emptyList(),
            episodes = parserParsed?.episodes ?: emptyList(),
            seasonPack = parserParsed?.seasonPack == true,
            releaseGroup = releaseGroup ?: parserParsed?.releaseGroup,
            container = parserParsed?.container,
            extension = parserParsed?.extension,
            network = parserParsed?.network,
            date = parserParsed?.date,
            editions = parserParsed?.editions ?: emptyList(),
            subbed = parserParsed?.subbed == true,
            dubbed = parserParsed?.dubbed == true,
            regraded = parserParsed?.regraded == true,
            repack = parserParsed?.repack == true,
            uncensored = parserParsed?.uncensored == true,
            unrated = parserParsed?.unrated == true,
            upscaled = parserParsed?.upscaled == true,
            serviceId = providerId,
            isCached = true,
            durationMs = durationMs,
            bitrate = parserParsed?.bitrate,
            indexer = parserParsed?.indexer,
            seeders = parserParsed?.seeders,
            age = parserParsed?.age,
            ageHours = parserParsed?.ageHours,
            isPrivate = parserParsed?.isPrivate == true,
            message = parserParsed?.message,
            transportKind = StreamTransportKind.CACHED,
            preservedMetadata = parserParsed?.preservedMetadata ?: PreservedStreamMetadata(
                filename = effectiveFilename,
                videoSize = sizeBytes
            )
        )
        return StreamCardModel(
            stream = stream,
            parsed = parsed,
            title = effectiveTitle,
            subtitle = null,
            detailLines = emptyList()
        )
    }
}

private fun StreamCardModel.toPlaybackInfo(
    request: BenchmarkAwareScoringScenarioRequest,
    fallbackCandidates: List<StreamCardModel>
): StreamPlaybackInfo {
    val selectedKey = stream.wrappedOriginalStreamKey
    val isDolbyVision = parsed.visualTags.any { tag ->
        val normalized = tag.lowercase()
        normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
    }
    return StreamPlaybackInfo(
        url = stream.getStreamUrl(),
        title = request.title ?: title,
        streamName = stream.name ?: stream.addonName,
        year = parsed.year,
        isExternal = false,
        isTorrent = stream.infoHash != null,
        infoHash = stream.infoHash,
        ytId = stream.ytId,
        headers = stream.behaviorHints?.proxyHeaders?.request,
        contentId = request.videoId,
        contentType = request.contentType,
        contentName = request.title,
        originalLanguage = request.originalLanguage,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = request.videoId,
        season = request.season,
        episode = request.episode,
        episodeTitle = null,
        bingeGroup = null,
        rememberedAudioLanguage = null,
        rememberedAudioName = null,
        filename = stream.behaviorHints?.filename,
        videoHash = stream.behaviorHints?.videoHash,
        videoSize = stream.behaviorHints?.videoSize,
        streamKey = selectedKey,
        isWebDl = parsed.quality.equals("WEB-DL", ignoreCase = true),
        isDolbyVisionCandidate = isDolbyVision,
        autoPlayFallbackCandidates = fallbackCandidates
            .filter { it.stream.wrappedOriginalStreamKey != selectedKey }
            .map { candidate ->
                AutoPlayStreamAlternative(
                    streamKey = candidate.stream.wrappedOriginalStreamKey,
                    url = candidate.stream.getStreamUrl(),
                    streamName = candidate.stream.name ?: candidate.stream.addonName,
                    headers = candidate.stream.behaviorHints?.proxyHeaders?.request,
                    filename = candidate.stream.behaviorHints?.filename,
                    videoHash = candidate.stream.behaviorHints?.videoHash,
                    videoSize = candidate.stream.behaviorHints?.videoSize,
                    isWebDl = candidate.parsed.quality.equals("WEB-DL", ignoreCase = true),
                    isDolbyVisionCandidate = candidate.parsed.visualTags.any { tag ->
                        val normalized = tag.lowercase()
                        normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
                    }
                )
            }
    )
}
