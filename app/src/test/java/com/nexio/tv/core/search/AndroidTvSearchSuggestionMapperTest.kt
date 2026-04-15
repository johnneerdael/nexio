package com.nexio.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTvSearchSuggestionMapperTest {

    @Test
    fun `movie suggestion includes deterministic year and minute runtime`() {
        val suggestion = requireNotNull(AndroidTvSearchSuggestionMapper.toSuggestion(
            result = result(
                releaseInfo = "1999",
                runtime = "136 min"
            ),
            rowId = 7
        ))

        assertEquals(7L, suggestion.rowId)
        assertEquals("The Matrix", suggestion.title)
        assertEquals("Movie", suggestion.subtitle)
        assertEquals("tt0133093", suggestion.itemId)
        assertEquals("movie", suggestion.contentType)
        assertEquals("https://v3-cinemeta.strem.io", suggestion.addonBaseUrl)
        assertEquals(1999, suggestion.productionYear)
        assertEquals(136L * 60L * 1000L, suggestion.durationMs)
    }

    @Test
    fun `series suggestion preserves type and omits ambiguous duration`() {
        val suggestion = requireNotNull(AndroidTvSearchSuggestionMapper.toSuggestion(
            result = result(
                contentType = "series",
                title = "Friends",
                releaseInfo = "1994-2004",
                runtime = "10 seasons"
            ),
            rowId = 1
        ))

        assertEquals("Series", suggestion.subtitle)
        assertEquals("series", suggestion.contentType)
        assertEquals(1994, suggestion.productionYear)
        assertNull(suggestion.durationMs)
    }

    @Test
    fun `mapper omits unclear year and runtime values`() {
        val suggestion = requireNotNull(AndroidTvSearchSuggestionMapper.toSuggestion(
            result = result(
                releaseInfo = "coming soon",
                runtime = "feature length"
            ),
            rowId = 1
        ))

        assertNull(suggestion.productionYear)
        assertNull(suggestion.durationMs)
    }

    @Test
    fun `mapper drops malformed result without id or type`() {
        assertNull(
            AndroidTvSearchSuggestionMapper.toSuggestion(
                result = result(id = "", contentType = "movie"),
                rowId = 1
            )
        )
        assertNull(
            AndroidTvSearchSuggestionMapper.toSuggestion(
                result = result(id = "tt1", contentType = ""),
                rowId = 1
            )
        )
    }

    private fun result(
        id: String = "tt0133093",
        contentType: String = "movie",
        title: String = "The Matrix",
        releaseInfo: String? = null,
        runtime: String? = null
    ): AndroidTvNativeSearchResult {
        return AndroidTvNativeSearchResult(
            id = id,
            contentType = contentType,
            title = title,
            poster = null,
            background = null,
            description = null,
            releaseInfo = releaseInfo,
            runtime = runtime,
            addonBaseUrl = "https://v3-cinemeta.strem.io"
        )
    }
}
