package com.nexio.tv.data.local

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.orDefault

internal fun MetaPreview.sanitizedForCache(): MetaPreview = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    trailerYtIds = trailerYtIds.orEmpty()
)

internal fun HomeDisplayMetadata.sanitizedForCache(): HomeDisplayMetadata = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty()
)

internal fun CatalogRow.sanitizedForCache(): CatalogRow = copy(
    items = items.orEmpty().map { it.sanitizedForCache() }
)

internal fun Meta.sanitizedForCache(): Meta = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    director = director.orEmpty(),
    writer = writer.orEmpty(),
    cast = cast.orEmpty(),
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    videos = videos.orEmpty(),
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() },
    links = links.orEmpty(),
    trailerYtIds = trailerYtIds.orEmpty()
)

internal fun TmdbEnrichment.sanitizedForCache(): TmdbEnrichment = copy(
    ratingSource = ratingSource.orDefault(TitleRatingSource.TMDB),
    genres = genres.orEmpty(),
    directorMembers = directorMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    writerMembers = writerMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    director = director.orEmpty(),
    writer = writer.orEmpty(),
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
)

internal fun TvMetadataEnrichment.sanitizedForCache(): TvMetadataEnrichment = copy(
    ratingSource = ratingSource.orDefault(),
    genres = genres.orEmpty(),
    airsDays = airsDays.orEmpty(),
    aliases = aliases.orEmpty(),
    contentRatings = contentRatings.orEmpty(),
    remoteIds = remoteIds.orEmpty(),
    castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
    productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
    networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
)

private fun MetaCastMember.sanitizedOrNull(): MetaCastMember? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(name = cleanName)
}

private fun MetaCompany.sanitizedOrNull(): MetaCompany? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(name = cleanName)
}
