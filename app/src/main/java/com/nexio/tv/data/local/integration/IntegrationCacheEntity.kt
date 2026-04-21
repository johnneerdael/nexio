package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "integration_cache")
data class IntegrationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val provider: String,
    val scopeKey: String,
    val blobPath: String,
    val mimeType: String,
    val expiresAtEpochMs: Long,
    val staleUntilEpochMs: Long,
    val updatedAtEpochMs: Long,
    val ownerToken: String?
)
