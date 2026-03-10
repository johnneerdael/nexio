package com.nexio.tv.data.remote.dto.debrid

import com.squareup.moshi.Json

data class PremiumizeAccountInfoDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "customer_id") val customerId: Int? = null,
    @Json(name = "premium_until") val premiumUntil: Long? = null
)

data class PremiumizeListAllDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "files") val files: List<PremiumizeListAllFileDto> = emptyList()
)

data class PremiumizeListAllFileDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "path") val path: String? = null
)

data class PremiumizeItemDetailsDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "folder_id") val folderId: String? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "stream_link") val streamLink: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "resx") val width: String? = null,
    @Json(name = "resy") val height: String? = null,
    @Json(name = "duration") val duration: String? = null,
    @Json(name = "transcode_status") val transcodeStatus: String? = null
)
