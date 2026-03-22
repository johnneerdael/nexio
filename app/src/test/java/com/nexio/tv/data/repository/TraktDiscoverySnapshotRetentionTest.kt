package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktDiscoverySnapshotRetentionTest {

    @Test
    fun `preserves previous built in snapshot when refreshed snapshot is empty`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.RECOMMENDED_MOVIES)
        )
        val previousSnapshot = TraktDiscoverySnapshot(
            recommendationMovieItems = listOf(preview("tt1", "Cached Recommendation")),
            updatedAtMs = 100L
        )
        val refreshedSnapshot = TraktDiscoverySnapshot(updatedAtMs = 200L)

        assertTrue(
            shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
                previousSnapshot = previousSnapshot,
                refreshedSnapshot = refreshedSnapshot,
                prefs = prefs
            )
        )
    }

    @Test
    fun `preserves previous custom list snapshot when refreshed snapshot is empty`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = emptySet(),
            selectedPopularListKeys = setOf("popular:cached-list")
        )
        val previousSnapshot = TraktDiscoverySnapshot(
            customListCatalogs = listOf(
                TraktCustomListCatalog(
                    key = "popular:cached-list",
                    catalogId = "cached_movies",
                    catalogName = "Cached Movies",
                    type = ContentType.MOVIE,
                    items = listOf(preview("tt2", "Cached Movie"))
                )
            ),
            updatedAtMs = 100L
        )
        val refreshedSnapshot = TraktDiscoverySnapshot(updatedAtMs = 200L)

        assertTrue(
            shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
                previousSnapshot = previousSnapshot,
                refreshedSnapshot = refreshedSnapshot,
                prefs = prefs
            )
        )
    }

    @Test
    fun `does not preserve previous snapshot when refreshed snapshot still has configured content`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.RECOMMENDED_MOVIES)
        )
        val previousSnapshot = TraktDiscoverySnapshot(
            recommendationMovieItems = listOf(preview("tt1", "Cached Recommendation")),
            updatedAtMs = 100L
        )
        val refreshedSnapshot = TraktDiscoverySnapshot(
            recommendationMovieItems = listOf(preview("tt3", "Fresh Recommendation")),
            updatedAtMs = 200L
        )

        assertFalse(
            shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
                previousSnapshot = previousSnapshot,
                refreshedSnapshot = refreshedSnapshot,
                prefs = prefs
            )
        )
    }

    @Test
    fun `does not preserve previous snapshot when only up next is enabled`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.UP_NEXT)
        )
        val previousSnapshot = TraktDiscoverySnapshot(
            recommendationMovieItems = listOf(preview("tt1", "Cached Recommendation")),
            updatedAtMs = 100L
        )
        val refreshedSnapshot = TraktDiscoverySnapshot(updatedAtMs = 200L)

        assertFalse(
            shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
                previousSnapshot = previousSnapshot,
                refreshedSnapshot = refreshedSnapshot,
                prefs = prefs
            )
        )
    }

    private fun preview(id: String, title: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = title,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2026",
            runtime = null,
            imdbRating = 8.0f,
            tomatoesRating = null,
            genres = listOf("Drama")
        )
    }
}
