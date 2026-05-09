package com.nexio.tv.core.image

import coil.key.Keyer
import coil.request.Options
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.util.toHexLowercase
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class LegacyRemoteArtworkModel(
    val key: String,
    val imageType: ArtworkType,
    val url: SensitiveArtworkUrl
) {
    init {
        require(key.isNotBlank()) { "LegacyRemoteArtworkModel key must not be blank" }
    }

    override fun toString(): String = key
}

fun String?.toLegacyArtworkCoilModelOrNull(
    ownerKey: String,
    imageType: ArtworkType
): Any? {
    val model = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (model.isSafeArtworkUriString()) return model
    if (!model.isValidRemoteArtworkUrl()) return null

    val key = "legacy-artwork:${imageType.name.lowercase(Locale.ROOT)}:${ownerKey.trim()}:${model.cacheIdentity().sha256()}"
    return LegacyRemoteArtworkModel(
        key = key,
        imageType = imageType,
        url = SensitiveArtworkUrl.of(model)
    )
}

private fun String.isSafeArtworkUriString(): Boolean =
    startsWith("nexio-artwork://") ||
        startsWith("nexio-placeholder://") ||
        startsWith("content://") ||
        startsWith("file://")

private fun String.isValidRemoteArtworkUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return false
    if (host == "api.ratingposterdb.com" || host == "api.top-posters.com") return false
    return true
}

private fun String.cacheIdentity(): String {
    val uri = URI(this)
    return URI(
        uri.scheme,
        uri.authority,
        uri.path,
        null,
        null
    ).toString()
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.toHexLowercase()
}

class LegacyRemoteArtworkKeyer : Keyer<LegacyRemoteArtworkModel> {
    override fun key(data: LegacyRemoteArtworkModel, options: Options): String =
        data.key
}
