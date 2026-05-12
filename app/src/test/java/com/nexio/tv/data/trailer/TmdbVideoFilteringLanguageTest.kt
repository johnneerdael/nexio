package com.nexio.tv.data.trailer

import com.nexio.tv.data.remote.api.TmdbVideoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbVideoFilteringLanguageTest {

    @Test
    fun `rankTmdbVideoCandidates accepts original-language matches for non-english titles`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(
                tmdbVideoResult(key = "germanvid111", iso = "de"),
                tmdbVideoResult(key = "englishvid11", iso = "en")
            ),
            originalLanguage = "de"
        )
        assertEquals(1, ranked.size)
        assertEquals("germanvid111", ranked.single().key)
    }

    @Test
    fun `rankTmdbVideoCandidates rejects english trailer when title is in another original language`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(tmdbVideoResult(key = "englishvid11", iso = "en")),
            originalLanguage = "ja"
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `rankTmdbVideoCandidates rejects candidate with no declared language`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(tmdbVideoResult(key = "unknownlang1", iso = null)),
            originalLanguage = "en"
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `rankTmdbVideoCandidates falls back to english when original language is unknown`() {
        val ranked = rankTmdbVideoCandidates(
            listOf(
                tmdbVideoResult(key = "germanvid111", iso = "de"),
                tmdbVideoResult(key = "englishvid11", iso = "en")
            ),
            originalLanguage = null
        )
        assertEquals(1, ranked.size)
        assertEquals("englishvid11", ranked.single().key)
    }

    @Test
    fun `isTmdbVideoLanguageEligible normalizes across 639-2 and 639-1 codes`() {
        assertTrue(isTmdbVideoLanguageEligible(videoLanguageCode = "de", titleOriginalLanguage = "deu"))
        assertTrue(isTmdbVideoLanguageEligible(videoLanguageCode = "de", titleOriginalLanguage = "ger"))
        assertFalse(isTmdbVideoLanguageEligible(videoLanguageCode = "en", titleOriginalLanguage = "deu"))
        assertFalse(isTmdbVideoLanguageEligible(videoLanguageCode = null, titleOriginalLanguage = "en"))
    }

    @Test
    fun `filterCacheableTmdbTrailerVideos no longer filters by language at the cache layer`() {
        val filtered = filterCacheableTmdbTrailerVideos(
            listOf(
                tmdbVideoResult(key = "germanvid111", iso = "de", type = "Trailer", site = "YouTube"),
                tmdbVideoResult(key = "englishvid11", iso = "en", type = "Trailer", site = "YouTube")
            )
        )
        // Cache must accept both — language gate moves to selection-time.
        assertEquals(2, filtered.size)
    }

    private fun tmdbVideoResult(
        key: String,
        iso: String?,
        type: String = "Trailer",
        site: String = "YouTube",
        official: Boolean = true,
        size: Int = 1080,
        publishedAt: String = "2024-01-01T00:00:00Z"
    ) = TmdbVideoResult(
        iso6391 = iso,
        name = key,
        key = key,
        site = site,
        size = size,
        type = type,
        official = official,
        publishedAt = publishedAt,
        id = key
    )
}
