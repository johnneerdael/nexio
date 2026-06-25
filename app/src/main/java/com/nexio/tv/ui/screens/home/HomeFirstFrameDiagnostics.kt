package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.MetaPreview

internal data class HomeFrameDiagnostics(
    val source: String,
    val expectedOrderKeys: List<String>,
    val actualOrderKeys: List<String>,
    val heroItemKeys: List<String>,
    val missingOrderKeys: List<String>,
    val extraOrderKeys: List<String>,
    val emptyRailKeys: List<String>,
    val loadingRailKeys: List<String>,
    val totalItemCount: Int,
    val missingPosterItemCount: Int,
    val placeholderPosterItemCount: Int,
    val signature: String
) {
    val isFinal: Boolean
        get() = expectedOrderKeys.isNotEmpty() &&
            missingOrderKeys.isEmpty() &&
            emptyRailKeys.isEmpty() &&
            loadingRailKeys.isEmpty() &&
            totalItemCount > 0

    val summary: String
        get() = "source=$source final=$isFinal expected=${expectedOrderKeys.size} actual=${actualOrderKeys.size} " +
            "missing=${missingOrderKeys.size} extra=${extraOrderKeys.size} empty=${emptyRailKeys.size} " +
            "loading=${loadingRailKeys.size} items=$totalItemCount hero=${heroItemKeys.size} missingPoster=$missingPosterItemCount " +
            "placeholderPoster=$placeholderPosterItemCount signature=$signature"

    val detail: String
        get() = "missingKeys=${missingOrderKeys.joinToString(limit = 12)} " +
            "emptyKeys=${emptyRailKeys.joinToString(limit = 12)} " +
            "loadingKeys=${loadingRailKeys.joinToString(limit = 12)}"
}

internal fun analyzeHomeFrame(
    source: String,
    expectedOrderKeys: List<String>,
    displayRows: List<CatalogRow>,
    fullRows: List<CatalogRow>,
    heroItems: List<MetaPreview> = emptyList()
): HomeFrameDiagnostics {
    val rowsByKey = linkedMapOf<String, CatalogRow>()
    for (i in displayRows.indices) {
        val row = displayRows[i]
        rowsByKey[homeCatalogGlobalKey(row)] = row
    }
    if (rowsByKey.isEmpty()) {
        for (i in fullRows.indices) {
            val row = fullRows[i]
            rowsByKey[homeCatalogGlobalKey(row)] = row
        }
    }

    val actualOrderKeys = rowsByKey.keys.toList()
    val actualSet = actualOrderKeys.toSet()
    val expectedSet = expectedOrderKeys.toSet()
    val emptyRailKeys = ArrayList<String>()
    val loadingRailKeys = ArrayList<String>()
    var totalItemCount = 0
    var missingPosterItemCount = 0
    var placeholderPosterItemCount = 0
    val signatureParts = ArrayList<String>(rowsByKey.size)
    val heroItemKeys = ArrayList<String>(heroItems.size)

    for ((key, row) in rowsByKey) {
        if (row.items.isEmpty()) emptyRailKeys += key
        if (row.isLoading) loadingRailKeys += key
        totalItemCount += row.items.size
        var rowSignature = key
        for (i in row.items.indices) {
            val item = row.items[i]
            if (!item.hasRenderablePoster()) missingPosterItemCount++
            if (item.hasPlaceholderPoster()) placeholderPosterItemCount++
            rowSignature += "|${item.apiType}:${item.id}:${item.posterSignature()}"
        }
        signatureParts += rowSignature
    }
    for (i in heroItems.indices) {
        val item = heroItems[i]
        heroItemKeys += "${item.apiType}:${item.id}:${item.posterSignature()}"
    }

    return HomeFrameDiagnostics(
        source = source,
        expectedOrderKeys = expectedOrderKeys,
        actualOrderKeys = actualOrderKeys,
        heroItemKeys = heroItemKeys,
        missingOrderKeys = expectedOrderKeys.filterNot { it in actualSet },
        extraOrderKeys = actualOrderKeys.filterNot { it in expectedSet },
        emptyRailKeys = emptyRailKeys,
        loadingRailKeys = loadingRailKeys,
        totalItemCount = totalItemCount,
        missingPosterItemCount = missingPosterItemCount,
        placeholderPosterItemCount = placeholderPosterItemCount,
        signature = (signatureParts.joinToString(separator = "||") + "::hero=" +
            heroItemKeys.joinToString(separator = "|")).hashCode().toString()
    )
}

internal fun expectedHomeFrameOrderKeys(
    configuredRails: List<HomeCatalogRail>,
    displayRows: List<CatalogRow>,
    fullRows: List<CatalogRow>
): List<String> {
    val keys = linkedSetOf<String>()
    for (i in fullRows.indices) keys += homeCatalogGlobalKey(fullRows[i])
    for (i in displayRows.indices) keys += homeCatalogGlobalKey(displayRows[i])
    if (configuredRails.isNotEmpty()) {
        return configuredRails
            .asSequence()
            .filter { it.enabled }
            .map { it.key }
            .toList()
    }
    return keys.toList()
}

private fun MetaPreview.hasRenderablePoster(): Boolean =
    artwork?.poster.isRenderableArtworkRef() ||
        !poster.isNullOrBlank()

private fun MetaPreview.hasPlaceholderPoster(): Boolean =
    artwork?.poster is ArtworkDisplayRef.Placeholder ||
        artwork?.backdrop is ArtworkDisplayRef.Placeholder ||
        artwork?.thumbnail is ArtworkDisplayRef.Placeholder ||
        poster?.startsWith("nexio-placeholder://") == true

private fun MetaPreview.posterSignature(): String =
    when (val ref = artwork?.poster) {
        is ArtworkDisplayRef.RuntimeAsset -> "runtime:${ref.assetKey?.value ?: ref.decisionKey.value}"
        is ArtworkDisplayRef.Placeholder -> "placeholder:${ref.placeholderType}"
        is ArtworkDisplayRef.LegacyString -> "legacy:${ref.value}"
        null -> poster.orEmpty()
    }

private fun ArtworkDisplayRef?.isRenderableArtworkRef(): Boolean =
    when (this) {
        is ArtworkDisplayRef.RuntimeAsset -> assetKey != null
        is ArtworkDisplayRef.LegacyString -> value.isNotBlank()
        is ArtworkDisplayRef.Placeholder -> false
        null -> false
    }
