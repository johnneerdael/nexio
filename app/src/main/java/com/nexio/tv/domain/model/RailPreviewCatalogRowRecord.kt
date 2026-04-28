package com.nexio.tv.domain.model

data class RailPreviewCatalogRowRecord(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString("catalog"),
    val previews: List<RailItemPreview>
) {
    fun toCatalogRow(): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            rawType = rawType,
            items = previews.map { it.toMetaPreview() },
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }
}
