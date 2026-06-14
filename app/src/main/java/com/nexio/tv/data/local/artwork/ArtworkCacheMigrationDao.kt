package com.nexio.tv.data.local.artwork

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArtworkCacheMigrationDao {
    @Query("SELECT * FROM artwork_cache_migrations WHERE `key` = :key")
    suspend fun get(key: String): ArtworkCacheMigrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markComplete(entity: ArtworkCacheMigrationEntity)
}
