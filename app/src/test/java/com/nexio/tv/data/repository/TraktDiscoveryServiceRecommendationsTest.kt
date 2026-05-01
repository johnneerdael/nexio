package com.nexio.tv.data.repository

import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.TraktRequestGate
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktCalendarEpisodeItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktDiscoveryServiceRecommendationsTest {
    @Test
    fun `ensureFresh publishes recommendation movies from trakt integration provider`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        coEvery {
            traktIntegrationProvider.fetchRecommendations(type = "movies", limit = 20)
        } returns listOf(
            TraktRecommendationItemDto(
                movie = TraktMovieDto(
                    title = "Fight Club",
                    year = 1999,
                    ids = TraktIdsDto(imdb = "tt0137523")
                )
            )
        )
        coEvery {
            traktIntegrationProvider.fetchRecommendations(type = "shows", limit = 20)
        } returns emptyList()
        coEvery { traktIntegrationProvider.fetchPopularLists(page = 1, limit = 30) } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserLists("me") } returns emptyList()

        val service = buildService(
            traktApi = traktApi,
            traktIntegrationProvider = traktIntegrationProvider,
            profileManager = testProfileManager(),
            dataStoreFactory = profileDataStoreFactoryForTest()
        )

        service.ensureFresh(force = true)

        val snapshot = service.observeSnapshot(autoRefreshOnStart = false).first()
        assertEquals(listOf("Fight Club"), snapshot.recommendationMovieItems.map { it.name })
    }

    @Test
    fun `ensureFresh publishes trending and popular rails from trakt integration provider`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        coEvery {
            traktIntegrationProvider.fetchTrendingMovies(limit = 20)
        } returns listOf(
            com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto(
                movie = TraktMovieDto(
                    title = "The Dark Knight",
                    year = 2008,
                    ids = TraktIdsDto(imdb = "tt0468569")
                )
            )
        )
        coEvery {
            traktIntegrationProvider.fetchTrendingShows(limit = 20)
        } returns listOf(
            com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto(
                show = TraktShowDto(
                    title = "Severance",
                    year = 2022,
                    ids = TraktIdsDto(imdb = "tt11280740")
                )
            )
        )
        coEvery {
            traktIntegrationProvider.fetchPopularMovies(limit = 20)
        } returns listOf(
            TraktMovieDto(
                title = "Dune",
                year = 2021,
                ids = TraktIdsDto(imdb = "tt1160419")
            )
        )
        coEvery {
            traktIntegrationProvider.fetchPopularShows(limit = 20)
        } returns listOf(
            TraktShowDto(
                title = "The Last of Us",
                year = 2023,
                ids = TraktIdsDto(imdb = "tt3581920")
            )
        )
        coEvery {
            traktIntegrationProvider.fetchRecommendations(type = any(), limit = any())
        } returns emptyList()
        coEvery { traktIntegrationProvider.fetchPopularLists(page = 1, limit = 30) } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserLists("me") } returns emptyList()

        val service = buildService(
            traktApi = traktApi,
            traktIntegrationProvider = traktIntegrationProvider,
            profileManager = testProfileManager(),
            dataStoreFactory = profileDataStoreFactoryForTest(),
            enabledCatalogs = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.TRENDING_SHOWS,
                TraktCatalogIds.POPULAR_MOVIES,
                TraktCatalogIds.POPULAR_SHOWS
            )
        )

        service.ensureFresh(force = true)

        val snapshot = service.observeSnapshot(autoRefreshOnStart = false).first()
        assertEquals(listOf("The Dark Knight"), snapshot.trendingMovieItems.map { it.name })
        assertEquals(listOf("Severance"), snapshot.trendingShowItems.map { it.name })
        assertEquals(listOf("Dune"), snapshot.popularMovieItems.map { it.name })
        assertEquals(listOf("The Last of Us"), snapshot.popularShowItems.map { it.name })
        coVerify(exactly = 0) { traktApi.getTrendingMovies(any(), any()) }
        coVerify(exactly = 0) { traktApi.getTrendingShows(any(), any()) }
        coVerify(exactly = 0) { traktApi.getPopularMovies(any(), any()) }
        coVerify(exactly = 0) { traktApi.getPopularShows(any(), any()) }
    }

    @Test
    fun `ensureFresh publishes calendar shows from trakt integration provider`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        coEvery {
            traktIntegrationProvider.fetchCalendarShows(any(), days = 7)
        } returns listOf(
            TraktCalendarEpisodeItemDto(
                firstAired = "2026-04-24T20:00:00.000Z",
                show = TraktShowDto(
                    title = "Andor",
                    year = 2022,
                    ids = TraktIdsDto(imdb = "tt9253284")
                ),
                episode = TraktEpisodeDto(
                    season = 2,
                    number = 5,
                    title = "Messenger"
                )
            )
        )
        coEvery { traktIntegrationProvider.fetchRecommendations(any(), any()) } returns emptyList()
        coEvery { traktIntegrationProvider.fetchPopularLists(page = 1, limit = 30) } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserLists("me") } returns emptyList()

        val service = buildService(
            traktApi = traktApi,
            traktIntegrationProvider = traktIntegrationProvider,
            profileManager = testProfileManager(),
            dataStoreFactory = profileDataStoreFactoryForTest(),
            enabledCatalogs = setOf(TraktCatalogIds.CALENDAR)
        )

        service.ensureFresh(force = true)

        val snapshot = service.observeSnapshot(autoRefreshOnStart = false).first()
        assertEquals(listOf("Andor  S2E5"), snapshot.calendarItems.map { it.name })
        coVerify(exactly = 0) { traktApi.getMyShowsCalendar(any(), any(), any()) }
    }

    private suspend fun buildService(
        traktApi: TraktApi,
        traktIntegrationProvider: TraktIntegrationProvider,
        profileManager: ProfileManager,
        dataStoreFactory: com.nexio.tv.data.local.ProfileDataStoreFactory,
        enabledCatalogs: Set<String> = setOf(TraktCatalogIds.RECOMMENDED_MOVIES)
    ): TraktDiscoveryService {
        val authDataStore = TraktAuthDataStore(
            factory = dataStoreFactory,
            profileManager = profileManager
        )
        authDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access",
                tokenType = "Bearer",
                expiresIn = 3600,
                refreshToken = "refresh",
                createdAt = System.currentTimeMillis() / 1000L
            )
        )
        authDataStore.saveUser(username = "johnneerdael", userSlug = "johnneerdael")
        val authService = TraktAuthService(
            traktIntegrationProvider = object : dagger.Lazy<TraktIntegrationProvider> { override fun get() = traktIntegrationProvider },
            traktAuthDataStore = authDataStore,
            requestGate = TraktRequestGate(),
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
        )

        val traktSettings = mockk<TraktSettingsDataStore>()
        every { traktSettings.catalogPreferences } returns flowOf(
            TraktCatalogPreferences(
                enabledCatalogs = enabledCatalogs,
                selectedPopularListKeys = emptySet()
            )
        )
        every { traktSettings.dismissedRecommendationKeys } returns flowOf(emptySet())
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        coEvery { posterResolver.getActiveProvider() } returns null
        every { posterResolver.apply(any<com.nexio.tv.domain.model.Meta>(), null) } answers { firstArg() }
        every { posterResolver.apply(any<com.nexio.tv.domain.model.MetaPreview>(), null) } answers { firstArg() }
        val snapshotStore = mockk<TraktDiscoverySnapshotStore>(relaxed = true)
        every { snapshotStore.read(any()) } returns null
        val debugSettings = mockk<DebugSettingsDataStore>()
        every { debugSettings.diskFirstHomeStartupEnabled } returns flowOf(false)
        val outbox = mockk<TraktMutationOutboxCoordinator>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        coEvery { progressService.getRecentActivities(any()) } returns null
        coEvery { traktApi.getLastActivities(any()) } returns Response.success(null)

        return TraktDiscoveryService(
            traktAuthService = TraktRepositoryAuthGateway(authService),
            traktIntegrationProvider = traktIntegrationProvider,
            traktSettingsDataStore = traktSettings,
            posterRatingsUrlResolver = posterResolver,
            snapshotStore = snapshotStore,
            debugSettingsDataStore = debugSettings,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traktProgressService = progressService
        )
    }
}
