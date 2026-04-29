package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.RatingSeed
import com.nexio.tv.domain.model.SourcePayloadQuality
import com.nexio.tv.domain.model.TrailerHint
import java.util.Locale

class KitsuRailPreviewMapper {
    fun mapAnime(
        railId: String,
        anime: KitsuAnimeResource,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val kitsuId = anime.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val attributes = anime.attributes
        val sourceItemId = "kitsu:$kitsuId"
        val stableIds = ProviderIds(kitsu = kitsuId)
        val itemType = if (attributes?.subtype.equals("movie", ignoreCase = true)) {
            ContentType.MOVIE
        } else {
            ContentType.SERIES
        }
        val posterUrl = attributes?.posterImage.bestKitsuImage()
        val backdropUrl = attributes?.coverImage.bestKitsuImage()
        val rating = attributes?.averageRating?.toDoubleOrNull()?.div(10.0)
        val display = RailDisplaySeed(
            title = firstNonBlank(
                attributes?.canonicalTitle,
                attributes?.titles?.get("en"),
                attributes?.titles?.get("en_jp")
            ),
            originalTitle = firstNonBlank(attributes?.titles?.get("ja_jp")),
            year = yearFromDate(attributes?.startDate),
            releaseDate = firstNonBlank(attributes?.startDate),
            overview = firstNonBlank(attributes?.synopsis, attributes?.description),
            runtimeText = attributes?.episodeLength?.let { "$it min" },
            posterUrl = posterUrl,
            posterShape = PosterShape.POSTER,
            backdropUrl = backdropUrl,
            rating = rating?.let { RatingSeed(provider = ProviderId.KITSU, value = it) },
            ratingText = rating?.let { String.format(Locale.US, "%.1f", it) },
            trailerHint = attributes?.youtubeVideoId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { TrailerHint.YouTube(it) }
        )
        val rank = position + 1

        return RailItemPreview(
            railId = railId,
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
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
                    stableIds.kitsu.orEmpty(),
                    display.title.orEmpty(),
                    display.originalTitle.orEmpty(),
                    display.releaseDate.orEmpty(),
                    display.overview.orEmpty(),
                    display.runtimeText.orEmpty(),
                    display.posterUrl.orEmpty(),
                    display.posterShape?.name.orEmpty(),
                    display.backdropUrl.orEmpty(),
                    display.rating?.value?.toString().orEmpty(),
                    rank.toString()
                ).joinToString(separator = "|")
            ),
            generatedAtMs = generatedAtMs
        )
    }

    private fun KitsuImage?.bestKitsuImage(): String? =
        firstNonBlank(this?.large, this?.original)
}
