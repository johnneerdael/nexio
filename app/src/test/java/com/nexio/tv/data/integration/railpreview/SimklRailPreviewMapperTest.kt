package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryRatingsDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TrailerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                runtime = 148,
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
                ratings = SimklDiscoveryRatingsDto(imdb = 8.8, simkl = 8.4),
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
