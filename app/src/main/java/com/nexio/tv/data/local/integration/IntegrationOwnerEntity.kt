package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "integration_cache_owner")
data class IntegrationOwnerEntity(
    @PrimaryKey val key: String,
    val cacheKey: String,
    val ownerType: String,
    val ownerKey: String
)
