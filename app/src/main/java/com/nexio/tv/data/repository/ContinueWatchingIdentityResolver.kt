package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class ContinueWatchingIdentityResolver @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade?,
    private val streamFetchIdentityResolver: StreamFetchIdentityResolver
) {
    internal constructor() : this(
        metadataRouterFacade = null,
        streamFetchIdentityResolver = StreamFetchIdentityResolver()
    )

    suspend fun resolveOrFallback(input: RawContinueWatchingInput): ContinueWatchingRecord {
        val progress = input.progress
        val facade = metadataRouterFacade
            ?: return legacyFallback(input, IllegalStateException("identity resolver unavailable in test helper"))
        return runCatching {
            val previewStableIds = observedIds(progress)
            val request = MetadataRequest(
                contentId = progress.contentId.toProviderRequestContentId(),
                contentType = ContentType.fromString(progress.contentType),
                sourceContext = MetadataSourceContext(
                    itemType = progress.contentType,
                    previewStableIds = previewStableIds
                ),
                language = input.languageTag,
                seasonNumber = progress.season,
                depth = MetadataDepth.IDENTITY
            )
            val bundle = facade.resolveStableIdBundle(
                request,
                StableIdResolutionTrigger.CONTINUE_WATCHING,
                ContinueWatchingItemKeys.legacyParentKey(progress.contentType, progress.contentId)
            )
            val identity = bundle.toContentIdentity()
            val mediaKind = progress.toMediaKind(identity.providerIds)
            val episodeContext = progress.toEpisodeContextOrNull()
            val streamFetchIdentity = resolveStreamFetchIdentity(
                mediaKind = mediaKind,
                identity = identity,
                progress = progress,
                episodeContext = episodeContext
            )
            val warnings = if (mediaKind != MetadataMediaKind.UNKNOWN && streamFetchIdentity == null) {
                listOf("stream fetch identity unresolved")
            } else {
                emptyList()
            }
            val canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = mediaKind,
                canonicalParent = identity,
                season = episodeContext?.season,
                episode = episodeContext?.number,
                profileId = input.profileId
            )
            val parentKey = ContinueWatchingItemKeys.parentKey(mediaKind, identity, progress.contentId)
            val contentKey = episodeContext?.let {
                ContinueWatchingItemKeys.episodeKey(mediaKind, identity, it.season, it.number, progress.contentId)
            } ?: parentKey
            val resumeIdentity = progress.toResumeIdentity()

            ContinueWatchingRecord(
                profileId = input.profileId,
                parentId = parentKey,
                contentId = contentKey,
                provider = TrackingProvider.TRAKT,
                routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
                positionMs = progress.position,
                durationMs = progress.duration,
                episodeContext = episodeContext,
                clickTimeDisplayMetadata = null,
                source = progress.toRecordSource(),
                updatedAt = progress.lastWatched.coerceAtLeast(1L),
                canonicalKey = canonicalKey,
                displayIdentity = identity,
                streamFetchIdentity = streamFetchIdentity,
                trackingIdentity = progress.toTrackingIdentity(),
                resumeIdentities = listOf(resumeIdentity),
                primaryResumeLookupKey = resumeIdentity.lookupKey(),
                identityConfidence = if (streamFetchIdentity != null) IdentityConfidence.HIGH else IdentityConfidence.MEDIUM,
                identityWarnings = warnings,
                languageTag = input.languageTag
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            legacyFallback(input, error)
        }
    }

    private suspend fun resolveStreamFetchIdentity(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        progress: WatchProgress,
        episodeContext: ContinueWatchingRecord.EpisodeContext?
    ): StreamFetchIdentity? =
        when (mediaKind) {
            MetadataMediaKind.SERIES,
            MetadataMediaKind.ANIME -> episodeContext?.let {
                streamFetchIdentityResolver.resolveForEpisode(
                    canonicalIdentity = identity,
                    knownIds = identity.providerIds,
                    season = it.season,
                    episode = it.number,
                    sourceContext = StreamSourceContext(mediaKind, progress.videoId)
                )
            }

            MetadataMediaKind.MOVIE -> streamFetchIdentityResolver.resolveForMovie(
                canonicalIdentity = identity,
                knownIds = identity.providerIds,
                sourceContext = StreamSourceContext(mediaKind, progress.videoId)
            )

            MetadataMediaKind.UNKNOWN -> null
        }

    private fun legacyFallback(input: RawContinueWatchingInput, error: Throwable): ContinueWatchingRecord {
        val progress = input.progress
        val mediaKind = progress.toMediaKind(observedIds(progress))
        val emptyIdentity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds()
        )
        val episodeContext = progress.toSafeEpisodeContextOrNull()
        val parentKey = ContinueWatchingItemKeys.parentKey(
            mediaKind = mediaKind,
            identity = emptyIdentity,
            fallbackRawId = progress.contentId
        )
        val contentKey = episodeContext?.let {
            ContinueWatchingItemKeys.episodeKey(
                mediaKind = mediaKind,
                identity = emptyIdentity,
                season = it.season,
                episode = it.number,
                fallbackRawId = progress.contentId
            )
        } ?: parentKey
        val resumeIdentity = progress.toSafeResumeIdentity()

        return ContinueWatchingRecord(
            profileId = input.profileId,
            parentId = parentKey,
            contentId = contentKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = progress.position,
            durationMs = progress.duration,
            episodeContext = episodeContext,
            clickTimeDisplayMetadata = null,
            source = progress.toRecordSource(),
            updatedAt = progress.lastWatched.coerceAtLeast(1L),
            trackingIdentity = progress.toTrackingIdentity(),
            resumeIdentities = listOf(resumeIdentity),
            primaryResumeLookupKey = resumeIdentity.lookupKey(),
            identityConfidence = IdentityConfidence.LOW,
            identityWarnings = listOf("identity resolution failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"),
            languageTag = input.languageTag
        )
    }

    private fun StableIdBundle.toContentIdentity(): ContentIdentity {
        val observed = source.observedIds
        val providerIds = ProviderIds(
            imdb = sidecars.imdbId ?: observed.imdb,
            tmdb = canonical.tmdbMovieId ?: observed.tmdb,
            tvdb = canonical.tvdbSeriesId ?: observed.tvdb,
            trakt = observed.trakt,
            simkl = observed.simkl,
            kitsu = canonical.kitsuAnimeId ?: observed.kitsu,
            slug = observed.slug,
            mal = sidecars.malId ?: observed.mal,
            anilist = sidecars.anilistId ?: observed.anilist,
            anidb = sidecars.anidbId ?: observed.anidb
        )
        val canonicalProvider = canonical.provider()
        return ContentIdentity(
            canonicalProvider = canonicalProvider,
            canonicalId = canonical.idFor(canonicalProvider),
            providerIds = providerIds
        )
    }

    private fun CanonicalStableIds.provider(): ProviderId? =
        when {
            !kitsuAnimeId.isNullOrBlank() -> ProviderId.KITSU
            !tvdbSeriesId.isNullOrBlank() -> ProviderId.TVDB
            !tmdbMovieId.isNullOrBlank() -> ProviderId.TMDB
            else -> null
        }

    private fun CanonicalStableIds.idFor(provider: ProviderId?): String? =
        when (provider) {
            ProviderId.TVDB -> tvdbSeriesId
            ProviderId.TMDB -> tmdbMovieId
            ProviderId.KITSU -> kitsuAnimeId
            else -> null
        }

    private fun WatchProgress.toEpisodeContextOrNull(): ContinueWatchingRecord.EpisodeContext? =
        if (season != null || episode != null) {
            ContinueWatchingRecord.EpisodeContext(
                season = requireNotNull(season),
                number = requireNotNull(episode)
            )
        } else {
            null
        }

    private fun WatchProgress.toSafeEpisodeContextOrNull(): ContinueWatchingRecord.EpisodeContext? =
        if (season != null && episode != null && season > 0 && episode > 0) {
            ContinueWatchingRecord.EpisodeContext(
                season = season,
                number = episode
            )
        } else {
            null
        }

    private fun WatchProgress.toRecordSource(): ContinueWatchingRecord.Source =
        if (source == WatchProgress.SOURCE_LOCAL) {
            ContinueWatchingRecord.Source.LOCAL
        } else {
            ContinueWatchingRecord.Source.REMOTE
        }

    private fun WatchProgress.toMediaKind(observedIds: ProviderIds): MetadataMediaKind =
        when {
            contentType.trim().equals("anime", ignoreCase = true) || observedIds.hasAnimeProviderId() -> {
                MetadataMediaKind.ANIME
            }
            else -> contentType.toContentTypeMediaKind()
        }

    private fun String.toContentTypeMediaKind(): MetadataMediaKind =
        when (ContentType.fromString(this)) {
            ContentType.MOVIE -> MetadataMediaKind.MOVIE
            ContentType.SERIES,
            ContentType.TV -> MetadataMediaKind.SERIES
            else -> MetadataMediaKind.UNKNOWN
        }

    private fun ProviderIds.hasAnimeProviderId(): Boolean =
        !kitsu.isNullOrBlank() ||
            !mal.isNullOrBlank() ||
            !anilist.isNullOrBlank() ||
            !anidb.isNullOrBlank()

    private fun observedIds(progress: WatchProgress): ProviderIds {
        val fromContentId = progress.contentId.toProviderIds()
        val fromVideoId = progress.videoId.toProviderIds()
        return ProviderIds(
            imdb = fromContentId.imdb ?: fromVideoId.imdb,
            tmdb = fromContentId.tmdb ?: fromVideoId.tmdb,
            tvdb = fromContentId.tvdb ?: fromVideoId.tvdb,
            trakt = fromContentId.trakt ?: fromVideoId.trakt ?: (progress.traktShowId ?: progress.traktMovieId)?.toString(),
            simkl = fromContentId.simkl ?: fromVideoId.simkl,
            kitsu = fromContentId.kitsu ?: fromVideoId.kitsu,
            slug = fromContentId.slug ?: fromVideoId.slug,
            mal = fromContentId.mal ?: fromVideoId.mal,
            anilist = fromContentId.anilist ?: fromVideoId.anilist,
            anidb = fromContentId.anidb ?: fromVideoId.anidb
        )
    }
}
