package com.nexio.tv.data.local.integration

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "integration_external_id",
    indices = [
        Index("mediaKey"),
        Index(value = ["provider", "externalId", "idType"], unique = true)
    ]
)
data class ExternalIdEntity(
    @PrimaryKey val key: String,
    val mediaKey: String,
    val provider: String,
    val externalId: String,
    val idType: String,
    val mappingSource: String? = null,
    val evidence: String? = null,
    val expiresAtEpochMs: Long? = null
)
