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
    val localization: LocalizationDisplayState
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
