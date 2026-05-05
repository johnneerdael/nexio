package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRailOrderStoreTest {
    private val gson = Gson()
    private val codec = HomeRailOrderStateCodec(gson)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    private fun stubProfileManager(profileId: Int = 0): ProfileManager =
        mockk<ProfileManager>(relaxed = true).also {
            every { it.activeProfileId } returns MutableStateFlow(profileId)
        }

    @Test
    fun `state defaults to Empty when persisted json is null`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layout.homeRailOrderStateJson } returns flowOf(null)
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        assertEquals(HomeRailOrderState.Empty, store.state.first())
    }

    @Test
    fun `updateOrder persists and bumps version`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers {
            persisted.value = firstArg()
        }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a"), HomeRailKey("b")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a"), HomeRailKey("b")),
        )
        advanceUntilIdle()

        val state = store.state.first()
        assertEquals(listOf(HomeRailKey("a"), HomeRailKey("b")), state.orderedKeys)
        assertEquals(1L, state.version)
        assertEquals(RailOrderMutationSource.ANDROID_ORDER_SCREEN, state.lastMutationSource)
        coVerify { layout.setHomeRailOrderStateJson(any()) }
    }

    @Test
    fun `back-to-back updateOrder calls each see the most recent in-memory state`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        // The flow does NOT update on writes — simulating DataStore I/O latency.
        // Without the in-memory cache, both updateOrder calls read the initial null state.
        coEvery { layout.homeRailOrderStateJson } returns flowOf(null)
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } returns Unit

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a")),
        )
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a"), HomeRailKey("b")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a"), HomeRailKey("b")),
        )
        advanceUntilIdle()

        // Capture all persisted JSON values across both updateOrder invocations.
        // Using mutableListOf (rather than slot, which only stores the last and errors
        // on multi-invocation in MockK 1.13+) so we can decode the LAST persisted JSON
        // and confirm version was bumped to 2 (not 1).
        val captured = mutableListOf<String>()
        coVerify(atLeast = 1) { layout.setHomeRailOrderStateJson(capture(captured)) }
        // Decode the LAST persisted JSON and verify version reflects two mutations.
        val lastPersisted = codec.decode(captured.last())
        assertEquals(2L, lastPersisted.version)
        assertEquals(listOf(HomeRailKey("a"), HomeRailKey("b")), lastPersisted.orderedKeys)
    }

    @Test
    fun `effectiveOrder recomputes when liveDefinitions changes alone`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layout.homeRailOrderStateJson } returns flowOf(
            codec.encode(HomeRailOrderState.Empty.copy(
                orderedKeys = listOf(HomeRailKey("k")),
            ))
        )
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val store = HomeRailOrderStore(layout, codec, fixedClock, testScope, CapturingHomeRailOrderDiagnosticsSink(), stubProfileManager())

        val live = MutableStateFlow(
            listOf(
                HomeRailDefinition(
                    HomeRailKey("k"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC, "k",
                    enabled = true,
                    defaultSortKey = DefaultSortKey(3, 0),
                    publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
                )
            )
        )

        val effective = store.effectiveOrder(live)
        advanceUntilIdle()
        assertEquals(listOf(HomeRailKey("k")), effective.value.visibleKeys)

        // State unchanged; flip the live definition's enabled flag.
        live.value = live.value.map { it.copy(enabled = false) }
        advanceUntilIdle()
        assertEquals(emptyList<HomeRailKey>(), effective.value.visibleKeys)
    }

    @Test
    fun `updateOrder preserves keys currently unknown to liveDefinitions`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(
            codec.encode(HomeRailOrderState.Empty.copy(
                orderedKeys = listOf(
                    HomeRailKey("A"),
                    HomeRailKey("addon:offline:catalog"),
                    HomeRailKey("B"),
                ),
            ))
        )
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("B"), HomeRailKey("A")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("A"), HomeRailKey("B")), // addon is currently unknown
        )
        advanceUntilIdle()

        assertEquals(
            listOf(HomeRailKey("B"), HomeRailKey("A"), HomeRailKey("addon:offline:catalog")),
            store.state.first().orderedKeys,
        )
    }

    @Test
    fun `updateOrder treats omitted-known keys as explicit removal`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(
            codec.encode(HomeRailOrderState.Empty.copy(
                orderedKeys = listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
            ))
        )
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(layout, codec, fixedClock,
            TestScope(StandardTestDispatcher(testScheduler)),
            CapturingHomeRailOrderDiagnosticsSink(),
            stubProfileManager())

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("A"), HomeRailKey("C")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(HomeRailKey("A"), HomeRailKey("C")),
            store.state.first().orderedKeys,
        )
    }

    @Test
    fun `reconcileNow uses lastWrittenState cache when available`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(layout, codec, fixedClock,
            TestScope(StandardTestDispatcher(testScheduler)),
            CapturingHomeRailOrderDiagnosticsSink(),
            stubProfileManager())

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("k1"), HomeRailKey("k2")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("k1"), HomeRailKey("k2")),
        )
        advanceUntilIdle()

        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("k1"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC, "k1",
                enabled = true, defaultSortKey = DefaultSortKey(3, 0),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("k2"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC, "k2",
                enabled = true, defaultSortKey = DefaultSortKey(3, 1),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )

        val result = store.reconcileNow(live)
        assertEquals(listOf(HomeRailKey("k1"), HomeRailKey("k2")), result.visibleKeys)
    }

    @Test
    fun `reconcileNow falls back to live default order when no state and no cache`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layout.homeRailOrderStateJson } returns flowOf(null)
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val store = HomeRailOrderStore(layout, codec, fixedClock,
            TestScope(StandardTestDispatcher(testScheduler)),
            CapturingHomeRailOrderDiagnosticsSink(),
            stubProfileManager())

        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("simkl_x"), RailFamily.SIMKL, RailSource.PROVIDER_PUBLIC, "simkl_x",
                enabled = true, defaultSortKey = DefaultSortKey(1, 0),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("trakt_x"), RailFamily.TRAKT, RailSource.PROVIDER_PUBLIC, "trakt_x",
                enabled = true, defaultSortKey = DefaultSortKey(0, 0),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )

        val result = store.reconcileNow(live)
        // Both keys are "newly discovered" (no saved order), so they sort by family rank.
        // Trakt rank 0 < Simkl rank 1, so trakt first.
        assertEquals(
            listOf(HomeRailKey("trakt_x"), HomeRailKey("simkl_x")),
            result.visibleKeys,
        )
    }

    @Test
    fun `reorderProviderKeys overload uses cached liveDefinitions`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(layout, codec, fixedClock,
            TestScope(StandardTestDispatcher(testScheduler)),
            CapturingHomeRailOrderDiagnosticsSink(),
            stubProfileManager())

        // Seed the cache by calling onLiveDefinitionsArrived with a TMDB definition list.
        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("tmdb_popular"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC,
                "tmdb_popular", enabled = true, defaultSortKey = DefaultSortKey(3, 0),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("tmdb_top"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC,
                "tmdb_top", enabled = true, defaultSortKey = DefaultSortKey(3, 1),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )
        // Seed the order so we can verify a swap.
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("tmdb_popular"), HomeRailKey("tmdb_top")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("tmdb_popular"), HomeRailKey("tmdb_top")),
        )
        advanceUntilIdle()

        // Populate the cached liveDefinitions via reconcileNow.
        store.reconcileNow(live)

        // Now call the no-arg overload — it should swap to [tmdb_top, tmdb_popular].
        store.reorderProviderKeys(
            family = RailFamily.TMDB,
            providerOrder = listOf(HomeRailKey("tmdb_top"), HomeRailKey("tmdb_popular")),
            source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(HomeRailKey("tmdb_top"), HomeRailKey("tmdb_popular")),
            store.state.first().orderedKeys,
        )
    }

    @Test
    fun `updateOrder emits rail_order_mutation diagnostics event`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val sink = CapturingHomeRailOrderDiagnosticsSink()
        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = sink,
            profileManager = stubProfileManager(),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a"), HomeRailKey("b")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a"), HomeRailKey("b")),
        )
        advanceUntilIdle()

        val mutationEvent = sink.events.firstOrNull { it.eventType == "home.rail_order_mutation" }
        assertEquals("home.rail_order_mutation", mutationEvent?.eventType)
        assertEquals(true, mutationEvent?.payload?.contains("ANDROID_ORDER_SCREEN"))
        assertEquals(true, mutationEvent?.payload?.contains("after=[HomeRailKey(value=a), HomeRailKey(value=b)]"))
    }

    @Test
    fun `tryMigrate sees persisted state even before stateIn upstream decodes`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val initialState = HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("X")))
        val persisted = MutableStateFlow<String?>(codec.encode(initialState))
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(listOf("LEGACY"))
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        // Invoke tryMigrate immediately after construction. state.value may still be
        // HomeRailOrderState.Empty because the stateIn upstream has not yet decoded.
        // currentForMutation() awaits homeRailOrderStateJson.first() and sees the
        // real persisted state — so migration is a no-op (branch 1: state non-empty).
        store.tryMigrate(persistedSyntheticOrder = emptyList(), liveDefinitions = emptyList())
        advanceUntilIdle()

        // If the bug were present (read state.value = Empty), legacy ["LEGACY"] would
        // have been seeded over the persisted ["X"]. With the fix, "X" survives.
        val state = store.state.first()
        assertEquals(listOf(HomeRailKey("X")), state.orderedKeys)
    }

    @Test
    fun `tryMigrate seeds from legacy when state json is null`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(listOf("A", "B"))
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(listOf("D"))
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = CapturingHomeRailOrderDiagnosticsSink(),
            profileManager = stubProfileManager(),
        )

        store.tryMigrate(
            persistedSyntheticOrder = emptyList(),
            liveDefinitions = emptyList(),
        )
        advanceUntilIdle()

        val state = store.state.first()
        assertEquals(listOf(HomeRailKey("A"), HomeRailKey("B")), state.orderedKeys)
        assertEquals(setOf(HomeRailKey("D")), state.disabledKeys)
        assertEquals(RailOrderMutationSource.MIGRATION, state.lastMutationSource)
    }

    @Test
    fun `profile switch clears lastWrittenState cache`() = runTest {
        val activeProfileId = MutableStateFlow(0)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns activeProfileId

        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(
            codec.encode(HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("p0"))))
        )
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = testScope,
            profileManager = profileManager,
            diagnostics = mockk(relaxed = true),
        )

        // Trigger a mutation on profile 0 to populate lastWrittenState.
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("p0_changed")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("p0_changed")),
        )
        advanceUntilIdle()

        // Switch to profile 1. Update the persisted-flow to simulate a different profile's state.
        persisted.value = codec.encode(HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("p1"))))
        activeProfileId.value = 1
        advanceUntilIdle()

        // The next mutation on profile 1 should NOT see the profile 0 cache; it should
        // observe the persisted profile 1 state via currentForMutation()'s flow read.
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("p1_changed")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("p1_changed")),
        )
        advanceUntilIdle()

        val finalJson = persisted.value
        val finalState = codec.decode(finalJson)
        // The merge should have started from profile 1's persisted state, NOT profile 0's cached state.
        // Assert that "p0_changed" is NOT in the final orderedKeys.
        assertEquals(false, finalState.orderedKeys.contains(HomeRailKey("p0_changed")))
    }
}
