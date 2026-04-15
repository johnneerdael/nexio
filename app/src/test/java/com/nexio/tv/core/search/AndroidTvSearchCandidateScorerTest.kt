package com.nexio.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvSearchCandidateScorerTest {

    @Test
    fun `exact local title with duration outranks weaker live result`() {
        val scored = AndroidTvSearchCandidateScorer.score(
            query = "matrix",
            candidates = listOf(
                candidate(
                    title = "The Matrix",
                    source = AndroidTvSearchCandidateSource.LOCAL_HOME,
                    releaseInfo = "1999",
                    runtime = "136 min"
                ),
                candidate(
                    title = "Matrix",
                    source = AndroidTvSearchCandidateSource.LIVE_CINEMETA,
                    releaseInfo = "1999",
                    runtime = null
                )
            ),
            limit = 10
        )

        assertEquals(AndroidTvSearchCandidateSource.LOCAL_HOME, scored.first().candidate.source)
        assertEquals(AndroidTvSearchTitleMatch.EXACT, scored.first().titleMatch)
        assertTrue(scored.first().hasDuration)
    }

    @Test
    fun `colon subtitle punctuation normalizes to exact match`() {
        val scored = AndroidTvSearchCandidateScorer.score(
            query = "daredevil born again",
            candidates = listOf(candidate(title = "Daredevil: Born Again")),
            limit = 10
        )

        assertEquals(AndroidTvSearchTitleMatch.EXACT, scored.single().titleMatch)
    }

    @Test
    fun `short noisy contains query is rejected`() {
        val scored = AndroidTvSearchCandidateScorer.score(
            query = "rev",
            candidates = listOf(candidate(title = "Daredevil: Born Again")),
            limit = 10
        )

        assertTrue(scored.isEmpty())
    }

    @Test
    fun `explanation carries match components without raw query`() {
        val scored = AndroidTvSearchCandidateScorer.score(
            query = "matrix",
            candidates = listOf(candidate(title = "The Matrix")),
            limit = 10
        ).single()

        assertTrue(scored.explanation.contains("source="))
        assertTrue(scored.explanation.contains("title_match="))
        assertFalse(scored.explanation.contains("matrix"))
    }

    private fun candidate(
        title: String,
        source: AndroidTvSearchCandidateSource = AndroidTvSearchCandidateSource.LOCAL_HOME,
        releaseInfo: String? = "1999",
        runtime: String? = "120 min"
    ): AndroidTvSearchCandidate {
        return AndroidTvSearchCandidate(
            id = "tt1",
            contentType = "movie",
            title = title,
            poster = "poster",
            background = null,
            description = "description",
            releaseInfo = releaseInfo,
            runtime = runtime,
            addonBaseUrl = "https://example.com",
            source = source
        )
    }
}
