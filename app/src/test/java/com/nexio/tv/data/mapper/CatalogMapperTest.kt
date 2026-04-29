package com.nexio.tv.data.mapper

import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogMapperTest {
    @Test
    fun `tmdb addon search shape maps genre and year for first paint`() {
        val preview = MetaPreviewDto(
            id = "tmdb:687163",
            type = "movie",
            name = "Project Hail Mary",
            poster = "https://image.tmdb.org/t/p/w500/yihdXomYb5kTeSivtFndMy5iDmf.jpg",
            background = "https://image.tmdb.org/t/p/original/8Tfys3mDZVp4tNoH2ktm06a0Tau.jpg",
            posterShape = "regular",
            description = "Science teacher Ryland Grace wakes up on a spaceship light years from home.",
            imdbRating = "8.2",
            genre = listOf("Science Fiction", "Adventure"),
            year = "2026"
        ).toDomain()

        assertEquals("tmdb:687163", preview.id)
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("movie", preview.apiType)
        assertEquals("Project Hail Mary", preview.name)
        assertEquals(PosterShape.POSTER, preview.posterShape)
        assertEquals("2026", preview.releaseInfo)
        assertEquals(listOf("Science Fiction", "Adventure"), preview.genres)
        assertEquals(8.2f, preview.imdbRating)
        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, preview.firstPaintSource)
    }
}
