package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IntegrationCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCacheEntry(entity: IntegrationCacheEntity)

    @Query("SELECT * FROM integration_cache WHERE cacheKey = :cacheKey")
    suspend fun getCacheEntry(cacheKey: String): IntegrationCacheEntity?
}
