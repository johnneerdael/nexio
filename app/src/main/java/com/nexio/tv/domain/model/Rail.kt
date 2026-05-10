package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Structure-only rail: addon/catalog metadata, ordered list of opaque
 * [RailItemKey]s, pagination + loading state. Mirrors [CatalogRow]'s
 * structural fields exactly, but the `items` field carries only KEYS
 * — item content lives in
 * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository] and
 * is looked up by [RailItemKey.key].
 *
 * Plan B Phase 3.1 of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`).
 * Foundation type — no production wiring yet. Sub-projects 3.2-3.6 wire
 * this into consumers and producer; sub-project 3.9 retires the legacy
 * [CatalogRow] if/when no consumer still needs it.
 */
@Immutable
data class Rail(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val items: List<RailItemKey>,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100
) {
    val apiType: String
        get() = type.toApiString(rawType)
}
