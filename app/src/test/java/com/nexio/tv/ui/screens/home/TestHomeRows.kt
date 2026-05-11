package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape

internal object TestHomeRows {
    fun catalogRow(apiType: String): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "top",
            catalogName = "Top",
            type = ContentType.UNKNOWN,
            rawType = apiType,
            items = emptyList()
        )
    }

    fun heroRow(apiType: String?): HeroCarouselRow {
        return HeroCarouselRow(
            key = "row",
            title = "Row",
            globalRowIndex = 0,
            items = emptyList(),
            apiType = apiType
        )
    }

    fun modernCatalogItem(itemType: String): ModernCarouselItem {
        val preview = HeroPreview(
            title = "Title",
            logo = null,
            description = null,
            contentTypeText = itemType,
            yearText = null,
            imdbText = null,
            tomatoesText = null,
            genres = emptyList(),
            poster = null,
            backdrop = null,
            imageUrl = null
        )
        return ModernCarouselItem(
            key = "item",
            itemKey = com.nexio.tv.domain.model.homeDisplayItemKey(itemType, "tt123"),
            title = "Title",
            subtitle = null,
            imageUrl = null,
            heroPreview = preview,
            payload = ModernPayload.Catalog(
                focusKey = "focus",
                itemId = "tt123",
                itemType = itemType,
                addonBaseUrl = "https://addon.example",
                trailerTitle = "Title",
                trailerReleaseInfo = null,
                trailerApiType = itemType
            ),
            metaPreview = MetaPreview(
                id = "tt123",
                type = ContentType.MOVIE,
                rawType = itemType,
                name = "Title",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
        )
    }
}
