package com.nexio.tv.data.integration.railpreview

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.RatingSeed
import com.nexio.tv.domain.model.SourcePayloadQuality

class MDBListRailPreviewMapper {
    fun mapJsonObject(
        railId: String,
        item: JsonObject,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val itemId = item.stringValue("id") ?: return null
        val sourceItemId = "mdblist:list:$railId:item:$itemId"
        val itemType = item.contentType()
        val stableIds = ProviderIds(
            imdb = item.stringValue("imdb_id") ?: item.stringValue("imdb"),
            tmdb = item.stringValue("tmdb_id") ?: item.stringValue("tmdb"),
            tvdb = item.stringValue("tvdb_id") ?: item.stringValue("tvdb")
        )
        val rank = position + 1
        val display = RailDisplaySeed(
            title = firstNonBlank(item.stringValue("title"), item.stringValue("name")),
            year = item.intValue("year"),
            overview = firstNonBlank(item.stringValue("description"), item.stringValue("overview")),
            genres = item.stringArray("genres"),
            posterUrl = item.stringValue("poster"),
            rating = item.ratingSeed()
        )

        return RailItemPreview(
            railId = railId,
            railSource = RailSource.BUILT_IN_MDBLIST,
            sourceProvider = ProviderId.MDBLIST,
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
                    stableIds.imdb.orEmpty(),
                    stableIds.tmdb.orEmpty(),
                    stableIds.tvdb.orEmpty(),
                    display.title.orEmpty(),
                    display.year?.toString().orEmpty(),
                    display.overview.orEmpty(),
                    display.posterUrl.orEmpty(),
                    display.genres.joinToString(","),
                    display.rating?.provider?.name.orEmpty(),
                    display.rating?.value?.toString().orEmpty(),
                    rank.toString()
                ).joinToString(separator = "|")
            ),
            generatedAtMs = generatedAtMs
        )
    }

    private fun JsonObject.contentType(): ContentType =
        when (stringValue("type")?.lowercase()) {
            "movie" -> ContentType.MOVIE
            "show", "series", "tv" -> ContentType.SERIES
            else -> ContentType.UNKNOWN
        }

    private fun JsonObject.ratingSeed(): RatingSeed? {
        val imdbRating = get("ratings")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.doubleValue("imdb")
        if (imdbRating != null) {
            return RatingSeed(provider = ProviderId.IMDB, value = imdbRating)
        }

        val mdbListRating = doubleValue("rating") ?: return null
        return RatingSeed(provider = ProviderId.MDBLIST, value = mdbListRating)
    }

    private fun JsonObject.stringValue(key: String): String? =
        get(key)?.asPrimitiveOrNull()?.let { primitive ->
            when {
                primitive.isString -> primitive.asString
                primitive.isNumber -> primitive.asNumber.toString()
                else -> null
            }
        }?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intValue(key: String): Int? =
        get(key)?.asPrimitiveOrNull()?.let { primitive ->
            when {
                primitive.isNumber -> primitive.asInt
                primitive.isString -> primitive.asString.toIntOrNull()
                else -> null
            }
        }

    private fun JsonObject.doubleValue(key: String): Double? =
        get(key)?.asPrimitiveOrNull()?.let { primitive ->
            when {
                primitive.isNumber -> primitive.asDouble
                primitive.isString -> primitive.asString.toDoubleOrNull()
                else -> null
            }
        }

    private fun JsonObject.stringArray(key: String): List<String> =
        get(key)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.strings()
            .orEmpty()

    private fun JsonArray.strings(): List<String> =
        mapNotNull { element ->
            element.asPrimitiveOrNull()
                ?.takeIf { it.isString }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    private fun JsonElement.asPrimitiveOrNull() =
        takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asJsonPrimitive
}
