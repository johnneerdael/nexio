package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAssetJsonAdapter
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.domain.model.ProviderId
import com.squareup.moshi.Moshi
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverEpisodeTest {

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
    fun `MHA S3E1 projects to TVDB S3E1 with HIGH confidence for trakt scrobble`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("13881", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 1, episode = 1)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNotNull(proj.scrobbleCoordinate)
        assertEquals(ProviderId.TVDB, proj.scrobbleCoordinate?.provider)
        assertEquals("305074", proj.scrobbleCoordinate?.seriesId)
        assertEquals(3, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
        assertEquals(CoordinateConfidence.HIGH, proj.confidence)
        assertNull(proj.fallbackReason)
    }

    @Test
    fun `One Piece episode 892 projects to TVDB S21E1 via range rule`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("12", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 892)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertEquals(21, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
        assertEquals(CoordinateConfidence.HIGH, proj.confidence)
    }

    @Test
    fun `One Piece episode 1156 uses open-ended range to TVDB S23E1`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("12", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 1156)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertEquals(23, proj.scrobbleCoordinate?.season)
        assertEquals(1, proj.scrobbleCoordinate?.episode)
    }

    @Test
    fun `unmapped Kitsu id returns unresolved with KITSU_NOT_IN_PACK`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity("88888", null))
        val src = SourceEpisodeCoordinate(sourceKitsuId = "88888", season = 1, episode = 1)
        val proj = r.resolveEpisodeProjection(work, src, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNull(proj.scrobbleCoordinate)
        assertEquals(FallbackReason.KITSU_NOT_IN_PACK, proj.fallbackReason)
        assertEquals(CoordinateConfidence.LOW, proj.confidence)
    }
}
