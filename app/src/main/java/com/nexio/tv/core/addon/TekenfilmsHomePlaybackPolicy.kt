package com.nexio.tv.core.addon

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import java.util.Locale

object TekenfilmsHomePlaybackPolicy {
    const val ADDON_ID: String = "org.nexio.tekenfilms"
    const val BASE_URL: String = "https://tekenfilms.nexioapp.org"
    const val CARTOONS_BASE_URL: String = "https://cartoons.nexioapp.org"
    const val CATALOG_ID: String = "tekenfilms_nl"
    const val TYPE: String = "movie"
    const val ITEM_ID_PREFIX: String = "tekenfilms:"
    private val SUPPORTED_TYPES = setOf("movie", "series")
    private val IMDB_ID_REGEX = Regex("""tt\d+(?::\d+:\d+)?""", RegexOption.IGNORE_CASE)

    fun isTekenfilmsRow(row: CatalogRow): Boolean {
        return isSupportedBaseUrl(row.addonBaseUrl) &&
            row.addonId == ADDON_ID &&
            isSupportedType(row.apiType)
    }

    fun isTekenfilmsItem(row: CatalogRow, item: MetaPreview): Boolean {
        return isTekenfilmsRow(row) &&
            isSupportedType(item.apiType) &&
            isSupportedItemId(item.id)
    }

    fun isTekenfilmsItem(
        addonBaseUrl: String,
        addonId: String,
        catalogId: String?,
        itemType: String,
        itemId: String
    ): Boolean {
        return isSupportedBaseUrl(addonBaseUrl) &&
            addonId == ADDON_ID &&
            isSupportedType(itemType) &&
            isSupportedItemId(itemId)
    }

    fun isSupportedBaseUrl(addonBaseUrl: String): Boolean {
        return when (normalizeBaseUrl(addonBaseUrl)) {
            BASE_URL, CARTOONS_BASE_URL -> true
            else -> false
        }
    }

    fun isSupportedItemId(itemId: String): Boolean {
        val normalized = itemId.trim()
        return normalized.startsWith(ITEM_ID_PREFIX) || IMDB_ID_REGEX.matches(normalized)
    }

    fun isSupportedType(itemType: String): Boolean {
        return itemType.trim().lowercase(Locale.ROOT) in SUPPORTED_TYPES
    }

    fun normalizeBaseUrl(value: String): String {
        return value.trim()
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")
            .lowercase(Locale.ROOT)
    }
}
