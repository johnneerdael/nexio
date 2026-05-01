package com.nexio.tv.data.repository

import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktListIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktDiscoveryServiceCustomListTest {
    @Test
    fun `selected personal list publishes season items as show catalog row`() = runTest {
        val traktApi = mockk<TraktApi>()
        coEvery { traktApi.getLastActivities(any()) } returns Response.success(
            TraktLastActivitiesResponseDto(all = "2026-04-19T20:00:00.000Z")
        )
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        coEvery { traktIntegrationProvider.fetchUserLists("me") } returns listOf(
            TraktListSummaryDto(
                name = "Anime",
                type = "personal",
                itemCount = 1,
                ids = TraktListIdsDto(slug = "anime")
            )
        )
        coEvery { traktIntegrationProvider.fetchPopularLists(page = 1, limit = 30) } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserListItems("me", "anime", "movies") } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserListItems("me", "anime", "shows") } returns emptyList()
        coEvery { traktIntegrationProvider.fetchUserListItems("me", "anime", "seasons") } returns
            listOf(
                TraktListItemDto(
                    type = "season",
                    season = TraktListSeasonDto(number = 1),
                    show = TraktShowDto(
                        title = "Anime Show",
                        year = 2025,
                        ids = TraktIdsDto(trakt = 999999, imdb = "tt7654321")
                    )
                )
            )
        coEvery { traktIntegrationProvider.fetchUserListItems("me", "anime", "episodes") } returns emptyList()

        val service = buildService(
            traktApi = traktApi,
            traktIntegrationProvider = traktIntegrationProvider
        )

        service.ensureFresh(force = true)

        val snapshot = service.observeSnapshot(autoRefreshOnStart = false)
            .first()
        val customCatalog = snapshot
            .customListCatalogs
            .single()
        assertEquals(listOf("johnneerdael/anime"), snapshot.popularLists.map { it.key })
        assertEquals(listOf("me/anime"), snapshot.popularLists.single().alternateKeys)
        assertEquals("me/anime", customCatalog.key)
        assertEquals("Anime (Shows)", customCatalog.catalogName)
        assertEquals(listOf("Anime Show"), customCatalog.items.map { it.name })
    }

    private suspend fun buildService(
        traktApi: TraktApi,
        traktIntegrationProvider: TraktIntegrationProvider
    ): TraktDiscoveryService {
        val profileManager = testProfileManager()
        val authenticatedState = com.nexio.tv.data.local.TraktAuthState(
            accessToken = "access",
            refreshToken = "refresh",
            tokenType = "Bearer",
            username = "johnneerdael",
            userSlug = "johnneerdael"
        )
        val authGateway = mockk<TraktRepositoryAuthGateway> {
            coEvery { getCurrentAuthState() } returns authenticatedState
        }

        val traktSettings = mockk<TraktSettingsDataStore>()
        every { traktSettings.catalogPreferences } returns flowOf(
            TraktCatalogPreferences(selectedPopularListKeys = setOf("me/anime"))
        )
        every { traktSettings.dismissedRecommendationKeys } returns flowOf(emptySet())
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        coEvery { posterResolver.getActiveProvider() } returns null
        every { posterResolver.apply(any<Meta>(), null) } answers { firstArg() }
        every { posterResolver.apply(any<com.nexio.tv.domain.model.MetaPreview>(), null) } answers { firstArg() }
        val snapshotStore = mockk<TraktDiscoverySnapshotStore>(relaxed = true)
        every { snapshotStore.read(any()) } returns null
        val debugSettings = mockk<DebugSettingsDataStore>()
        every { debugSettings.diskFirstHomeStartupEnabled } returns flowOf(false)
        val outbox = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        coEvery { progressService.getRecentActivities(any()) } returns null

        return TraktDiscoveryService(
            traktAuthService = authGateway,
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
