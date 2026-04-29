package com.nexio.tv.core.metadata.router

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * F-B-06 pin: when an identity lookup returns null, the resolver MUST persist a NEGATIVE
 * IdMapping in IdMappingStore. A second resolve() within NEGATIVE_TTL_MS short-circuits via
 * the store read (no lookup call).
 */
class MetadataIdentityResolverNegativeCacheTest {

    @Test
    fun `failed lookup writes NEGATIVE mapping and second resolve short-circuits`() = runTest {
        val store = InMemoryIdMappingStore()
        val lookup = mockk<MetadataIdentityResolver.Lookup>()
        coEvery { lookup.tmdbToTvdb("nonexistent") } returns null
        coEvery { lookup.tvdbToTmdb(any()) } returns null

        // idMappingStore is the new param added by Task 7 — does not exist yet (compile RED)
        val resolver = MetadataIdentityResolver(lookup = lookup, idMappingStore = store)

        // Route where resolver will look up a TVDB id from a TMDB parentId (tmdb-as-series conflict)
        val routeIn = MetadataRoute(
            parentId = "tmdb:nonexistent",
            provider = MetadataPrimaryProvider.TVDB,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tmdb:nonexistent"),
            targetIdRequiresIdentityResolution = true,
            trace = emptyList()
        )

        // First call: lookup returns null → resolver MUST write a NEGATIVE mapping
        resolver.resolve(routeIn)
        coVerify(exactly = 1) { lookup.tmdbToTvdb("nonexistent") }

        // Second call: store has NEGATIVE mapping → MUST short-circuit, no lookup call
        resolver.resolve(routeIn)
        coVerify(exactly = 1) { lookup.tmdbToTvdb("nonexistent") }  // STILL 1 — second resolve didn't call

        // Verify the NEGATIVE mapping is in the store.
        // readRaw() does NOT filter NEGATIVE entries — added by Task 7, compile RED until then.
        val mapping = store.readRaw(
            provider = MetadataPrimaryProvider.TVDB,
            sourceId = MetadataIdParser.parse("tmdb:nonexistent")
        )
        assertNotNull("expected NEGATIVE mapping in store after failed lookup", mapping)
        assertEquals(IdMappingSource.NEGATIVE, mapping?.source)
    }
}
