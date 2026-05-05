package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProvenanceFile(
    @Json(name = "generatedAt") val generatedAt: String,
    @Json(name = "sources") val sources: Map<String, ProvenanceSource>,
    @Json(name = "overlay") val overlay: ProvenanceOverlay,
    @Json(name = "counts") val counts: AssetCounts
)

@JsonClass(generateAdapter = true)
data class ProvenanceSource(
    @Json(name = "url") val url: String,
    @Json(name = "commit") val commit: String?,
    @Json(name = "fetchedAt") val fetchedAt: String
)

@JsonClass(generateAdapter = true)
data class ProvenanceOverlay(
    @Json(name = "version") val version: Int,
    @Json(name = "entryCount") val entryCount: Int
)
