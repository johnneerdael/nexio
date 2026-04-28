package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality

class TraktRailPreviewMapper {
    fun mapTrendingShow(
        railId: String,
        item: TraktTrendingShowItemDto,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val show = item.show ?: return null
        val ids = show.ids ?: return null
        val traktId = ids.trakt ?: return null

        return mapPreview(
            railId = railId,
            sourceItemId = "trakt:show:$traktId",
            itemType = ContentType.SERIES,
            title = show.title,
            year = show.year,
            ids = ids,
            watchers = item.watchers,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    fun mapTrendingMovie(
        railId: String,
        item: TraktTrendingMovieItemDto,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val movie = item.movie ?: return null
        val ids = movie.ids ?: return null
        val traktId = ids.trakt ?: return null

        return mapPreview(
            railId = railId,
            sourceItemId = "trakt:movie:$traktId",
            itemType = ContentType.MOVIE,
            title = movie.title,
            year = movie.year,
            ids = ids,
            watchers = item.watchers,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    private fun mapPreview(
        railId: String,
        sourceItemId: String,
        itemType: ContentType,
        title: String?,
        year: Int?,
        ids: TraktIdsDto,
        watchers: Int?,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview {
        val stableIds = ids.toProviderIds()
        val rank = position + 1

        return RailItemPreview(
            railId = railId,
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = sourceItemId,
            itemType = itemType,
            stableIds = stableIds,
            display = RailDisplaySeed(
                title = title,
                year = year
            ),
            ranking = RailRankingMetadata(
                watchers = watchers,
                rank = rank
            ),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = stablePayloadHash(
                listOf(
                    railId,
                    sourceItemId,
                    itemType.name,
                    stableIds.imdb.orEmpty(),
                    stableIds.tmdb.orEmpty(),
                    stableIds.tvdb.orEmpty(),
                    stableIds.trakt.orEmpty(),
                    stableIds.slug.orEmpty(),
                    title.orEmpty(),
                    year?.toString().orEmpty(),
                    watchers?.toString().orEmpty(),
                    rank.toString()
                ).joinToString(separator = "|")
            ),
            generatedAtMs = generatedAtMs
        )
    }

    private fun TraktIdsDto.toProviderIds(): ProviderIds = ProviderIds(
        imdb = imdb?.trim()?.takeIf { it.isNotEmpty() },
        tmdb = tmdb?.toString(),
        tvdb = tvdb?.toString(),
        trakt = trakt?.toString(),
        slug = slug?.trim()?.takeIf { it.isNotEmpty() }
    )
}
