package com.nuvio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabasePlugin(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseAddon(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SyncCodeResult(
    val code: String
)

@Serializable
data class ClaimSyncResult(
    @SerialName("result_owner_id") val ownerId: String? = null,
    val success: Boolean,
    val message: String
)

@Serializable
data class SupabaseLinkedDevice(
    val id: String? = null,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_user_id") val deviceUserId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null
)

@Serializable
data class SupabaseWatchProgress(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long,
    val duration: Long,
    @SerialName("last_watched") val lastWatched: Long,
    @SerialName("progress_key") val progressKey: String
)
