package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Opaque key for looking up a resolved item in
 * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository]. Carries
 * just enough to derive the authority lookup key via [homeDisplayItemKey]
 * — no item content. The authority owns item content; rails carry only
 * structure + ordered keys.
 *
 * Plan B Phase 3.1 of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-phase-3-catalog-pipeline-restructure-design.md`).
 */
@Immutable
data class RailItemKey(
    val apiType: String,
    val contentId: String
) {
    /** Authority lookup key. Equivalent to `homeDisplayItemKey(apiType, contentId)`. */
    val key: String get() = homeDisplayItemKey(apiType, contentId)
}
