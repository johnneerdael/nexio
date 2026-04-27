package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataIdentityResolver @Inject constructor(
    private val lookup: Lookup,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    interface Lookup {
        suspend fun tmdbToTvdb(tmdbId: String): String?
        suspend fun tvdbToTmdb(tvdbId: String): String?
    }

    suspend fun resolve(route: MetadataRoute): MetadataRoute {
        if (!route.targetIdRequiresIdentityResolution) return route
        val parsed = MetadataIdParser.parse(route.parentId)
        val (resolverName, apiShapeId, lookupResult) = when {
            parsed.scheme == AnimeIdScheme.TMDB && route.provider == MetadataPrimaryProvider.TVDB ->
                Triple("TmdbToTvdbResolver", "identity.tmdb_to_tvdb", lookup.tmdbToTvdb(parsed.value))
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

        if (lookupResult == null) return route

        return route.copy(
            targetIds = route.targetIds + (route.provider to lookupResult),
            targetIdRequiresIdentityResolution = false,
            trace = route.trace + MetadataRouteTrace(
                reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
                detail = "provider-native conflict identity resolved for ${route.provider}"
            )
        )
    }
}
