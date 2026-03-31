package com.nexio.tv.data.remote.dto.debrid

import com.squareup.moshi.Json

data class TorBoxEnvelopeDto<T>(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "detail") val detail: String? = null,
    @Json(name = "data") val data: T? = null
)

data class TorBoxUserDto(
    @Json(name = "email") val email: String? = null,
    @Json(name = "plan") val plan: String? = null
)

data class TorBoxCheckCachedRequestDto(
    @Json(name = "hashes") val hashes: List<String>
)

data class TorBoxCachedTorrentDto(
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "files") val files: List<TorBoxFileDto> = emptyList()
)

data class TorBoxFileDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "mimetype") val mimeType: String? = null
)

data class TorBoxCreateTorrentDto(
    @Json(name = "torrent_id") val torrentId: Int? = null,
    @Json(name = "torrentId") val legacyTorrentId: Int? = null,
    @Json(name = "id") val id: Int? = null
) {
    fun resolvedTorrentId(): Int? = torrentId ?: legacyTorrentId ?: id
}

data class TorBoxTorrentListItemDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "download_finished") val downloadFinished: Boolean? = null,
    @Json(name = "downloadFinished") val legacyDownloadFinished: Boolean? = null,
    @Json(name = "download_present") val downloadPresent: Boolean? = null,
    @Json(name = "downloadPresent") val legacyDownloadPresent: Boolean? = null,
    @Json(name = "download_state") val downloadState: String? = null,
    @Json(name = "downloadState") val legacyDownloadState: String? = null,
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "active") val active: Boolean? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "createdAt") val legacyCreatedAt: String? = null,
    @Json(name = "files") val files: List<TorBoxFileDto> = emptyList()
) {
    fun isDownloaded(): Boolean = (downloadFinished ?: legacyDownloadFinished) == true &&
        (downloadPresent ?: legacyDownloadPresent) == true

    fun resolvedState(): String? = downloadState ?: legacyDownloadState
}
