package com.nexio.tv.data.repository.mdblist

import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MDBListIdMapperTest {
    @Test
    fun `movie watchlist payload prefers imdb and tmdb ids`() {
        val item = LibraryEntryInput(
            itemId = "tmdb:550",
            itemType = "movie",
            title = "Fight Club",
            year = 1999,
            imdbId = "tt0137523",
            tmdbId = 550,
        )

        val payload = MDBListIdMapper.watchlistPayloadFor(item)

        assertEquals(1, payload.movies?.size)
        assertEquals("tt0137523", payload.movies?.single()?.imdb)
        assertEquals(550, payload.movies?.single()?.tmdb)
        assertNull(payload.shows)
    }

    @Test
    fun `show watchlist payload uses show ids`() {
        val item = LibraryEntryInput(
            itemId = "tmdb:1399",
            itemType = "series",
            title = "Game of Thrones",
            year = 2011,
            imdbId = "tt0944947",
            tmdbId = 1399,
        )

        val payload = MDBListIdMapper.watchlistPayloadFor(item)

        assertNull(payload.movies)
        assertEquals(1, payload.shows?.size)
        assertEquals("tt0944947", payload.shows?.single()?.imdb)
        assertEquals(1399, payload.shows?.single()?.tmdb)
    }

    @Test
    fun `episode scrobble payload uses show ids plus season episode coordinate`() {
        val item = TrackingScrobbleItem.Episode(
            contentId = "tmdb:1399",
            showTitle = "Game of Thrones",
            showYear = 2011,
            season = 1,
            number = 1,
            episodeTitle = "Winter Is Coming",
            hydratedIds = ProviderIds(
                imdb = "tt0944947",
                tmdb = "1399",
                tvdb = "121361",
            ),
        )

        val payload = MDBListIdMapper.scrobblePayloadFor(item, progressPercent = 42f)

        assertNull(payload.movie)
        assertEquals("tt0944947", payload.show?.ids?.imdb)
        assertEquals(1399, payload.show?.ids?.tmdb)
        assertNull(payload.show?.ids?.tvdb)
        assertEquals(1, payload.show?.season?.number)
        assertEquals(1, payload.show?.season?.episode?.number)
        assertEquals(42.0, payload.progress, 0.001)
        assertEquals("Nexio", payload.appVersion)
    }

    @Test
    fun `episode scrobble identity rejects tvdb only payloads`() {
        val item = TrackingScrobbleItem.Episode(
            contentId = "tvdb:303904",
            showTitle = "Australian Survivor",
            showYear = null,
            season = 12,
            number = 22,
            episodeTitle = "Build a Raft",
            hydratedIds = ProviderIds(tvdb = "303904"),
        )

        assertEquals(false, MDBListIdMapper.hasScrobbleIdentity(item))
        val payload = MDBListIdMapper.scrobblePayloadFor(item, progressPercent = 0f)
        assertNull(payload.show?.ids?.tvdb)
        assertNull(payload.show?.ids?.imdb)
        assertNull(payload.show?.ids?.tmdb)
    }

    @Test
    fun `scrobble progress is rounded to five total digits for MDBList validator`() {
        val item = TrackingScrobbleItem.Episode(
            contentId = "tmdb:114922",
            showTitle = "Citadel",
            showYear = 2023,
            season = 2,
            number = 2,
            episodeTitle = "Cold Plunge",
            hydratedIds = ProviderIds(tmdb = "114922"),
        )

        val payload = MDBListIdMapper.scrobblePayloadFor(item, progressPercent = 20.86837387084961f)

        assertEquals(20.87, payload.progress, 0.0001)
    }

    @Test
    fun `ids from watchlist row do not invent mdblist provider id`() {
        val ids = MDBListIdMapper.idsFrom(
            imdb = "tt0137523",
            tmdb = 550,
            tvdb = null,
        )

        assertEquals(ProviderIds(imdb = "tt0137523", tmdb = "550"), ids)
    }
}
