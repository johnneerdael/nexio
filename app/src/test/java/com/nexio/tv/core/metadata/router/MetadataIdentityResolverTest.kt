package com.nexio.tv.core.metadata.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIdentityResolverTest {
    @Test
    fun `tmdb series conflict resolves tvdb target before execution`() = runTest {
        val resolver = MetadataIdentityResolver(
            lookup = FakeLookup(tmdbToTvdb = mapOf("1399" to "121361"), tvdbToTmdb = emptyMap())
        )
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES
        )

        val resolved = resolver.resolve(route)

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("121361", resolved.targetIds[MetadataPrimaryProvider.TVDB])
    }

    @Test
    fun `tvdb movie conflict resolves tmdb target before execution`() = runTest {
        val resolver = MetadataIdentityResolver(
            lookup = FakeLookup(tmdbToTvdb = emptyMap(), tvdbToTmdb = mapOf("121361" to "550"))
        )
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tvdb:121361",
            mediaKind = MetadataMediaKind.MOVIE
        )

        val resolved = resolver.resolve(route)

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("550", resolved.targetIds[MetadataPrimaryProvider.TMDB])
    }

    @Test
    fun `unresolved conflict remains marked unresolved`() = runTest {
        val resolver = MetadataIdentityResolver(FakeLookup(emptyMap(), emptyMap()))
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES
        )

        val resolved = resolver.resolve(route)

        assertTrue(resolved.targetIdRequiresIdentityResolution)
    }

    private fun conflictRoute(
        provider: MetadataPrimaryProvider,
        parentId: String,
        mediaKind: MetadataMediaKind
    ) = MetadataRoute(
        provider = provider,
        parentId = parentId,
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
        sourceContext = MetadataSourceContext(),
        targetIds = mapOf(provider to parentId),
        trace = emptyList(),
        targetIdRequiresIdentityResolution = true
    )

    private class FakeLookup(
        private val tmdbToTvdb: Map<String, String>,
        private val tvdbToTmdb: Map<String, String>
    ) : MetadataIdentityResolver.Lookup {
        override suspend fun tmdbToTvdb(tmdbId: String): String? = tmdbToTvdb[tmdbId]
        override suspend fun tvdbToTmdb(tvdbId: String): String? = tvdbToTmdb[tvdbId]
    }
}
