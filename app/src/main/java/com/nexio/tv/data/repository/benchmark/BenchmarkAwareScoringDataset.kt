package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson
import com.nexio.tv.core.stream.AioStrictStreamParser
import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.PreservedStreamMetadata
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
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
}

data class BenchmarkAwareScoringScenarioRequest(
    val requestId: String? = null,
    val videoId: String,
    val contentType: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null
)

data class BenchmarkAwareScoringPreferencePair(
    val preferredStreamKey: String,
    val otherStreamKey: String,
    val reason: String? = null
)

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
