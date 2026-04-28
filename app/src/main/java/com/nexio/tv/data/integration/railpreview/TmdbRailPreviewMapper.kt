package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.RatingSeed
import com.nexio.tv.domain.model.SourcePayloadQuality

class TmdbRailPreviewMapper {
    fun mapResult(
        railId: String,
        result: TmdbMediaResult,
        itemType: ContentType,
        position: Int,
        generatedAtMs: Long,
        genreNames: Map<Int, String> = emptyMap()
    ): RailItemPreview {
        val sourceItemId = "tmdb:${result.id}"
        val stableIds = ProviderIds(tmdb = result.id.toString())
        val releaseDate = firstNonBlank(result.releaseDate, result.firstAirDate)
        val display = RailDisplaySeed(
            title = firstNonBlank(result.title, result.name),
            originalTitle = firstNonBlank(result.originalTitle, result.originalName),
            year = yearFromDate(releaseDate),
            releaseDate = releaseDate,
            overview = result.overview,
            genres = result.genreIds.orEmpty().mapNotNull { genreNames[it] },
            posterUrl = tmdbImageUrl(result.posterPath),
            backdropUrl = tmdbImageUrl(result.backdropPath, "w1280"),
            rating = result.voteAverage?.let {
                RatingSeed(provider = ProviderId.TMDB, value = it, votes = result.voteCount)
            }
        )
        val rank = position + 1

        return RailItemPreview(
            railId = railId,
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = sourceItemId,
            itemType = itemType,
            stableIds = stableIds,
            display = display,
            ranking = RailRankingMetadata(rank = rank),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = stablePayloadHash(
                listOf(
                    railId,
                    sourceItemId,
                    itemType.name,
                    stableIds.tmdb.orEmpty(),
                    display.title.orEmpty(),
                    display.originalTitle.orEmpty(),
                    display.releaseDate.orEmpty(),
                    display.overview.orEmpty(),
                    display.posterUrl.orEmpty(),
                    display.backdropUrl.orEmpty(),
                    display.genres.joinToString(","),
                    display.rating?.value?.toString().orEmpty(),
                    display.rating?.votes?.toString().orEmpty(),
                    rank.toString()
                ).joinToString(separator = "|")
            ),
            generatedAtMs = generatedAtMs
        )
    }
}
