package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class V13AccountSnapshotEnvelope(
    @SerialName("contract_version") val contractVersion: Int,
    val settings: V13AccountSettingsSections,
    val addons: V10AccountAddonsSection,
    val secrets: V10AccountSecretsSection
)

@Serializable
data class V13AccountSettingsSections(
    val sections: List<V13AccountSettingsSectionRow>,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V13AccountSettingsSectionRow(
    @SerialName("section_key") val sectionKey: String,
    val payload: JsonElement,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("sync_revision") val syncRevision: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long
)

@Serializable
data class V13SectionPushResult(
    val applied: Boolean,
    @SerialName("section_key") val sectionKey: String,
    val reason: String? = null,
    @SerialName("sync_revision") val syncRevision: Long? = null,
    @SerialName("current_updated_at_ms") val currentUpdatedAtMs: Long? = null
)

@Serializable
data class V13BatchPushResult(
    val applied: Boolean,
    val sections: List<V13SectionPushResult> = emptyList()
)
