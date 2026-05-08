package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest

object ContinueWatchingItemKeys {
    fun parentKey(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        fallbackRawId: String
    ): String {
        val kind = mediaKind.keySegment()
        val canonicalProvider = identity.canonicalProvider
        val canonicalId = identity.canonicalId?.trim()?.takeIf { it.isNotEmpty() }
        if (canonicalProvider != null && canonicalProvider.isGloballyStable() && canonicalId != null) {
            return "$kind:${canonicalProvider.keySegment()}:$canonicalId"
        }

        identity.providerIds.bestKnownProviderKey()?.let { providerKey ->
            return "$kind:$providerKey"
        }

        require(fallbackRawId.isNotBlank()) {
            "fallbackRawId must not be blank when canonical and provider IDs are unavailable"
        }
        return "$kind:raw:${fallbackRawId.stableRawHash()}"
    }

    fun episodeKey(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        season: Int,
        episode: Int,
        fallbackRawId: String
    ): String {
        require(season > 0) { "season must be positive" }
        require(episode > 0) { "episode must be positive" }
        return "${parentKey(mediaKind, identity, fallbackRawId)}:s${season}e${episode}"
    }

    fun legacyParentKey(contentType: String, contentId: String): String {
        val type = contentType.trim().lowercase().takeIf { it.isNotEmpty() } ?: "unknown"
        return "$type:${contentId.trim()}"
    }
}

internal fun MetadataMediaKind.keySegment(): String = name.lowercase()

private fun ProviderId.keySegment(): String = name.lowercase()

private fun ProviderId.isGloballyStable(): Boolean =
    this == ProviderId.TVDB ||
        this == ProviderId.TMDB ||
        this == ProviderId.KITSU ||
        this == ProviderId.IMDB ||
        this == ProviderId.TRAKT ||
        this == ProviderId.SIMKL

private fun ProviderIds.bestKnownProviderKey(): String? =
    listOf(
        "tvdb" to tvdb,
        "tmdb" to tmdb,
        "kitsu" to kitsu,
        "imdb" to imdb,
        "trakt" to trakt,
        "simkl" to simkl,
        "mal" to mal,
        "anilist" to anilist,
        "anidb" to anidb
    ).firstNotNullOfOrNull { (provider, id) ->
        id?.trim()?.takeIf { it.isNotEmpty() }?.let { "$provider:$it" }
    }

private fun String.stableRawHash(): String {
    val raw = trim()
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(16)
}
