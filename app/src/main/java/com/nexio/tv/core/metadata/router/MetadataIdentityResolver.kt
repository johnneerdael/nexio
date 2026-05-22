package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataIdentityResolver @Inject constructor(
    private val lookup: Lookup,
    private val idMappingStore: IdMappingStore = InMemoryIdMappingStore(),
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    interface Lookup {
        suspend fun tmdbToTvdb(tmdbId: String): String?
        suspend fun imdbToTmdb(imdbId: String, mediaType: String): String? = null
        suspend fun imdbToTvdb(imdbId: String): String? = null
        suspend fun tvdbToTmdb(tvdbId: String): String?
    }

    suspend fun resolve(route: MetadataRoute): MetadataRoute {
        if (!route.targetIdRequiresIdentityResolution) return route

        val parsed = MetadataIdParser.parse(route.parentId)
        val tvdbSidecarSourceId = route.tvdbSidecarSourceId()
        val cacheSourceId = tvdbSidecarSourceId ?: parsed.identityCacheSourceId(route)
        val now = System.currentTimeMillis()
        val lookupPlan = when {
            tvdbSidecarSourceId != null -> IdentityLookupPlan(
                resolverName = "TvdbToTmdbResolver",
                apiShapeId = "identity.tvdb_to_tmdb"
            ) { lookup.tvdbToTmdb(tvdbSidecarSourceId.value) }
            parsed.scheme == AnimeIdScheme.TMDB && route.provider == MetadataPrimaryProvider.TVDB -> IdentityLookupPlan(
                resolverName = "TmdbToTvdbResolver",
                apiShapeId = "identity.tmdb_to_tvdb"
            ) { lookup.tmdbToTvdb(parsed.value) }
            parsed.scheme == AnimeIdScheme.IMDB && route.provider == MetadataPrimaryProvider.TVDB -> IdentityLookupPlan(
                resolverName = "ImdbToTvdbResolver",
                apiShapeId = "identity.imdb_to_tvdb"
            ) { lookup.imdbToTvdb(parsed.value) }
            parsed.scheme == AnimeIdScheme.IMDB && route.provider == MetadataPrimaryProvider.TMDB -> IdentityLookupPlan(
                resolverName = "ImdbToTmdbResolverV2",
                apiShapeId = "identity.imdb_to_tmdb"
            ) { lookup.imdbToTmdb(parsed.value, route.tmdbLookupMediaType()) }
            parsed.scheme == AnimeIdScheme.TVDB && route.provider == MetadataPrimaryProvider.TMDB -> IdentityLookupPlan(
                resolverName = "TvdbToTmdbResolver",
                apiShapeId = "identity.tvdb_to_tmdb"
            ) { lookup.tvdbToTmdb(parsed.value) }
            else -> IdentityLookupPlan(
                resolverName = "Unknown",
                apiShapeId = "identity.unknown"
            ) { null }
        }

        // F-B-06: short-circuit on prior negative-cached failure for the same resolver.
        // Older builds persisted `Unknown` negatives for routes that now have a real resolver
        // (e.g. IMDb series -> TMDB TV). Those must not poison the upgraded resolver path.
        if (cacheSourceId.scheme != AnimeIdScheme.UNKNOWN) {
            val existing = idMappingStore.readRaw(provider = route.provider, sourceId = cacheSourceId)
            if (
                existing?.source == IdMappingSource.NEGATIVE &&
                existing.evidence.contains("via ${lookupPlan.resolverName}")
            ) {
                return route
            }
        }

        val resolverName = lookupPlan.resolverName
        val apiShapeId = lookupPlan.apiShapeId
        val lookupResult = lookupPlan.lookup()

        traceEvents.emitIdentityResolution(
            sourceId = route.parentId,
            targetProvider = route.provider.name,
            resolver = resolverName,
            apiShapeId = apiShapeId,
            cacheDecision = "UNKNOWN",
            executedNetwork = false,
            resultId = lookupResult,
            success = lookupResult != null
        )

        if (lookupResult == null) {
            // F-B-06: persist NEGATIVE mapping for 24-hour TTL
            if (cacheSourceId.scheme != AnimeIdScheme.UNKNOWN) {
                idMappingStore.persist(
                    IdMapping(
                        sourceId = cacheSourceId,
                        provider = route.provider,
                        providerId = "",
                        source = IdMappingSource.NEGATIVE,
                        evidence = "identity lookup failed via $resolverName",
                        expiresAtEpochMs = IdMappingTtlPolicy.expiresAt(IdMappingSource.NEGATIVE, now)
                    )
                )
            }
            return route
        }

        if (cacheSourceId.scheme != AnimeIdScheme.UNKNOWN) {
            idMappingStore.persist(
                IdMapping(
                    sourceId = cacheSourceId,
                    provider = route.provider,
                    providerId = lookupResult,
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "identity lookup via $resolverName",
                    expiresAtEpochMs = IdMappingTtlPolicy.expiresAt(IdMappingSource.PROVIDER_LOOKUP, now)
                )
            )
        }

        // F2-B-06: ROUTING_ID_TYPE_CONFLICT semantic clarification (cluster G F2-B-01, commit 37f44a99b).
        // The conflict-trace entry is appended ONLY when the route's original decision reason was
        // already ROUTING_ID_TYPE_CONFLICT — the entry signals "we resolved a known provider-native
        // conflict via identity lookup", not "we performed identity resolution". For routes whose
        // reason is ITEM_TYPE_SERIES, ITEM_TYPE_MOVIE, or any other non-conflict reason that
        // incidentally require identity resolution (e.g. tt14403178 → tvdb:NN look-up), no
        // ROUTING_ID_TYPE_CONFLICT trace entry is added, avoiding false-positive conflict signals
        // in downstream trace consumers.
        return route.copy(
            targetIds = route.targetIds + (route.provider to lookupResult),
            targetIdRequiresIdentityResolution = false,
            trace = if (route.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT) {
                // F2-B-01: only append the conflict-trace entry when the route ACTUALLY originated as a
                // provider-native conflict. For ITEM_TYPE_SERIES / ITEM_TYPE_MOVIE routes that happen to
                // need identity resolution (e.g. tt14403178 → tvdb:NN), no conflict happened.
                route.trace + MetadataRouteTrace(
                    reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
                    detail = "provider-native conflict identity resolved for ${route.provider}"
                )
            } else route.trace
        )
    }

    private fun ParsedMetadataId.identityCacheSourceId(route: MetadataRoute): ParsedMetadataId =
        when {
            scheme == AnimeIdScheme.TMDB &&
                route.provider == MetadataPrimaryProvider.TVDB &&
                route.mediaKind == MetadataMediaKind.SERIES ->
                ParsedMetadataId(
                    scheme = AnimeIdScheme.TMDB,
                    value = "tv:$value",
                    raw = "tmdb:tv:$value"
                )
            else -> this
        }

    private fun MetadataRoute.tmdbLookupMediaType(): String =
        when (mediaKind) {
            MetadataMediaKind.SERIES -> "tv"
            else -> "movie"
        }

    private class IdentityLookupPlan(
        val resolverName: String,
        val apiShapeId: String,
        val lookup: suspend () -> String?
    )

    private fun MetadataRoute.tvdbSidecarSourceId(): ParsedMetadataId? {
        if (provider != MetadataPrimaryProvider.TMDB) return null
        if (mediaKind != MetadataMediaKind.SERIES) return null
        val targetId = targetIds[MetadataPrimaryProvider.TVDB] ?: return null
        return targetId.toTvdbParsedIdOrNull()
    }

    private fun String.toTvdbParsedIdOrNull(): ParsedMetadataId? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null

        val parsed = MetadataIdParser.parse(trimmed)
        if (parsed.scheme == AnimeIdScheme.TVDB) {
            return ParsedMetadataId(
                scheme = AnimeIdScheme.TVDB,
                value = parsed.value,
                raw = "tvdb:${parsed.value}"
            )
        }

        if (trimmed.all { it.isDigit() }) {
            return ParsedMetadataId(
                scheme = AnimeIdScheme.TVDB,
                value = trimmed,
                raw = "tvdb:$trimmed"
            )
        }

        return null
    }
}
