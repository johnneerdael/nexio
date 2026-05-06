package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PersonDetail
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level TMDB secondary adapter used by ProviderPlanRunner adapters only.
 *
 * Production UI, ViewModel, home, screensaver, player, and facade code must not inject this class.
 * Provider-plan adapters may call it while executing IntegrationRuntime-governed provider steps.
 */
@Singleton
class MetadataSecondaryRepository @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val kitsuMetadataService: KitsuMetadataService
) {
    suspend fun fetchTmdbEnrichment(
        tmdbId: String,
        contentType: ContentType
    ): TmdbEnrichment? =
        tmdbMetadataService.fetchEnrichment(tmdbId = tmdbId, contentType = contentType)

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType
    ): List<MetaPreview> =
        tmdbMetadataService.fetchMoreLikeThis(tmdbId = tmdbId, contentType = contentType)

    suspend fun fetchReviews(
        tmdbId: String,
        contentType: ContentType
    ): List<MetaReview> =
        tmdbMetadataService.fetchReviews(tmdbId = tmdbId, contentType = contentType)

    suspend fun fetchMovieCollection(collectionId: Int): List<MetaPreview> =
        tmdbMetadataService.fetchMovieCollection(collectionId = collectionId)

    suspend fun fetchSeasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        language: String?
    ): List<TmdbEpisode> =
        tmdbMetadataService.fetchSeasonEpisodes(
            tvId = tvId,
            seasonNumber = seasonNumber,
            language = language
        )

    suspend fun fetchKitsuAdvancedDetail(
        rawId: String,
        mediaKind: ContentMediaKind,
        preferredLanguageCode: String?
    ): KitsuAdvancedAnimeDetail? =
        kitsuMetadataService.fetchAdvancedDetail(
            rawId = rawId,
            mediaKind = mediaKind,
            preferredLanguageCode = preferredLanguageCode
        )

    suspend fun fetchKitsuReviews(
        rawId: String,
        mediaKind: ContentMediaKind,
        page: Int,
        limit: Int
    ): ReviewsPage =
        kitsuMetadataService.fetchReviews(
            rawId = rawId,
            mediaKind = mediaKind,
            page = page,
            limit = limit
        )

    suspend fun findPersonIdByExactName(name: String): Int? =
        tmdbMetadataService.findPersonIdByExactName(name)

    suspend fun findCompanyIdByExactName(name: String): Int? =
        tmdbMetadataService.findCompanyIdByExactName(name)

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean
    ): PersonDetail? =
        tmdbMetadataService.fetchPersonDetail(
            personId = personId,
            preferCrewCredits = preferCrewCredits
        )
}
