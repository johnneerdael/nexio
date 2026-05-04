package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceProfileIsolationTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun cachedActivities_does_not_leak_across_profiles() = runBlocking {
        // getRecentActivities calls runtimeState() (→ currentTraktProfileId()) once per property
        // access: cache-miss path: 1 getter + 2 setters = 3; cache-hit path: 2 getters = 2.
        // 4 calls total: miss(3) + miss(3) + hit(2) + hit(2) = 10 accesses.
        every { traktIntegrationProvider.currentTraktProfileId() } returnsMany
            listOf(1, 1, 1, 2, 2, 2, 1, 1, 2, 2)
        val profile1Activities = TraktLastActivitiesResponseDto(
            all = "2026-05-04T10:00:00Z",
            episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-05-04T09:00:00Z")
        )
        val profile2Activities = TraktLastActivitiesResponseDto(
            all = "2026-05-04T10:05:00Z",
            episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-05-04T10:00:00Z")
        )
        coEvery { traktIntegrationProvider.getLastActivities() } returnsMany listOf(
            IntegrationCallResult.Success(profile1Activities),
            IntegrationCallResult.Success(profile2Activities)
        )

        val first = service.getRecentActivities(maxAgeMs = 60_000L)
        val second = service.getRecentActivities(maxAgeMs = 60_000L)
        val third = service.getRecentActivities(maxAgeMs = 60_000L)
        val fourth = service.getRecentActivities(maxAgeMs = 60_000L)

        assertEquals("2026-05-04T09:00:00Z", first?.episodes?.watchedAt)
        assertEquals("2026-05-04T10:00:00Z", second?.episodes?.watchedAt)
        assertEquals("2026-05-04T09:00:00Z", third?.episodes?.watchedAt)
        assertEquals("2026-05-04T10:00:00Z", fourth?.episodes?.watchedAt)
        coVerify(exactly = 2) { traktIntegrationProvider.getLastActivities() }
    }
}
