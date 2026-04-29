package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class MetadataRouter @Inject constructor(
    private val normalizer: MetadataRequestNormalizer,
    private val animeIdentityIndex: AnimeIdentityIndex,
    private val idMappingStore: IdMappingStore,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    ),
    private val tmdbExternalIdLookup: TmdbExternalIdLookupProvider? = null
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

    private suspend fun kitsuDirect(
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

    private suspend fun providerNativeOrConflict(
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

    private suspend fun fallbackByItemType(
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

    private suspend fun route(
        normalized: NormalizedMetadataRequest,
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        reason: MetadataDecisionReason,
        targetId: String,
        trace: List<MetadataRouteTrace>,
        requiresIdentityResolution: Boolean = false
    ): MetadataRoute {
        val targetIdResult = buildTargetIds(
            normalized = normalized,
            provider = provider,
            targetId = targetId
        )
        val effectiveRequiresIdentityResolution = requiresIdentityResolution || targetIdResult.requiresIdentityResolution
        traceEvents.emitRouteDecision(
            contentId = normalized.originalContentId,
            parentId = normalized.parentId,
            itemType = normalized.contentType.name.lowercase(),
            provider = provider.name,
            mediaKind = mediaKind.name,
            reason = reason.name,
            usedInputs = listOf("item.id", "item.type", "previewStableIds", "AnimeIdentityIndex", "IdMappingStore"),
            ignoredInputs = listOf("catalog.type", "addon.name", "genre", "animeType", "links", "trend"),
            targetIdRequiresIdentityResolution = effectiveRequiresIdentityResolution,
            targetIds = targetIdResult.targetIds.mapKeys { it.key.name }
        )
        return MetadataRoute(
            provider = provider,
            parentId = normalized.parentId,
            mediaKind = mediaKind,
            reason = reason,
            sourceContext = normalized.sourceContext,
            language = normalized.language,
            seasonNumber = normalized.seasonNumber,
            targetIds = targetIdResult.targetIds,
            targetIdRequiresIdentityResolution = effectiveRequiresIdentityResolution,
            trace = trace.toList(),
            pagination = normalized.pagination
        )
    }

    private data class TargetIdBuildResult(
        val targetIds: Map<MetadataPrimaryProvider, String>,
        val requiresIdentityResolution: Boolean
    )

    private suspend fun buildTargetIds(
        normalized: NormalizedMetadataRequest,
        provider: MetadataPrimaryProvider,
        targetId: String
    ): TargetIdBuildResult {
        val builder = mutableMapOf<MetadataPrimaryProvider, String>()
        val stableIds = normalized.sourceContext.previewStableIds

        stableIds.tmdb?.numericProviderTarget("tmdb")?.let { builder[MetadataPrimaryProvider.TMDB] = it }
        stableIds.tvdb?.numericProviderTarget("tvdb")?.let { builder[MetadataPrimaryProvider.TVDB] = it }
        stableIds.kitsu?.numericProviderTarget("kitsu")?.let { builder[MetadataPrimaryProvider.KITSU] = it }
        stableIds.imdb?.canonicalImdbTarget()?.let { builder[MetadataPrimaryProvider.IMDB] = it }

        val targetParsed = MetadataIdParser.parse(targetId)
        when (targetParsed.scheme) {
            AnimeIdScheme.TMDB -> builder.putIfAbsent(MetadataPrimaryProvider.TMDB, "tmdb:${targetParsed.value}")
            AnimeIdScheme.TVDB -> builder.putIfAbsent(MetadataPrimaryProvider.TVDB, "tvdb:${targetParsed.value}")
            AnimeIdScheme.KITSU -> builder.putIfAbsent(MetadataPrimaryProvider.KITSU, "kitsu:${targetParsed.value}")
            AnimeIdScheme.IMDB -> builder.putIfAbsent(MetadataPrimaryProvider.IMDB, targetParsed.value.canonicalImdbTarget() ?: targetParsed.value)
            else -> builder.putIfAbsent(provider, targetId)
        }

        val originalParsed = MetadataIdParser.parse(normalized.originalContentId)
        if (originalParsed.scheme == AnimeIdScheme.IMDB) {
            builder.putIfAbsent(MetadataPrimaryProvider.IMDB, originalParsed.value)
        }

        val lookup = tmdbExternalIdLookup
        if (provider == MetadataPrimaryProvider.TMDB &&
            !builder.containsKey(MetadataPrimaryProvider.TMDB) &&
            builder.containsKey(MetadataPrimaryProvider.IMDB) &&
            lookup != null
        ) {
            val mediaType = when (normalized.contentType) {
                ContentType.SERIES, ContentType.TV -> "tv"
                else -> "movie"
            }
            runCatching { lookup.findTmdbIdByImdbId(builder.getValue(MetadataPrimaryProvider.IMDB), mediaType) }
                .getOrNull()
                ?.let { tmdbId -> builder[MetadataPrimaryProvider.TMDB] = "tmdb:$tmdbId" }
        }

        if (provider == MetadataPrimaryProvider.TMDB &&
            !builder.containsKey(MetadataPrimaryProvider.IMDB) &&
            lookup != null
        ) {
            val tmdbId = builder[MetadataPrimaryProvider.TMDB]
                ?.substringAfter(':')
                ?.toIntOrNull()
            if (tmdbId != null) {
                val mediaType = when (normalized.contentType) {
                    ContentType.SERIES, ContentType.TV -> "tv"
                    else -> "movie"
                }
                runCatching { lookup.findImdbIdByTmdbId(tmdbId, mediaType) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.canonicalImdbTarget()
                    ?.let { imdb -> builder[MetadataPrimaryProvider.IMDB] = imdb }
            }
        }

        val providerHasNativeTarget = when (provider) {
            MetadataPrimaryProvider.TMDB -> builder.containsKey(MetadataPrimaryProvider.TMDB)
            MetadataPrimaryProvider.TVDB -> builder.containsKey(MetadataPrimaryProvider.TVDB)
            MetadataPrimaryProvider.KITSU -> builder.containsKey(MetadataPrimaryProvider.KITSU)
            MetadataPrimaryProvider.IMDB -> builder.containsKey(MetadataPrimaryProvider.IMDB)
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL -> builder.containsKey(provider)
        }

        val canResolveThroughKnownCrossProviderTarget = when (provider) {
            MetadataPrimaryProvider.TVDB -> builder.containsKey(MetadataPrimaryProvider.IMDB) || builder.containsKey(MetadataPrimaryProvider.TMDB)
            MetadataPrimaryProvider.TMDB -> builder.containsKey(MetadataPrimaryProvider.TVDB)
            else -> false
        }

        return TargetIdBuildResult(
            targetIds = builder.toMap(),
            requiresIdentityResolution = !providerHasNativeTarget && canResolveThroughKnownCrossProviderTarget
        )
    }

    private fun String.numericProviderTarget(prefix: String): String? {
        val trimmed = trim().takeIf { it.isNotBlank() } ?: return null
        val raw = if (trimmed.startsWith("$prefix:", ignoreCase = true)) {
            trimmed.substringAfter(':').trim()
        } else {
            trimmed
        }
        if (!Regex("^\\d+$").matches(raw)) return null
        return "$prefix:$raw"
    }

    private fun String.canonicalImdbTarget(): String? {
        val trimmed = trim().takeIf { it.isNotBlank() } ?: return null
        val raw = if (trimmed.startsWith("imdb:", ignoreCase = true)) trimmed.substringAfter(':').trim() else trimmed
        if (!Regex("^tt\\d+$", RegexOption.IGNORE_CASE).matches(raw)) return null
        return "tt" + raw.drop(2)
    }
}
