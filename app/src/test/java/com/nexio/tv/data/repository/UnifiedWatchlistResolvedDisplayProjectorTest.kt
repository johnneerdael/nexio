package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistMembershipConfidence
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedWatchlistResolvedDisplayProjectorTest {
    @Test
    fun `row title comes from resolved display item`() = runTest {
        val surface = ResolvedDisplaySurfaceRepository(activeProfileSession = { testProfileSession(1) })
        surface.replaceForTest(
            surfaceKey = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
            profileId = 1,
            items = listOf(resolved("movie:imdb:tt2543164", "Resolved Arrival"))
        )

        val rows = UnifiedWatchlistResolvedDisplayProjector(surface)
            .observeRows(1, listOf(membership("movie:imdb:tt2543164", "Raw Arrival")))
            .first()

        assertEquals("Resolved Arrival", rows.single().displayItem.display.title)
    }

    @Test
    fun `unresolved memberships are not emitted as raw fallback rows`() = runTest {
        val surface = ResolvedDisplaySurfaceRepository(activeProfileSession = { testProfileSession(1) })

        val rows = UnifiedWatchlistResolvedDisplayProjector(surface)
            .observeRows(1, listOf(membership("movie:imdb:tt2543164", "Raw Arrival")))
            .first()

        assertEquals(emptyList<Any>(), rows)
    }

    private fun membership(key: String, title: String) = UnifiedWatchlistMembership(
        authorityKey = key,
        contentType = ContentType.MOVIE,
        presentIn = setOf(UnifiedWatchlistSource.TRAKT),
        sourceRefs = emptyList(),
        confidence = UnifiedWatchlistMembershipConfidence.STRONG,
        title = title,
        imdbId = "tt2543164"
    )

    private fun resolved(key: String, title: String) = ResolvedDisplayItem(
        itemKey = key,
        contentId = "tt2543164",
        parentId = "tt2543164",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "imdb",
        canonicalId = "tt2543164",
        imdbId = "tt2543164",
        stableIds = ProviderIds(imdb = "tt2543164"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = title,
            year = 2016,
            releaseDate = null,
            overview = null,
            genres = emptyList(),
            runtimeText = null
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1L
    )

    private fun testProfileSession(profileId: Int) = ActiveProfileSession(
        profileId = profileId,
        sessionId = "test-session",
        sessionOrdinal = 1L,
        startedAtMs = 1L
    )
}
