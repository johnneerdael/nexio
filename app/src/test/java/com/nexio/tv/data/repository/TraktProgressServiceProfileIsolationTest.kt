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
import java.util.concurrent.atomic.AtomicInteger

class TraktProgressServiceProfileIsolationTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun cachedActivities_does_not_leak_across_profiles() = runBlocking {
        // currentTraktProfileId() is read multiple times per getRecentActivities call (once
        // per runtimeState() accessor). Use a counter the test orchestrates between calls so
        // the test doesn't depend on the exact internal read count.
        val activeProfileId = AtomicInteger(1)
        every { traktIntegrationProvider.currentTraktProfileId() } answers { activeProfileId.get() }
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

        activeProfileId.set(1)
        val first = service.getRecentActivities(maxAgeMs = 60_000L)
        activeProfileId.set(2)
        val second = service.getRecentActivities(maxAgeMs = 60_000L)
        activeProfileId.set(1)
        val third = service.getRecentActivities(maxAgeMs = 60_000L)
        activeProfileId.set(2)
        val fourth = service.getRecentActivities(maxAgeMs = 60_000L)

        assertEquals("2026-05-04T09:00:00Z", first?.episodes?.watchedAt)
        assertEquals("2026-05-04T10:00:00Z", second?.episodes?.watchedAt)
        assertEquals("2026-05-04T09:00:00Z", third?.episodes?.watchedAt)
        assertEquals("2026-05-04T10:00:00Z", fourth?.episodes?.watchedAt)
        coVerify(exactly = 2) { traktIntegrationProvider.getLastActivities() }
    }

    @Test
    fun episodeInfoCache_does_not_leak_across_profiles() = runBlocking {
        val activeProfileId = AtomicInteger(1)
        every { traktIntegrationProvider.currentTraktProfileId() } answers { activeProfileId.get() }

        // Profile 1 caches an entry via the test seam.
        activeProfileId.set(1)
        service.testOnlyPutEpisodeInfo(
            contentId = "tvdb:81189",
            season = 1,
            episode = 1,
            info = sampleResolvedEpisodeInfo()
        )

        // Profile 2's cache must not see profile 1's entry.
        activeProfileId.set(2)
        val profile2HasIt = service.testOnlyEpisodeInfoCacheContains(
            contentId = "tvdb:81189",
            season = 1,
            episode = 1
        )
        assertEquals(false, profile2HasIt)

        // Profile 1's cache still has it.
        activeProfileId.set(1)
        val profile1HasIt = service.testOnlyEpisodeInfoCacheContains(
            contentId = "tvdb:81189",
            season = 1,
            episode = 1
        )
        assertEquals(true, profile1HasIt)
    }

    @Test
    fun event_driven_refresh_throttle_does_not_leak_across_profiles() = runBlocking {
        val activeProfileId = AtomicInteger(1)
        every { traktIntegrationProvider.currentTraktProfileId() } answers { activeProfileId.get() }

        // Profile 1: first call fires (no prior throttle).
        activeProfileId.set(1)
        service.requestEventDrivenRefresh()
        // Profile 1: second call within throttle window — suppressed.
        service.requestEventDrivenRefresh()
        // Profile 2: first call fires (different profile, independent throttle).
        activeProfileId.set(2)
        service.requestEventDrivenRefresh()

        // Each profile's per-profile counter must reflect exactly one firing — proving the
        // throttle is not shared across profiles. Querying the per-profile counter (rather
        // than a singleton total) keeps all observable test state inside TraktProgressRuntimeState.
        assertEquals(1, service.testOnlyEventDrivenRefreshFiringCount(profileId = 1))
        assertEquals(1, service.testOnlyEventDrivenRefreshFiringCount(profileId = 2))
    }

    private fun sampleResolvedEpisodeInfo() =
        TraktProgressService.ResolvedEpisodeInfo(videoId = "tvdb:81189:1:1", released = null)
}
