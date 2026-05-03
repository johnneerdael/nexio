package com.nexio.tv.core.metadata.router

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * F-B-06 pin: when an identity lookup returns null, the resolver MUST persist a NEGATIVE
 * IdMapping in IdMappingStore. A second resolve() within NEGATIVE_TTL_MS short-circuits via
 * the store read (no lookup call).
 */
class MetadataIdentityResolverNegativeCacheTest {

    @Test
    fun `legacy tmdb negative mapping does not block tmdb tv series bridge`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = MetadataIdParser.parse("tmdb:37854"),
                    provider = MetadataPrimaryProvider.TVDB,
                    providerId = "",
                    source = IdMappingSource.NEGATIVE,
                    evidence = "legacy tmdb-series lookup failed before kind-aware ids"
                )
            )
        )
        val lookup = mockk<MetadataIdentityResolver.Lookup>()
        coEvery { lookup.tmdbToTvdb("37854") } returns "81797"
        coEvery { lookup.tvdbToTmdb(any()) } returns null

        val resolver = MetadataIdentityResolver(lookup = lookup, idMappingStore = store)
        val resolved = resolver.resolve(
            MetadataRoute(
                parentId = "tmdb:37854",
                provider = MetadataPrimaryProvider.TVDB,
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:37854"),
                targetIdRequiresIdentityResolution = true,
                trace = emptyList()
            )
        )

        assertFalse(resolved.targetIdRequiresIdentityResolution)
        assertEquals("81797", resolved.targetIds[MetadataPrimaryProvider.TVDB])
        coVerify(exactly = 1) { lookup.tmdbToTvdb("37854") }

        val kindAwareMapping = store.readRaw(
            provider = MetadataPrimaryProvider.TVDB,
            sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "tv:37854", "tmdb:tv:37854")
        )
        assertNotNull("expected kind-aware tmdb tv mapping after successful lookup", kindAwareMapping)
        assertEquals(IdMappingSource.PROVIDER_LOOKUP, kindAwareMapping?.source)
        assertEquals("81797", kindAwareMapping?.providerId)
    }

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
            sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "tv:nonexistent", "tmdb:tv:nonexistent")
        )
        assertNotNull("expected NEGATIVE mapping in store after failed lookup", mapping)
        assertEquals(IdMappingSource.NEGATIVE, mapping?.source)
    }
}
