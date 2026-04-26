package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata

enum class MetadataPrimaryProvider { TMDB, TVDB, KITSU }

enum class MetadataDecisionReason {
    KITSU_PREFIX_DIRECT,
    ANIME_PREFIX_MAPPED_TO_KITSU,
    ID_MAPPING_TO_KITSU,
    PROVIDER_NATIVE_DIRECT,
    ROUTING_ID_TYPE_CONFLICT,
    ITEM_TYPE_MOVIE,
    ITEM_TYPE_SERIES,
    UNSUPPORTED_TYPE
}

enum class MetadataMediaKind { MOVIE, SERIES, ANIME, UNKNOWN }

enum class MetadataDepth { PREVIEW, DETAIL_CORE, DETAIL_MEDIA, DETAIL_SECONDARY, SEASON, PLAYER }

data class MetadataSourceContext(
    val addonId: String? = null,
    val catalogId: String? = null,
    val catalogType: String? = null,
    val itemType: String? = null,
    val sourceName: String? = null,
    val addonMetadata: HomeDisplayMetadata? = null,
    val rowItemIds: List<String> = emptyList()
)

data class MetadataRequest(
    val contentId: String,
    val contentType: ContentType,
    val sourceContext: MetadataSourceContext,
    val language: String? = null,
    val seasonNumber: Int? = null,
    val depth: MetadataDepth = MetadataDepth.DETAIL_CORE
)

data class NormalizedMetadataRequest(
    val originalContentId: String,
    val parentId: String,
    val contentType: ContentType,
    val mediaKind: MetadataMediaKind,
    val sourceContext: MetadataSourceContext,
    val language: String?,
    val seasonNumber: Int?,
    val depth: MetadataDepth
)

data class MetadataRouteTrace(
    val reason: MetadataDecisionReason,
    val detail: String
)

data class MetadataRoute(
    val provider: MetadataPrimaryProvider,
    val parentId: String,
    val mediaKind: MetadataMediaKind,
    val reason: MetadataDecisionReason,
    val sourceContext: MetadataSourceContext,
    val language: String? = null,
    val targetIds: Map<MetadataPrimaryProvider, String>,
    val targetIdRequiresIdentityResolution: Boolean = false,
    val trace: List<MetadataRouteTrace>
)
