package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryRatingValue
import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryRatingsDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TrailerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklRailPreviewMapperTest {
    @Test
    fun `simkl maps discovery item to preview`() {
        val preview = SimklRailPreviewMapper().mapDiscoveryItem(
            railId = "simkl_trending_movies",
            item = SimklDiscoveryItemDto(
                title = "Inception",
                year = 2010,
                released = "2010-07-16",
                overview = "A thief steals corporate secrets through dream-sharing technology.",
                runtimeText = "148 min",
                genres = listOf("Action", "Science Fiction"),
                poster = "52/52598920_m.jpg",
                fanart = "10/10668001_f.jpg",
                ids = SimklIdsDto(
                    simkl = 12345,
                    imdb = "tt1375666",
                    tmdb = "27205",
                    tvdb = "123",
                    mal = "456"
                ),
                ratings = SimklDiscoveryRatingsDto(
                    imdb = SimklDiscoveryRatingValue(rating = 8.8),
                    simkl = SimklDiscoveryRatingValue(rating = 8.4)
                ),
                trailer = "yt123"
            ),
            itemType = ContentType.MOVIE,
            position = 0,
            generatedAtMs = 1_000L
        )!!

        assertEquals("simkl:12345", preview.sourceItemId)
        assertEquals("tt1375666", preview.stableIds.imdb)
        assertEquals("https://simkl.in/posters/52/52598920_m.jpg", preview.display.posterUrl)
        assertEquals("https://simkl.in/fanart/10/10668001_f.jpg", preview.display.backdropUrl)
        assertEquals("yt123", (preview.display.trailerHint as TrailerHint.YouTube).videoId)
    }

    @Test
    fun `simkl maps live discovery json shape to rich preview`() {
        val preview = SimklRailPreviewMapper().mapDiscoveryItem(
            railId = "simkl_trending_movies",
            item = SimklDiscoveryItemDto(
                title = "Project Hail Mary",
                releaseDate = "03/15/2026",
                theater = "03/15/2026",
                overview = "Science teacher Ryland Grace wakes up on a spaceship light years from home.",
                runtimeText = "2h 37m",
                genres = listOf("Adventure", "Comedy", "Science Fiction"),
                poster = "19/195417372d9325feb5",
                fanart = "19/19676837733d10098c",
                ids = SimklIdsDto(
                    simklId = 1306562,
                    slug = "project-hail-mary",
                    imdb = "tt12042730",
                    tmdb = "687163",
                    tvdb = "346729"
                ),
                ratings = SimklDiscoveryRatingsDto(
                    imdb = SimklDiscoveryRatingValue(rating = 8.3, votes = 245000),
                    simkl = SimklDiscoveryRatingValue(rating = 8.68, votes = 1101)
                ),
                trailer = "tvd7UUHzdhA"
            ),
            itemType = ContentType.MOVIE,
            position = 2,
            generatedAtMs = 1_000L
        )!!

        assertEquals("simkl:1306562", preview.sourceItemId)
        assertEquals("tt12042730", preview.stableIds.imdb)
        assertEquals("687163", preview.stableIds.tmdb)
        assertEquals("346729", preview.stableIds.tvdb)
        assertEquals("project-hail-mary", preview.stableIds.slug)
        assertEquals("Project Hail Mary", preview.display.title)
        assertEquals(2026, preview.display.year)
        assertEquals("03/15/2026", preview.display.releaseDate)
        assertEquals("2h 37m", preview.display.runtimeText)
        assertEquals(listOf("Adventure", "Comedy", "Science Fiction"), preview.display.genres)
        assertEquals("https://simkl.in/posters/19/195417372d9325feb5_m.jpg", preview.display.posterUrl)
        assertEquals("https://simkl.in/fanart/19/19676837733d10098c_w.jpg", preview.display.backdropUrl)
        assertEquals(ProviderId.IMDB, preview.display.rating?.provider)
        assertEquals(8.3, preview.display.rating?.value ?: 0.0, 0.0001)
        assertEquals(245000, preview.display.rating?.votes)
        assertEquals("tvd7UUHzdhA", (preview.display.trailerHint as TrailerHint.YouTube).videoId)
        assertTrue(preview.sourcePayloadHash.isNotBlank())
    }

    @Test
    fun `simkl missing id returns null`() {
        val preview = SimklRailPreviewMapper().mapDiscoveryItem(
            railId = "simkl_trending_movies",
            item = SimklDiscoveryItemDto(title = "No ID", ids = SimklIdsDto(imdb = "tt0000000")),
            itemType = ContentType.MOVIE,
            position = 0,
            generatedAtMs = 1_000L
        )

        assertNull(preview)
    }
}
