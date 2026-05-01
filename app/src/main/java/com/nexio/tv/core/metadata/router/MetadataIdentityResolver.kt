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
        suspend fun imdbToTvdb(imdbId: String): String? = null
        suspend fun tvdbToTmdb(tvdbId: String): String?
    }

    suspend fun resolve(route: MetadataRoute): MetadataRoute {
        if (!route.targetIdRequiresIdentityResolution) return route

        val parsed = MetadataIdParser.parse(route.parentId)
        val now = System.currentTimeMillis()

        // F-B-06: short-circuit on prior negative-cached failure
        if (parsed.scheme != AnimeIdScheme.UNKNOWN) {
            val existing = idMappingStore.readRaw(provider = route.provider, sourceId = parsed)
            if (existing?.source == IdMappingSource.NEGATIVE) {
                return route
            }
        }

        val (resolverName, apiShapeId, lookupResult) = when {
            parsed.scheme == AnimeIdScheme.TMDB && route.provider == MetadataPrimaryProvider.TVDB ->
                Triple("TmdbToTvdbResolver", "identity.tmdb_to_tvdb", lookup.tmdbToTvdb(parsed.value))
            parsed.scheme == AnimeIdScheme.IMDB && route.provider == MetadataPrimaryProvider.TVDB ->
                Triple("ImdbToTvdbResolver", "identity.imdb_to_tvdb", lookup.imdbToTvdb(parsed.value))
            parsed.scheme == AnimeIdScheme.TVDB && route.provider == MetadataPrimaryProvider.TMDB ->
                Triple("TvdbToTmdbResolver", "identity.tvdb_to_tmdb", lookup.tvdbToTmdb(parsed.value))
            else -> Triple("Unknown", "identity.unknown", null)
        }

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
            if (parsed.scheme != AnimeIdScheme.UNKNOWN) {
                idMappingStore.persist(
                    IdMapping(
                        sourceId = parsed,
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
}
