package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.anime.ContentMediaKind
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
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeCharacter
import com.nexio.tv.core.tvdb.KitsuAdvancedProductionCompany
import com.nexio.tv.core.tvdb.KitsuAdvancedRelatedTitle
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DetailAdvancedMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
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
    private val detailSecondaryDisplayRepository: DetailSecondaryDisplayRepository
) {
    constructor(metadataRouterFacade: MetadataRouterFacade) : this(
        metadataRouterFacade = metadataRouterFacade,
        detailRatingDisplayRepository = DetailRatingDisplayRepository.noOp(),
        detailSecondaryDisplayRepository = DetailSecondaryDisplayRepository.noOp()
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
        val kitsuBridge = result.fetchKitsuBridgeDetail(request, identity)
        val cast = kitsuBridge?.castMembers?.takeIf { it.isNotEmpty() } ?: resolvedDocument.castMembers
        val crew = resolvedDocument.crewMembers
        val productionCompanies = kitsuBridge?.productionCompanies
            ?.takeIf { it.isNotEmpty() }
            ?: resolvedDocument.productionCompanies
        val networks = resolvedDocument.networks
        val recommendations = result.providerRunResult.toFieldValues(ResolvedField.RECOMMENDATIONS)
            .flatMap(::recommendationsFrom)
            .ifEmpty { kitsuBridge?.recommendations.orEmpty() }
        val reviews = result.providerRunResult.toFieldValues(ResolvedField.REVIEWS)
            .flatMap(::reviewsFrom)
            .ifEmpty { kitsuBridge?.reviewsPage?.reviews.orEmpty() }

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
                selectedLanguage = resolvedDocument.language ?: request.language,
                fallbackReason = resolvedDocument.localizationFallbackReason()
            ),
            advanced = resolvedDocument.toDetailAdvancedMetadata(
                productionCompanies = productionCompanies,
                networks = networks
            ),
            ratings = ratings ?: ResolvedDetailRatingDisplay(),
            reviewPagination = kitsuBridge?.reviewsPage.toReviewPaginationDisplayState(MetadataPrimaryProvider.KITSU)
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
            ResolvedDetailRatingDisplay()
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
                targetIds = route?.targetIds.orEmpty()
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
        targetIds: Map<MetadataPrimaryProvider, String>
    ): ProviderIds {
        val remoteProviderIds = ProviderIds(
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

    private fun MetadataResolutionResult.ratingSource(): TitleRatingSource =
        resolvedDocument.sourceProviders[ResolvedField.RATING].toTitleRatingSource()
            ?: displayMetadata.ratingSource.orDefault()

    private fun MetadataResolutionResult.toTrailerDisplayState(): TrailerDisplayState {
        val fallbackTrailerYtIds = providerRunResult.toFieldValues(ResolvedField.TRAILERS)
            .flatMap(::youtubeIdsFromTrailerValue)
            .distinct()
        return TrailerDisplayState(
            fallbackTrailerYtIds = fallbackTrailerYtIds,
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

    private fun ResolvedMetadataDocument.localizationFallbackReason(): String? =
        localization.values
            .firstOrNull { it.fallbackRole != MetadataLocalizationFallbackRole.LOCALIZED }
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

    private fun reviewsFrom(value: Any?): List<com.nexio.tv.domain.model.MetaReview> =
        when (value) {
            is com.nexio.tv.domain.model.MetaReview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<com.nexio.tv.domain.model.MetaReview>()
            else -> emptyList()
        }

    private data class KitsuBridgeDetail(
        val castMembers: List<MetaCastMember>,
        val productionCompanies: List<MetaCompany>,
        val recommendations: List<MetaPreview>,
        val reviewsPage: ReviewsPage?
    )

    private suspend fun MetadataResolutionResult.fetchKitsuBridgeDetail(
        request: MetadataRequest,
        identity: ContentIdentity
    ): KitsuBridgeDetail? {
        if (route?.provider != MetadataPrimaryProvider.KITSU) return null
        val rawId = identity.providerIds.kitsu
            ?.takeIf { it.isNotBlank() }
            ?.let { "kitsu:$it" }
            ?: request.sourceContext.previewSourceItemId?.takeIf { it.isNotBlank() }
            ?: request.contentId
        val mediaKind = when (request.contentType) {
            ContentType.MOVIE -> ContentMediaKind.MOVIE
            else -> ContentMediaKind.SERIES
        }
        val advanced = try {
            detailSecondaryDisplayRepository.fetchKitsuAdvancedDetail(
                rawId = rawId,
                mediaKind = mediaKind,
                preferredLanguageCode = resolvedDocument.language ?: request.language
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val reviewPage = request.pagination?.page ?: 1
        val reviewLimit = request.pagination?.limit ?: 20
        val reviewsPage = try {
            detailSecondaryDisplayRepository.fetchKitsuReviews(
                rawId = rawId,
                mediaKind = mediaKind,
                page = reviewPage,
                limit = reviewLimit
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (advanced == null && reviewsPage?.reviews.orEmpty().isEmpty()) return null

        return KitsuBridgeDetail(
            castMembers = advanced?.characters.orEmpty().map { it.toCastMember() },
            productionCompanies = advanced?.productionCompanies.orEmpty().map { it.toCompany() },
            recommendations = advanced?.relatedTitles.orEmpty().map { it.toPreview() },
            reviewsPage = reviewsPage
        )
    }

    private fun KitsuAdvancedAnimeCharacter.toCastMember(): MetaCastMember =
        MetaCastMember(
            name = characterName,
            character = actorName ?: role,
            photo = characterImage,
            provider = "kitsu",
            providerId = characterId
        )

    private fun KitsuAdvancedProductionCompany.toCompany(): MetaCompany =
        MetaCompany(
            name = producerName,
            kind = MetaCompanyKind.COMPANY,
            provider = "kitsu",
            providerId = producerId
        )

    private fun KitsuAdvancedRelatedTitle.toPreview(): MetaPreview =
        MetaPreview(
            id = "kitsu:$mediaId",
            type = if (mediaType.equals("movie", ignoreCase = true)) ContentType.MOVIE else ContentType.SERIES,
            name = title,
            poster = displayPoster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = synopsis,
            releaseInfo = releaseInfo,
            imdbRating = null,
            genres = emptyList()
        )

    private fun recommendationsFrom(value: Any?): List<com.nexio.tv.domain.model.MetaPreview> =
        when (value) {
            is com.nexio.tv.domain.model.MetaPreview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<com.nexio.tv.domain.model.MetaPreview>()
            else -> emptyList()
        }

    private fun youtubeIdsFromTrailerValue(value: Any?): List<String> =
        when (value) {
            is TrailerResolutionResult.External -> listOfNotNull(value.url.youtubeIdFromUrl())
            is TrailerResolutionResult.Playback -> emptyList()
            is TrailerPlaybackSource -> emptyList()
            is TmdbVideoResult -> listOfNotNull(value.key?.trim()?.takeIf { it.isNotBlank() })
            is String -> listOfNotNull(value.youtubeIdFromUrl() ?: value.trim().takeIf { it.isNotBlank() })
            is Collection<*> -> value.flatMap(::youtubeIdsFromTrailerValue)
            else -> emptyList()
        }

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
