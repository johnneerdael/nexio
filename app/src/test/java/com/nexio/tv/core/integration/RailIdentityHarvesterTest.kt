package com.nexio.tv.core.integration

import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
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

        assertEquals(4, facts.size)
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tt1375666")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tt1375666")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TMDB, parseMetadataId("tvdb:999")!!))
        assertNotNull(idMappingStore.lookup(MetadataPrimaryProvider.TVDB, parseMetadataId("tmdb:27205")!!))
        assertNull(parseMetadataId("simkl:12345"))
    }
}
