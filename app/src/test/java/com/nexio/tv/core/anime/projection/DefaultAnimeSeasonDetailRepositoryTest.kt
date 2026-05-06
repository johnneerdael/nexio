package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeEpisodeMappingRecord
import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapIndexes
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeRangeRule
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
    fun `MHA - hydrates videos for every curated season tab from each member resource`() = runBlocking {
        // Three MHA members in the asset, each with its own tvdbSeason. The detail
        // repository must fetch episodes from EVERY member (not just the source one)
        // and re-key them under the curated season number.
        val asset = AnimeIdMapAsset(
            schemaVersion = 2,
            identityRecordsByKitsu = mapOf(
                "11469" to series("11469", tvdbSeason = "1"),
                "12268" to series("12268", tvdbSeason = "2"),
                "13881" to series("13881", tvdbSeason = "3"),
            ),
            indexes = AnimeIdMapIndexes(
                byKitsu = mapOf("11469" to "11469", "12268" to "12268", "13881" to "13881"),
                byTvdb = mapOf("305074" to listOf("11469", "12268", "13881")),
            ),
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()

        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:11469", ContentMediaKind.SERIES, emptyList()) } returns
            (1..13).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:12268", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (1 to it) to kitsuEp(season = 1, ep = it) }
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:13881", ContentMediaKind.SERIES, emptyList()) } returns
            (1..25).associate { (1 to it) to kitsuEp(season = 1, ep = it) }

        val repository = repositoryFor(mapping, kitsu)
        val baseMeta = buildMeta(id = "kitsu:13881")
        val result = repository.resolveAndHydrateAnimeDetail(
            baseMeta = baseMeta,
            sourceKitsuId = "kitsu:13881",
            requestedSeason = null,
        )

        assertTrue("Expected Success but got $result", result is AnimeDetailResult.Success)
        val success = result as AnimeDetailResult.Success
        assertEquals(SeasonPresentationSource.CURATED_PER_RESOURCE, success.presentation.source)
        // Tabs cover seasons 1, 2, 3
        assertEquals(setOf(1, 2, 3), success.presentation.seasons.map { it.seasonNumber }.toSet())
        // Videos cover all three seasons even though the source was kitsu:13881 (S3)
        assertEquals(setOf(1, 2, 3), success.meta.videos.mapNotNull { it.season }.toSet())
        // Episode counts: 13 + 25 + 25 = 63
        assertEquals(63, success.meta.videos.size)
        assertEquals(13, success.meta.videos.count { it.season == 1 })
        assertEquals(25, success.meta.videos.count { it.season == 2 })
        assertEquals(25, success.meta.videos.count { it.season == 3 })
    }

    @Test
    fun `One Piece - re-keys flat Kitsu episodes via range rules into TVDB seasons`() = runBlocking {
        // Single Kitsu resource (12) with 100 flat episodes, all reported under season 1.
        // The asset has range rules: ep 1-50 → TVDB S1 (offset 0), ep 51-100 → TVDB S2 (offset -50).
        // The repository must project each Kitsu episode through the resolver and re-key
        // them under the TVDB season.
        val mappingRecord = AnimeEpisodeMappingRecord(
            anidb = "69",
            tvdbSeriesId = "81797",
            ranges = listOf(
                AnimeRangeRule(
                    sourceSeason = 1, startEpisode = 1, endEpisode = 50,
                    targetProvider = "TVDB", targetSeason = 1, offset = 0,
                ),
                AnimeRangeRule(
                    sourceSeason = 1, startEpisode = 51, endEpisode = 100,
                    targetProvider = "TVDB", targetSeason = 2, offset = -50,
                ),
            ),
        )
        val asset = AnimeIdMapAsset(
            schemaVersion = 2,
            identityRecordsByKitsu = mapOf(
                "12" to series("12", tvdb = "81797", tvdbSeason = "a", hasMappingRules = true),
            ),
            episodeMappingsByAnidb = mapOf("69" to mappingRecord),
            indexes = AnimeIdMapIndexes(
                byKitsu = mapOf("12" to "12"),
                byAnidb = mapOf("69" to "12"),
                byTvdb = mapOf("81797" to listOf("12")),
            ),
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:12", ContentMediaKind.SERIES, emptyList()) } returns
            (1..100).associate { (1 to it) to kitsuEp(season = 1, ep = it) }

        val repository = repositoryFor(mapping, kitsu)
        val baseMeta = buildMeta(id = "kitsu:12")
        val result = repository.resolveAndHydrateAnimeDetail(
            baseMeta = baseMeta,
            sourceKitsuId = "kitsu:12",
            requestedSeason = null,
        )

        assertTrue("Expected Success but got $result", result is AnimeDetailResult.Success)
        val success = result as AnimeDetailResult.Success
        assertEquals(SeasonPresentationSource.CURATED_RANGE_RULES, success.presentation.source)
        // Two TVDB seasons (1 and 2)
        assertEquals(setOf(1, 2), success.presentation.seasons.map { it.seasonNumber }.toSet())
        assertEquals(setOf(1, 2), success.meta.videos.mapNotNull { it.season }.toSet())
        // Re-keyed: 50 episodes in S1, 50 in S2
        assertEquals(50, success.meta.videos.count { it.season == 1 })
        assertEquals(50, success.meta.videos.count { it.season == 2 })
        // S2 ep1 corresponds to Kitsu ep 51 (offset -50 → 51 + (-50) = 1)
        val s2e1 = success.meta.videos.first { it.season == 2 && it.episode == 1 }
        assertEquals("kitsu:12:2:1", s2e1.id)
    }

    @Test
    fun `unresolved Kitsu falls back to Kitsu-native episode list`() = runBlocking {
        // Kitsu is in the asset but has no curated season — falls into the
        // "Kitsu-only" graceful-degrade path. Detail still hydrates from Kitsu directly.
        val asset = AnimeIdMapAsset(
            schemaVersion = 2,
            identityRecordsByKitsu = mapOf(
                "55555" to AnimeIdMapRecord(
                    kitsu = "55555", mediaType = "series", sourceType = "TV",
                    // no tvdb, no tvdbSeason → resolver returns unresolvedPresentation
                ),
            ),
            indexes = AnimeIdMapIndexes(byKitsu = mapOf("55555" to "55555")),
        )
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:55555", ContentMediaKind.SERIES, emptyList()) } returns
            (1..12).associate { (1 to it) to kitsuEp(season = 1, ep = it) }

        val repository = repositoryFor(mapping, kitsu)
        val baseMeta = buildMeta(id = "kitsu:55555")
        val result = repository.resolveAndHydrateAnimeDetail(
            baseMeta = baseMeta,
            sourceKitsuId = "kitsu:55555",
            requestedSeason = null,
        )

        assertTrue("Expected Success but got $result", result is AnimeDetailResult.Success)
        val success = result as AnimeDetailResult.Success
        assertEquals(12, success.meta.videos.size)
        assertNotNull(success.presentation.fallbackReason)
    }

    @Test
    fun `Kitsu not in pack returns Error`() = runBlocking {
        // No record at all in the asset. Resolver returns KITSU_NOT_IN_PACK,
        // presentation has no curated tabs and no source resource to fetch from.
        // We attempt the Kitsu-native fallback but it returns no data.
        val asset = AnimeIdMapAsset(schemaVersion = 2, indexes = AnimeIdMapIndexes())
        val mapping = AnimeIdMappingService(assetProvider = { asset })
        val kitsu = mockk<KitsuMetadataService>()
        coEvery { kitsu.fetchEpisodeEnrichment("kitsu:99999", ContentMediaKind.SERIES, emptyList()) } returns emptyMap()

        val repository = repositoryFor(mapping, kitsu)
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

    private fun repositoryFor(
        mapping: AnimeIdMappingService,
        kitsu: KitsuMetadataService,
    ) = DefaultAnimeSeasonDetailRepository(
        animeSeasonProjectionResolver = DefaultAnimeSeasonProjectionResolver(
            mappingService = mapping,
            store = InMemoryAnimeEpisodeCoordinateStore(),
            traceEvents = mockk(relaxed = true),
        ),
        kitsuMetadataService = kitsu,
    )

    private fun series(
        kitsu: String,
        tvdb: String = "305074",
        imdb: String = "tt5626028",
        tvdbSeason: String? = null,
        hasMappingRules: Boolean = false,
    ) = AnimeIdMapRecord(
        kitsu = kitsu,
        anidb = when (kitsu) { "11469" -> "11739"; "12268" -> "12233"; "13881" -> "13485"; "12" -> "69"; else -> null },
        tvdb = tvdb,
        imdb = imdb,
        mediaType = "series",
        sourceType = "TV",
        tvdbSeason = tvdbSeason,
        hasMappingRules = hasMappingRules,
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
