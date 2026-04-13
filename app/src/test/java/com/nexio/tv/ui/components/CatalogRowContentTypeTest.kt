package com.nexio.tv.ui.components

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRowContentTypeTest {
    @Test
    fun `catalog row item content type uses item api type`() {
        val item = MetaPreview(
            id = "tt123",
            type = ContentType.UNKNOWN,
            rawType = "anime",
            name = "Title",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )

        assertEquals("anime", catalogRowItemContentType(item))
    }
}
