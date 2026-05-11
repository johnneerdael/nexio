package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Uniform v10 push-result envelope returned by every `sync_*_v10` push RPC.
 *
 *   { "applied": true,  "current_updated_at_ms": 1747... }
 *   { "applied": false, "reason": "stale_base",     "current_updated_at_ms": 1747... }
 *   { "applied": false, "reason": "field_conflict", "current_updated_at_ms": 1747...,
 *     "conflict_paths": [...], "sync_revision": ... }
 */
@Serializable
data class V10PushResult(
    val applied: Boolean,
    @SerialName("current_updated_at_ms") val currentUpdatedAtMs: Long,
    val reason: String? = null,
    @SerialName("conflict_paths") val conflictPaths: List<String> = emptyList(),
    @SerialName("sync_revision") val syncRevision: Long? = null
)

@Serializable
data class V10AccountSnapshotEnvelope(
    @SerialName("contract_version") val contractVersion: Int,
    val settings: V10AccountSettingsSection,
    val addons: V10AccountAddonsSection,
    val secrets: V10AccountSecretsSection
)

@Serializable
data class V10AccountSettingsSection(
    val payload: JsonElement,
    @SerialName("sync_revision") val syncRevision: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10AccountAddonsSection(
    val items: List<AccountAddonPayload>,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10AccountSecretsSection(
    val items: List<V10AccountSecretRow>,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10AccountSecretRow(
    @SerialName("secret_type") val secretType: String,
    @SerialName("secret_ref") val secretRef: String,
    @SerialName("masked_preview") val maskedPreview: String? = null,
    val status: String,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10ProfileSettingsEnvelope(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("settings_json") val settingsJson: JsonElement,
    @SerialName("sync_revision") val syncRevision: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10ProfileAuthTokensEnvelope(
    @SerialName("contract_version") val contractVersion: Int,
    val items: List<V10ProfileAuthTokenRow>,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V10ProfileAuthTokenRow(
    @SerialName("token_type") val tokenType: String,
    @SerialName("token_payload") val tokenPayload: JsonElement,
    @SerialName("masked_preview") val maskedPreview: String? = null,
    val linked: Boolean,
    @SerialName("revoked_at_ms") val revokedAtMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)
