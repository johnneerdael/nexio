package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContinueWatchingSnapshotServiceProfileBoundaryTest {

    @Test
    fun `foreign snapshot is not publishable for active profile`() {
        val foreign = ProfileOwnedContinueWatchingSnapshot(
            profileId = 1,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-one")),
                updatedAtMs = 10L
            )
        )

        assertEquals(false, foreign.isOwnedBy(2))
    }

    @Test
    fun `owned snapshot is publishable for active profile`() {
        val owned = ProfileOwnedContinueWatchingSnapshot(
            profileId = 2,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-two")),
                updatedAtMs = 20L
            )
        )

        assertEquals(true, owned.isOwnedBy(2))
    }

    @Test
    fun `observing snapshot waits for persisted active profile snapshot before emitting default empty`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
        val persisted = ContinueWatchingSnapshot(
            resumeItems = listOf(sampleProgress("tt-persisted")),
            updatedAtMs = 50L
        )
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(1) } answers {
                Thread.sleep(200L)
                persisted
            }
        }
        val service = ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns MutableStateFlow(emptyList())
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns MutableStateFlow(false)
                every { observeRemoteSnapshotLoaded(any()) } returns MutableStateFlow(false)
                every { observeAllProgress(any()) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                val trackingState = MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
                every { state } returns trackingState
                every { stateForProfile(any()) } returns trackingState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            profileManager = profileManager
        )

        val firstSnapshot = service.observeSnapshot().first()

        assertEquals(1, firstSnapshot.profileId)
        assertEquals(
            "tt-persisted",
            firstSnapshot.snapshot.resumeItems.single().contentId
        )
    }

    @Test
    fun `stale default profile emission after switch is not stamped as secondary profile snapshot`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val profileSwitched = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns profileSwitched
        }
        val providerState = MutableStateFlow(
            EffectiveTrackingProviderState(traktAuthenticated = true)
        )
        val remoteLoaded = MutableStateFlow(true)
        val progress = MutableStateFlow(listOf(sampleProgress("tt-default-user")))
        val persistedProfileIds = mutableListOf<Int>()
        val persistedSnapshots = mutableListOf<ContinueWatchingSnapshot>()
        val snapshotSlot = slot<ContinueWatchingSnapshot>()
        val profileSlot = slot<Int>()
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
            every { write(capture(snapshotSlot), profileId = capture(profileSlot)) } answers {
                persistedSnapshots += snapshotSlot.captured
                persistedProfileIds += profileSlot.captured
            }
        }

        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns progress
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns remoteLoaded
                every { observeRemoteSnapshotLoaded(any()) } returns remoteLoaded
                every { observeAllProgress(any()) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                every { state } returns providerState
                every { stateForProfile(any()) } returns providerState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            profileManager = profileManager
        )

        awaitCondition { persistedProfileIds.contains(1) }

        activeProfileId.value = 2
        profileSwitched.emit(2)
        progress.value = listOf(sampleProgress("tt-default-user", lastWatched = 2L))

        remoteLoaded.value = false
        progress.value = emptyList()
        Thread.sleep(100L)
        remoteLoaded.value = true
        progress.value = listOf(sampleProgress("tt-profile-two", lastWatched = 3L))

        awaitCondition {
            persistedProfileIds.zip(persistedSnapshots).any { (profileId, snapshot) ->
                profileId == 2 && snapshot.resumeItems.any { it.contentId == "tt-profile-two" }
            }
        }

        val leakedSecondaryWrites = persistedProfileIds.zip(persistedSnapshots).filter { (profileId, snapshot) ->
            profileId == 2 && snapshot.resumeItems.any { it.contentId == "tt-default-user" }
        }
        assertFalse(
            "Default profile continue-watching data must not be persisted as profile 2 data",
            leakedSecondaryWrites.isNotEmpty()
        )
    }

    @Test
    fun `profile snapshot ignores global tracking next up rows from another profile`() = runTest {
        val activeProfileId = MutableStateFlow(2)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
        val persistedProfileIds = mutableListOf<Int>()
        val persistedSnapshots = mutableListOf<ContinueWatchingSnapshot>()
        val snapshotSlot = slot<ContinueWatchingSnapshot>()
        val profileSlot = slot<Int>()
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
            every { write(capture(snapshotSlot), profileId = capture(profileSlot)) } answers {
                persistedSnapshots += snapshotSlot.captured
                persistedProfileIds += profileSlot.captured
            }
        }
        val leakedProfileOneNextUp = TrackingNextUpEntry(
            contentId = "series:profile-one",
            name = "Profile One Only",
            season = 1,
            episode = 2,
            episodeTitle = "Leak",
            videoId = "series:profile-one:1:2",
            firstAired = "2024-01-01T00:00:00.000Z",
            firstAiredMs = 1L,
            activityAtMs = 10L
        )

        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns MutableStateFlow(emptyList())
                every { observeProgress(2) } returns MutableStateFlow(listOf(sampleProgress("tt-profile-two")))
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns MutableStateFlow(true)
                every { observeRemoteSnapshotLoaded(2) } returns MutableStateFlow(true)
                every { observeAllProgress() } returns flowOf(listOf(sampleProgress("tt-profile-one")))
                every { observeAllProgress(2) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp() } returns flowOf(listOf(leakedProfileOneNextUp))
                every { observeContinueWatchingNextUp(2) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(listOf(leakedProfileOneNextUp))
                every { observeSyntheticContinueWatchingNextUp(2) } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                val trackingState = MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
                every { state } returns trackingState
                every { stateForProfile(any()) } returns trackingState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            profileManager = profileManager
        )

        awaitCondition {
            persistedProfileIds.contains(2)
        }

        val profileTwoWrites = persistedProfileIds.zip(persistedSnapshots)
            .filter { (profileId, _) -> profileId == 2 }
            .map { (_, snapshot) -> snapshot }
        assertFalse(
            "Profile 2 must not persist global profile 1 next-up rows",
            profileTwoWrites.any { snapshot ->
                snapshot.nextUpItems.any { it.contentId == "series:profile-one" } ||
                    snapshot.traktUpNextItems.any { it.contentId == "series:profile-one" } ||
                    snapshot.resumeItems.any { it.contentId == "tt-profile-one" }
            }
        )
    }

    @Test
    fun `first live publish after profile switch does not retain contaminated cached rows`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val profileSwitched = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns profileSwitched
        }
        val remoteLoaded = MutableStateFlow(false)
        val profileOneProgress = MutableStateFlow(listOf(sampleProgress("tt-profile-one")))
        val profileTwoProgress = MutableStateFlow(listOf(sampleProgress("tt-profile-two")))
        val persistedProfileIds = mutableListOf<Int>()
        val persistedSnapshots = mutableListOf<ContinueWatchingSnapshot>()
        val snapshotSlot = slot<ContinueWatchingSnapshot>()
        val profileSlot = slot<Int>()
        val contaminatedProfileTwoSnapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(sampleProgress("tt-profile-one")),
            records = listOf(
                ContinueWatchingRecord(
                    profileId = 2,
                    parentId = "tt-profile-one",
                    contentId = "tt-profile-one",
                    provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
                    routingVersion = 1,
                    positionMs = 100L,
                    durationMs = 1_000L,
                    episodeContext = null,
                    clickTimeDisplayMetadata = null,
                    source = ContinueWatchingRecord.Source.REMOTE,
                    updatedAt = 10L
                )
            ),
            updatedAtMs = 10L
        )
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } answers {
                when (firstArg<Int>()) {
                    2 -> contaminatedProfileTwoSnapshot
                    else -> null
                }
            }
            every { write(capture(snapshotSlot), profileId = capture(profileSlot)) } answers {
                persistedSnapshots += snapshotSlot.captured
                persistedProfileIds += profileSlot.captured
            }
        }

        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns MutableStateFlow(emptyList())
                every { observeProgress(1) } returns profileOneProgress
                every { observeProgress(2) } returns profileTwoProgress
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded(any()) } returns remoteLoaded
                every { observeAllProgress(any()) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeRemoteSnapshotLoaded() } returns remoteLoaded
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                val trackingState = MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
                every { state } returns trackingState
                every { stateForProfile(any()) } returns trackingState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            profileManager = profileManager
        )

        activeProfileId.value = 2
        profileSwitched.emit(2)
        remoteLoaded.value = true

        awaitCondition {
            persistedProfileIds.zip(persistedSnapshots).any { (profileId, snapshot) ->
                profileId == 2 && snapshot.resumeItems.any { it.contentId == "tt-profile-two" }
            }
        }

        val profileTwoWrites = persistedProfileIds.zip(persistedSnapshots)
            .filter { (profileId, snapshot) ->
                profileId == 2 && snapshot.resumeItems.any { it.contentId == "tt-profile-two" }
            }
            .map { (_, snapshot) -> snapshot }
        assertFalse(
            "Profile 2 first live publish must not retain contaminated cached profile 1 rows",
            profileTwoWrites.any { snapshot ->
                snapshot.resumeItems.any { it.contentId == "tt-profile-one" } ||
                    snapshot.records.any { it.contentId == "tt-profile-one" }
            }
        )
    }

    @Test
    fun `continue watching ownership rail is profile scoped and canonicalized`() = runTest {
        val activeProfileId = MutableStateFlow(7)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
        val capturedMembership = slot<RailMembership>()
        val ownershipService = mockk<IntegrationOwnershipService>()
        coEvery { ownershipService.upsertRailMembership(capture(capturedMembership)) } returns Unit
        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns MutableStateFlow(listOf(sampleProgress("series:tt0944947")))
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns MutableStateFlow(true)
                every { observeRemoteSnapshotLoaded(any()) } returns MutableStateFlow(true)
                every { observeAllProgress(any()) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                val trackingState = MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
                every { state } returns trackingState
                every { stateForProfile(any()) } returns trackingState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = mockk(relaxed = true),
            profileManager = profileManager,
            ownershipService = ownershipService
        )

        awaitCondition { capturedMembership.isCaptured }

        val membership = capturedMembership.captured
        assertEquals("profile:7:home:continue_watching", membership.rail.railKey)
        assertEquals("series:imdb:tt0944947", membership.items.single().mediaKey)
        assertFalse(membership.rail.railKey.startsWith("profile:7:home:catalog:"))
    }

    @Test
    fun `continue watching syncs rail ownership before persisting snapshot`() = runTest {
        val activeProfileId = MutableStateFlow(7)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
        val callOrder = mutableListOf<String>()
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
            every { write(any(), profileId = any()) } answers {
                callOrder += "snapshot_write"
            }
        }
        val ownershipService = mockk<IntegrationOwnershipService>(relaxed = true) {
            coEvery { upsertRailMembership(any()) } answers {
                callOrder += "rail_sync"
            }
        }

        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { observeProgress(any()) } returns MutableStateFlow(listOf(sampleProgress("series:tt0944947")))
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns MutableStateFlow(true)
                every { observeRemoteSnapshotLoaded(any()) } returns MutableStateFlow(true)
                every { observeAllProgress(any()) } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                val trackingState = MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
                every { state } returns trackingState
                every { stateForProfile(any()) } returns trackingState
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            profileManager = profileManager,
            ownershipService = ownershipService
        )

        awaitCondition {
            callOrder.size >= 2
        }

        assertEquals(listOf("rail_sync", "snapshot_write"), callOrder.takeLast(2))
    }

    private fun sampleProgress(id: String, lastWatched: Long = 1L): WatchProgress {
        return WatchProgress(
            contentId = id,
            contentType = if (id.startsWith("series:")) "series" else "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            lastWatched = lastWatched,
            progressPercent = 10f
        )
    }

    private fun awaitCondition(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        org.junit.Assert.assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }
}
