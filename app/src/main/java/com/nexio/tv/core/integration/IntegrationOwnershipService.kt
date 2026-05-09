package com.nexio.tv.core.integration

import androidx.room.withTransaction
import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.data.local.integration.RailStoreDao
import javax.inject.Inject
import javax.inject.Singleton

data class RailMembership(
    val rail: RailCacheEntity,
    val items: List<RailItemEntity>,
    val mediaIdentities: List<MediaIdentityEntity> = emptyList(),
    val externalIds: List<ExternalIdEntity> = emptyList()
)

@Singleton
class IntegrationOwnershipService @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val mediaIdentityDao: MediaIdentityDao,
    private val orphanCleanupService: IntegrationOrphanCleanupService,
    private val db: IntegrationCacheDatabase
) {
    // F2-G-03: wrapped in an outer withTransaction to eliminate the partial-write window where
    // a CW rail upsert could leave rail rows committed but item/identity rows not yet written.
    suspend fun upsertRailMembership(membership: RailMembership) {
        val removedMediaKeys = db.withTransaction {
            val previousItems = railStoreDao.itemsForRail(membership.rail.railKey)
            railStoreDao.upsertRail(membership.rail)
            railStoreDao.replaceRailItems(membership.rail.railKey, membership.items)
            // Indexed iteration to avoid ArrayList$Itr capture in continuation. The
            // suspending upsertMediaIdentity call would save the iterator into the
            // continuation's L$N field, pinning the source List<MediaIdentity> for
            // the lifetime of the (possibly cancelled) coroutine.
            val mediaIdentities = membership.mediaIdentities
            for (i in mediaIdentities.indices) {
                mediaIdentityDao.upsertMediaIdentity(mediaIdentities[i])
            }
            val externalIdsByMedia = membership.externalIds.groupBy { it.mediaKey }
            // Same: replaceExternalIds is suspending; iterate Map entries by snapshotted
            // key list to avoid retaining the iterator in continuation state.
            val externalIdKeys = externalIdsByMedia.keys.toList()
            for (i in externalIdKeys.indices) {
                val mediaKey = externalIdKeys[i]
                mediaIdentityDao.replaceExternalIds(mediaKey, externalIdsByMedia.getValue(mediaKey))
            }
            previousItems.map { it.mediaKey }.toSet() -
                membership.items.map { it.mediaKey }.toSet()
        }
        orphanCleanupService.cleanupAll(removedMediaKeys)
    }

    suspend fun removeRail(railKey: String) {
        val items = railStoreDao.itemsForRail(railKey)
        railStoreDao.replaceRailItems(railKey, emptyList())
        railStoreDao.deleteRail(railKey)
        orphanCleanupService.cleanupAll(items.map { it.mediaKey })
    }

    suspend fun syncRails(namespacePrefix: String, memberships: List<RailMembership>) {
        val desiredKeys = memberships.map { it.rail.railKey }.toSet()
        // Snapshot the dao result and iterate by index. forEach's lambda would capture
        // the iterator into our continuation across the suspending removeRail call,
        // pinning the source List for the duration of the coroutine.
        val existingRails = railStoreDao.railsWithPrefix(namespacePrefix).toList()
        for (i in existingRails.indices) {
            val existing = existingRails[i]
            if (existing.railKey !in desiredKeys) {
                removeRail(existing.railKey)
            }
        }
        for (i in memberships.indices) {
            upsertRailMembership(memberships[i])
        }
    }

    suspend fun cleanupOrphansAfterRailRefresh(railKey: String) {
        val items = railStoreDao.itemsForRail(railKey)
        orphanCleanupService.cleanupAll(items.map { it.mediaKey })
    }
}
