package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
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

/**
 * Maps Kitsu API resources into [RailItemPreview]s. [animeIdMappingService] is
 * consulted synchronously (mmap-backed, ~free) at construction time so the
 * preview's `stableIds` arrives at the artwork resolver pre-enriched with
 * imdb/tmdb/tvdb. Without this enrichment RPDB's capability check fails for
 * anime items (kitsu-only ids are not in `rpdbDescriptor.supportedIdTypes`),
 * the resolver falls back to ADDON, and `preferredArtworkProviders[POSTER]`
 * gets tagged ADDON — which then poisons the surface tie-breaker by treating
 * the addon URL as the "preferred" provider for anime rows.
 *
 * Constructor overload without the service keeps existing test sites working
 * (`KitsuRailPreviewMapper()` falls back to kitsu-only stableIds, matching
 * the legacy behaviour these tests asserted).
 */
class KitsuRailPreviewMapper(
    private val animeIdMappingService: AnimeIdMappingService? = null
) {
    fun mapAnime(
        railId: String,
        anime: KitsuAnimeResource,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val kitsuId = anime.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val attributes = anime.attributes
        val sourceItemId = "kitsu:$kitsuId"
        val itemType = if (attributes?.subtype.equals("movie", ignoreCase = true)) {
            ContentType.MOVIE
        } else {
            ContentType.SERIES
        }
        val mediaKind = if (itemType == ContentType.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES
        val stableIds = animeIdMappingService
            ?.resolveProviderIdsForKitsu(kitsuId, mediaKind)
            ?: ProviderIds(kitsu = kitsuId)
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
