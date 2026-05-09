package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryHomeSuppressionTest {

    @Test
    fun `home publish with element-wise reference equality is suppressed`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val itemA = resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club")
        val itemB = resolvedItem(itemKey = "movie:tmdb:680", title = "Pulp Fiction")
        val items = listOf(itemA, itemB)

        val firstPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = items
        )
        // Republish the same instances — the mapper memoization ensures upstream
        // produces the same refs for unchanged content. We expect suppression.
        val secondPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = items
        )

        assertTrue(firstPublished)
        assertFalse(secondPublished)
    }

    @Test
    fun `home publish with different content is not suppressed`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        val firstPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club"))
        )
        // Different ResolvedDisplayItem instance — fails ref-equality, must publish.
        val secondPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club Director's Cut"))
        )

        assertTrue(firstPublished)
        assertTrue(secondPublished)
    }

    @Test
    fun `home publish with different size is not suppressed`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val itemA = resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club")
        val itemB = resolvedItem(itemKey = "movie:tmdb:680", title = "Pulp Fiction")

        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(itemA, itemB)
        )
        val secondPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(itemA)
        )

        assertTrue(secondPublished)
    }

    private fun profileSession(
        profileId: Int,
        sessionId: String
    ) = ActiveProfileSession(
        profileId = profileId,
        sessionId = sessionId,
        sessionOrdinal = profileId.toLong(),
        startedAtMs = 1_000L + profileId
    )

    private fun resolvedItem(
        itemKey: String,
        title: String
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = "Overview",
            genres = listOf("Drama"),
            runtimeText = "139m"
        ),
        artwork = ArtworkBundle(),
        rating = TitleRating(8.8, TitleRatingSource.IMDB),
        trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1L
    )
}
