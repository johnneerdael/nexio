package com.nexio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SearchSuggestionPosterModelTest {
    @Test
    fun `remote poster url becomes safe model without exposing url`() {
        val registry = SearchSuggestionPosterRegistry()
        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg"
        )

        assertNotNull(model)
        assertEquals(
            "https://image.tmdb.org/t/p/w92/poster.jpg",
            registry.resolve(model!!)
        )
        assertFalse(model.toString().contains("https://"))
        assertFalse(model.key.contains("https://"))
    }

    @Test
    fun `invalid poster url is not registered`() {
        val registry = SearchSuggestionPosterRegistry()

        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "javascript:alert(1)"
        )

        assertEquals(null, model)
    }
}
