package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class RailPreviewMapperTest {
    @Test
    fun `simkl image fragments become full urls`() {
        assertEquals(
            "https://simkl.in/posters/52/52598920_m.jpg",
            simklImageUrl("52/52598920_m.jpg")
        )
    }

    @Test
    fun `stable item key prefers imdb before source raw id`() {
        assertEquals(
            "movie:imdb:tt1375666",
            railPreviewItemKey(ContentType.MOVIE, ProviderIds(imdb = "tt1375666"), "simkl:123")
        )
    }
}
