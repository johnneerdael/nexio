package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdBundleRequest
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
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
    private val streamFetchIdentityResolver: StreamFetchIdentityResolver,
    // Optional so the internal test constructor stays parameter-free. When null,
    // crossBridgeIdentity() is a no-op; the resolver just returns whatever the
    // facade gave it (legacy behavior).
    private val stableIdBundleResolver: StableIdBundleResolver? = null
) {
    internal constructor() : this(
        metadataRouterFacade = null,
        streamFetchIdentityResolver = StreamFetchIdentityResolver(),
        stableIdBundleResolver = null
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
            val initialIdentity = bundle.toContentIdentity()
            val mediaKind = progress.toMediaKind(initialIdentity.providerIds)
            // Cross-bridge step. The facade's route is determined by the call's contentType
            // + provider; a Trakt CW record arrives as IMDB and a local CW record arrives as
            // TMDB, and the facade's single-direction resolve doesn't always bridge to the
            // OTHER namespace. For dedup to work in ContinueWatchingMerger, both records
            // need to share a provider key. Without bridging here, the same movie appears
            // as two entries (e.g. Roast of Kevin Hart: trakt knows imdb+trakt, local knows
            // tmdb-only — disjoint sets → no bucket overlap → no dedup).
            val identity = crossBridgeIdentity(
                identity = initialIdentity,
                mediaKind = mediaKind,
                contentType = progress.contentType,
                contentId = progress.contentId,
            )
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
                provider = providerForSource(progress.source),
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
                languageTag = input.languageTag,
                idBundle = identity.providerIds.toContinueWatchingIdBundle(episodeContext),
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
            provider = providerForSource(progress.source),
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
            languageTag = input.languageTag,
            idBundle = observedIds(progress).toContinueWatchingIdBundle(episodeContext),
        )
    }

    private fun providerForSource(source: String): TrackingProvider = when (source) {
        WatchProgress.SOURCE_SIMKL_PLAYBACK,
        WatchProgress.SOURCE_SIMKL_HISTORY -> TrackingProvider.SIMKL
        WatchProgress.SOURCE_TRAKT_PLAYBACK,
        WatchProgress.SOURCE_TRAKT_HISTORY,
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> TrackingProvider.TRAKT
        else -> TrackingProvider.TRAKT  // SOURCE_LOCAL and unknown sources stay TRAKT for legacy display
    }

    private fun ProviderIds.toContinueWatchingIdBundle(
        episodeContext: ContinueWatchingRecord.EpisodeContext?,
    ): ContinueWatchingIdBundle = ContinueWatchingIdBundle(
        imdb = imdb?.takeIf { it.isNotBlank() },
        tmdb = tmdb?.takeIf { it.isNotBlank() },
        tvdb = tvdb?.takeIf { it.isNotBlank() },
        kitsu = kitsu?.takeIf { it.isNotBlank() },
        mal = mal?.takeIf { it.isNotBlank() },
        anilist = anilist?.takeIf { it.isNotBlank() },
        anidb = anidb?.takeIf { it.isNotBlank() },
        trakt = trakt?.takeIf { it.isNotBlank() },
        simkl = simkl?.takeIf { it.isNotBlank() },
        season = episodeContext?.season,
        episode = episodeContext?.number,
    )

    /**
     * Fill any missing cross-provider IDs on [identity] by consulting
     * [stableIdBundleResolver] with an explicit routeProvider for the OTHER
     * namespace. Movies bridge TMDB ↔ IMDB; series bridge TVDB ↔ IMDB.
     * Cache-backed (IdMappingStore checked first); a cache miss falls through
     * to the Lookup interface which may make a network call — bounded by the
     * number of records being resolved (≤ snapshot size). Returns the original
     * identity reference on no-op so downstream reference-equality holds.
     */
    private suspend fun crossBridgeIdentity(
        identity: ContentIdentity,
        mediaKind: MetadataMediaKind,
        contentType: String,
        contentId: String,
    ): ContentIdentity {
        val resolver = stableIdBundleResolver ?: return identity
        val current = identity.providerIds
        // Compute which directions are worth attempting. Only bridge between the
        // primary canonical providers for each media kind to keep the call count
        // bounded; anime is left to the existing kitsu/mal/anidb pipeline.
        val passes = mutableListOf<MetadataPrimaryProvider>()
        when (mediaKind) {
            MetadataMediaKind.MOVIE -> {
                if (current.imdb.isNullOrBlank() && !current.tmdb.isNullOrBlank()) {
                    passes += MetadataPrimaryProvider.TMDB // tmdb → imdb bridge
                }
                if (current.tmdb.isNullOrBlank() && !current.imdb.isNullOrBlank()) {
                    passes += MetadataPrimaryProvider.TMDB // imdb → tmdb bridge (same routeProvider; resolver fills both)
                }
            }
            MetadataMediaKind.SERIES -> {
                if (current.imdb.isNullOrBlank() && !current.tvdb.isNullOrBlank()) {
                    passes += MetadataPrimaryProvider.TVDB
                }
                if (current.tvdb.isNullOrBlank() && !current.imdb.isNullOrBlank()) {
                    passes += MetadataPrimaryProvider.TVDB
                }
            }
            else -> Unit
        }
        if (passes.isEmpty()) return identity

        var providerIds = current
        passes.distinct().forEach { route ->
            val sourceProvider = when (route) {
                MetadataPrimaryProvider.TMDB -> ProviderId.TMDB
                MetadataPrimaryProvider.TVDB -> ProviderId.TVDB
                else -> null
            }
            val bundle = runCatching {
                resolver.resolve(
                    StableIdBundleRequest(
                        itemKey = "$contentType:$contentId",
                        itemType = ContentType.fromString(contentType),
                        routeProvider = route,
                        knownIds = providerIds,
                        sourceProvider = sourceProvider,
                        sourceItemId = contentId,
                        railId = null,
                        trigger = StableIdResolutionTrigger.CONTINUE_WATCHING
                    )
                )
            }.getOrNull() ?: return@forEach
            providerIds = providerIds.copy(
                imdb = providerIds.imdb ?: bundle.sidecars.imdbId,
                tmdb = providerIds.tmdb ?: bundle.canonical.tmdbMovieId,
                tvdb = providerIds.tvdb ?: bundle.canonical.tvdbSeriesId,
                kitsu = providerIds.kitsu ?: bundle.canonical.kitsuAnimeId,
            )
        }
        if (providerIds == current) return identity
        // Strengthen canonicalProvider/canonicalId once we have a richer set.
        val strongerCanonicalProvider = when {
            !providerIds.tvdb.isNullOrBlank() && identity.canonicalProvider != ProviderId.TVDB -> ProviderId.TVDB
            !providerIds.tmdb.isNullOrBlank() && identity.canonicalProvider == null -> ProviderId.TMDB
            else -> identity.canonicalProvider
        }
        val strongerCanonicalId = when (strongerCanonicalProvider) {
            ProviderId.TVDB -> providerIds.tvdb
            ProviderId.TMDB -> providerIds.tmdb
            ProviderId.KITSU -> providerIds.kitsu
            else -> identity.canonicalId
        } ?: identity.canonicalId
        return identity.copy(
            canonicalProvider = strongerCanonicalProvider,
            canonicalId = strongerCanonicalId,
            providerIds = providerIds,
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
