package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverPresentationTest {

    @Test
    fun `MHA Season 3 click defaults selectedSeason to 3 not 1`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469"),
                "13881" to series("13881"),
            )
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:11469", ContentMediaKind.SERIES, emptyList()) } returns
            (1..13).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
            presentationCache = InMemoryAnimeSeasonPresentationCache(),
        )

        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
        val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

        assertEquals(3, presentation.selectedSeason)
        assertEquals(SeasonPresentationSource.KITSU_SEASON_NUMBERS, presentation.source)
        assertEquals("11469", presentation.seasons.first { it.seasonNumber == 1 }.episodesKitsuMemberId)
        assertEquals("13881", presentation.seasons.first { it.seasonNumber == 3 }.episodesKitsuMemberId)
    }

    @Test
    fun `flat Kitsu One Piece presents single season with KITSU_FLAT_FALLBACK`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf("12" to series("12", tvdb = "81797", imdb = "tt0388629"))
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        // One Piece: Kitsu reports all episodes under season 1 (flat-franchise model)
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:12", ContentMediaKind.SERIES, emptyList()) } returns
            (1..1050).associate { (1 to it) to kitsuEp(season = 1, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = mapping, kitsuMetadataService = kitsu, store = InMemoryAnimeEpisodeCoordinateStore(), traceEvents = mockk(relaxed = true), presentationCache = InMemoryAnimeSeasonPresentationCache())
        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "12", animeStremioId = null))
        val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "12", requestedSeason = null)

        assertEquals(1, presentation.selectedSeason)
        assertEquals(SeasonPresentationSource.KITSU_FLAT_FALLBACK, presentation.source)
        assertEquals(1, presentation.seasons.size)
        assertEquals(true, presentation.seasons.first().isFlatFallback)
    }

    @Test
    fun `resolveSeasonPresentation returns cached result without calling fetchEpisodeEnrichment again`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf("13881" to series("13881", tvdb = "305074"))
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        var callCount = 0
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } answers {
            callCount++
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }
        }
        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
            presentationCache = InMemoryAnimeSeasonPresentationCache(),
        )
        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))

        resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)
        resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

        assertEquals("fetchEpisodeEnrichment must be called exactly once", 1, callCount)
    }

    @Test
    fun `resolveSeasonPresentation cache hit with non-null requestedSeason overrides selectedSeason`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469"),
                "13881" to series("13881"),
            )
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:11469", ContentMediaKind.SERIES, emptyList()) } returns
            (1..13).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }
        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
            presentationCache = InMemoryAnimeSeasonPresentationCache(),
        )
        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))
        resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = null)

        val presentation = resolver.resolveSeasonPresentation(work, sourceKitsuId = "13881", requestedSeason = 1)

        assertEquals(1, presentation.selectedSeason)
        assertEquals(SeasonPresentationSource.KITSU_SEASON_NUMBERS, presentation.source)
    }

    private fun series(kitsu: String, tvdb: String = "305074", imdb: String = "tt5626028") =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "series", sourceType = "TV")

    private fun kitsuEp(season: Int, ep: Int) = TvEpisodeMetadata(
        providerEpisodeId = "kitsu:ep$season-$ep",
        seasonNumber = season,
        episodeNumber = ep,
        title = "S${season}E$ep",
        overview = null,
        thumbnail = null,
        airDate = null,
        runtimeMinutes = 24,
    )
}
