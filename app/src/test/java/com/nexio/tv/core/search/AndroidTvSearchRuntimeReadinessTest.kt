package com.nexio.tv.core.search

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvSearchRuntimeReadinessTest {

    @Test
    fun `prioritizes missing runtime movie and series candidates`() {
        val movie = preview("movie-1", ContentType.MOVIE, "Movie", runtime = null)
        val series = preview("series-1", ContentType.SERIES, "Series", runtime = null)
        val hydrated = preview("movie-2", ContentType.MOVIE, "Hydrated", runtime = "120 min")

        val candidates = AndroidTvSearchRuntimeReadiness.prioritizeMissingRuntimeCandidates(
            listOf(hydrated, series, movie),
            limit = 10
        )

        assertEquals(listOf("Movie", "Series"), candidates.map { it.name }.sorted())
        assertTrue(AndroidTvSearchRuntimeReadiness.hasReliableRuntime(hydrated))
        assertFalse(AndroidTvSearchRuntimeReadiness.hasReliableRuntime(movie))
    }

    @Test
    fun `respects hydration limit`() {
        val items = (1..5).map { index ->
            preview("movie-$index", ContentType.MOVIE, "Movie $index", runtime = null)
        }

        val candidates = AndroidTvSearchRuntimeReadiness.prioritizeMissingRuntimeCandidates(items, limit = 2)

        assertEquals(2, candidates.size)
    }

    private fun preview(
        id: String,
        type: ContentType,
        title: String,
        runtime: String?
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = title,
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2024",
            runtime = runtime,
            imdbRating = null,
            genres = emptyList()
        )
    }
}
