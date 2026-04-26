package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataIdentityResolver @Inject constructor(
    private val lookup: Lookup
) {
    interface Lookup {
        suspend fun tmdbToTvdb(tmdbId: String): String?
        suspend fun tvdbToTmdb(tvdbId: String): String?
    }

    suspend fun resolve(route: MetadataRoute): MetadataRoute {
        if (!route.targetIdRequiresIdentityResolution) return route
        val parsed = MetadataIdParser.parse(route.parentId)
        val resolvedTarget = when {
            parsed.scheme == AnimeIdScheme.TMDB && route.provider == MetadataPrimaryProvider.TVDB ->
                lookup.tmdbToTvdb(parsed.value)
            parsed.scheme == AnimeIdScheme.TVDB && route.provider == MetadataPrimaryProvider.TMDB ->
                lookup.tvdbToTmdb(parsed.value)
            else -> null
        } ?: return route

        return route.copy(
            targetIds = route.targetIds + (route.provider to resolvedTarget),
            targetIdRequiresIdentityResolution = false,
            trace = route.trace + MetadataRouteTrace(
                reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
                detail = "provider-native conflict identity resolved for ${route.provider}"
            )
        )
    }
}
