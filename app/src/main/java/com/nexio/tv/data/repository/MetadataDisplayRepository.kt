package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationFieldTrace
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ProviderPlanRunResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.RatingResolution
import com.nexio.tv.core.metadata.router.resolver.RatingResolver
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.rankedTmdbTrailerPlaybackRefs
import com.nexio.tv.data.trailer.rankedTmdbTrailerYoutubeIds
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DetailAdvancedMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PeopleDisplay
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ReviewPaginationDisplayState
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDetailRatingDisplay
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.orDefault
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataDisplayRepository @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val detailRatingDisplayRepository: DetailRatingDisplayRepository,
    private val animeIdMappingService: AnimeIdMappingService
) {
    constructor(metadataRouterFacade: MetadataRouterFacade) : this(
        metadataRouterFacade = metadataRouterFacade,
        detailRatingDisplayRepository = DetailRatingDisplayRepository.noOp(),
        animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
    )

    constructor(
        metadataRouterFacade: MetadataRouterFacade,
        detailRatingDisplayRepository: DetailRatingDisplayRepository
    ) : this(
        metadataRouterFacade = metadataRouterFacade,
        detailRatingDisplayRepository = detailRatingDisplayRepository,
        animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
    )

    suspend fun resolveDetailDisplay(
        request: MetadataRequest,
        ratingContext: DetailRatingDisplayContext? = null
    ): ResolvedDetailDisplayDocument {
        val result = metadataRouterFacade.resolveRequest(request)
        val resolvedDocument = result.resolvedDocument
        val identity = result.toContentIdentity()
        val primaryTitleRatingCandidate = result.toPrimaryProviderRatingCandidate()
        val effectiveRatingContext = if (ratingContext != null) {
            ratingContext.copy(
                primaryProviderTitleRatingCandidate = ratingContext.primaryProviderTitleRatingCandidate ?: primaryTitleRatingCandidate,
                previewFallbackTitleRatingCandidate = ratingContext.previewFallbackTitleRatingCandidate
                    ?: ratingContext.meta.toPreviewFallbackRatingCandidate()
            )
        } else {
            resolvedDocument.toRatingDisplayContext(request, identity)
                ?.copy(primaryProviderTitleRatingCandidate = primaryTitleRatingCandidate)
        }
        val ratings = resolveRatings(effectiveRatingContext, identity)
        val cast = resolvedDocument.castMembers
        val crew = resolvedDocument.crewMembers
        val productionCompanies = resolvedDocument.productionCompanies
        val networks = resolvedDocument.networks
        val recommendations = result.providerRunResult.toFieldValues(ResolvedField.RECOMMENDATIONS)
            .flatMap(::recommendationsFrom)
        val reviews = result.providerRunResult.toFieldValues(ResolvedField.REVIEWS)
            .flatMap(::reviewsFrom)
        val reviewsPage = result.providerRunResult.toReviewsPage()
        val selectedLocalizationTrace = resolvedDocument.selectedLocalizationTrace()

        return ResolvedDetailDisplayDocument(
            route = result.route,
            identity = identity,
            fields = resolvedDocument.toResolvedDisplayFields(),
            artwork = result.displayArtwork(),
            rating = ratings?.titleRating,
            trailer = result.toTrailerDisplayState(),
            seasons = emptyList(),
            people = PeopleDisplay(cast = cast, crew = crew)
                .takeIf { it.cast.isNotEmpty() || it.crew.isNotEmpty() },
            reviews = reviews,
            recommendations = recommendations,
            collection = emptyList(),
            sourceTrace = resolvedDocument.toSourceTrace(),
            localization = LocalizationDisplayState(
                requestedLanguage = request.language,
                selectedLanguage = selectedLocalizationTrace?.selectedLanguage
                    ?: resolvedDocument.language
                    ?: request.language,
                fallbackReason = selectedLocalizationTrace?.localizationFallbackReason()
            ),
            advanced = resolvedDocument.toDetailAdvancedMetadata(
                productionCompanies = productionCompanies,
                networks = networks
            ),
            ratings = ratings ?: ResolvedDetailRatingDisplay(),
            reviewPagination = reviewsPage.toReviewPaginationDisplayState(result.route?.provider ?: MetadataPrimaryProvider.KITSU)
        )
    }

    private suspend fun resolveRatings(
        context: DetailRatingDisplayContext?,
        identity: ContentIdentity
    ): ResolvedDetailRatingDisplay? {
        val effectiveContext = context ?: return null
        return try {
            detailRatingDisplayRepository.resolve(
                meta = effectiveContext.meta,
                fallbackItemId = effectiveContext.fallbackItemId,
                fallbackItemType = effectiveContext.fallbackItemType,
                providerIds = identity.providerIds,
                episodesBySeason = effectiveContext.episodesBySeason,
                primaryProviderTitleRatingCandidate = effectiveContext.primaryProviderTitleRatingCandidate,
                previewFallbackTitleRatingCandidate = effectiveContext.previewFallbackTitleRatingCandidate
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            effectiveContext.toFallbackRatingDisplay()
        }
    }

    private fun MetadataResolutionResult.toContentIdentity(): ContentIdentity {
        val (provider, id) = resolvedDocument.canonicalId.parseCanonicalIdentity()

        return ContentIdentity(
            canonicalProvider = provider,
            canonicalId = id,
            providerIds = providerIdsFor(
                provider = provider,
                id = id,
                remoteIds = resolvedDocument.remoteIds,
                targetIds = route?.targetIds.orEmpty(),
                mediaKind = route?.mediaKind
            )
        )
    }

    private fun String?.parseCanonicalIdentity(): Pair<ProviderId?, String?> {
        val raw = this?.trim().orEmpty()
        val providerName = raw.substringBefore(':', missingDelimiterValue = "").trim()
        val id = raw.substringAfter(':', missingDelimiterValue = "").trim()
        if (providerName.isEmpty() || id.isEmpty()) return null to this

        val provider = ProviderId.entries.firstOrNull {
            it.name.equals(providerName, ignoreCase = true)
        }

        return provider to id
    }

    private fun providerIdsFor(
        provider: ProviderId?,
        id: String?,
        remoteIds: Map<String, Set<String>>,
        targetIds: Map<MetadataPrimaryProvider, String>,
        mediaKind: MetadataMediaKind?
    ): ProviderIds {
        val mergedRemoteAndTargets = ProviderIds(
            imdb = remoteIds.firstValueFor("imdb"),
            tmdb = remoteIds.firstValueFor("tmdb"),
            tvdb = remoteIds.firstValueFor("tvdb"),
            trakt = remoteIds.firstValueFor("trakt"),
            simkl = remoteIds.firstValueFor("simkl"),
            kitsu = remoteIds.firstValueFor("kitsu"),
            mal = remoteIds.firstValueFor("mal"),
            anilist = remoteIds.firstValueFor("anilist"),
            anidb = remoteIds.firstValueFor("anidb")
        ).mergeMissing(targetIds.toProviderIds())

        // Kitsu addons return only kitsu/mal/anilist — never imdb/tmdb/tvdb.
        // MetadataIdentityResolver only knows TMDB↔TVDB and IMDB→TVDB, so it
        // also never produces cross-IDs for kitsu sources. Consult the local
        // AnimeIdMappingService binary asset (mmap'd, no I/O cost) so that
        // subtitle providers downstream (Wyzie, OpenSubtitles) receive IMDB
        // instead of a kitsu id they cannot search by.
        val animeMapIds = animeMapProviderIdsForKitsu(
            provider = provider,
            id = id,
            kitsuFromRemote = mergedRemoteAndTargets.kitsu,
            mediaKind = mediaKind
        )
        val remoteProviderIds = mergedRemoteAndTargets.mergeMissing(animeMapIds)
        if (id.isNullOrBlank()) return remoteProviderIds

        return when (provider) {
            ProviderId.TMDB -> remoteProviderIds.copy(tmdb = remoteProviderIds.tmdb ?: id)
            ProviderId.TVDB -> remoteProviderIds.copy(tvdb = remoteProviderIds.tvdb ?: id)
            ProviderId.KITSU -> remoteProviderIds.copy(kitsu = remoteProviderIds.kitsu ?: id)
            ProviderId.IMDB -> remoteProviderIds.copy(imdb = remoteProviderIds.imdb ?: id)
            ProviderId.TRAKT -> remoteProviderIds.copy(trakt = remoteProviderIds.trakt ?: id)
            ProviderId.SIMKL -> remoteProviderIds.copy(simkl = remoteProviderIds.simkl ?: id)
            else -> remoteProviderIds
        }
    }

    private fun animeMapProviderIdsForKitsu(
        provider: ProviderId?,
        id: String?,
        kitsuFromRemote: String?,
        mediaKind: MetadataMediaKind?
    ): ProviderIds {
        val rawKitsu = when {
            provider == ProviderId.KITSU && !id.isNullOrBlank() -> id
            !kitsuFromRemote.isNullOrBlank() -> kitsuFromRemote
            else -> return ProviderIds()
        }
        // Strip any series:season:episode suffix — canonical ids for episode
        // surfaces arrive as "7442:1:1" but the anime map indexes by bare kitsu id.
        val kitsuId = rawKitsu.removePrefix("kitsu:")
            .substringBefore(':')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return ProviderIds()
        val resolvedKind = when (mediaKind) {
            MetadataMediaKind.MOVIE -> ContentMediaKind.MOVIE
            MetadataMediaKind.SERIES, MetadataMediaKind.ANIME -> ContentMediaKind.SERIES
            // The asset stores both movies and series; for unknown kind fall
            // back to SERIES (dominant shape) — resolveProviderIdsForKitsu
            // does the final matches(mediaKind) gate inside the service.
            MetadataMediaKind.UNKNOWN, null -> ContentMediaKind.SERIES
        }
        return animeIdMappingService.resolveProviderIdsForKitsu(kitsuId, resolvedKind)
    }

    private fun Map<MetadataPrimaryProvider, String>.toProviderIds(): ProviderIds =
        ProviderIds(
            imdb = firstValueFor(MetadataPrimaryProvider.IMDB),
            tmdb = firstValueFor(MetadataPrimaryProvider.TMDB),
            tvdb = firstValueFor(MetadataPrimaryProvider.TVDB),
            trakt = firstValueFor(MetadataPrimaryProvider.TRAKT),
            simkl = firstValueFor(MetadataPrimaryProvider.SIMKL),
            kitsu = firstValueFor(MetadataPrimaryProvider.KITSU)
        )

    private fun Map<MetadataPrimaryProvider, String>.firstValueFor(provider: MetadataPrimaryProvider): String? =
        entries.firstOrNull { it.key == provider }
            ?.value
            ?.normalizeProviderTargetId(provider)

    private fun String.normalizeProviderTargetId(provider: MetadataPrimaryProvider): String? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        val prefix = value.substringBefore(':', missingDelimiterValue = "")
        return (if (prefix.equals(provider.name, ignoreCase = true)) value.substringAfter(':') else value)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun ProviderIds.mergeMissing(fallback: ProviderIds): ProviderIds =
        copy(
            imdb = imdb ?: fallback.imdb,
            tmdb = tmdb ?: fallback.tmdb,
            tvdb = tvdb ?: fallback.tvdb,
            trakt = trakt ?: fallback.trakt,
            simkl = simkl ?: fallback.simkl,
            kitsu = kitsu ?: fallback.kitsu,
            mal = mal ?: fallback.mal,
            anilist = anilist ?: fallback.anilist,
            anidb = anidb ?: fallback.anidb
        )

    private fun Map<String, Set<String>>.firstValueFor(providerKey: String): String? =
        entries.firstOrNull { it.key.equals(providerKey, ignoreCase = true) }
            ?.value
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

    private fun ResolvedMetadataDocument.toResolvedDisplayFields(): ResolvedDisplayFields =
        ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = releaseDate.parseYearPrefix(),
            releaseDate = releaseDate,
            overview = overview,
            genres = genres,
            runtimeText = runtimeMinutes?.let { "$it min" }
        )

    private fun ResolvedMetadataDocument.toDetailAdvancedMetadata(
        productionCompanies: List<MetaCompany>,
        networks: List<MetaCompany>
    ): DetailAdvancedMetadata =
        DetailAdvancedMetadata(
            ageRating = ageRating,
            countries = countries,
            language = language,
            originalLanguage = originalLanguage,
            productionCompanies = productionCompanies,
            networks = networks,
            airsTime = airsTime,
            originalCountry = originalCountry,
            originalNetwork = originalNetwork,
            latestNetwork = latestNetwork,
            platformName = platformName
        )

    private fun ResolvedMetadataDocument.toRatingDisplayContext(
        request: MetadataRequest,
        identity: ContentIdentity
    ): DetailRatingDisplayContext? {
        val fallbackItemId = identity.providerIds.imdb
            ?: identity.canonicalId
            ?: request.contentId.trim().takeIf { it.isNotBlank() }
            ?: return null
        val itemType = request.sourceContext.itemType
            ?: request.contentType.toApiString()
        return DetailRatingDisplayContext(
            meta = toRatingMeta(
                id = fallbackItemId,
                contentType = request.contentType,
                itemType = itemType
            ),
            fallbackItemId = fallbackItemId,
            fallbackItemType = itemType,
            episodesBySeason = emptyMap()
        )
    }

    private fun ResolvedMetadataDocument.toRatingMeta(
        id: String,
        contentType: ContentType,
        itemType: String
    ): Meta =
        Meta(
            id = id,
            type = contentType,
            rawType = itemType,
            name = title ?: id,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = backdrop,
            logo = logo,
            description = overview,
            releaseInfo = releaseDate,
            imdbRating = when (val resolvedRating = rating) {
                is Number -> resolvedRating.toFloat()
                is String -> resolvedRating.toFloatOrNull()
                else -> null
            },
            genres = genres,
            runtime = runtimeMinutes?.toString(),
            director = emptyList(),
            cast = emptyList(),
            castMembers = castMembers,
            videos = emptyList(),
            productionCompanies = productionCompanies,
            networks = networks,
            ageRating = ageRating,
            country = countries.joinToString(", ").takeIf { it.isNotBlank() },
            awards = null,
            language = language,
            links = emptyList(),
            artwork = artwork.takeUnless { it.isEmpty() }
        )

    private fun String?.parseYearPrefix(): Int? {
        val value = this ?: return null
        if (value.length < 4) return null
        val prefix = value.take(4)
        if (!prefix.all { it.isDigit() }) return null

        return prefix.toIntOrNull()
    }

    private fun MetadataResolutionResult.displayArtwork(): ArtworkBundle {
        val fallbackArtwork = displayMetadata.artwork
        val merged = ArtworkBundle(
            poster = resolvedDocument.artwork.poster
                ?: resolvedDocument.poster.toLegacyArtworkRef(ArtworkType.POSTER)
                ?: fallbackArtwork?.poster,
            backdrop = resolvedDocument.artwork.backdrop
                ?: resolvedDocument.backdrop.toLegacyArtworkRef(ArtworkType.BACKDROP)
                ?: fallbackArtwork?.backdrop,
            logo = resolvedDocument.artwork.logo
                ?: resolvedDocument.logo.toLegacyArtworkRef(ArtworkType.LOGO)
                ?: fallbackArtwork?.logo,
            thumbnail = resolvedDocument.artwork.thumbnail ?: fallbackArtwork?.thumbnail
        )

        return merged.takeIf { it.hasArtwork() } ?: ArtworkBundle()
    }

    private fun ArtworkBundle.hasArtwork(): Boolean =
        poster != null || backdrop != null || logo != null || thumbnail != null

    private fun ArtworkBundle.isEmpty(): Boolean =
        poster == null && backdrop == null && logo == null && thumbnail == null

    private fun String?.toLegacyArtworkRef(imageType: ArtworkType): ArtworkDisplayRef? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ArtworkDisplayRef.LegacyString(
            value = value,
            imageType = imageType,
            trace = ArtworkTrace(
                sourceRole = ArtworkSourceRole.LEGACY_STRING_COMPAT.name,
                reason = "resolved_metadata_string_artwork"
            ),
            displayHints = ArtworkDisplayHints()
        )
    }

    private fun MetadataResolutionResult.toPrimaryProviderRatingCandidate(): RatingCandidate? {
        val value = when (val rating = resolvedDocument.rating) {
            is Number -> rating.toDouble()
            is String -> rating.toDoubleOrNull()
            else -> return null
        } ?: return null

        return RatingCandidate(
            value = value,
            sourceRole = SourceRole.PRIMARY_PROVIDER,
            sourceProvider = ratingSource().name,
            confidence = Confidence.MEDIUM
        )
    }

    private fun Meta.toPreviewFallbackRatingCandidate(): RatingCandidate? =
        imdbRating?.takeIf { it > 0.0f }?.let { value ->
            RatingCandidate(
                value = value.toDouble(),
                sourceRole = SourceRole.PREVIEW_FALLBACK,
                sourceProvider = ratingSource.orDefault().name,
                confidence = Confidence.LOW
            )
        }

    private fun DetailRatingDisplayContext.toFallbackRatingDisplay(): ResolvedDetailRatingDisplay {
        val resolvedTitleRating = RatingResolver.resolveTitleRating(
            listOfNotNull(
                primaryProviderTitleRatingCandidate,
                previewFallbackTitleRatingCandidate
            )
        )?.toTitleRating()

        return ResolvedDetailRatingDisplay(titleRating = resolvedTitleRating)
    }

    private fun RatingResolution.toTitleRating(): TitleRating =
        TitleRating(
            value = value,
            source = sourceProvider.toTitleRatingSource() ?: TitleRatingSource.IMDB
        )

    private fun MetadataResolutionResult.ratingSource(): TitleRatingSource =
        resolvedDocument.sourceProviders[ResolvedField.RATING].toTitleRatingSource()
            ?: displayMetadata.ratingSource.orDefault()

    private fun MetadataResolutionResult.toTrailerDisplayState(): TrailerDisplayState {
        val fallbackTrailerYtIds = providerRunResult.toFieldValues(ResolvedField.TRAILERS)
            .flatMap(::youtubeIdsFromTrailerValue)
            .distinct()
        val resolution = metadataRouterFacade.resolveTrailer(
            TrailerResolveRequest(
                itemKey = route?.parentId ?: resolvedDocument.canonicalId ?: displayMetadata.title.orEmpty(),
                title = displayMetadata.title.orEmpty(),
                year = displayMetadata.releaseInfo?.take(4)?.takeIf { it.length == 4 },
                stableIds = ProviderIds(),
                fallbackYtIds = fallbackTrailerYtIds,
                surface = TrailerSurface.DETAIL,
                type = route?.sourceContext?.itemType,
                contentId = route?.parentId ?: resolvedDocument.canonicalId,
                providerCandidates = providerRunResult.toFieldValues(ResolvedField.TRAILERS)
                    .flatMap(::trailerPlaybackRefsFrom)
            )
        )
        return TrailerDisplayState(
            fallbackTrailerYtIds = listOfNotNull((resolution.selected as? TrailerPlaybackRef.YouTubeId)?.videoId)
                .ifEmpty { fallbackTrailerYtIds.takeIf { resolution.selected != null }.orEmpty() },
            selectedPlaybackRef = resolution.selected,
            availabilityReason = resolution.availability.reason,
            surface = TrailerSurface.DETAIL.name.lowercase(),
            resolverSource = resolvedDocument.sourceProviders[ResolvedField.TRAILERS],
            lastResolvedAtMs = null
        )
    }

    private fun String?.toTitleRatingSource(): TitleRatingSource? =
        when (val source = this?.trim()?.uppercase()) {
            "TMDB" -> TitleRatingSource.TMDB
            else -> TitleRatingSource.TMDB.takeIf { source?.startsWith("TMDB_") == true }
        }

    private fun ResolvedMetadataDocument.toSourceTrace(): List<HydratedHomeFieldTrace> {
        val fields = (sourceRoles.keys + sourceProviders.keys).distinctBy(ResolvedField::name)

        return fields.map { field ->
            HydratedHomeFieldTrace(
                field = field.name,
                selectedProvider = sourceProviders[field].orEmpty(),
                sourceRole = sourceRoles[field]?.name.orEmpty()
            )
        }
    }

    private fun ResolvedMetadataDocument.selectedLocalizationTrace(): MetadataLocalizationFieldTrace? {
        val visibleTextTraces = listOfNotNull(
            localization[ResolvedField.OVERVIEW],
            localization[ResolvedField.TITLE]
        )

        return visibleTextTraces.firstOrNull { trace ->
            trace.fallbackRole != MetadataLocalizationFallbackRole.LOCALIZED
        } ?: visibleTextTraces.firstOrNull()
            ?: localization.values.firstOrNull()
    }

    private fun MetadataLocalizationFieldTrace.localizationFallbackReason(): String? =
        takeIf { fallbackRole != MetadataLocalizationFallbackRole.LOCALIZED }
            ?.toFallbackReason()

    private fun MetadataLocalizationFieldTrace.toFallbackReason(): String =
        "${field.name} fell back to $selectedLanguage via ${selectedProvider.name} (${fallbackRole.name})"

    private fun ReviewsPage?.toReviewPaginationDisplayState(
        provider: MetadataPrimaryProvider
    ): ReviewPaginationDisplayState {
        val page = this ?: return ReviewPaginationDisplayState()
        return ReviewPaginationDisplayState(
            provider = provider.toProviderId(),
            hasMore = page.hasMore,
            nextPage = page.nextPage,
            pageSize = 20
        )
    }

    private fun MetadataPrimaryProvider.toProviderId(): ProviderId? =
        when (this) {
            MetadataPrimaryProvider.TMDB -> ProviderId.TMDB
            MetadataPrimaryProvider.TVDB -> ProviderId.TVDB
            MetadataPrimaryProvider.KITSU -> ProviderId.KITSU
            MetadataPrimaryProvider.IMDB -> ProviderId.IMDB
            MetadataPrimaryProvider.TRAKT -> ProviderId.TRAKT
            MetadataPrimaryProvider.SIMKL -> ProviderId.SIMKL
            else -> null
        }

    private fun ProviderPlanRunResult?.toFieldValues(field: ResolvedField): List<Any?> =
        this?.stepResults
            ?.mapNotNull { stepResult -> stepResult.candidate?.fields?.get(field)?.value }
            .orEmpty()

    private fun reviewsFrom(value: Any?): List<MetaReview> =
        when (value) {
            is ReviewsPage -> value.reviews
            is MetaReview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<MetaReview>()
            else -> emptyList()
        }

    private fun recommendationsFrom(value: Any?): List<MetaPreview> =
        when (value) {
            is MetaPreview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<MetaPreview>()
            else -> emptyList()
        }

    private fun reviewsPageFrom(value: Any?): ReviewsPage? =
        value as? ReviewsPage

    private fun ProviderPlanRunResult?.toReviewsPage(): ReviewsPage? =
        toFieldValues(ResolvedField.REVIEWS)
            .firstNotNullOfOrNull(::reviewsPageFrom)

    private fun youtubeIdsFromTrailerValue(value: Any?): List<String> =
        when (value) {
            is TrailerResolutionResult.External -> listOfNotNull(value.url.youtubeIdFromUrl())
            is TrailerResolutionResult.Playback -> emptyList()
            is TrailerPlaybackSource -> emptyList()
            is TmdbVideoResult -> rankedTmdbTrailerYoutubeIds(listOf(value))
            is String -> listOfNotNull(value.youtubeIdFromUrl() ?: value.trim().takeIf { it.isNotBlank() })
            is Collection<*> -> {
                val tmdbVideos = value.filterIsInstance<TmdbVideoResult>()
                if (tmdbVideos.size == value.size) {
                    rankedTmdbTrailerYoutubeIds(tmdbVideos)
                } else {
                    value.flatMap(::youtubeIdsFromTrailerValue)
                }
            }
            else -> emptyList()
        }

    private fun trailerPlaybackRefsFrom(value: Any?): List<TrailerPlaybackRef> =
        when (value) {
            is TrailerResolutionResult.External -> listOf(TrailerPlaybackRef.ExternalUrl(value.url))
            is TrailerResolutionResult.Playback -> listOf(value.source.toTrailerPlaybackRef())
            is TrailerPlaybackSource -> listOf(value.toTrailerPlaybackRef())
            is TmdbVideoResult -> rankedTmdbTrailerPlaybackRefs(listOf(value))
            is String -> listOfNotNull(
                (value.youtubeIdFromUrl() ?: value.trim().takeIf { it.isNotBlank() })
                    ?.let(TrailerPlaybackRef::YouTubeId)
            )
            is Collection<*> -> {
                val tmdbVideos = value.filterIsInstance<TmdbVideoResult>()
                if (tmdbVideos.size == value.size) {
                    rankedTmdbTrailerPlaybackRefs(tmdbVideos)
                } else {
                    value.flatMap(::trailerPlaybackRefsFrom)
                }
            }
            else -> emptyList()
        }

    private fun TrailerPlaybackSource.toTrailerPlaybackRef(): TrailerPlaybackRef =
        TrailerPlaybackRef.InAppSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            userAgent = userAgent
        )

    private fun String.youtubeIdFromUrl(): String? {
        val value = trim()
        if (value.isBlank()) return null
        val watchId = value.substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
        if (watchId != null) return watchId
        return value.substringAfter("youtu.be/", missingDelimiterValue = "")
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
    }
}
