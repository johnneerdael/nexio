package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "integration_rail_item_preview",
    primaryKeys = ["railId", "itemKey"],
    indices = [
        Index("railId"),
        Index(value = ["sourceProvider", "sourceItemId"]),
        Index("sourceItemId"),
        Index("hydrationState"),
        Index("expiresAtEpochMs")
    ]
)
data class RailItemPreviewEntity(
    val itemKey: String,
    val railId: String,
    val railSource: String,
    val sourceProvider: String,
    val sourceItemId: String,
    val itemType: String,
    val stableIdsJson: String,
    val displaySeedJson: String,
    val rankingJson: String?,
    val sourcePayloadQuality: String,
    val sourcePayloadHash: String,
    val hydrationState: String,
    val fetchedAtEpochMs: Long,
    val expiresAtEpochMs: Long
)
