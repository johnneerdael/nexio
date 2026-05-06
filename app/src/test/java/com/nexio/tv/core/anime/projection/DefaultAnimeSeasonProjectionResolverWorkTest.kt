package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAssetJsonAdapter
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.squareup.moshi.Moshi
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverWorkTest {

    private fun fixtureService(): AnimeIdMappingService {
        val json = javaClass.getResource("/fixtures/nexio-anime-map-v1-test.json")!!.readText()
        val asset = AnimeIdMapAssetJsonAdapter(Moshi.Builder().build()).fromJson(json)!!
        return AnimeIdMappingService(assetProvider = { asset })
    }

    private fun resolver(): DefaultAnimeSeasonProjectionResolver = DefaultAnimeSeasonProjectionResolver(
        mappingService = fixtureService(),
        store = InMemoryAnimeEpisodeCoordinateStore(),
        traceEvents = mockk(relaxed = true),
    )

    @Test
    fun `resolveWork groups MHA series Kitsu records under one work via shared tvdb`() = runBlocking {
        val work = resolver().resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))

        assertEquals("anime-work:tvdb:305074", work.groupKey.value)
        assertTrue("expected 11469 in members ${'$'}{work.memberKitsuIds}", "11469" in work.memberKitsuIds)
        assertTrue("expected 13881 in members ${'$'}{work.memberKitsuIds}", "13881" in work.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, work.confidence)
    }

    @Test
    fun `resolveWork for One Piece groups under TVDB 81797`() = runBlocking {
        val work = resolver().resolveWork(AnimeSourceIdentity(sourceKitsuId = "12", animeStremioId = null))

        assertEquals("anime-work:tvdb:81797", work.groupKey.value)
        assertTrue("12" in work.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, work.confidence)
    }

    @Test
    fun `resolveWork for unknown Kitsu falls back to LOW confidence with kitsu-only group key`() = runBlocking {
        val work = resolver().resolveWork(AnimeSourceIdentity(sourceKitsuId = "88888", animeStremioId = null))

        assertEquals(AnimeGroupingConfidence.LOW, work.confidence)
        assertEquals("anime-work:kitsu:88888", work.groupKey.value)
    }
}
