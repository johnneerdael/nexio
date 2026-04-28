package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class MetadataRouter @Inject constructor(
    private val normalizer: MetadataRequestNormalizer,
    private val animeIdentityIndex: AnimeIdentityIndex,
    private val idMappingStore: IdMappingStore,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    suspend fun route(request: MetadataRequest): MetadataRoute {
        require(request.depth != MetadataDepth.PREVIEW) {
            "MetadataRouter does not route preview-depth requests"
        }

        val normalized = normalizer.normalize(request)
        val parsedId = MetadataIdParser.parse(normalized.parentId)
        val trace = mutableListOf<MetadataRouteTrace>()

        return when (parsedId.scheme) {
            AnimeIdScheme.KITSU -> kitsuDirect(normalized, parsedId, trace)
            AnimeIdScheme.MAL,
            AnimeIdScheme.ANILIST,
            AnimeIdScheme.ANIDB -> animePrefixMapped(normalized, parsedId, trace)
            AnimeIdScheme.IMDB -> imdbMappedOrFallback(normalized, parsedId, trace)
            AnimeIdScheme.TMDB -> providerNativeOrConflict(
                normalized = normalized,
                parsedId = parsedId,
                nativeType = ContentType.MOVIE,
                nativeProvider = MetadataPrimaryProvider.TMDB,
                conflictFallbackProvider = MetadataPrimaryProvider.TVDB,
                trace = trace
            )
            AnimeIdScheme.TVDB -> providerNativeOrConflict(
                normalized = normalized,
                parsedId = parsedId,
                nativeType = ContentType.SERIES,
                nativeProvider = MetadataPrimaryProvider.TVDB,
                conflictFallbackProvider = MetadataPrimaryProvider.TMDB,
                trace = trace
            )
            AnimeIdScheme.TRAKT,
            AnimeIdScheme.SIMKL,
            AnimeIdScheme.UNKNOWN -> fallbackByItemType(normalized, trace)
        }
    }

    private fun kitsuDirect(
        normalized: NormalizedMetadataRequest,
        parsedId: ParsedMetadataId,
        trace: MutableList<MetadataRouteTrace>
    ): MetadataRoute {
        trace += MetadataRouteTrace(MetadataDecisionReason.KITSU_PREFIX_DIRECT, "Kitsu prefix ${parsedId.raw} routes directly")
        return route(
            normalized = normalized,
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
            targetId = "kitsu:${parsedId.value}",
            trace = trace
        )
    }

    private suspend fun animePrefixMapped(
        normalized: NormalizedMetadataRequest,
        parsedId: ParsedMetadataId,
        trace: MutableList<MetadataRouteTrace>
    ): MetadataRoute {
        val localMapping = idMappingStore.lookupKitsu(parsedId)
        if (localMapping != null) {
            trace += MetadataRouteTrace(
                MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU,
                "Mapped ${parsedId.raw} to kitsu:${localMapping.providerId} from ${localMapping.source}"
            )
            return route(
                normalized = normalized,
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU,
                targetId = "kitsu:${localMapping.providerId}",
                trace = trace
            )
        }

        val kitsuId = animeIdentityIndex.resolveKitsuId(parsedId)
        if (kitsuId != null) {
            idMappingStore.persist(
                IdMapping(
                    sourceId = parsedId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = kitsuId,
                    source = IdMappingSource.FRIBB,
                    evidence = "anime identity mapping ${parsedId.scheme}:${parsedId.value} -> kitsu:$kitsuId"
                )
            )
            trace += MetadataRouteTrace(
                MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU,
                "Mapped ${parsedId.raw} to kitsu:$kitsuId from FRIBB"
            )
            return route(
                normalized = normalized,
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU,
                targetId = "kitsu:$kitsuId",
                trace = trace
            )
        }
        trace += MetadataRouteTrace(MetadataDecisionReason.UNSUPPORTED_TYPE, "No deterministic Kitsu mapping for ${parsedId.raw}")
        return fallbackByItemType(normalized, trace)
    }

    private suspend fun imdbMappedOrFallback(
        normalized: NormalizedMetadataRequest,
        parsedId: ParsedMetadataId,
        trace: MutableList<MetadataRouteTrace>
    ): MetadataRoute {
        val localMapping = idMappingStore.lookupKitsu(parsedId)
        if (localMapping != null) {
            trace += MetadataRouteTrace(
                MetadataDecisionReason.ID_MAPPING_TO_KITSU,
                "Mapped ${parsedId.raw} to kitsu:${localMapping.providerId} from ${localMapping.source}"
            )
            return route(
                normalized = normalized,
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.ID_MAPPING_TO_KITSU,
                targetId = "kitsu:${localMapping.providerId}",
                trace = trace
            )
        }

        val kitsuId = animeIdentityIndex.resolveKitsuId(parsedId)
        if (kitsuId != null) {
            idMappingStore.persist(
                IdMapping(
                    sourceId = parsedId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = kitsuId,
                    source = IdMappingSource.FRIBB,
                    evidence = "fribb ${parsedId.raw} -> kitsu:$kitsuId"
                )
            )
            trace += MetadataRouteTrace(MetadataDecisionReason.ID_MAPPING_TO_KITSU, "Mapped ${parsedId.raw} to kitsu:$kitsuId from FRIBB")
            return route(
                normalized = normalized,
                provider = MetadataPrimaryProvider.KITSU,
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.ID_MAPPING_TO_KITSU,
                targetId = "kitsu:$kitsuId",
                trace = trace
            )
        }

        trace += MetadataRouteTrace(MetadataDecisionReason.UNSUPPORTED_TYPE, "No deterministic anime mapping for IMDb id ${parsedId.raw}")
        return fallbackByItemType(normalized, trace)
    }

    private fun providerNativeOrConflict(
        normalized: NormalizedMetadataRequest,
        parsedId: ParsedMetadataId,
        nativeType: ContentType,
        nativeProvider: MetadataPrimaryProvider,
        conflictFallbackProvider: MetadataPrimaryProvider,
        trace: MutableList<MetadataRouteTrace>
    ): MetadataRoute {
        if (normalized.contentType == nativeType || nativeType == ContentType.SERIES && normalized.contentType == ContentType.TV) {
            trace += MetadataRouteTrace(
                MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                "Provider-native id ${parsedId.raw} matches ${normalized.contentType}"
            )
            return route(
                normalized = normalized,
                provider = nativeProvider,
                mediaKind = normalized.mediaKind,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                targetId = normalized.parentId,
                trace = trace
            )
        }

        trace += MetadataRouteTrace(
            MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
            "Provider-native id ${parsedId.raw} conflicts with ${normalized.contentType}"
        )
        return fallbackByItemType(
            normalized = normalized,
            trace = trace,
            conflictFallbackProvider = conflictFallbackProvider,
            requiresIdentityResolution = true
        )
    }

    private fun fallbackByItemType(
        normalized: NormalizedMetadataRequest,
        trace: MutableList<MetadataRouteTrace>,
        conflictFallbackProvider: MetadataPrimaryProvider? = null,
        requiresIdentityResolution: Boolean = false
    ): MetadataRoute =
        when (normalized.contentType) {
            ContentType.MOVIE -> {
                trace += MetadataRouteTrace(MetadataDecisionReason.ITEM_TYPE_MOVIE, "Movie item type routes to TMDB for ${normalized.parentId}")
                route(
                    normalized = normalized,
                    provider = MetadataPrimaryProvider.TMDB,
                    mediaKind = MetadataMediaKind.MOVIE,
                    reason = MetadataDecisionReason.ITEM_TYPE_MOVIE,
                    targetId = normalized.parentId,
                    trace = trace,
                    requiresIdentityResolution = requiresIdentityResolution
                )
            }
            ContentType.SERIES,
            ContentType.TV -> {
                trace += MetadataRouteTrace(MetadataDecisionReason.ITEM_TYPE_SERIES, "Series item type routes to TVDB for ${normalized.parentId}")
                route(
                    normalized = normalized,
                    provider = MetadataPrimaryProvider.TVDB,
                    mediaKind = MetadataMediaKind.SERIES,
                    reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                    targetId = normalized.parentId,
                    trace = trace,
                    requiresIdentityResolution = requiresIdentityResolution
                )
            }
            else -> {
                val provider = conflictFallbackProvider ?: MetadataPrimaryProvider.TMDB
                trace += MetadataRouteTrace(MetadataDecisionReason.UNSUPPORTED_TYPE, "Unsupported item type routes to $provider for ${normalized.parentId}")
                route(
                    normalized = normalized,
                    provider = provider,
                    mediaKind = MetadataMediaKind.UNKNOWN,
                    reason = MetadataDecisionReason.UNSUPPORTED_TYPE,
                    targetId = normalized.parentId,
                    trace = trace,
                    requiresIdentityResolution = requiresIdentityResolution
                )
            }
        }

    private fun route(
        normalized: NormalizedMetadataRequest,
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        reason: MetadataDecisionReason,
        targetId: String,
        trace: List<MetadataRouteTrace>,
        requiresIdentityResolution: Boolean = false
    ): MetadataRoute {
        traceEvents.emitRouteDecision(
            contentId = normalized.originalContentId,
            parentId = normalized.parentId,
            itemType = normalized.contentType.name.lowercase(),
            provider = provider.name,
            mediaKind = mediaKind.name,
            reason = reason.name,
            usedInputs = listOf("item.id", "item.type", "AnimeIdentityIndex", "IdMappingStore"),
            ignoredInputs = listOf("catalog.type", "addon.name", "genre", "animeType", "links", "trend"),
            targetIdRequiresIdentityResolution = requiresIdentityResolution,
            targetIds = mapOf(provider.name to targetId)
        )
        return MetadataRoute(
            provider = provider,
            parentId = normalized.parentId,
            mediaKind = mediaKind,
            reason = reason,
            sourceContext = normalized.sourceContext,
            language = normalized.language,
            seasonNumber = normalized.seasonNumber,
            targetIds = mapOf(provider to targetId),
            targetIdRequiresIdentityResolution = requiresIdentityResolution,
            trace = trace.toList()
        )
    }
}
