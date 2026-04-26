package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailStoreDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationHydrationCoordinatorTest {
    @Test
    fun `coordinator returns planned stale rails without generic notifier side effects`() = runTest {
        val fixture = hydrationCoordinatorFixture()
        fixture.seedRail("profile:7:home:catalog:tmdb:popular:movies", active = true, stale = true)
        val coordinator = DefaultIntegrationHydrationCoordinator(fixture.planner)

        val planned = coordinator.hydrateNextBatch(limit = 2)

        assertEquals(listOf("profile:7:home:catalog:tmdb:popular:movies"), planned.map { it.railKey })
    }
}

private data class HydrationCoordinatorFixture(
    val activeRailTracker: ActiveRailTracker,
    val planner: IntegrationHydrationPlanner,
    val railStoreDao: RailStoreDao
) {
    suspend fun seedRail(railKey: String, active: Boolean, stale: Boolean) {
        val now = planner.nowMsProvider()
        railStoreDao.upsertRail(
            RailCacheEntity(
                railKey = railKey,
                provider = "TEST",
                kind = "TEST",
                paramsHash = railKey,
                fetchedAtEpochMs = now - 1_000L,
                expiresAtEpochMs = if (stale) now - 100L else now + 10_000L,
                staleUntilEpochMs = now + 60_000L
            )
        )
        if (active) activeRailTracker.markActive(railKey)
    }
}

private fun hydrationCoordinatorFixture(): HydrationCoordinatorFixture {
    val db = inMemoryIntegrationCacheDatabase()
    val tracker = ActiveRailTracker()
    val planner = IntegrationHydrationPlanner(db.railStoreDao(), tracker).also {
        it.nowMsProvider = { 10_000L }
    }
    return HydrationCoordinatorFixture(
        activeRailTracker = tracker,
        planner = planner,
        railStoreDao = db.railStoreDao()
    )
}
