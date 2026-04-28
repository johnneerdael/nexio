package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailItemPreview
import java.security.MessageDigest

interface RailPreviewMapper<I> {
    fun map(
        railId: String,
        item: I,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview?
}

fun railPreviewItemKey(itemType: ContentType, stableIds: ProviderIds, sourceItemId: String): String =
    stableIds.bestStableItemKey(itemType, sourceItemId)

fun stablePayloadHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

fun tmdbImageUrl(path: String?, size: String = "w500"): String? {
    val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalizedSize = size.trim().takeIf { it.isNotEmpty() } ?: "w500"
    if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) return normalizedPath
    return "https://image.tmdb.org/t/p/$normalizedSize/${normalizedPath.trimStart('/')}"
}

fun simklImageUrl(fragment: String?): String? {
    val normalizedFragment = fragment?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalizedFragment.startsWith("http://") || normalizedFragment.startsWith("https://")) return normalizedFragment
    return "https://simkl.in/posters/${normalizedFragment.trimStart('/')}"
}

fun yearFromDate(value: String?): Int? {
    val trimmed = value?.trim()?.takeIf { it.length >= 4 } ?: return null
    return trimmed.take(4).toIntOrNull()
}

fun firstNonBlank(vararg values: String?): String? =
    values.firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }
