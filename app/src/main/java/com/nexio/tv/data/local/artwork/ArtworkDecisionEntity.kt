package com.nexio.tv.data.local.artwork

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artwork_decisions",
    indices = [
        Index("imageType"),
        Index("settingsHash"),
        Index("credentialHash"),
        Index("policyVersion"),
        Index("expiresAtMs"),
        Index("staleUntilMs")
    ]
)
data class ArtworkDecisionEntity(
    @PrimaryKey val decisionKey: String,
    val ownerType: String,
    val ownerContentId: String?,
    val ownerItemKey: String?,
    val ownerSourcePayloadHash: String?,
    val canonicalContentId: String?,
    val imageType: String,
    val selectedProviderKey: String?,
    val selectedSourceRole: String,
    val settingsHash: String?,
    val credentialHash: String?,
    val policyVersion: Int,
    val imageLanguage: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long?,
    val payloadJson: String
)

@Entity(
    tableName = "artwork_preview_links",
    indices = [Index("canonicalKey")]
)
data class ArtworkPreviewLinkEntity(
    @PrimaryKey val previewKey: String,
    val canonicalKey: String
)
