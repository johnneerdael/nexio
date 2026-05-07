package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import java.lang.reflect.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        val published = repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(item)
        )

        assertTrue(published)
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

        val published = repository.publishResolvedItems(
            profileSession = staleSession,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Stale item"))
        )

        assertFalse(published)
        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 1))
        assertEquals(emptyList<ResolvedDisplayItem>(), repository.getSnapshot(profileId = 2))
    }

    @Test
    fun `publishResolvedItems stores a content level deduped surface`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        val published = repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail A Title"),
                resolvedItem(itemKey = "movie:tmdb:550", title = "Rail B Title")
            )
        )

        assertTrue(published)
        assertEquals(1, repository.getSnapshot(profileId = 1).size)
        assertEquals("Rail A Title", repository.getSnapshot(profileId = 1).single().display.title)
    }

    @Test
    fun `home and screensaver surfaces keep independent snapshots for same profile`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })

        val homePublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:home", title = "Visible Home"))
        )
        val screensaverPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:trending", title = "Trending Screensaver"))
        )

        assertTrue(homePublished)
        assertTrue(screensaverPublished)
        assertEquals(
            "Visible Home",
            repository.getSnapshot(ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY, profileId = 1).single().display.title
        )
        assertEquals(
            "Trending Screensaver",
            repository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, profileId = 1).single().display.title
        )
    }

    @Test
    fun `observeScreensaverSurface does not emit when only home surface changes`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val emissions = mutableListOf<List<ResolvedDisplayItem>>()
        val collectJob = launch {
            repository.observeScreensaverSurface(profileId = 1).collect { items ->
                emissions += items
            }
        }

        runCurrent()
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:home", title = "Visible Home"))
        )
        runCurrent()

        assertEquals(listOf(emptyList<ResolvedDisplayItem>()), emissions)
        collectJob.cancel()
    }

    @Test
    fun `screensaver surface publish suppresses semantically unchanged timestamp churn`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val emissions = mutableListOf<List<ResolvedDisplayItem>>()
        val collectJob = launch {
            repository.observeScreensaverSurface(profileId = 1).collect { items ->
                emissions += items
            }
        }
        val first = resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club")
        val samePayloadNewTimestamp = first.copy(updatedAtMs = first.updatedAtMs + 1_000L)

        runCurrent()
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(first)
        )
        runCurrent()
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(samePayloadNewTimestamp)
        )
        runCurrent()

        assertEquals(2, emissions.size)
        assertEquals(emptyList<ResolvedDisplayItem>(), emissions[0])
        assertEquals(listOf(first), emissions[1])
        assertEquals(listOf(first), repository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, 1))
        collectJob.cancel()
    }

    @Test
    fun `screensaver surface publish returns false for semantically unchanged timestamp churn`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val first = resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club")
        val samePayloadNewTimestamp = first.copy(updatedAtMs = first.updatedAtMs + 1_000L)

        val firstPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(first)
        )
        val secondPublished = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(samePayloadNewTimestamp)
        )

        assertTrue(firstPublished)
        assertEquals(false, secondPublished)
    }

    @Test
    fun `observeItem emits the stored resolved item for the requested profile and key`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val expectedItem = resolvedItem(itemKey = "movie:tmdb:550", title = "Observed Title")

        val published = repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(
                expectedItem,
                resolvedItem(itemKey = "movie:tmdb:551", title = "Other Title")
            )
        )

        assertTrue(published)
        assertEquals(
            expectedItem,
            repository.observeItem(profileId = 1, itemKey = "movie:tmdb:550").first()
        )
        assertNull(repository.observeItem(profileId = 2, itemKey = "movie:tmdb:550").first())
    }

    @Test
    fun `incremental publish preserves existing trailer state when incoming update has no trailer state`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val resolvedWithTrailer = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview Title",
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("trailer-a"),
                selectedPlaybackRef = TrailerPlaybackRef.YouTubeId("trailer-a"),
                availabilityReason = "fallback_youtube_id",
                surface = "home"
            )
        )
        val hydratedWithoutTrailer = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Canonical Title",
            trailer = TrailerDisplayState()
        )

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(resolvedWithTrailer)
        )
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(hydratedWithoutTrailer),
            replace = false
        )

        val published = repository.getSnapshot(profileId = 1).single()
        assertEquals("Canonical Title", published.display.title)
        assertEquals(TrailerPlaybackRef.YouTubeId("trailer-a"), published.trailer.selectedPlaybackRef)
        assertEquals(listOf("trailer-a"), published.trailer.fallbackTrailerYtIds)
    }

    @Test
    fun `publishResolvedItems is synchronized to keep profile validation coupled to state mutation`() {
        val method = ResolvedDisplaySurfaceRepository::class.java.getDeclaredMethod(
            "publishResolvedItems",
            ActiveProfileSession::class.java,
            List::class.java
        )

        assertTrue(Modifier.isSynchronized(method.modifiers))
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
        overview: String = "Overview",
        trailer: TrailerDisplayState = TrailerDisplayState(fallbackTrailerYtIds = emptyList())
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
        trailer = trailer,
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1L
    )
}
