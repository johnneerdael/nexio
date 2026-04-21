package com.nexio.tv.core.tvdb

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TvdbEpisodeOrder
import com.nexio.tv.domain.model.TvdbSeasonOrderContext

data class TvMetadataRequest(
    val contentId: String,
    val fallbackContentId: String? = null,
    val contentType: ContentType,
    val language: String? = null,
    val seasonNumbers: List<Int> = emptyList()
)

data class TvMetadataDecision<T>(
    val provider: TvProvider,
    val reason: TvMetadataDecisionReason,
    val value: T?,
    val diagnostics: List<TvMetadataDiagnosticEvent> = emptyList()
)

data class TvMetadataEnrichment(
    val seriesTvdbId: Int?,
    val localizedTitle: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val backdrop: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val rating: Double? = null,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val runtimeMinutes: Int? = null,
    val ageRating: String? = null,
    val countries: List<String>? = null,
    val language: String? = null,
    val airsDays: Map<String, Boolean> = emptyMap(),
    val airsTime: String? = null,
    val averageRuntimeMinutes: Int? = null,
    val originalCountry: String? = null,
    val originalNetwork: String? = null,
    val latestNetwork: String? = null,
    val platformName: String? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val aliases: List<String> = emptyList(),
    val contentRatings: List<String> = emptyList(),
    val remoteIds: Map<String, Set<String>> = emptyMap(),
    val seasonOrderContext: TvdbSeasonOrderContext? = null,
    val castMembers: List<MetaCastMember> = emptyList(),
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList()
)

data class TvEpisodeMetadata(
    val providerEpisodeId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val absoluteNumber: Int? = null,
    val airsAfterSeason: Int? = null,
    val airsBeforeSeason: Int? = null,
    val airsBeforeEpisode: Int? = null,
    val linkedMovieTvdbId: Int? = null,
    val finaleType: String? = null,
    val tvdbEpisodeOrder: TvdbEpisodeOrder? = null
)

data class TvSeasonEpisode(
    val episodeNumber: Int?,
    val airDate: String?,
    val metadata: TvEpisodeMetadata
)

data class KitsuAdvancedAnimeDetail(
    val characters: List<KitsuAdvancedAnimeCharacter> = emptyList(),
    val staff: List<KitsuAdvancedAnimeStaffMember> = emptyList(),
    val relatedTitles: List<KitsuAdvancedRelatedTitle> = emptyList(),
    val productionCompanies: List<KitsuAdvancedProductionCompany> = emptyList(),
    val franchiseIds: Set<String> = emptySet()
)

data class KitsuAdvancedAnimeCharacter(
    val characterId: String,
    val characterName: String,
    val role: String? = null,
    val characterImage: String? = null,
    val actorId: String? = null,
    val actorName: String? = null,
    val actorImage: String? = null,
    val language: String? = null,
    val featured: Boolean = false
)

data class KitsuAdvancedAnimeStaffMember(
    val personId: String,
    val personName: String,
    val role: String? = null
)

data class KitsuAdvancedRelatedTitle(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val synopsis: String? = null,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val relationKind: String
)

data class KitsuAdvancedProductionCompany(
    val producerId: String,
    val producerName: String,
    val role: String? = null
)

data class TvdbTrailerCandidate(
    val url: String,
    val name: String? = null,
    val type: String? = null,
    val seasonNumber: Int? = null
) {
    val isRecap: Boolean
        get() = name?.contains("recap", ignoreCase = true) == true ||
            type?.contains("recap", ignoreCase = true) == true
}

sealed interface TvdbTrailerUsability {
    data class YouTube(val url: String, val videoId: String) : TvdbTrailerUsability
    data class External(val url: String) : TvdbTrailerUsability
    data class DirectMedia(val url: String) : TvdbTrailerUsability
    data class Unusable(val reason: String, val url: String?) : TvdbTrailerUsability
}
