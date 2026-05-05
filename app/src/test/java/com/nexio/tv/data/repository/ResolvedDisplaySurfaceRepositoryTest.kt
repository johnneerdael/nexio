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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryTest {
    @Test
    fun `resolved display item carries canonical display fields artwork rating stable ids and trailer state`() {
        val item = ResolvedDisplayItem(
            itemKey = "movie:tmdb:550",
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "550",
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            display = ResolvedDisplayFields(
                title = "Fight Club",
                originalTitle = null,
                year = 1999,
                releaseDate = "1999",
                overview = "An insomniac office worker...",
                genres = listOf("Drama"),
                runtimeText = "139m"
            ),
            artwork = ArtworkBundle(),
            rating = TitleRating(value = 8.8, source = TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 123L
        )

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("Fight Club", item.display.title)
        assertEquals("tt0137523", item.stableIds.imdb)
        assertEquals(8.8, item.rating?.value ?: 0.0, 0.0)
        assertTrue(item.trailer.fallbackTrailerYtIds.isEmpty())
    }

    @Test
    fun `publishResolvedItems stores final items without recomposing overlays`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val item = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Already Final Home Title",
            overview = "Already final overview"
        )

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(item)
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("Already Final Home Title", snapshot.single().display.title)
        assertEquals("Already final overview", snapshot.single().display.overview)
    }

    @Test
    fun `publishResolvedItems rejects stale profile publish after profile switch`() = runTest {
        val staleSession = profileSession(profileId = 1, sessionId = "session-a")
        val activeSession = MutableStateFlow(profileSession(profileId = 2, sessionId = "session-b"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        repository.publishResolvedItems(
            profileSession = staleSession,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Stale item"))
        )

        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 1))
        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 2))
    }

    @Test
    fun `publishResolvedItems stores a content level deduped surface`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail A Title"),
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail B Title")
            )
        )

        assertEquals(1, repository.getSnapshot(profileId = 1).size)
        assertEquals("Rail A Title", repository.getSnapshot(profileId = 1).single().display.title)
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
        title: String,
        overview: String = "Overview"
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
            overview = overview,
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
