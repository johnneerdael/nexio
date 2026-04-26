package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "integration_rail_cache")
data class RailCacheEntity(
    @PrimaryKey val railKey: String,
    val provider: String,
    val kind: String,
    val paramsHash: String,
    val fetchedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val staleUntilEpochMs: Long
)
