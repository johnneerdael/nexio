package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Guard tests for the account-owned key scoping invariant.
 *
 * `RailKeyFactory` encodes account scope indirectly via `profileId`: every account-owned
 * rail (Trakt user lists, SIMKL personal lists, home catalogs) is keyed under
 * `profile:<profileId>:...`. Different provider accounts on the device are bound to
 * different profiles, so per-profile namespacing is what prevents cross-account
 * collisions.
 *
 * These tests lock in that invariant so a future refactor cannot accidentally drop
 * the profile scope and re-introduce key collisions across accounts/profiles.
 */
class HomeRailKeyScopingTest {

    @Test
    fun `trakt library keys with same listKey on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.traktLibrary(profileId = 1, listKey = "watchlist")
        val keyForProfileB = RailKeyFactory.traktLibrary(profileId = 2, listKey = "watchlist")
        assertNotEquals(
            "Same Trakt list on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `simkl library keys with same listKey on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.simklLibrary(profileId = 1, listKey = "watching")
        val keyForProfileB = RailKeyFactory.simklLibrary(profileId = 2, listKey = "watching")
        assertNotEquals(
            "Same SIMKL list on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `home catalog keys with same catalogId on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.homeCatalog(profileId = 1, catalogId = "tmdb:popular:movies")
        val keyForProfileB = RailKeyFactory.homeCatalog(profileId = 2, catalogId = "tmdb:popular:movies")
        assertNotEquals(
            "Same home catalog on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `trakt and simkl namespaces never overlap on the same profile`() {
        val traktKey = RailKeyFactory.traktLibrary(profileId = 5, listKey = "watchlist")
        val simklKey = RailKeyFactory.simklLibrary(profileId = 5, listKey = "watchlist")
        assertNotEquals(
            "Identical listKey under different providers must not collapse to the same key",
            traktKey,
            simklKey
        )
        assertFalse(
            "Trakt key must not fall under the SIMKL namespace",
            traktKey.startsWith(RailKeyFactory.simklLibraryNamespace(profileId = 5))
        )
        assertFalse(
            "SIMKL key must not fall under the Trakt namespace",
            simklKey.startsWith(RailKeyFactory.traktLibraryNamespace(profileId = 5))
        )
    }

    @Test
    fun `account-owned keys live under the profile-scoped namespace`() {
        val profileId = 9
        val traktKey = RailKeyFactory.traktLibrary(profileId = profileId, listKey = "favorites")
        val simklKey = RailKeyFactory.simklLibrary(profileId = profileId, listKey = "plantowatch")
        val catalogKey = RailKeyFactory.homeCatalog(profileId = profileId, catalogId = "tmdb:popular:tv")
        assertTrue(
            "Trakt account-owned keys must be profile-scoped",
            traktKey.startsWith("profile:$profileId:")
        )
        assertTrue(
            "SIMKL account-owned keys must be profile-scoped",
            simklKey.startsWith("profile:$profileId:")
        )
        assertTrue(
            "Home catalog keys must be profile-scoped",
            catalogKey.startsWith("profile:$profileId:")
        )
    }

    @Test
    fun `same profile and same listKey produces identical keys (sanity check)`() {
        val a = RailKeyFactory.traktLibrary(profileId = 4, listKey = "watchlist")
        val b = RailKeyFactory.traktLibrary(profileId = 4, listKey = "watchlist")
        assertEquals("Equal inputs must produce equal keys", a, b)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `HomeRailOrderState reads are scoped per profile via LayoutPreferenceDataStore`() = runTest {
        // GIVEN two profiles each with distinct persisted HomeRailOrderState backing.
        val profile1Json = MutableStateFlow<String?>(
            HomeRailOrderStateCodec(Gson()).encode(
                HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("profile1_key")))
            )
        )
        val profile2Json = MutableStateFlow<String?>(
            HomeRailOrderStateCodec(Gson()).encode(
                HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("profile2_key")))
            )
        )

        val activeProfileId = MutableStateFlow(1)
        val profileManager = mockk<com.nexio.tv.core.profile.ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns activeProfileId

        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        // Simulate LayoutPreferenceDataStore.profileFlow by routing reads through the
        // active profile id. The MockK 'answers' block re-evaluates per access.
        coEvery { layout.homeRailOrderStateJson } answers {
            when (activeProfileId.value) {
                1 -> profile1Json
                else -> profile2Json
            }
        }
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        // Route persisted writes back to the active profile's MutableStateFlow so the test
        // can observe the merged result. Without this hook, persist() is a no-op against the
        // relaxed mock and the assertions below would observe only the seed state.
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers {
            val json = firstArg<String>()
            when (activeProfileId.value) {
                1 -> profile1Json.value = json
                else -> profile2Json.value = json
            }
        }

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = HomeRailOrderStateCodec(Gson()),
            clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
            scope = testScope,
            profileManager = profileManager,
            diagnostics = mockk(relaxed = true),
        )

        // WHEN the store is observed under profile 1.
        advanceUntilIdle()
        val profile1State = store.state.first()

        // THEN we see profile 1's keys, not profile 2's.
        assertEquals(listOf(HomeRailKey("profile1_key")), profile1State.orderedKeys)

        // WHEN the active profile switches to profile 2.
        activeProfileId.value = 2
        advanceUntilIdle()

        // THEN subsequent mutations observe profile 2's persisted state, never profile 1's
        // (the I3 fix invalidates the cache on profile switch). Drive a mutation that
        // exposes which state was used as the merge base via the unknown-saved-key
        // preservation rule.
        store.tryMigrate(persistedSyntheticOrder = emptyList(), liveDefinitions = emptyList())
        advanceUntilIdle()
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("profile2_added")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("profile2_added")),
        )
        advanceUntilIdle()

        // The unknown-saved-key preservation rule means the merge base's keys (those
        // absent from the live set) get appended. If the cache leaked profile 1's
        // state, "profile1_key" would appear in the persisted result; if profile-scoping
        // worked, "profile2_key" appears instead.
        val captured = profile2Json.value!!
        val mergedState = HomeRailOrderStateCodec(Gson()).decode(captured)
        assertEquals(false, mergedState.orderedKeys.contains(HomeRailKey("profile1_key")))
        assertEquals(true, mergedState.orderedKeys.contains(HomeRailKey("profile2_key")))
        assertEquals(true, mergedState.orderedKeys.contains(HomeRailKey("profile2_added")))
    }

    @Test
    fun `re-authentication with a different account produces distinct rail keys`() {
        // Trakt user-list keys are derived from per-account listKey strings (the API's
        // unique list identifier). Re-authenticating as a different Trakt account
        // surfaces a different listKey set, which produces different rail keys —
        // ensuring the new account's rails do not collide with the previous account's
        // entries in HomeRailOrderState or SyntheticHomeCatalogStore.
        val profileId = 1
        val accountAList = "trakt-account-A-list-42"
        val accountBList = "trakt-account-B-list-42"

        // Same profile, different list keys (one from each account).
        val keyForAccountA = RailKeyFactory.traktLibrary(profileId, accountAList)
        val keyForAccountB = RailKeyFactory.traktLibrary(profileId, accountBList)

        assertNotEquals(keyForAccountA, keyForAccountB)

        // Sanity check: the same profile + same listKey does produce the same key
        // (idempotent), which means re-running the same auth produces stable rails.
        val keyForAccountARepeat = RailKeyFactory.traktLibrary(profileId, accountAList)
        assertEquals(keyForAccountA, keyForAccountARepeat)
    }
}
