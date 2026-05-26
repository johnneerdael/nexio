package com.nexio.tv.core.addon

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import java.util.Locale

object TekenfilmsHomePlaybackPolicy {
    const val ADDON_ID: String = "org.nexio.tekenfilms"
    const val BASE_URL: String = "https://tekenfilms.nexioapp.org"
    const val CATALOG_ID: String = "tekenfilms_nl"
    const val TYPE: String = "movie"
    const val ITEM_ID_PREFIX: String = "tekenfilms:"
    private val IMDB_ID_REGEX = Regex("""tt\d+""", RegexOption.IGNORE_CASE)

    fun isTekenfilmsRow(row: CatalogRow): Boolean {
        return normalizeBaseUrl(row.addonBaseUrl) == BASE_URL &&
            row.addonId == ADDON_ID &&
            row.catalogId == CATALOG_ID &&
            row.apiType.equals(TYPE, ignoreCase = true)
    }

    fun isTekenfilmsItem(row: CatalogRow, item: MetaPreview): Boolean {
        return isTekenfilmsRow(row) &&
            item.apiType.equals(TYPE, ignoreCase = true) &&
            isSupportedItemId(item.id)
    }

    fun isTekenfilmsItem(
        addonBaseUrl: String,
        addonId: String,
        catalogId: String?,
        itemType: String,
        itemId: String
    ): Boolean {
        return normalizeBaseUrl(addonBaseUrl) == BASE_URL &&
            addonId == ADDON_ID &&
            catalogId == CATALOG_ID &&
            itemType.equals(TYPE, ignoreCase = true) &&
            isSupportedItemId(itemId)
    }

    fun isSupportedItemId(itemId: String): Boolean {
        val normalized = itemId.trim()
        return normalized.startsWith(ITEM_ID_PREFIX) || IMDB_ID_REGEX.matches(normalized)
    }

    fun normalizeBaseUrl(value: String): String {
        return value.trim()
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")
            .lowercase(Locale.ROOT)
    }
}
