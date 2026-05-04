package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.toLegacyArtworkString

@Immutable
data class Meta(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val genres: List<String>,
    val runtime: String?,
    val director: List<String>,
    val writer: List<String> = emptyList(),
    val cast: List<String>,
    val castMembers: List<MetaCastMember> = emptyList(),
    val videos: List<Video>,
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val ageRating: String? = null,
    val country: String?,
    val awards: String?,
    val language: String?,
    val links: List<MetaLink>,
    val trailerYtIds: List<String> = emptyList(),
    val tvdbSeasonOrderContext: TvdbSeasonOrderContext? = null,
    val posterProviderTag: String? = null,
    val localReleaseInfo: String? = null,
    @Transient
    val artwork: ArtworkBundle? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val displayPoster: String?
        get() = artwork?.poster.toLegacyArtworkString() ?: poster

    val displayBackground: String?
        get() = artwork?.backdrop.toLegacyArtworkString() ?: background

    val displayLogo: String?
        get() = artwork?.logo.toLegacyArtworkString() ?: logo
}

@Immutable
data class MetaCastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null,
    val tvdbPeopleId: Int? = null,
    val provider: String? = null,
    val providerId: String? = null
)

@Immutable
data class MetaCompany(
    val tmdbId: Int? = null,
    val name: String,
    val logo: String? = null,
    val kind: MetaCompanyKind = MetaCompanyKind.COMPANY,
    val provider: String? = null,
    val providerId: String? = null
)

@Immutable
enum class MetaCompanyKind {
    COMPANY,
    NETWORK
}

@Immutable
data class TvdbSeasonTypeSummary(
    val id: Int?,
    val name: String?,
    val type: String?
)

@Immutable
data class TvdbSeasonOrderContext(
    val defaultSeasonTypeId: Int?,
    val defaultSeasonTypeName: String?,
    val defaultSeasonTypeSlug: String?,
    val availableSeasonTypes: List<TvdbSeasonTypeSummary> = emptyList(),
    val alternateOrderPreservedButNotApplied: Boolean = false
)

@Immutable
data class TvdbEpisodeOrder(
    val defaultSeason: Int?,
    val defaultEpisode: Int?,
    val absoluteNumber: Int?,
    val airsAfterSeason: Int?,
    val airsBeforeSeason: Int?,
    val airsBeforeEpisode: Int?,
    val alternateOrderPreservedButNotApplied: Boolean = false
)

@Immutable
enum class OrganizationDiscoverType {
    MOVIE_COMPANY,
    TV_COMPANY,
    TV_NETWORK
}

@Immutable
data class Video(
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val streams: List<Stream> = emptyList(),
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val runtime: Int? = null, // episode runtime in minutes
    val tvdbEpisodeOrder: TvdbEpisodeOrder? = null,
    val localReleaseInfo: String? = null,
    @Transient
    val thumbnailArtwork: ArtworkDisplayRef? = null
) {
    val displayThumbnail: String?
        get() = thumbnailArtwork.toLegacyArtworkString() ?: thumbnail
}

@Immutable
data class MetaLink(
    val name: String,
    val category: String,
    val url: String
)
