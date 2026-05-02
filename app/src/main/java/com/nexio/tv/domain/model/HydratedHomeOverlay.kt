package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.security.MessageDigest

private const val DEFAULT_HOME_OVERLAY_POLICY_VERSION = 1

enum class HomeItemHydrationState {
    PREVIEW_ONLY,
    IDENTITY_RESOLVING,
    HYDRATION_QUEUED,
    HYDRATING,
    CANONICAL_READY,
    FAILED_USING_PREVIEW,
    STALE_READY
}

@Immutable
data class HydratedHomeFieldTrace(
    val field: String,
    val selectedProvider: String,
    val sourceRole: String,
    val rejectedCandidates: List<String> = emptyList()
)

@Immutable
data class HydratedHomeOverlay(
    val overlayKey: String,
    val itemKey: String,
    val canonicalProvider: ProviderId,
    val canonicalId: String,
    val imdbId: String?,
    val contentType: ContentType,
    val languageTag: String,
    val policyVersion: Int = DEFAULT_HOME_OVERLAY_POLICY_VERSION,
    val fields: HomeDisplayMetadata,
    val fieldTrace: List<HydratedHomeFieldTrace>,
    val displayHash: String,
    val updatedAtMs: Long,
    val staleAtMs: Long,
    val expiresAtMs: Long,
    val state: HomeItemHydrationState = HomeItemHydrationState.CANONICAL_READY
) {
    fun isStale(nowMs: Long): Boolean = nowMs >= staleAtMs

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

fun hydratedHomeOverlayKey(
    canonicalProvider: ProviderId,
    canonicalId: String,
    contentType: ContentType,
    languageTag: String,
    policyVersion: Int = DEFAULT_HOME_OVERLAY_POLICY_VERSION
): String {
    return "canonical:${canonicalProvider.name}:${canonicalId.trim()}:type:${contentType.name}:lang:${languageTag.trim()}:policy:$policyVersion"
}

fun HomeDisplayMetadata.hydratedHomeDisplayHash(): String {
    val raw = listOf(
        title.orEmpty(),
        logo.orEmpty(),
        description.orEmpty(),
        genres.joinToString("|"),
        releaseInfo.orEmpty(),
        runtime.orEmpty(),
        imdbRating?.toString().orEmpty(),
        ratingSource?.name.orEmpty(),
        tomatoesRating?.toString().orEmpty(),
        poster.orEmpty(),
        posterProviderTag.orEmpty(),
        backdrop.orEmpty()
    ).joinToString(separator = "\u001F")
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))

    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
