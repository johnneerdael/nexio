package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class RailStoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRail(entity: RailCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRailItems(items: List<RailItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPreview(entity: RailItemPreviewEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPreviews(entities: List<RailItemPreviewEntity>)

    @Query("DELETE FROM integration_rail_item WHERE railKey = :railKey")
    protected abstract suspend fun deleteItemsForRailInternal(railKey: String)

    @Query("DELETE FROM integration_rail_cache WHERE railKey = :railKey")
    abstract suspend fun deleteRail(railKey: String)

    @Query("DELETE FROM integration_rail_item_preview WHERE itemKey = :itemKey")
    abstract suspend fun deletePreview(itemKey: String)

    @Query("DELETE FROM integration_rail_item_preview WHERE expiresAtEpochMs <= :nowMs")
    abstract suspend fun deleteExpiredPreviews(nowMs: Long): Int

    @Query("SELECT * FROM integration_rail_cache WHERE railKey = :railKey")
    abstract suspend fun rail(railKey: String): RailCacheEntity?

    @Query("SELECT * FROM integration_rail_item WHERE railKey = :railKey ORDER BY position ASC")
    abstract suspend fun itemsForRail(railKey: String): List<RailItemEntity>

    @Query("SELECT * FROM integration_rail_item_preview WHERE itemKey = :itemKey")
    abstract suspend fun preview(itemKey: String): RailItemPreviewEntity?

    @Query("SELECT * FROM integration_rail_item_preview WHERE itemKey IN (:itemKeys)")
    abstract suspend fun previews(itemKeys: List<String>): List<RailItemPreviewEntity>

    @Query("SELECT railKey FROM integration_rail_item WHERE mediaKey = :mediaKey ORDER BY railKey ASC")
    abstract suspend fun railsForMedia(mediaKey: String): List<String>

    @Query("SELECT * FROM integration_rail_cache WHERE railKey LIKE :namespacePrefix || '%' ORDER BY railKey ASC")
    abstract suspend fun railsWithPrefix(namespacePrefix: String): List<RailCacheEntity>

    @Query("SELECT * FROM integration_rail_cache WHERE expiresAtEpochMs <= :nowMs ORDER BY expiresAtEpochMs ASC LIMIT :limit")
    abstract suspend fun staleRails(nowMs: Long, limit: Int): List<RailCacheEntity>

    @Transaction
    open suspend fun replaceRailItems(railKey: String, items: List<RailItemEntity>) {
        deleteItemsForRailInternal(railKey)
        if (items.isNotEmpty()) {
            insertRailItems(items)
        }
    }

    @Transaction
    open suspend fun deleteRailWithMembership(railKey: String) {
        deleteItemsForRailInternal(railKey)
        deleteRail(railKey)
    }
}
