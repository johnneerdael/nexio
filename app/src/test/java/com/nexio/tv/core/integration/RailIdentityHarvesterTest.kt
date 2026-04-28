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
import org.junit.Assert.assertNull
import org.junit.Test

class RailIdentityHarvesterTest {
    @Test
    fun `harvest persists explicit simkl imdb tmdb tvdb ids without invented mappings`() = runTest {
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

        assertEquals(2, facts.size)
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tt1375666")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tt1375666")!!))
        assertNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tvdb:999")!!))
        assertNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tmdb:27205")!!))
        assertNull(parseMetadataId("simkl:12345"))
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

        assertEquals(4, facts.size)
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("tt0388629")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("mal:21")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("anilist:1")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.KITSU, parseMetadataId("anidb:2")!!))
    }

    @Test
    fun `harvested router observed fact does not overwrite fribb mapping`() = runTest {
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

        assertEquals("fribb-tmdb", idMappingStore.lookup(MetadataPrimaryProvider.TMDB, imdbSource)?.providerId)
    }
}
