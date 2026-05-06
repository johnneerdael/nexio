package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapIndexes
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
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

    @Test
    fun `MHA S3 - returns Success with 25 episodes in season 3`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 2,
            identityRecordsByKitsu = mapOf(
                "11469" to series("11469", tvdbSeason = "1"),
                "13881" to series("13881", tvdbSeason = "3"),
            ),
            indexes = AnimeIdMapIndexes(
                byKitsu = mapOf("11469" to "11469", "13881" to "13881"),
                byTvdb = mapOf("305074" to listOf("11469", "13881")),
            ),
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()

        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, listOf(3)) } returns
            (1..25).associate { (3 to it) to kitsuEp(season = 3, ep = it) }

        val resolver = DefaultAnimeSeasonProjectionResolver(
            mappingService = mapping,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
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

    @Test
    fun `empty episode map returns Error`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 2,
            identityRecordsByKitsu = mapOf("99999" to series("99999", tvdbSeason = "1")),
            indexes = AnimeIdMapIndexes(
                byKitsu = mapOf("99999" to "99999"),
                byTvdb = mapOf("305074" to listOf("99999")),
            ),
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()

        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:99999", ContentMediaKind.SERIES, any()) } returns emptyMap()

        val resolver = DefaultAnimeSeasonProjectionResolver(
            mappingService = mapping,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
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

    private fun series(kitsu: String, tvdb: String = "305074", imdb: String = "tt5626028", tvdbSeason: String? = null) =
        AnimeIdMapRecord(
            kitsu = kitsu, tvdb = tvdb, imdb = imdb,
            mediaType = "series", sourceType = "TV",
            tvdbSeason = tvdbSeason,
        )

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
