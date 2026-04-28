package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "integration_provider_backoff")
data class IntegrationProviderBackoffEntity(
    @PrimaryKey val key: String,
    val provider: String,
    val scopeKey: String,
    val blockedUntilEpochMs: Long,
    val statusCode: Int?,
    val reason: String?,
    val updatedAtEpochMs: Long,
    val consecutiveFailures: Int = 0
)
