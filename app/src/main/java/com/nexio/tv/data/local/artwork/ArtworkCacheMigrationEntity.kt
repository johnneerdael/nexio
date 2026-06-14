package com.nexio.tv.data.local.artwork

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artwork_cache_migrations")
data class ArtworkCacheMigrationEntity(
    @PrimaryKey val key: String,
    val completedAtMs: Long,
    val importedCount: Int,
    val skippedCount: Int
)
