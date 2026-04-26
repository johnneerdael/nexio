package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailStoreDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationOrphanCleanupService @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val cacheStore: IntegrationCacheStore
) {
    suspend fun cleanupIfOrphaned(mediaKey: String): Boolean {
        val remainingOwners = railStoreDao.railsForMedia(mediaKey)
        if (remainingOwners.isNotEmpty()) return false
        return cacheStore.deleteOwnedMedia(mediaKey) > 0
    }

    suspend fun cleanupAll(mediaKeys: Collection<String>): List<String> {
        val deleted = mutableListOf<String>()
        mediaKeys.distinct().forEach { mediaKey ->
            if (cleanupIfOrphaned(mediaKey)) {
                deleted += mediaKey
            }
        }
        return deleted
    }
}
