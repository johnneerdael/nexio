package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnimeSeasonDetailRepositoryTest {

    // ---- MHA Season 3: expects Success with 25 episodes in season 3 ----

    @Test
    fun `MHA S3 - returns Success with 25 episodes in season 3`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469"),
                "13881" to series("13881"),
            )
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()

        // Resolver needs all-members' episodes (emptyList() = no season filter)
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:11469", ContentMediaKind.SERIES, emptyList()) } returns
            (1..13).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }

        // Repository fetches only the selected season's episodes for the correct member
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, listOf(3)) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
            presentationCache = InMemoryAnimeSeasonPresentationCache(),
        )
        val repository = DefaultAnimeSeasonDetailRepository(
            animeSeasonProjectionResolver = resolver,
            kitsuMetadataService = kitsu,
        )

        val baseMeta = buildMeta(id = "kitsu:13881")
        val result = repository.resolveAndHydrateAnimeDetail(
            baseMeta = baseMeta,
            sourceKitsuId = "kitsu:13881",
            requestedSeason = null,
        )

        assertTrue("Expected Success but got $result", result is AnimeDetailResult.Success)
        val success = result as AnimeDetailResult.Success
        assertEquals(3, success.presentation.selectedSeason)
        assertEquals(25, success.meta.videos.size)
        assertTrue(success.meta.videos.all { it.season == 3 })
    }

    // ---- Empty episode map: expects Error ----

    @Test
    fun `empty episode map returns Error`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf("99999" to series("99999"))
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()

        // Resolver discovery returns empty (no episodes at all)
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:99999", ContentMediaKind.SERIES, emptyList()) } returns emptyMap()

        // Repository fetch also returns empty (season 1 default)
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:99999", ContentMediaKind.SERIES, any()) } returns emptyMap()

        val resolver = DefaultAnimeSeasonProjectionResolver(
            idMappingService = mapping,
            kitsuMetadataService = kitsu,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
            presentationCache = InMemoryAnimeSeasonPresentationCache(),
        )
        val repository = DefaultAnimeSeasonDetailRepository(
            animeSeasonProjectionResolver = resolver,
            kitsuMetadataService = kitsu,
        )

        val baseMeta = buildMeta(id = "kitsu:99999")
        val result = repository.resolveAndHydrateAnimeDetail(
            baseMeta = baseMeta,
            sourceKitsuId = "kitsu:99999",
            requestedSeason = null,
        )

        assertTrue("Expected Error but got $result", result is AnimeDetailResult.Error)
        val error = result as AnimeDetailResult.Error
        assertNotNull(error.message)
        assertTrue(error.message.contains("unavailable", ignoreCase = true))
    }

    // ---- Helpers ----

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

    private fun buildMeta(id: String) = Meta(
        id = id,
        type = ContentType.SERIES,
        name = "Test Anime",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
    )
}
