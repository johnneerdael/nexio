package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "integration_media_identity")
data class MediaIdentityEntity(
    @PrimaryKey val mediaKey: String,
    val mediaType: String,
    val title: String?,
    val year: Int?,
    val updatedAtEpochMs: Long
)
