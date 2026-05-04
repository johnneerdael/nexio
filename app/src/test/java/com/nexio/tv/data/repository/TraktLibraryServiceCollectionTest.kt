package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktCollectionKind
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktLibrarySnapshotStore
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionShowItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionWriteCountsDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktLibraryServiceCollectionTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true).also {
        every { it.isEffectivelyAuthenticated } returns flowOf(false)
    }
    private val debugSettingsDataStore = mockk<DebugSettingsDataStore>(relaxed = true).also {
        every { it.diskFirstHomeStartupEnabled } returns flowOf(false)
    }
    private val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true).also {
        every { it.read(any()) } returns null
    }
    private val service = TraktLibraryService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktAuthService = TraktRepositoryAuthGateway(mockk(relaxed = true)),
        traktMutationOutboxCoordinator = mockk<ProviderMutationOutboxCoordinator>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true),
        debugSettingsDataStore = debugSettingsDataStore,
        traktAuthDataStore = traktAuthDataStore,
        snapshotStore = snapshotStore
    )

    @Test
    fun isInCollection_matches_movie_by_any_id_form() = runBlocking {
        val item = TraktCollectionMovieItemDto(
            collectedAt = "2014-09-01T09:10:11.000Z",
            movie = TraktMovieDto(
                title = "Batman Begins", year = 2005,
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns
            IntegrationCallResult.Success(listOf(item))
        coEvery { traktIntegrationProvider.getCollectionShows() } returns
            IntegrationCallResult.Success(emptyList())

        service.refreshCollection()

        assertEquals(true, service.isInCollection("tt0372784").first())
        assertEquals(true, service.isInCollection("tmdb:272").first())
        assertEquals(true, service.isInCollection("trakt:6").first())
        assertEquals(true, service.isInCollection("batman-begins-2005").first())
        assertEquals(false, service.isInCollection("tt9999999").first())
    }

    @Test
    fun isInCollection_matches_show_by_tvdb_id() = runBlocking {
        val item = TraktCollectionShowItemDto(
            lastCollectedAt = "2014-07-14T01:00:00.000Z",
            show = TraktShowDto(
                title = "Breaking Bad", year = 2008,
                ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189, imdb = "tt0903747", tmdb = 1396)
            )
        )
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns
            IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.getCollectionShows() } returns
            IntegrationCallResult.Success(listOf(item))

        service.refreshCollection()

        assertEquals(true, service.isInCollection("tvdb:81189").first())
        assertEquals(true, service.isInCollection("tt0903747").first())
    }

    @Test
    fun refreshCollection_force_invalidates_provider_caches() = runBlocking {
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns
            IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.getCollectionShows() } returns
            IntegrationCallResult.Success(emptyList())

        service.refreshCollection(force = true)

        // Both kinds invalidated before re-fetch.
        coVerify { traktIntegrationProvider.invalidateCollectionSnapshot(TraktCollectionKind.MOVIES) }
        coVerify { traktIntegrationProvider.invalidateCollectionSnapshot(TraktCollectionKind.SHOWS) }
    }

    @Test
    fun addToCollection_optimistically_marks_item_in_membership_then_confirms() = runBlocking {
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.addToCollection(any()) } returns
            IntegrationCallResult.Success(TraktCollectionAddResponseDto(
                added = TraktCollectionWriteCountsDto(movies = 1)
            ))
        service.refreshCollection()

        service.addToCollection(
            contentId = "tt0372784",
            contentType = "movie"
        )

        assertEquals(true, service.isInCollection("tt0372784").first())
    }

    @Test
    fun addToCollection_rolls_back_membership_on_network_failure() = runBlocking {
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.addToCollection(any()) } returns IntegrationCallResult.Missing
        service.refreshCollection()

        runCatching {
            service.addToCollection(contentId = "tt0372784", contentType = "movie")
        }

        assertEquals(false, service.isInCollection("tt0372784").first())
    }

    @Test
    fun removeFromCollection_optimistically_clears_membership_then_confirms() = runBlocking {
        val item = TraktCollectionMovieItemDto(
            movie = TraktMovieDto(
                title = "Batman Begins", year = 2005,
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns
            IntegrationCallResult.Success(listOf(item))
        coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.removeFromCollection(any()) } returns
            IntegrationCallResult.Success(TraktCollectionRemoveResponseDto(
                deleted = TraktCollectionWriteCountsDto(movies = 1)
            ))
        service.refreshCollection()

        assertEquals(true, service.isInCollection("tt0372784").first())

        service.removeFromCollection(contentId = "tt0372784", contentType = "movie")

        assertEquals(false, service.isInCollection("tt0372784").first())
    }

    @Test
    fun removeFromCollection_rolls_back_on_failure() = runBlocking {
        val item = TraktCollectionMovieItemDto(
            movie = TraktMovieDto(
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        coEvery { traktIntegrationProvider.getCollectionMovies() } returns
            IntegrationCallResult.Success(listOf(item))
        coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
        coEvery { traktIntegrationProvider.removeFromCollection(any()) } returns IntegrationCallResult.Missing
        service.refreshCollection()

        runCatching {
            service.removeFromCollection(contentId = "tt0372784", contentType = "movie")
        }

        assertEquals(true, service.isInCollection("tt0372784").first())  // restored
    }
}
