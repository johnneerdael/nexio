package com.nexio.tv.core.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeIdMappingServiceTest {
    @Test
    fun `resolves native and mapped ids to kitsu id`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "3936"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "5114"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.ANILIST, "5114"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.ANIDB, "6107"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TVDB, "85249"), ContentMediaKind.SERIES))
        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.IMDB, "tt1355642"), ContentMediaKind.SERIES))
    }

    @Test
    fun `tmdb lookup uses requested media kind`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertEquals("3936", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "31911"), ContentMediaKind.SERIES))
        assertEquals("198", service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "12174"), ContentMediaKind.MOVIE))
        assertNull(service.resolveKitsuId(AnimeStremioId(AnimeIdSource.TMDB, "31911"), ContentMediaKind.MOVIE))
    }

    @Test
    fun `returns null when id has no local mapping`() {
        val service = AnimeIdMappingService(assetProvider = { fixtureAsset() })

        assertNull(service.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "999999"), ContentMediaKind.SERIES))
    }

    @Test
    fun `resolveKitsuId returns null when assetProvider throws (missing asset on profileable)`() {
        val service = AnimeIdMappingService(
            assetProvider = { throw java.io.FileNotFoundException("anime/anime-id-map.json") }
        )

        val resolved = service.resolveKitsuId(
            id = AnimeStremioId(source = AnimeIdSource.MAL, value = "1"),
            mediaKind = ContentMediaKind.SERIES
        )

        assertNull(resolved)
    }

    @Test
    fun `resolveKitsuId returns null when assetProvider throws JSON parse error`() {
        val service = AnimeIdMappingService(
            assetProvider = { error("Unable to parse anime ID map asset") }
        )

        val resolved = service.resolveKitsuId(
            id = AnimeStremioId(source = AnimeIdSource.IMDB, value = "tt0000001"),
            mediaKind = ContentMediaKind.MOVIE
        )

        assertNull(resolved)
    }

    private fun fixtureAsset(): AnimeIdMapAsset {
        return AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = mapOf(
                "3936" to AnimeIdMapRecord(
                    kitsu = "3936",
                    mal = "5114",
                    anilist = "5114",
                    anidb = "6107",
                    tmdb = "31911",
                    tvdb = "85249",
                    imdb = "tt1355642",
                    mediaType = "series"
                ),
                "198" to AnimeIdMapRecord(kitsu = "198", mal = "222", tmdb = "12174", mediaType = "movie")
            ),
            byKitsu = mapOf("3936" to "3936", "198" to "198"),
            byMal = mapOf("5114" to "3936", "222" to "198"),
            byAnilist = mapOf("5114" to "3936"),
            byAnidb = mapOf("6107" to "3936"),
            byTvdb = mapOf("85249" to "3936"),
            byTmdbMovie = mapOf("12174" to "198"),
            byTmdbSeries = mapOf("31911" to "3936"),
            byImdb = mapOf("tt1355642" to "3936")
        )
    }
}
