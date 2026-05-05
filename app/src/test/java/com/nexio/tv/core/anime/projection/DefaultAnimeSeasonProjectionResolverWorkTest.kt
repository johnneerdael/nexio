package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.KitsuMetadataService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverWorkTest {

    @Test
    fun `groups all MHA series Kitsu records under one work via shared tvdb`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469", tvdb = "305074", imdb = "tt5626028"),
                "12268" to series("12268", tvdb = "305074", imdb = "tt5626028"),
                "13881" to series("13881", tvdb = "305074", imdb = "tt5626028"),
                "14084" to movie("14084", tvdb = "305074", imdb = "tt7745068"),
            )
        )
        val service = AnimeIdMappingService(assetProvider = { asset })
        val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = service, kitsuMetadataService = mockk(relaxed = true))

        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "13881", animeStremioId = null))

        assertEquals("anime-work:tvdb:305074", work.groupKey.value)
        assertEquals(setOf("11469", "12268", "13881"), work.memberKitsuIds)
        assertTrue("movie 14084 must NOT be grouped into the series work", "14084" !in work.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, work.confidence)
    }

    @Test
    fun `does not group OVA or special into series work even when tvdb matches`() = runBlocking {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "11469" to series("11469", tvdb = "305074"),
                "99999" to AnimeIdMapRecord("99999", tvdb = "305074", mediaType = "series", sourceType = "OVA"),
            )
        )
        val service = AnimeIdMappingService(assetProvider = { asset })
        val resolver = DefaultAnimeSeasonProjectionResolver(idMappingService = service, kitsuMetadataService = mockk(relaxed = true))

        val work = resolver.resolveWork(AnimeSourceIdentity(sourceKitsuId = "11469", animeStremioId = null))

        assertTrue("OVA must not be grouped into series work", "99999" !in work.memberKitsuIds)
    }

    private fun series(kitsu: String, tvdb: String = "305074", imdb: String = "tt5626028") =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "series", sourceType = "TV")

    private fun movie(kitsu: String, tvdb: String, imdb: String) =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb, mediaType = "movie", sourceType = "MOVIE")
}
