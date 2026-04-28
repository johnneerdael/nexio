package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryRatingsDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.RatingSeed
import com.nexio.tv.domain.model.SourcePayloadQuality
import com.nexio.tv.domain.model.TrailerHint

class SimklRailPreviewMapper {
    fun mapDiscoveryItem(
        railId: String,
        item: SimklDiscoveryItemDto,
        itemType: ContentType,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val ids = item.ids ?: return null
        val simklId = ids.simkl ?: ids.simklId ?: return null
        val sourceItemId = "simkl:$simklId"
        val stableIds = ids.toProviderIds(simklId)
        val releaseDate = firstNonBlank(item.released, item.date)
        val display = RailDisplaySeed(
            title = firstNonBlank(item.title),
            year = item.year ?: yearFromDate(releaseDate),
            releaseDate = releaseDate,
            overview = firstNonBlank(item.overview),
            runtimeText = item.runtime?.let { "$it min" },
            genres = item.genres.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
            posterUrl = simklImageUrl(item.poster),
            backdropUrl = simklImageUrl(item.fanart),
            rating = item.ratings.toRatingSeed(),
            trailerHint = item.trailer
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { TrailerHint.YouTube(it) }
        )
        val rank = position + 1

        return RailItemPreview(
            railId = railId,
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
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
                    stableIds.simkl.orEmpty(),
                    stableIds.imdb.orEmpty(),
                    stableIds.tmdb.orEmpty(),
                    stableIds.tvdb.orEmpty(),
                    stableIds.mal.orEmpty(),
                    display.title.orEmpty(),
                    display.releaseDate.orEmpty(),
                    display.overview.orEmpty(),
                    display.runtimeText.orEmpty(),
                    display.genres.joinToString(","),
                    display.posterUrl.orEmpty(),
                    display.backdropUrl.orEmpty(),
                    display.rating?.provider?.name.orEmpty(),
                    display.rating?.value?.toString().orEmpty(),
                    rank.toString()
                ).joinToString(separator = "|")
            ),
            generatedAtMs = generatedAtMs
        )
    }

    private fun SimklIdsDto.toProviderIds(simklId: Long): ProviderIds = ProviderIds(
        simkl = simklId.toString(),
        imdb = imdb?.trim()?.takeIf { it.isNotEmpty() },
        tmdb = tmdb?.trim()?.takeIf { it.isNotEmpty() },
        tvdb = tvdb?.trim()?.takeIf { it.isNotEmpty() },
        mal = mal?.trim()?.takeIf { it.isNotEmpty() },
        slug = slug?.trim()?.takeIf { it.isNotEmpty() }
    )

    private fun SimklDiscoveryRatingsDto?.toRatingSeed(): RatingSeed? {
        val imdbRating = this?.imdb
        if (imdbRating != null) return RatingSeed(provider = ProviderId.IMDB, value = imdbRating)

        val simklRating = this?.simkl ?: return null
        return RatingSeed(provider = ProviderId.SIMKL, value = simklRating)
    }
}
