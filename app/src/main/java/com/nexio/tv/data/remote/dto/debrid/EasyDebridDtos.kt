package com.nexio.tv.data.remote.dto.debrid

import com.squareup.moshi.Json

data class EasyDebridUserDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "paid_until") val paidUntil: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "message") val message: String? = null
)

data class EasyDebridLookupRequestDto(
    @Json(name = "urls") val urls: List<String>
)

data class EasyDebridLookupDto(
    @Json(name = "cached") val cached: List<Boolean> = emptyList()
)

data class EasyDebridLookupDetailsDto(
    @Json(name = "result") val result: List<EasyDebridLookupDetailsResultDto> = emptyList()
)

data class EasyDebridLookupDetailsResultDto(
    @Json(name = "cached") val cached: Boolean = false,
    @Json(name = "files") val files: List<EasyDebridLookupFileDto> = emptyList()
)

data class EasyDebridLookupFileDto(
    @Json(name = "size") val size: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "folder") val folder: String? = null
)

data class EasyDebridGenerateRequestDto(
    @Json(name = "url") val url: String
)

data class EasyDebridGenerateDto(
    @Json(name = "files") val files: List<EasyDebridGeneratedFileDto> = emptyList()
)

data class EasyDebridGeneratedFileDto(
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "directory") val directory: List<String> = emptyList(),
    @Json(name = "size") val size: Long? = null,
    @Json(name = "url") val url: String? = null
)
