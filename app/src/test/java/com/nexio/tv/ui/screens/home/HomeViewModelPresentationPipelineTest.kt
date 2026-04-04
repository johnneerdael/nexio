package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeViewModelPresentationPipelineTest {

    @Test
    fun `consumeDeferredFocusedItemEnrichment keeps pending item while refresh is running`() {
        val pendingItem = testPreview("tt15940132", "War Machine")
        var replayedItem: MetaPreview? = null

        val remaining = consumeDeferredFocusedItemEnrichment(
            pendingItem = pendingItem,
            startupRefreshPending = true,
            catalogsLoadInProgress = false,
            traktDiscoveryRefreshInProgress = false,
            mdbListDiscoveryRefreshInProgress = false,
            onReady = { replayedItem = it }
        )

        assertEquals(pendingItem, remaining)
        assertNull(replayedItem)
    }

    @Test
    fun `consumeDeferredFocusedItemEnrichment replays pending item after refresh completes`() {
        val pendingItem = testPreview("tt15940132", "War Machine")
        var replayedItem: MetaPreview? = null

        val remaining = consumeDeferredFocusedItemEnrichment(
            pendingItem = pendingItem,
            startupRefreshPending = false,
            catalogsLoadInProgress = false,
            traktDiscoveryRefreshInProgress = false,
            mdbListDiscoveryRefreshInProgress = false,
            onReady = { replayedItem = it }
        )

        assertNull(remaining)
        assertEquals(pendingItem, replayedItem)
    }

    @Test
    fun `applyTomatoesToTraktSnapshot updates matching synthetic items`() {
        val untouched = testPreview("tt1", "Untouched")
        val target = testPreview("tt15940132", "War Machine")
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(untouched, target),
            customListCatalogs = listOf(
                TraktCustomListCatalog(
                    key = "custom",
                    catalogId = "custom_movies",
                    catalogName = "Custom Movies",
                    type = ContentType.MOVIE,
                    items = listOf(target)
                )
            )
        )

        val updated = applyTomatoesToTraktSnapshot(snapshot, "tt15940132", 71.0)

        assertNotSame(snapshot, updated)
        assertEquals(null, updated.trendingMovieItems.first().tomatoesRating)
        assertEquals(71.0, updated.trendingMovieItems.last().tomatoesRating ?: 0.0, 0.0)
        assertEquals(
            71.0,
            updated.customListCatalogs.first().items.first().tomatoesRating ?: 0.0,
            0.0
        )
    }

    @Test
    fun `applyTomatoesToTraktSnapshot returns same instance when item is absent`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(testPreview("tt1", "Untouched"))
        )

        val updated = applyTomatoesToTraktSnapshot(snapshot, "tt15940132", 71.0)

        assertSame(snapshot, updated)
    }

    @Test
    fun `applyTomatoesOverridesToTraktSnapshot reapplies tomatoes after snapshot replacement`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(testPreview("tt15940132", "War Machine"))
        )

        val updated = applyTomatoesOverridesToTraktSnapshot(
            snapshot = snapshot,
            tomatoesByItemId = mapOf("tt15940132" to 71.0)
        )

        assertEquals(71.0, updated.trendingMovieItems.first().tomatoesRating ?: 0.0, 0.0)
    }

    @Test
    fun `nextUpToMetaPreview builds trakt up next preview from next up item`() {
        val preview = nextUpToMetaPreview(
            ContinueWatchingItem.NextUp(
                NextUpInfo(
                    contentId = "show-a",
                    contentType = "series",
                    name = "Show A",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "show-a:2:4",
                    season = 2,
                    episode = 4,
                    episodeTitle = "Episode 4",
                    thumbnail = null,
                    released = "2026-03-23T00:00:00.000Z",
                    lastWatched = 1_000L
                )
            )
        )

        assertEquals(ContentType.SERIES, preview.type)
        assertEquals("Show A • S2E4 Episode 4", preview.name)
        assertEquals(PosterShape.LANDSCAPE, preview.posterShape)
    }

    @Test
    fun `continue watching runtime warm candidates only include episodic items missing runtime`() {
        val missingEpisode = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "show-a",
                contentType = "series",
                name = "Show A",
                poster = null,
                backdrop = null,
                logo = null,
                displayMetadata = HomeDisplayMetadata(runtime = null),
                videoId = "show-a:2:4",
                season = 2,
                episode = 4,
                episodeTitle = "Episode 4",
                thumbnail = null,
                lastWatched = 1_000L
            )
        )
        val alreadyHasRuntime = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "show-b",
                contentType = "series",
                name = "Show B",
                poster = null,
                backdrop = null,
                logo = null,
                displayMetadata = HomeDisplayMetadata(runtime = "47m"),
                videoId = "show-b:1:2",
                season = 1,
                episode = 2,
                episodeTitle = "Episode 2",
                thumbnail = null,
                lastWatched = 1_000L
            )
        )
        val movie = ContinueWatchingItem.InProgress(
            progress = com.nexio.tv.domain.model.WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Movie",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 1_000L,
                duration = 0L,
                lastWatched = 42L
            )
        )

        val candidates = continueWatchingItemsMissingRuntime(
            listOf(missingEpisode, alreadyHasRuntime, movie)
        )

        assertEquals(listOf(missingEpisode), candidates)
    }

    @Test
    fun `mergeFocusedItemEnrichment carries external trailer ids into the preview`() {
        val preview = testPreview("tt15940132", "War Machine")
        val merged = mergeFocusedItemEnrichment(
            currentItem = preview,
            tmdbEnrichment = null,
            externalMeta = Meta(
                id = preview.id,
                type = ContentType.MOVIE,
                name = preview.name,
                poster = preview.poster,
                posterShape = preview.posterShape,
                background = preview.background,
                logo = preview.logo,
                description = "Updated description",
                releaseInfo = preview.releaseInfo,
                imdbRating = 9.0f,
                genres = listOf("Action"),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList(),
                videos = emptyList(),
                trailerYtIds = listOf("dQw4w9WgXcQ")
            )
        )

        assertEquals(listOf("dQw4w9WgXcQ"), merged.trailerYtIds)
        assertEquals("Updated description", merged.description)
    }

    private fun testPreview(id: String, title: String): MetaPreview {
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
            imdbRating = 8.8f,
            tomatoesRating = null,
            genres = listOf("Action")
        )
    }
}
