package com.nexio.tv.data.repository

import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral tests for [ContinueWatchingSnapshotService.observeProfileSnapshot].
 *
 * F-G-01 path B: verifies the typed profile-scoped flow filters by profileId and unwraps
 * to [ContinueWatchingSnapshot], replacing the manual chained-filter workaround.
 *
 * Approach A: construct a minimal real service (all deps mocked, auth=false so the live
 * pipeline stays dormant) and drive [snapshotState] + [persistedSnapshotReady] directly via
 * reflection to control what [observeSnapshot] emits.
 */
class ContinueWatchingSnapshotServiceObserveProfileSnapshotTest {

    /**
     * Build a minimal [ContinueWatchingSnapshotService] with all dependencies mocked.
     * Auth is always false → upstream combine never fires → snapshotState stays at whatever
     * we seed via [seedSnapshot].
     */
    private fun buildService(): ContinueWatchingSnapshotService {
        val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
            every { state } returns flowOf(EffectiveTrackingProviderState())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } returns Unit
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns flowOf(emptyList())
        }
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
        }
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val metaRepository = mockk<MetaRepository>(relaxed = true)

        return ContinueWatchingSnapshotService(
            watchProgressRepository = watchProgressRepository,
            trackingProgressService = trackingProgressService,
            trackingProviderStateService = trackingProviderStateService,
            traktSettingsDataStore = traktSettingsDataStore,
            metaRepository = metaRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            snapshotStore = snapshotStore
        )
    }

    /**
     * Seed both [rawSnapshotState] and [snapshotState] (plus [persistedSnapshotReady]) via
     * reflection so [observeSnapshot] emits the supplied [owned] value. Seeding rawSnapshotState
     * is necessary because the service's internal combine pipeline pushes rawSnapshotState into
     * snapshotState on Dispatchers.IO, which would overwrite a snapshotState-only seed.
     */
    @Suppress("UNCHECKED_CAST")
    private fun seedSnapshot(
        service: ContinueWatchingSnapshotService,
        owned: ProfileOwnedContinueWatchingSnapshot
    ) {
        val clazz = ContinueWatchingSnapshotService::class.java

        val rawField = clazz.getDeclaredField("rawSnapshotState")
        rawField.isAccessible = true
        (rawField.get(service) as MutableStateFlow<ProfileOwnedContinueWatchingSnapshot>).value = owned

        val snapshotStateField = clazz.getDeclaredField("snapshotState")
        snapshotStateField.isAccessible = true
        (snapshotStateField.get(service) as MutableStateFlow<ProfileOwnedContinueWatchingSnapshot>)
            .value = owned

        val readyField = clazz.getDeclaredField("persistedSnapshotReady")
        readyField.isAccessible = true
        (readyField.get(service) as MutableStateFlow<Boolean>).value = true
    }

    // ── Test 1: filters by profile and unwraps to snapshot ────────────────────

    @Test
    fun `observeProfileSnapshot filters by profile and unwraps to snapshot`() = runTest {
        val service = buildService()
        val targetSnapshot = ContinueWatchingSnapshot(
            resumeItems = emptyList(),
            updatedAtMs = 42L
        )
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = 2, snapshot = targetSnapshot)

        // Seed the foreign profile first (with a non-default snapshot to guarantee a StateFlow
        // emission), then switch to the target profile. This ensures the MutableStateFlow
        // value has changed at least twice before .first() subscribes, so the combine
        // has a stable current value to emit.
        seedSnapshot(service, ProfileOwnedContinueWatchingSnapshot(
            profileId = 1,
            snapshot = ContinueWatchingSnapshot(updatedAtMs = 1L)
        ))
        seedSnapshot(service, owned)

        val result = service.observeProfileSnapshot(profileId = 2).first()

        assertEquals(targetSnapshot, result)
    }

    // ── Test 2: drops snapshots owned by a different profile ──────────────────

    @Test
    fun `observeProfileSnapshot drops snapshots from other profiles`() = runTest {
        val service = buildService()

        val foreignSnapshot = ContinueWatchingSnapshot(updatedAtMs = 10L)
        val mine = ContinueWatchingSnapshot(updatedAtMs = 99L)

        // Seed with the foreign snapshot first, then immediately switch to ours so .first()
        // receives the matching emission.
        seedSnapshot(service, ProfileOwnedContinueWatchingSnapshot(profileId = 1, snapshot = foreignSnapshot))

        // Now switch to the matching profile — observeProfileSnapshot must skip profile 1
        // and return the profile-2 emission.
        seedSnapshot(service, ProfileOwnedContinueWatchingSnapshot(profileId = 2, snapshot = mine))

        val result = service.observeProfileSnapshot(profileId = 2).first()

        assertEquals(mine, result)
    }

    // ── Test 3: require guard rejects non-positive profileId ─────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `observeProfileSnapshot throws for profileId zero`() {
        val service = buildService()
        service.observeProfileSnapshot(profileId = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `observeProfileSnapshot throws for negative profileId`() {
        val service = buildService()
        service.observeProfileSnapshot(profileId = -1)
    }
}
