package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress

data class ContinueWatchingCanonicalKey(
    val mediaKind: MetadataMediaKind,
    val canonicalParent: ContentIdentity,
    val season: Int?,
    val episode: Int?,
    val profileId: Int
) {
    init {
        require(profileId > 0) { "profileId must be positive" }
        require((season == null) == (episode == null)) { "season and episode must both be present or both absent" }
        require(season == null || season > 0) { "season must be positive when present" }
        require(episode == null || episode > 0) { "episode must be positive when present" }
    }

    fun stableKey(): String {
        require(canonicalParent.hasGloballyStableIdentity()) {
            "canonicalParent must include a globally stable provider id"
        }

        val parentKey = ContinueWatchingItemKeys.parentKey(
            mediaKind = mediaKind,
            identity = canonicalParent,
            fallbackRawId = canonicalParent.canonicalId.orEmpty()
        )
        val episodeSuffix = if (season != null && episode != null) ":s${season}e${episode}" else ""
        return "profile:$profileId:$parentKey$episodeSuffix"
    }
}

data class ResumeIdentity(
    val source: ContinueWatchingSource,
    val contentId: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long?,
    val progressPercent: Float?,
    val lastWatchedMs: Long
) {
    init {
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        require(positionMs >= 0L) { "positionMs must be non-negative" }
        require(durationMs == null || durationMs >= 0L) { "durationMs must be null or non-negative" }
        require((season == null) == (episode == null)) { "season and episode must both be present or both absent" }
        require(season == null || season > 0) { "season must be positive when present" }
        require(episode == null || episode > 0) { "episode must be positive when present" }
        require(lastWatchedMs >= 0L) { "lastWatchedMs must be non-negative" }
        require(progressPercent == null || progressPercent in 0f..100f) {
            "progressPercent must be null or between 0 and 100"
        }
    }

    val isEpisode: Boolean
        get() = season != null && episode != null

    fun lookupKey(): String {
        val segments = listOf(contentId, videoId, season?.toString() ?: "null", episode?.toString() ?: "null")
        if (segments.none { it.contains('|') }) {
            return segments.joinToString(separator = "|")
        }
        return segments.joinToString(separator = "|", prefix = "lp:") { segment ->
            "${segment.length}:$segment"
        }
    }
}

data class StreamFetchIdentity(
    val contentId: String,
    val videoId: String,
    val idScheme: StreamIdScheme,
    val confidence: IdentityConfidence,
    val trace: List<String>
) {
    init {
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        require(trace.all { it.isNotBlank() }) { "trace entries must not be blank" }
    }
}

data class TrackingIdentity(
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val providerIds: ProviderIds = ProviderIds()
) {
    init {
        require(traktShowId == null || traktShowId > 0) { "traktShowId must be positive when present" }
        require(traktEpisodeId == null || traktEpisodeId > 0) { "traktEpisodeId must be positive when present" }
        require(traktPlaybackId == null || traktPlaybackId > 0L) { "traktPlaybackId must be positive when present" }
        require(traktMovieId == null || traktMovieId > 0) { "traktMovieId must be positive when present" }
    }
}

data class RawContinueWatchingInput(
    val profileId: Int,
    val progress: WatchProgress,
    val languageTag: String
) {
    init {
        require(profileId > 0) { "profileId must be positive" }
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
    }
}

data class ContinueWatchingIdentityResolution(
    val canonicalKey: ContinueWatchingCanonicalKey,
    val displayIdentity: ContentIdentity,
    val streamFetchIdentity: StreamFetchIdentity?,
    val trackingIdentity: TrackingIdentity?,
    val confidence: IdentityConfidence,
    val warnings: List<String>
) {
    init {
        require(warnings.all { it.isNotBlank() }) { "warnings must not contain blank entries" }
    }
}

enum class ContinueWatchingSource {
    LOCAL,
    TRAKT_PLAYBACK,
    TRAKT_HISTORY,
    TRAKT_SHOW_PROGRESS,
    SYNTHETIC
}

enum class StreamIdScheme {
    IMDB_MOVIE,
    IMDB_EPISODE,
    UNRESOLVED
}

enum class IdentityConfidence {
    HIGH,
    MEDIUM,
    LOW
}

fun WatchProgress.toResumeIdentity(): ResumeIdentity =
    toResumeIdentity(season = season, episode = episode)

internal fun WatchProgress.toSafeResumeIdentity(): ResumeIdentity {
    val safeEpisode = safeEpisodeCoordinate()
    return toResumeIdentity(season = safeEpisode?.first, episode = safeEpisode?.second)
}

private fun WatchProgress.toResumeIdentity(season: Int?, episode: Int?): ResumeIdentity =
    ResumeIdentity(
        source = source.toContinueWatchingSource(),
        contentId = contentId,
        videoId = videoId,
        season = season,
        episode = episode,
        positionMs = position,
        durationMs = duration,
        progressPercent = progressPercent,
        lastWatchedMs = lastWatched
    )

private fun WatchProgress.safeEpisodeCoordinate(): Pair<Int, Int>? =
    if (season != null && episode != null && season > 0 && episode > 0) {
        season to episode
    } else {
        null
    }

fun WatchProgress.toTrackingIdentity(): TrackingIdentity? {
    if (traktPlaybackId == null &&
        traktMovieId == null &&
        traktShowId == null &&
        traktEpisodeId == null
    ) {
        return null
    }

    return TrackingIdentity(
        traktShowId = traktShowId,
        traktEpisodeId = traktEpisodeId,
        traktPlaybackId = traktPlaybackId,
        traktMovieId = traktMovieId,
        providerIds = contentId.toProviderIds()
            .withFallbackProviderIds(videoId.toProviderIds())
            .withDerivedTraktId(
                derivedTraktId = (traktShowId ?: traktMovieId)?.toString()
            )
    )
}

private fun String.toContinueWatchingSource(): ContinueWatchingSource =
    when (this) {
        WatchProgress.SOURCE_LOCAL -> ContinueWatchingSource.LOCAL
        WatchProgress.SOURCE_TRAKT_PLAYBACK -> ContinueWatchingSource.TRAKT_PLAYBACK
        WatchProgress.SOURCE_TRAKT_HISTORY -> ContinueWatchingSource.TRAKT_HISTORY
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> ContinueWatchingSource.TRAKT_SHOW_PROGRESS
        else -> ContinueWatchingSource.SYNTHETIC
    }

private fun ContentIdentity.hasGloballyStableIdentity(): Boolean =
    canonicalProvider?.isGloballyStable() == true && !canonicalId.isNullOrBlank() ||
        providerIds.hasGloballyStableId()

private fun ProviderIds.hasGloballyStableId(): Boolean =
    !tvdb.isNullOrBlank() ||
        !tmdb.isNullOrBlank() ||
        !kitsu.isNullOrBlank() ||
        !imdb.isNullOrBlank() ||
        !trakt.isNullOrBlank() ||
        !simkl.isNullOrBlank() ||
        !mal.isNullOrBlank() ||
        !anilist.isNullOrBlank() ||
        !anidb.isNullOrBlank()

private fun ProviderId.isGloballyStable(): Boolean =
    when (this) {
        ProviderId.TVDB,
        ProviderId.TMDB,
        ProviderId.KITSU,
        ProviderId.IMDB,
        ProviderId.TRAKT,
        ProviderId.SIMKL -> true

        ProviderId.ADDON,
        ProviderId.MDBLIST -> false
    }

internal fun String.toProviderIds(): ProviderIds {
    val rawId = trim()
    rawId.toCanonicalImdbTitleIdOrNull()?.let { imdbId ->
        return ProviderIds(imdb = imdbId)
    }
    val rawImdbParentId = rawId.substringBefore(':')
    rawImdbParentId.toCanonicalImdbTitleIdOrNull()?.let { imdbId ->
        return ProviderIds(imdb = imdbId)
    }

    val segments = rawId.split(':').map { it.trim() }
    val prefix = segments.firstOrNull()?.lowercase().orEmpty()
    val valueSegmentIndex = if (segments.getOrNull(1)?.lowercase() in providerTypeSegments) 2 else 1
    val value = segments.getOrNull(valueSegmentIndex)?.takeIf { it.isNotEmpty() }

    return when (prefix) {
        "imdb" -> ProviderIds(imdb = value?.toCanonicalImdbTitleIdOrNull())
        "tmdb" -> ProviderIds(tmdb = value)
        "tvdb" -> ProviderIds(tvdb = value)
        "kitsu" -> ProviderIds(kitsu = value)
        "trakt" -> ProviderIds(trakt = value)
        "simkl" -> ProviderIds(simkl = value)
        "mal" -> ProviderIds(mal = value)
        "anilist" -> ProviderIds(anilist = value)
        "anidb" -> ProviderIds(anidb = value)
        else -> ProviderIds()
    }
}

internal fun String.toProviderRequestContentId(): String {
    val rawId = trim()
    val segments = rawId.split(':').map { it.trim() }
    val prefix = segments.firstOrNull()?.lowercase().orEmpty()
    val typedValue = segments.getOrNull(2)?.takeIf {
        segments.getOrNull(1)?.lowercase() in providerTypeSegments && it.isNotEmpty()
    }

    return when {
        prefix in normalizedRequestProviders && typedValue != null -> "$prefix:$typedValue"
        else -> rawId
    }
}

private fun ProviderIds.withFallbackProviderIds(fallback: ProviderIds): ProviderIds =
    ProviderIds(
        imdb = imdb ?: fallback.imdb,
        tmdb = tmdb ?: fallback.tmdb,
        tvdb = tvdb ?: fallback.tvdb,
        trakt = trakt ?: fallback.trakt,
        simkl = simkl ?: fallback.simkl,
        kitsu = kitsu ?: fallback.kitsu,
        slug = slug ?: fallback.slug,
        mal = mal ?: fallback.mal,
        anilist = anilist ?: fallback.anilist,
        anidb = anidb ?: fallback.anidb
    )

private fun ProviderIds.withDerivedTraktId(derivedTraktId: String?): ProviderIds =
    copy(trakt = trakt ?: derivedTraktId)

private val normalizedRequestProviders = setOf("tmdb", "tvdb", "trakt", "kitsu", "mal", "anilist", "anidb")

private val providerTypeSegments = setOf("anime", "movie", "series", "show", "tv")

private fun String.toCanonicalImdbTitleIdOrNull(): String? =
    trim().lowercase().takeIf { it.matches(Regex("^tt\\d+$")) }
