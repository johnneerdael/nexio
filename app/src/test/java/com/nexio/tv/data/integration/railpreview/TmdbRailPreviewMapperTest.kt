package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbRailPreviewMapperTest {
    @Test
    fun `tmdb maps rich media result to preview`() {
        val preview = TmdbRailPreviewMapper().mapResult(
            railId = "tmdb_trending_movies",
            result = TmdbMediaResult(
                id = 27205,
                title = "Inception",
                originalTitle = "Inception",
                posterPath = "/poster.jpg",
                backdropPath = "/backdrop.jpg",
                overview = "A thief steals corporate secrets through dream-sharing technology.",
                releaseDate = "2010-07-16",
                genreIds = listOf(28, 878),
                voteAverage = 8.4,
                voteCount = 12345
            ),
            itemType = ContentType.MOVIE,
            position = 0,
            generatedAtMs = 1_000L,
            genreNames = mapOf(28 to "Action", 878 to "Science Fiction")
        )

        assertEquals("tmdb:27205", preview.sourceItemId)
        assertEquals(ContentType.MOVIE, preview.itemType)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", preview.display.posterUrl)
        assertEquals(ProviderId.TMDB, preview.display.rating?.provider)
        assertEquals(SourcePayloadQuality.RICH_PREVIEW, preview.sourcePayloadQuality)
    }
}
