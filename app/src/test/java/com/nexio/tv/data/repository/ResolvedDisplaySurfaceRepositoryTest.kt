package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
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
    fun `publishResolvedItems preserves resolved logo artwork`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val logo = artworkRef(key = "logo-94997", imageType = ArtworkType.LOGO)
        val item = resolvedItem(
            itemKey = "series:tmdb:94997",
            title = "House of the Dragon"
        ).copy(artwork = ArtworkBundle(logo = logo))

        val published = repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(item)
        )

        assertTrue(published)
        assertEquals(
            logo,
            repository.getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, profileId = 1)
                .single()
                .artwork
                .logo
        )
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
    fun `homeAuthorityAliasKeysWithRenderablePoster excludes decision only poster refs`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val item = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Decision-only poster"
        ).copy(artwork = ArtworkBundle(poster = artworkRef(key = "poster-decision", imageType = ArtworkType.POSTER)))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(item))

        assertTrue(
            repository.homeAuthorityAliasKeysWithRenderablePoster(profileId = 1).isEmpty()
        )
    }

    @Test
    fun `homeAuthorityAliasKeysWithRenderablePoster includes asset backed poster refs`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val item = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Cached poster"
        ).copy(artwork = ArtworkBundle(poster = assetArtworkRef(key = "poster-asset", imageType = ArtworkType.POSTER)))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(item))

        assertTrue(
            "movie:tmdb:550" in repository.homeAuthorityAliasKeysWithRenderablePoster(profileId = 1)
        )
    }

    @Test
    fun `publishResolvedItems merges alias rows into one canonical authority item`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val tmdbRailItem = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "First paint title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val imdbRailItem = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Hydrated title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        val published = repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(tmdbRailItem, imdbRailItem)
        )

        assertTrue(published)
        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Hydrated title", snapshot.single().display.title)
        assertEquals("tt0137523", snapshot.single().stableIds.imdb)
        assertEquals(snapshot.single(), repository.observeItem(profileId = 1, itemKey = "movie:imdb:tt0137523").first())
    }

    @Test
    fun `incremental publish strengthens an alias match instead of appending duplicate row`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val preview = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val hydratedAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Canonical title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(preview)
        )
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(hydratedAlias),
            replace = false
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Canonical title", snapshot.single().display.title)
        assertEquals("tt0137523", snapshot.single().stableIds.imdb)
    }

    @Test
    fun `incremental alias update preserves existing surface position`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val first = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val second = resolvedItem(
            itemKey = "movie:tmdb:551",
            title = "Second title"
        ).copy(
            contentId = "tmdb:551",
            parentId = "tmdb:551",
            canonicalId = "551",
            imdbId = "tt-other",
            stableIds = ProviderIds(tmdb = "551", imdb = "tt-other")
        )
        val firstAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Canonical title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(first, second))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(firstAlias),
            replace = false
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(listOf("movie:tmdb:550", "movie:tmdb:551"), snapshot.map { it.itemKey })
        assertEquals("Canonical title", snapshot[0].display.title)
        assertEquals("Second title", snapshot[1].display.title)
    }

    @Test
    fun `replace alias update preserves existing authority key`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val preview = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val hydratedAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Canonical title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(preview))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(hydratedAlias),
            replace = true
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Canonical title", snapshot.single().display.title)
    }

    @Test
    fun `replace with competing aliases keeps hydrated display`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val preview = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val hydratedAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Canonical title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(preview))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(preview, hydratedAlias),
            replace = true
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Canonical title", snapshot.single().display.title)
        assertEquals("tt0137523", snapshot.single().stableIds.imdb)
    }

    @Test
    fun `replace with competing aliases keeps hydrated display when alias arrives first`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val preview = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Preview title"
        ).copy(stableIds = ProviderIds(tmdb = "550"), imdbId = null)
        val hydratedAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Canonical title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(preview))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(hydratedAlias, preview),
            replace = true
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Canonical title", snapshot.single().display.title)
        assertEquals("tt0137523", snapshot.single().stableIds.imdb)
    }

    @Test
    fun `restoreFromDisk skips existing aliases without overwriting memory`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val memoryItem = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Memory title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))
        val diskAlias = resolvedItem(
            itemKey = "movie:imdb:tt0137523",
            title = "Stale disk title"
        ).copy(stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"))

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(memoryItem))
        repository.restoreFromDisk(mapOf(diskAlias.itemKey to diskAlias), profileId = 1)

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("movie:tmdb:550", snapshot.single().itemKey)
        assertEquals("Memory title", snapshot.single().display.title)
    }

    @Test
    fun `bare content ids do not alias across providers`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val tmdbBare = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "TMDB title"
        ).copy(
            contentId = "550",
            parentId = "550",
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds()
        )
        val traktBare = resolvedItem(
            itemKey = "movie:trakt:550",
            title = "Trakt title"
        ).copy(
            contentId = "550",
            parentId = "550",
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds()
        )

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(tmdbBare, traktBare))

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(2, snapshot.size)
        assertEquals(listOf("TMDB title", "Trakt title"), snapshot.map { it.display.title })
    }

    @Test
    fun `stable simkl id participates in authority aliases`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val tmdbItem = resolvedItem(
            itemKey = "series:tmdb:94997",
            title = "TMDB title"
        ).copy(
            itemType = ContentType.SERIES,
            mediaKind = MetadataMediaKind.SERIES,
            contentId = "tmdb:tv:94997",
            parentId = "tmdb:tv:94997",
            stableIds = ProviderIds(tmdb = "94997", simkl = "5045")
        )
        val simklAlias = resolvedItem(
            itemKey = "series:simkl:5045",
            title = "Simkl title"
        ).copy(
            itemType = ContentType.SERIES,
            mediaKind = MetadataMediaKind.SERIES,
            contentId = "simkl:5045",
            parentId = "simkl:5045",
            stableIds = ProviderIds(simkl = "5045")
        )

        repository.publishResolvedItems(profileSession = activeSession.value, items = listOf(tmdbItem))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(simklAlias),
            replace = false
        )

        val snapshot = repository.getSnapshot(profileId = 1)
        assertEquals(1, snapshot.size)
        assertEquals("series:tmdb:94997", snapshot.single().itemKey)
        assertEquals("Simkl title", snapshot.single().display.title)
        assertEquals("5045", snapshot.single().stableIds.simkl)
        assertEquals(snapshot.single(), repository.observeItem(profileId = 1, itemKey = "tv:simkl:5045").first())
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
    fun `home surface publish suppresses semantically unchanged timestamp and trailer resolution churn`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val emissions = mutableListOf<List<ResolvedDisplayItem>>()
        val collectJob = launch {
            repository.observeHomeSurface(profileId = 1).collect { items ->
                emissions += items
            }
        }
        val first = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Fight Club",
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("trailer-a"),
                selectedPlaybackRef = TrailerPlaybackRef.YouTubeId("trailer-a"),
                availabilityReason = "fallback_youtube_id",
                surface = "home",
                lastResolvedAtMs = 100L
            )
        )
        val sameDisplayNewRuntimeState = first.copy(
            updatedAtMs = 2_000L,
            trailer = first.trailer.copy(
                selectedPlaybackRef = TrailerPlaybackRef.YouTubeId("trailer-b"),
                lastResolvedAtMs = 3_000L
            )
        )

        runCurrent()
        assertTrue(
            repository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
                profileSession = activeSession.value,
                items = listOf(first)
            )
        )
        runCurrent()
        assertTrue(
            repository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
                profileSession = activeSession.value,
                items = listOf(sameDisplayNewRuntimeState)
            )
        )
        runCurrent()

        assertEquals(2, emissions.size)
        assertEquals(emptyList<ResolvedDisplayItem>(), emissions[0])
        assertEquals(listOf(first), emissions[1])
        assertEquals(listOf(sameDisplayNewRuntimeState), repository.getSnapshot(profileId = 1))
        collectJob.cancel()
    }

    @Test
    fun `home surface stores identity strengthening without display emission`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val emissions = mutableListOf<List<ResolvedDisplayItem>>()
        val collectJob = launch {
            repository.observeHomeSurface(profileId = 1).collect { items ->
                emissions += items
            }
        }
        val first = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Fight Club"
        ).copy(
            imdbId = null,
            stableIds = ProviderIds(tmdb = "550")
        )
        val strengthened = first.copy(
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        )

        runCurrent()
        assertTrue(
            repository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
                profileSession = activeSession.value,
                items = listOf(first),
                replace = true
            )
        )
        runCurrent()
        assertTrue(
            repository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
                profileSession = activeSession.value,
                items = listOf(strengthened),
                replace = true
            )
        )
        runCurrent()

        assertEquals(2, emissions.size)
        assertEquals("tt0137523", repository.getSnapshot(profileId = 1).single().stableIds.imdb)
        assertEquals(null, emissions.last().single().stableIds.imdb)
        collectJob.cancel()
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
                resolvedItem(itemKey = "movie:tmdb:551", title = "Other Title").copy(
                    contentId = "tmdb:551",
                    parentId = "tmdb:551",
                    canonicalId = "551",
                    imdbId = "tt-other",
                    stableIds = ProviderIds(tmdb = "551", imdb = "tt-other")
                )
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
    fun `hasHomeAuthorityItem matches aliases and ignores preview-only entries by default`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val hydrated = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Hydrated Title"
        )
        val previewOnly = resolvedItem(
            itemKey = "movie:tmdb:551",
            title = "Preview Title"
        ).copy(
            contentId = "tmdb:551",
            parentId = "tmdb:551",
            canonicalId = "551",
            imdbId = null,
            stableIds = ProviderIds(tmdb = "551", imdb = "tt-preview"),
            hydrationState = HydrationState.PREVIEW_ONLY
        )

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(hydrated, previewOnly)
        )

        assertTrue(repository.hasHomeAuthorityItem(profileId = 1, itemKey = "movie:imdb:tt0137523"))
        assertTrue(repository.hasHomeAuthorityItem(profileId = 1, itemKey = "movie:tmdb:550"))
        assertFalse(repository.hasHomeAuthorityItem(profileId = 1, itemKey = "movie:imdb:tt-preview"))
        assertTrue(
            repository.hasHomeAuthorityItem(
                profileId = 1,
                itemKey = "movie:imdb:tt-preview",
                includePreviewOnly = true
            )
        )
        assertFalse(repository.hasHomeAuthorityItem(profileId = 2, itemKey = "movie:imdb:tt0137523"))
    }

    @Test
    fun `homeAuthorityItemsByAlias indexes all aliases to the authority item`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val item = resolvedItem(
            itemKey = "movie:tmdb:550",
            title = "Hydrated Title"
        )

        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(item)
        )

        val aliases = repository.homeAuthorityItemsByAlias(profileId = 1)
        assertEquals("Hydrated Title", aliases["movie:tmdb:550"]?.display?.title)
        assertEquals("Hydrated Title", aliases["movie:imdb:tt0137523"]?.display?.title)
    }

    @Test
    fun `same-rank display update keeps existing fields when feature signature is unchanged`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val first = resolvedItem(itemKey = "movie:tmdb:550", title = "Stable Title")
            .withResolvedSlots(title = "Stable Title", overview = "Stable Overview")
            .copy(displayLanguageTag = "en-US")
        val second = resolvedItem(itemKey = "movie:tmdb:550", title = "Racing Title")
            .withResolvedSlots(title = "Racing Title", overview = "Racing Overview")
            .copy(displayLanguageTag = "en-US")

        repository.publishResolvedItems(activeSession.value, listOf(first))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(second),
            replace = false
        )

        val published = repository.getSnapshot(profileId = 1).single()
        assertEquals("Stable Title", published.display.title)
        assertEquals("Stable Overview", published.display.overview)
    }

    @Test
    fun `same-rank display update can replace fields when feature signature changes`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        val first = resolvedItem(itemKey = "movie:tmdb:550", title = "English Title")
            .withResolvedSlots(title = "English Title", overview = "English Overview")
            .copy(displayLanguageTag = "en-US")
        val second = resolvedItem(itemKey = "movie:tmdb:550", title = "Nederlandse Titel")
            .withResolvedSlots(title = "Nederlandse Titel", overview = "Nederlandse Beschrijving")
            .copy(displayLanguageTag = "nl-NL")

        repository.publishResolvedItems(activeSession.value, listOf(first))
        repository.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = activeSession.value,
            items = listOf(second),
            replace = false
        )

        val published = repository.getSnapshot(profileId = 1).single()
        assertEquals("Nederlandse Titel", published.display.title)
        assertEquals("Nederlandse Beschrijving", published.display.overview)
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

    @Test
    fun `clearSurface removes only the requested profile surface`() = runTest {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val repository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession.value })
        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Profile 1"))
        )
        activeSession.value = profileSession(profileId = 2, sessionId = "session-b")
        repository.publishResolvedItems(
            profileSession = activeSession.value,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:551", title = "Profile 2"))
        )

        val cleared = repository.clearSurface(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileId = 1
        )

        assertTrue(cleared)
        assertTrue(repository.getSnapshot(profileId = 1).isEmpty())
        assertEquals("Profile 2", repository.getSnapshot(profileId = 2).single().display.title)
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

    private fun ResolvedDisplayItem.withResolvedSlots(
        title: String,
        overview: String = display.overview.orEmpty()
    ): ResolvedDisplayItem {
        val nowMs = 1L
        val slots = ResolvedDisplayFieldSlots(
            title = slot(title, nowMs),
            originalTitle = slot(display.originalTitle, nowMs),
            overview = slot(overview, nowMs),
            genres = slot(display.genres, nowMs),
            releaseInfo = slot(display.releaseDate, nowMs),
            runtime = slot(display.runtimeText, nowMs),
            rating = slot(rating, nowMs),
            poster = slot(artwork.poster, nowMs),
            backdrop = slot(artwork.backdrop, nowMs),
            logo = slot(artwork.logo, nowMs),
            thumbnail = slot(artwork.thumbnail, nowMs),
            posterProviderTag = slot(null, nowMs)
        )
        return copy(slots = slots)
    }

    private fun <T> slot(value: T?, nowMs: Long): ResolvedSlot<T> =
        ResolvedSlot(
            value = value,
            rank = DisplaySourceRank.RESOLVED,
            provider = "test",
            role = "test",
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun artworkRef(
        key: String,
        imageType: ArtworkType
    ) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = imageType,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )

    private fun assetArtworkRef(
        key: String,
        imageType: ArtworkType
    ) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = ArtworkAssetKey("asset-$key"),
        imageType = imageType,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )
}
