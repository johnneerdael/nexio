package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.trace.FirstPaintTracer
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.toHomeDisplayMetadata

/**
 * UI-layer wrapper around the pure [MetaPreview.toHomeDisplayMetadata] conversion that ALSO
 * emits a `metadata.first_paint` trace event. Use this only at the canonical Home first-paint
 * boundaries (live presentation pipeline). Other callers — overlay merges, screensaver prep,
 * catalog refresh fallbacks, persisted-snapshot rehydration — should call the pure
 * `toHomeDisplayMetadata()` extension directly so they do not emit spurious first-paint events.
 */
fun MetaPreview.toFirstPaintHomeDisplayMetadata(): HomeDisplayMetadata {
    val display = toHomeDisplayMetadata()
    FirstPaintTracer.recordHomePreview(
        contentId = id,
        itemType = apiType,
        source = firstPaintSource.name,
        fieldsUsed = collectFirstPaintFieldsUsed(display)
    )
    return display
}

private fun collectFirstPaintFieldsUsed(d: HomeDisplayMetadata): Set<String> {
    val fields = mutableSetOf<String>()
    if (!d.title.isNullOrBlank()) fields += "title"
    if (!d.poster.isNullOrBlank()) fields += "poster"
    if (!d.backdrop.isNullOrBlank()) fields += "background"
    if (!d.description.isNullOrBlank()) fields += "description"
    if (!d.releaseInfo.isNullOrBlank()) fields += "releaseInfo"
    if (!d.logo.isNullOrBlank()) fields += "logo"
    return fields
}
