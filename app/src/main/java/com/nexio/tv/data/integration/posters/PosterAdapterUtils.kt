package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType

/**
 * Shared utilities for poster provider adapters.
 *
 * F2-13-E: extracted from [RpdbMetadataProviderAdapter] and [TopPostersMetadataProviderAdapter]
 * to eliminate the duplicated private extension function in each adapter.
 */
internal fun MetadataMediaKind.toContentType(): ContentType = when (this) {
    MetadataMediaKind.MOVIE -> ContentType.MOVIE
    MetadataMediaKind.SERIES, MetadataMediaKind.ANIME -> ContentType.SERIES
    MetadataMediaKind.UNKNOWN -> ContentType.UNKNOWN
}
