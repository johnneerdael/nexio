package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataRoute

@Immutable
data class ResolvedDetailDisplayDocument(
    val route: MetadataRoute?,
    val identity: ContentIdentity,
    val fields: ResolvedDisplayFields,
    val artwork: ArtworkBundle,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState,
    val seasons: List<SeasonDisplay>,
    val people: PeopleDisplay?,
    val reviews: List<MetaReview>,
    val recommendations: List<MetaPreview>,
    val collection: List<MetaPreview>,
    val sourceTrace: List<HydratedHomeFieldTrace>,
    val localization: LocalizationDisplayState,
    val advanced: DetailAdvancedMetadata = DetailAdvancedMetadata(),
    val ratings: ResolvedDetailRatingDisplay = ResolvedDetailRatingDisplay(),
    val reviewPagination: ReviewPaginationDisplayState = ReviewPaginationDisplayState()
)

@Immutable
data class ContentIdentity(
    val canonicalProvider: ProviderId?,
    val canonicalId: String?,
    val providerIds: ProviderIds
)

@Immutable
data class SeasonDisplay(
    val seasonNumber: Int,
    val title: String?,
    val overview: String?,
    val episodes: List<SeasonEpisodeMark>
)

@Immutable
data class PeopleDisplay(
    val cast: List<MetaCastMember>,
    val crew: List<MetaCastMember>
)

@Immutable
data class LocalizationDisplayState(
    val requestedLanguage: String?,
    val selectedLanguage: String?,
    val fallbackReason: String?
)

@Immutable
data class DetailAdvancedMetadata(
    val ageRating: String? = null,
    val countries: List<String> = emptyList(),
    val language: String? = null,
    val originalLanguage: String? = null,
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val airsTime: String? = null,
    val originalCountry: String? = null,
    val originalNetwork: String? = null,
    val latestNetwork: String? = null,
    val platformName: String? = null
)

@Immutable
data class ResolvedDetailRatingDisplay(
    val titleRating: TitleRating? = null,
    val mdbListRatings: MDBListRatings? = null,
    val showMdbListImdb: Boolean = false,
    val episodeRatings: Map<Pair<Int, Int>, ResolvedEpisodeRating> = emptyMap(),
    val episodeRatingsError: String? = null
)

@Immutable
data class ResolvedEpisodeRating(
    val value: Double,
    val source: String
)

@Immutable
data class ReviewPaginationDisplayState(
    val provider: ProviderId? = null,
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
    val pageSize: Int = 20
)
