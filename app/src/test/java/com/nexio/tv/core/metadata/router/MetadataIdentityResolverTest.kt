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
            lookup = FakeLookup(tmdbToTvdb = mapOf("1399" to "121361"))
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
    fun `imdb series conflict resolves tvdb target through tvdb remote id lookup only`() = runTest {
        val lookup = FakeLookup(imdbToTvdb = mapOf("tt0944947" to "121361"))
        val resolver = MetadataIdentityResolver(lookup = lookup)
        val route = conflictRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tt0944947",
            mediaKind = MetadataMediaKind.SERIES
        )

        val resolved = resolver.resolve(route)

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("121361", resolved.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals(listOf(LookupCall.ImdbToTvdb("tt0944947")), lookup.calls)
    }

    @Test
    fun `tvdb movie conflict resolves tmdb target before execution`() = runTest {
        val resolver = MetadataIdentityResolver(
            lookup = FakeLookup(tvdbToTmdb = mapOf("121361" to "550"))
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
        val resolver = MetadataIdentityResolver(FakeLookup())
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

    private sealed class LookupCall {
        data class TmdbToTvdb(val tmdbId: String) : LookupCall()
        data class ImdbToTvdb(val imdbId: String) : LookupCall()
        data class TvdbToTmdb(val tvdbId: String) : LookupCall()
    }

    private class FakeLookup(
        private val tmdbToTvdb: Map<String, String> = emptyMap(),
        private val imdbToTvdb: Map<String, String> = emptyMap(),
        private val tvdbToTmdb: Map<String, String> = emptyMap()
    ) : MetadataIdentityResolver.Lookup {
        val calls = mutableListOf<LookupCall>()

        override suspend fun tmdbToTvdb(tmdbId: String): String? {
            calls += LookupCall.TmdbToTvdb(tmdbId)
            return tmdbToTvdb[tmdbId]
        }

        override suspend fun imdbToTvdb(imdbId: String): String? {
            calls += LookupCall.ImdbToTvdb(imdbId)
            return imdbToTvdb[imdbId]
        }

        override suspend fun tvdbToTmdb(tvdbId: String): String? {
            calls += LookupCall.TvdbToTmdb(tvdbId)
            return tvdbToTmdb[tvdbId]
        }
    }
}
