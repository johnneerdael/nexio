package com.nexio.tv.notices.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteNoticeManifest(
    val schemaVersion: Int,
    val notices: List<RemoteNoticeManifestItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RemoteNoticeManifestItem(
    val id: String,
    val title: String,
    val publishedAt: String,
    val markdownUrl: String,
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val expiresAt: String? = null
)

data class RemoteNoticeDisplay(
    val id: String,
    val title: String,
    val markdown: String,
    val markdownUrl: String,
    val publishedAt: String
)
