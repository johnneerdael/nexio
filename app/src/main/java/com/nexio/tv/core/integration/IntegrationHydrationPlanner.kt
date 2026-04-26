package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailStoreDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationHydrationPlanner @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val activeRailTracker: ActiveRailTracker
) {
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun planNextBatch(limit: Int): List<RailCacheEntity> =
        railStoreDao.staleRails(nowMs = nowMsProvider(), limit = 200)
            .sortedWith(
                compareByDescending<RailCacheEntity> { activeRailTracker.isActive(it.railKey) }
                    .thenBy { it.expiresAtEpochMs }
            )
            .take(limit)
}
