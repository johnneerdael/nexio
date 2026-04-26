package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "integration_rail_item",
    indices = [
        Index("railKey"),
        Index("mediaKey")
    ]
)
data class RailItemEntity(
    @PrimaryKey val key: String,
    val railKey: String,
    val mediaKey: String,
    val position: Int,
    val updatedAtEpochMs: Long
)
