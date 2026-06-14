package com.nexio.tv.data.local.artwork

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artwork_asset_records",
    indices = [
        Index("decisionKey"),
        Index(value = ["decisionKey", "fetchedAtMs"]),
        Index("imageType"),
        Index("providerKey"),
        Index("expiresAtMs"),
        Index("staleUntilMs")
    ]
)
data class ArtworkAssetRecordEntity(
    @PrimaryKey val assetKey: String,
    val decisionKey: String?,
    val providerKey: String?,
    val imageType: String,
    val imageLanguage: String,
    val relativePath: String,
    val mimeType: String?,
    val byteCount: Long,
    val sourceHash: String,
    val policyVersion: Int,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long
)
