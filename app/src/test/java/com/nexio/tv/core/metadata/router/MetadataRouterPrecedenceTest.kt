package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterPrecedenceTest {
    @Test
    fun `kitsu prefix routes directly without anime identity lookup`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex()
        val router = router(animeIndex = animeIndex)

        val route = router.route(request("kitsu:7442:1:1", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataMediaKind.ANIME, route.mediaKind)
        assertEquals(MetadataDecisionReason.KITSU_PREFIX_DIRECT, route.reason)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertFalse(route.targetIdRequiresIdentityResolution)
        assertTrue(animeIndex.lookups.isEmpty())
    }

    @Test
    fun `anime prefixes map deterministically to kitsu`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(
                AnimeIdentityMapping(AnimeIdScheme.MAL, "21", "7442"),
                AnimeIdentityMapping(AnimeIdScheme.ANILIST, "16498", "100"),
                AnimeIdentityMapping(AnimeIdScheme.ANIDB, "9541", "200")
            )
        )
        val router = router(animeIndex = animeIndex)

        val mal = router.route(request("mal:21", ContentType.SERIES))
        val anilist = router.route(request("anilist:16498", ContentType.SERIES))
        val anidb = router.route(request("anidb:9541", ContentType.SERIES))

        assertEquals("kitsu:7442", mal.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals("kitsu:100", anilist.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals("kitsu:200", anidb.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, mal.reason)
        assertEquals(3, animeIndex.lookups.size)
    }

    @Test
    fun `neutral imdb routes to kitsu from local mapping before fribb`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt0388629", "9999"))
        )
        val sourceId = ParsedMetadataId(AnimeIdScheme.IMDB, "tt0388629", "tt0388629")
        val mappingStore = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "7442",
                    source = IdMappingSource.LOCAL,
                    evidence = "seeded"
                )
            )
        )
        val router = router(animeIndex = animeIndex, mappingStore = mappingStore)

        val route = router.route(request("tt0388629", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals(MetadataDecisionReason.ID_MAPPING_TO_KITSU, route.reason)
        assertTrue(animeIndex.lookups.isEmpty())
    }

    @Test
    fun `neutral imdb routes to kitsu from fribb when local mapping absent`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt0388629", "7442"))
        )
        val mappingStore = InMemoryIdMappingStore()
        val router = router(animeIndex = animeIndex, mappingStore = mappingStore)

        val route = router.route(request("tt0388629", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals("kitsu:7442", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals(MetadataDecisionReason.ID_MAPPING_TO_KITSU, route.reason)
        assertEquals(listOf(AnimeIdentityLookup(AnimeIdScheme.IMDB, "tt0388629")), animeIndex.lookups)
        assertEquals(
            IdMappingSource.FRIBB,
            mappingStore.lookupKitsu(ParsedMetadataId(AnimeIdScheme.IMDB, "tt0388629", "tt0388629"))?.source
        )
    }

    @Test
    fun `mixed-case imdb routes through normalized in-memory mapping`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt0388629", "7442"))
        )
        val mappingStore = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = ParsedMetadataId(AnimeIdScheme.IMDB, "tt0388629", "tt0388629"),
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "7442",
                    source = IdMappingSource.LOCAL,
                    evidence = "seeded"
                )
            )
        )
        val router = router(animeIndex = animeIndex, mappingStore = mappingStore)

        val upperBare = router.route(request("TT0388629", ContentType.SERIES))
        val upperPrefixed = router.route(request("IMDb:TT0388629", ContentType.SERIES))
        val lowerPrefixed = router.route(request("imdb:tt0388629", ContentType.SERIES))

        assertEquals("kitsu:7442", upperBare.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals("kitsu:7442", upperPrefixed.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals("kitsu:7442", lowerPrefixed.targetIds[MetadataPrimaryProvider.KITSU])
        assertTrue(animeIndex.lookups.isEmpty())
    }

    @Test
    fun `tmdb and tvdb provider native ids route directly without fribb lookup`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex()
        val router = router(animeIndex = animeIndex)

        val movie = router.route(request("tmdb:550", ContentType.MOVIE))
        val series = router.route(request("tvdb:81189", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.TMDB, movie.provider)
        assertEquals("tmdb:550", movie.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, movie.reason)
        assertEquals(MetadataPrimaryProvider.TVDB, series.provider)
        assertEquals("tvdb:81189", series.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, series.reason)
        assertTrue(animeIndex.lookups.isEmpty())
    }

    @Test
    fun `provider native id type conflict records conflict and falls back by item type`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex()
        val router = router(animeIndex = animeIndex)

        val route = router.route(request("tmdb:1399", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tmdb:1399", route.parentId)
        assertEquals("tmdb:1399", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
        assertTrue(route.targetIdRequiresIdentityResolution)
        assertTrue(route.trace.any { it.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT })
        assertTrue(animeIndex.lookups.isEmpty())
    }

    @Test
    fun `anime identity index rejects tmdb and tvdb ids`() {
        val animeIndex = InMemoryAnimeIdentityIndex()

        assertThrows(IllegalArgumentException::class.java) {
            runTest { animeIndex.resolveKitsuId(ParsedMetadataId(AnimeIdScheme.TMDB, "550", "tmdb:550")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest { animeIndex.resolveKitsuId(ParsedMetadataId(AnimeIdScheme.TVDB, "81189", "tvdb:81189")) }
        }
    }

    @Test
    fun `mapping ttl policy applies source-specific expirations`() {
        val now = 1_000L
        val negativeExpiry = IdMappingTtlPolicy.expiresAt(IdMappingSource.NEGATIVE, now)

        assertEquals(now + IdMappingTtlPolicy.NEGATIVE_TTL_MS, negativeExpiry)
        assertEquals(null, IdMappingTtlPolicy.expiresAt(IdMappingSource.LOCAL, now))
        assertEquals(now + IdMappingTtlPolicy.POSITIVE_TTL_MS, IdMappingTtlPolicy.expiresAt(IdMappingSource.PROVIDER_LOOKUP, now))
        assertEquals(now + IdMappingTtlPolicy.POSITIVE_TTL_MS, IdMappingTtlPolicy.expiresAt(IdMappingSource.RAIL_PREVIEW, now))
        assertEquals(now + IdMappingTtlPolicy.POSITIVE_TTL_MS, IdMappingTtlPolicy.expiresAt(IdMappingSource.ROUTER_OBSERVED, now))
        assertEquals(now + IdMappingTtlPolicy.STATIC_ANIME_TTL_MS, IdMappingTtlPolicy.expiresAt(IdMappingSource.FRIBB, now))
    }

    @Test
    fun `mapping priority is local provider lookup rail preview fribb router observed then negative`() = runTest {
        val sourceId = ParsedMetadataId(AnimeIdScheme.IMDB, "tt0388629", "tt0388629")
        val store = InMemoryIdMappingStore()

        store.persist(mapping(sourceId, "negative", IdMappingSource.NEGATIVE))
        store.persist(mapping(sourceId, "provider", IdMappingSource.PROVIDER_LOOKUP))
        store.persist(mapping(sourceId, "rail", IdMappingSource.RAIL_PREVIEW))
        store.persist(mapping(sourceId, "fribb", IdMappingSource.FRIBB))
        store.persist(mapping(sourceId, "router", IdMappingSource.ROUTER_OBSERVED))
        store.persist(mapping(sourceId, "local", IdMappingSource.LOCAL))
        store.persist(mapping(sourceId, "negative-again", IdMappingSource.NEGATIVE))

        assertEquals("local", store.lookupKitsu(sourceId)?.providerId)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.LOCAL, IdMappingSource.PROVIDER_LOOKUP) < 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.PROVIDER_LOOKUP, IdMappingSource.RAIL_PREVIEW) < 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.RAIL_PREVIEW, IdMappingSource.FRIBB) < 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.FRIBB, IdMappingSource.ROUTER_OBSERVED) < 0)
        assertTrue(IdMappingTtlPolicy.comparePriority(IdMappingSource.NEGATIVE, IdMappingSource.ROUTER_OBSERVED) > 0)
    }

    @Test
    fun `mal prefix uses local mapping before fribb`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.MAL, "21", "fribb-7442"))
        )
        val sourceId = ParsedMetadataId(AnimeIdScheme.MAL, "21", "mal:21")
        val mappingStore = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "local-7442",
                    source = IdMappingSource.LOCAL,
                    evidence = "manual override"
                )
            )
        )
        val router = router(animeIndex = animeIndex, mappingStore = mappingStore)

        val route = router.route(request("mal:21", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals(MetadataMediaKind.ANIME, route.mediaKind)
        assertEquals("kitsu:local-7442", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, route.reason)
        assertTrue("Fribb must not be consulted when durable mapping exists", animeIndex.lookups.isEmpty())
    }

    @Test
    fun `anilist prefix uses router observed mapping before fribb`() = runTest {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.ANILIST, "16498", "fribb-100"))
        )
        val sourceId = ParsedMetadataId(AnimeIdScheme.ANILIST, "16498", "anilist:16498")
        val mappingStore = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "observed-100",
                    source = IdMappingSource.ROUTER_OBSERVED,
                    evidence = "runtime discovery"
                )
            )
        )
        val router = router(animeIndex = animeIndex, mappingStore = mappingStore)

        val route = router.route(request("anilist:16498", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.KITSU, route.provider)
        assertEquals("kitsu:observed-100", route.targetIds[MetadataPrimaryProvider.KITSU])
        assertTrue("Fribb must not be consulted when router-observed mapping exists", animeIndex.lookups.isEmpty())
    }

    @Test
    fun `router rejects preview depth`() {
        val router = router()

        assertThrows(IllegalArgumentException::class.java) {
            runTest { router.route(request("tmdb:550", ContentType.MOVIE, depth = MetadataDepth.PREVIEW)) }
        }
    }

    @Test
    fun `preview uses addon title without invoking anime identity lookups`() {
        val animeIndex = InMemoryAnimeIdentityIndex(
            mappings = listOf(AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt0388629", "7442"))
        )
        val router = router(animeIndex = animeIndex)
        val request = request(
            "imdb:tt0388629",
            ContentType.SERIES,
            depth = MetadataDepth.PREVIEW,
            sourceContext = MetadataSourceContext(addonMetadata = HomeDisplayMetadata(title = "Initial Preview Title"))
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            runTest { router.route(request) }
        }

        assertEquals("MetadataRouter does not route preview-depth requests", error.message)
        assertEquals("Initial Preview Title", request.sourceContext.addonMetadata?.title)
        assertEquals(emptyList<AnimeIdentityLookup>(), animeIndex.lookups)
    }

    @Test
    fun `catalog anime words do not route neutral ids without deterministic mapping`() = runTest {
        val router = router(
            animeIndex = InMemoryAnimeIdentityIndex(),
            mappingStore = InMemoryIdMappingStore()
        )

        val route = router.route(
            request(
                "tt9999999",
                ContentType.SERIES,
                sourceContext = MetadataSourceContext(catalogId = "anime-kitsu", sourceName = "Anime")
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
        assertFalse(route.targetIds.containsKey(MetadataPrimaryProvider.KITSU))
    }

    @Test
    fun `disney mixed row uses per item type for movie fallback`() = runTest {
        val route = router().route(
            request(
                "disney-neutral-id",
                ContentType.MOVIE,
                sourceContext = MetadataSourceContext(catalogId = "disney-mixed")
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_MOVIE, route.reason)
    }

    @Test
    fun `disney mixed row uses per item type for series fallback`() = runTest {
        val route = router().route(
            request(
                "disney-neutral-id",
                ContentType.SERIES,
                sourceContext = MetadataSourceContext(catalogId = "disney-mixed")
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, route.reason)
    }

    @Test
    fun `route trace records decision evidence`() = runTest {
        val router = router()

        val route = router.route(request("tmdb:550", ContentType.MOVIE))

        assertTrue(route.trace.isNotEmpty())
        assertNotNull(route.trace.first().detail)
        assertTrue(route.trace.any { it.detail.contains("tmdb:550") })
    }

    private fun router(
        animeIndex: AnimeIdentityIndex = InMemoryAnimeIdentityIndex(),
        mappingStore: IdMappingStore = InMemoryIdMappingStore()
    ): MetadataRouter = MetadataRouter(
        normalizer = MetadataRequestNormalizer(traceEvents = noopEvents()),
        animeIdentityIndex = animeIndex,
        idMappingStore = mappingStore
    )

    private fun noopEvents() = TraceMetadataEvents(RecordingTraceSink()) { null }

    private fun request(
        id: String,
        type: ContentType,
        depth: MetadataDepth = MetadataDepth.DETAIL_CORE,
        sourceContext: MetadataSourceContext = MetadataSourceContext()
    ): MetadataRequest = MetadataRequest(
        contentId = id,
        contentType = type,
        sourceContext = sourceContext,
        depth = depth
    )

    private fun mapping(
        sourceId: ParsedMetadataId,
        providerId: String,
        source: IdMappingSource
    ): IdMapping = IdMapping(
        sourceId = sourceId,
        provider = MetadataPrimaryProvider.KITSU,
        providerId = providerId,
        source = source,
        evidence = source.name
    )
}
