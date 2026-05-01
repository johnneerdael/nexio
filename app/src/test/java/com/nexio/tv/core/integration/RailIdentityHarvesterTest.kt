package com.nexio.tv.core.integration

import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.parseMetadataId
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RailIdentityHarvesterTest {
    @Test
    fun `harvest persists direct simkl source facts without simkl target mappings`() = runTest {
        val idMappingStore = InMemoryIdMappingStore()
        val harvester = RailIdentityHarvester(idMappingStore)
        val preview = RailItemPreview(
            railId = "simkl_movie_trending_today",
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
            sourceItemId = "simkl:12345",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(simkl = "12345", imdb = "tt1375666", tmdb = "27205", tvdb = "999"),
            display = RailDisplaySeed(title = "Inception"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        val facts = harvester.harvest(preview)

        assertEquals(9, facts.size)
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("simkl:12345")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tt1375666")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tt1375666")!!))
        assertTrue(facts.none { it.provider == MetadataPrimaryProvider.SIMKL })
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tvdb:999")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tmdb:27205")!!))
        assertTrue(facts.all { it.source == IdMappingSource.RAIL_PREVIEW })
    }

    @Test
    fun `harvest persists direct trakt source facts without trakt target mappings`() = runTest {
        val idMappingStore = InMemoryIdMappingStore()
        val harvester = RailIdentityHarvester(idMappingStore)
        val preview = RailItemPreview(
            railId = "trakt_popular_movies",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:67890",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(trakt = "67890", imdb = "tt0133093", tmdb = "603", tvdb = "777"),
            display = RailDisplaySeed(title = "The Matrix"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        val facts = harvester.harvest(preview)

        assertEquals(9, facts.size)
        assertTrue(facts.none { it.provider == MetadataPrimaryProvider.TRAKT })
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("trakt:67890")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tvdb:777")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tmdb:603")!!))
        assertTrue(facts.all { it.source == IdMappingSource.RAIL_PREVIEW })
    }

    @Test
    fun `rail identity harvest never creates trakt or simkl target mappings`() = runTest {
        val store = InMemoryIdMappingStore()
        val harvester = RailIdentityHarvester(store)
        val preview = RailItemPreview(
            railId = "trakt_popular_series",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:1",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                trakt = "1",
                simkl = "49108",
                imdb = "tt0903747",
                tmdb = "1396",
                tvdb = "81189"
            ),
            display = RailDisplaySeed(title = "Breaking Bad"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        val mappings = harvester.harvest(preview)

        assertTrue(mappings.none { it.provider == MetadataPrimaryProvider.TRAKT })
        assertTrue(mappings.none { it.provider == MetadataPrimaryProvider.SIMKL })
        assertTrue(mappings.any { it.provider == MetadataPrimaryProvider.IMDB && it.providerId == "tt0903747" })
        assertTrue(mappings.any { it.provider == MetadataPrimaryProvider.TMDB && it.providerId == "1396" })
        assertTrue(mappings.any { it.provider == MetadataPrimaryProvider.TVDB && it.providerId == "81189" })
    }

    @Test
    fun `harvest does not invent mappings when only tmdb id exists`() = runTest {
        val idMappingStore = InMemoryIdMappingStore()
        val harvester = RailIdentityHarvester(idMappingStore)
        val preview = RailItemPreview(
            railId = "tmdb_movie_popular",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:603",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "603"),
            display = RailDisplaySeed(title = "The Matrix"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        val facts = harvester.harvest(preview)

        assertEquals(0, facts.size)
    }

    @Test
    fun `harvest uses imdb and anime ids for explicit kitsu mappings only`() = runTest {
        val idMappingStore = InMemoryIdMappingStore()
        val harvester = RailIdentityHarvester(idMappingStore)
        val preview = RailItemPreview(
            railId = "anime_rail",
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            sourceItemId = "kitsu:7442",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(imdb = "tt0388629", kitsu = "7442", mal = "21", anilist = "1", anidb = "2"),
            display = RailDisplaySeed(title = "One Piece"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        val facts = harvester.harvest(preview)

        assertEquals(8, facts.size)
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("tt0388629")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("mal:21")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("anilist:1")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("anidb:2")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("kitsu:7442")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("mal:21")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("anilist:1")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.IMDB, parseMetadataId("anidb:2")!!))
    }

    @Test
    fun `harvested rail preview fact follows mapping priority over fribb mapping`() = runTest {
        val imdbSource = parseMetadataId("tt1375666")!!
        val idMappingStore = InMemoryIdMappingStore()
        idMappingStore.persist(
            IdMapping(
                sourceId = imdbSource,
                provider = MetadataPrimaryProvider.TMDB,
                providerId = "fribb-tmdb",
                source = IdMappingSource.FRIBB,
                evidence = "curated fribb"
            )
        )
        val harvester = RailIdentityHarvester(idMappingStore)
        val preview = RailItemPreview(
            railId = "simkl_movie_trending_today",
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
            sourceItemId = "simkl:12345",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(simkl = "12345", imdb = "tt1375666", tmdb = "27205"),
            display = RailDisplaySeed(title = "Inception"),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )

        harvester.harvest(preview)

        assertEquals("27205", idMappingStore.lookup(MetadataPrimaryProvider.TMDB, imdbSource)?.providerId)
    }
}
