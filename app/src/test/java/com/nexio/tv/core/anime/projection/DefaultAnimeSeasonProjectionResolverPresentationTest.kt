package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAssetJsonAdapter
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.squareup.moshi.Moshi
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverPresentationTest {

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
    fun `MHA presentation uses CURATED_PER_RESOURCE and includes seasons 1 and 3`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
        val presentation = r.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

        assertEquals(SeasonPresentationSource.CURATED_PER_RESOURCE, presentation.source)
        assertEquals(CoordinateConfidence.HIGH, presentation.confidence)
        val seasons = presentation.seasons.map { it.seasonNumber }.toSet()
        assertTrue("expected season 1 in tabs but got $seasons", 1 in seasons)
        assertTrue("expected season 3 in tabs but got $seasons", 3 in seasons)
        // member id of season 3 tab should be the source kitsu id 13881
        assertEquals("13881", presentation.seasons.first { it.seasonNumber == 3 }.episodesKitsuMemberId)
        assertEquals("11469", presentation.seasons.first { it.seasonNumber == 1 }.episodesKitsuMemberId)
    }

    @Test
    fun `One Piece presentation uses CURATED_RANGE_RULES with TVDB seasons`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity(sourceKitsuId = "12", animeStremioId = null))
        val presentation = r.resolveSeasonPresentation(work, sourceKitsuId = "12", requestedSeason = null)

        assertEquals(SeasonPresentationSource.CURATED_RANGE_RULES, presentation.source)
        assertEquals(CoordinateConfidence.HIGH, presentation.confidence)
        val seasons = presentation.seasons.map { it.seasonNumber }
        // fixture has TVDB ranges for seasons 1, 21, 22, 23
        assertEquals(listOf(1, 21, 22, 23), seasons)
    }

    @Test
    fun `unmapped Kitsu returns fallbackReason KITSU_NOT_IN_PACK`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity(sourceKitsuId = "88888", animeStremioId = null))
        val presentation = r.resolveSeasonPresentation(work, sourceKitsuId = "88888", requestedSeason = null)

        assertEquals(FallbackReason.KITSU_NOT_IN_PACK, presentation.fallbackReason)
        assertEquals(CoordinateConfidence.LOW, presentation.confidence)
        assertTrue("seasons must be empty for unresolved", presentation.seasons.isEmpty())
    }

    @Test
    fun `requestedSeason is honored when present in tabs`() = runBlocking {
        val r = resolver()
        val work = r.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
        val presentation = r.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = 1)

        assertEquals(1, presentation.selectedSeason)
    }

    @Test
    fun `record without curated season returns NO_CURATED_SEASON`() = runBlocking {
        val r = resolver()
        // 99999 is the fixture record with no tvdb/anidb at all
        val work = r.resolveWork(AnimeSourceIdentity(sourceKitsuId = "99999", animeStremioId = null))
        val presentation = r.resolveSeasonPresentation(work, sourceKitsuId = "99999", requestedSeason = null)

        assertEquals(FallbackReason.NO_CURATED_SEASON, presentation.fallbackReason)
        assertEquals(CoordinateConfidence.LOW, presentation.confidence)
    }
}
