package com.nexio.tv.data.remote.dto.debrid

import com.squareup.moshi.Json

data class RealDebridDeviceCodeResponseDto(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "direct_verification_url") val directVerificationUrl: String? = null
)

data class RealDebridDeviceCredentialsResponseDto(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

data class RealDebridTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "refresh_token") val refreshToken: String
)

data class RealDebridUserDto(
    @Json(name = "username") val username: String? = null
)

data class RealDebridDownloadDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filesize") val fileSize: Long? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "chunks") val chunks: Int? = null,
    @Json(name = "download") val download: String? = null,
    @Json(name = "generated") val generated: String? = null,
    @Json(name = "type") val type: String? = null
)

data class RealDebridTorrentDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "links") val links: List<String>? = null,
    @Json(name = "added") val added: String? = null,
    @Json(name = "ended") val ended: String? = null
)

data class RealDebridMagnetDto(
    @Json(name = "id") val id: String,
    @Json(name = "uri") val uri: String? = null
)

data class RealDebridTorrentInfoDto(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "original_filename") val originalFilename: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "bytes") val bytes: Long? = null,
    @Json(name = "original_bytes") val originalBytes: Long? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "split") val split: Int? = null,
    @Json(name = "progress") val progress: Int? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "added") val added: String? = null,
    @Json(name = "files") val files: List<RealDebridTorrentFileDto> = emptyList(),
    @Json(name = "links") val links: List<String> = emptyList(),
    @Json(name = "ended") val ended: String? = null,
    @Json(name = "speed") val speed: Int? = null,
    @Json(name = "seeders") val seeders: Int? = null
)

data class RealDebridTorrentFileDto(
    @Json(name = "id") val id: Int,
    @Json(name = "path") val path: String? = null,
    @Json(name = "bytes") val bytes: Long? = null,
    @Json(name = "selected") val selected: Int? = null
)

data class RealDebridInstantAvailabilityFileDto(
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "filesize") val filesize: Long? = null
)

data class RealDebridUnrestrictLinkDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filesize") val fileSize: Long? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "chunks") val chunks: Int? = null,
    @Json(name = "crc") val crc: Int? = null,
    @Json(name = "download") val download: String? = null,
    @Json(name = "streamable") val streamable: Int? = null
)

data class RealDebridMediaInfoDto(
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "hoster") val hoster: String? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "season") val season: String? = null,
    @Json(name = "episode") val episode: String? = null,
    @Json(name = "year") val year: String? = null,
    @Json(name = "duration") val duration: Double? = null,
    @Json(name = "bitrate") val bitrate: Long? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "details") val details: RealDebridMediaInfoDetailsDto? = null
)

data class RealDebridMediaInfoDetailsDto(
    @Json(name = "video") val video: Map<String, RealDebridMediaTrackDto>? = null,
    @Json(name = "audio") val audio: Map<String, RealDebridMediaTrackDto>? = null
)

data class RealDebridMediaTrackDto(
    @Json(name = "stream") val stream: String? = null,
    @Json(name = "lang") val lang: String? = null,
    @Json(name = "lang_iso") val langIso: String? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "colorspace") val colorspace: String? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "height") val height: Int? = null
)
