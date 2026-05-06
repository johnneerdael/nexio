package com.nexio.tv.core.artwork

import com.nexio.tv.core.image.toLegacyArtworkCoilModelOrNull

fun ArtworkDisplayRef?.toCoilModelOrNull(): Any? =
    when (this) {
        null -> null
        is ArtworkDisplayRef.RuntimeAsset,
        is ArtworkDisplayRef.Placeholder -> toLegacyArtworkString()
        is ArtworkDisplayRef.LegacyString -> value.toLegacyArtworkCoilModelOrNull(
            ownerKey = "legacy-string:${imageType.name.lowercase()}",
            imageType = imageType
        )
    }

fun String?.toSafeArtworkCoilModelOrNull(): Any? {
    val model = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return model.takeIf { it.isSafeArtworkUriString() }
}

private fun String.isSafeArtworkUriString(): Boolean =
    startsWith("nexio-artwork://") ||
        startsWith("nexio-placeholder://") ||
        startsWith("content://") ||
        startsWith("file://")
