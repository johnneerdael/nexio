package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationHydrationPlannerTest {
    @Test
    fun `hydration planner refreshes active stale rails before inactive stale rails`() = runTest {
        val fixture = plannerFixture()
        fixture.seedRail("home:tmdb:popular:movies", active = true, stale = true)
        fixture.seedRail("home:mdblist:top:horror", active = false, stale = true)

        val next = fixture.planner.planNextBatch(limit = 2)

        assertEquals("home:tmdb:popular:movies", next.first().railKey)
    }
}

private data class PlannerFixture(
    val activeRailTracker: ActiveRailTracker,
    val planner: IntegrationHydrationPlanner,
    val railStoreDao: com.nexio.tv.data.local.integration.RailStoreDao
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

private fun plannerFixture(): PlannerFixture {
    val db = inMemoryIntegrationCacheDatabase()
    val tracker = ActiveRailTracker()
    val planner = IntegrationHydrationPlanner(db.railStoreDao(), tracker).also {
        it.nowMsProvider = { 10_000L }
    }
    return PlannerFixture(
        activeRailTracker = tracker,
        planner = planner,
        railStoreDao = db.railStoreDao()
    )
}
