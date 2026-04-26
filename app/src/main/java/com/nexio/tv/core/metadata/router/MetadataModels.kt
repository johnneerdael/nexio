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
enum class ResolverType {
    ADDON_DISPLAY,
    RATING,
    ARTWORK,
    REVIEWS,
    TRACKING,
    SKIP_SEGMENTS,
    TRAILERS,
    RECOMMENDATIONS,
    ORGANIZATION_PERSON
}

enum class ResolvedField {
    CANONICAL_ID,
    TITLE,
    OVERVIEW,
    RELEASE_DATE,
    RUNTIME,
    GENRES,
    AGE_RATING,
    CAST,
    CREW,
    EPISODES,
    POSTER,
    BACKDROP,
    LOGO,
    RATING,
    REVIEWS,
    TRAILERS,
    RECOMMENDATIONS,
    TRACKING,
    SKIP_SEGMENTS
}

enum class FieldOwner {
    PRIMARY,
    ARTWORK,
    RATING,
    REVIEWS,
    TRAILERS,
    RECOMMENDATIONS,
    TRACKING,
    SKIP_SEGMENTS,
    ORGANIZATION_PERSON
}

data class FieldValue(
    val value: Any,
    val owner: FieldOwner
)

data class MetadataCandidate(
    val provider: MetadataPrimaryProvider,
    val resolverType: ResolverType? = null,
    val fields: Map<ResolvedField, FieldValue>
)

data class IgnoredFieldOverwrite(
    val field: ResolvedField,
    val existingOwner: FieldOwner,
    val attemptedOwner: FieldOwner,
    val attemptedValue: Any
)

data class ResolvedMetadataDocument(
    val canonicalId: String?,
    val title: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val rating: Any?,
    val fieldOwners: Map<ResolvedField, FieldOwner>,
    val ignoredOverwrites: List<IgnoredFieldOverwrite>
)

data class ResolverSchedule(
    val depth: MetadataDepth,
    val localResolvers: List<ResolverType>,
    val networkResolvers: List<ResolverType>
)

enum class ProviderPlanRole { PRIMARY_CORE, MEDIA, SECONDARY, SEASON, PLAYER }

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
    val seasonNumber: Int? = null,
    val targetIds: Map<MetadataPrimaryProvider, String>,
    val targetIdRequiresIdentityResolution: Boolean = false,
    val trace: List<MetadataRouteTrace>
)

data class ProviderPlanStep(
    val apiShapeId: String,
    val provider: MetadataPrimaryProvider,
    val role: ProviderPlanRole,
    val required: Boolean
)

data class ProviderExecutionPlan(
    val route: MetadataRoute,
    val depth: MetadataDepth,
    val steps: List<ProviderPlanStep>
)
