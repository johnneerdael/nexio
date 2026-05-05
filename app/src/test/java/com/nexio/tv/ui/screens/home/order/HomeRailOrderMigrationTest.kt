package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailOrderMigrationTest {
    private val keys = { strings: List<String> -> strings.map(::HomeRailKey) }

    @Test
    fun `branch 1 — already migrated is no-op`() {
        val current = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("A")),
            lastMutationSource = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            version = 5,
        )
        val migrated = migrateHomeRailOrderState(
            current = current,
            legacyOrder = keys(listOf("X")),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("Y")),
            nowMs = 1000L,
        )
        assertEquals(current, migrated)
    }

    @Test
    fun `branch 2 — legacy order seeds when state empty and legacy non-empty`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = keys(listOf("A", "B", "C")),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("Y")),
            nowMs = 1000L,
        )
        assertEquals(keys(listOf("A", "B", "C")), migrated.orderedKeys)
        assertEquals(RailOrderMutationSource.MIGRATION, migrated.lastMutationSource)
    }

    @Test
    fun `branch 3 — live default order seeds when no legacy and live available`() {
        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("trakt:popular"), RailFamily.TRAKT, RailSource.PROVIDER_PUBLIC,
                "t", true, DefaultSortKey(0, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("simkl:trending"), RailFamily.SIMKL, RailSource.PROVIDER_PUBLIC,
                "s", true, DefaultSortKey(1, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = emptyList(),
            liveDefinitions = live,
            persistedSyntheticOrder = keys(listOf("simkl:trending", "trakt:popular")),
            nowMs = 1000L,
        )
        assertEquals(
            keys(listOf("trakt:popular", "simkl:trending")),
            migrated.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION, migrated.lastMutationSource)
    }

    @Test
    fun `branch 4 — synthetic fallback when no legacy and no live definitions`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("simkl:trending", "tmdb:popular")),
            nowMs = 1000L,
        )
        assertEquals(
            keys(listOf("simkl:trending", "tmdb:popular")),
            migrated.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK, migrated.lastMutationSource)
    }

    @Test
    fun `disabledKeys carry over independent of orderedKeys branch`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = keys(listOf("D")),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = emptyList(),
            nowMs = 1000L,
        )
        assertEquals(setOf(HomeRailKey("D")), migrated.disabledKeys)
    }

    @Test
    fun `synthetic-fallback overwrite consumes liveDefinitions and bumps source`() {
        val initial = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("simkl:trending", "tmdb:popular")),
            lastMutationSource = RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK,
        )
        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("trakt:popular"), RailFamily.TRAKT, RailSource.PROVIDER_PUBLIC,
                "t", true, DefaultSortKey(0, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("simkl:trending"), RailFamily.SIMKL, RailSource.PROVIDER_PUBLIC,
                "s", true, DefaultSortKey(1, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("tmdb:popular"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC,
                "t2", true, DefaultSortKey(3, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )
        val finalized = finalizeSyntheticFallback(
            current = initial,
            liveDefinitions = live,
            nowMs = 2000L,
        )
        assertEquals(
            keys(listOf("trakt:popular", "simkl:trending", "tmdb:popular")),
            finalized.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION, finalized.lastMutationSource)
    }

    @Test
    fun `synthetic-fallback overwrite is skipped when user has mutated state`() {
        val initial = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("X")),
            lastMutationSource = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
        )
        val finalized = finalizeSyntheticFallback(
            current = initial,
            liveDefinitions = emptyList(),
            nowMs = 2000L,
        )
        assertEquals(initial, finalized)
    }
}
