package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

interface IntegrationHydrationCoordinator {
    suspend fun hydrateNextBatch(limit: Int): List<RailCacheEntity>
}

object NoOpIntegrationHydrationCoordinator : IntegrationHydrationCoordinator {
    override suspend fun hydrateNextBatch(limit: Int): List<RailCacheEntity> = emptyList()
}

@Singleton
class DefaultIntegrationHydrationCoordinator @Inject constructor(
    private val planner: IntegrationHydrationPlanner
) : IntegrationHydrationCoordinator {
    override suspend fun hydrateNextBatch(limit: Int): List<RailCacheEntity> =
        if (limit <= 0) emptyList() else planner.planNextBatch(limit)
}
