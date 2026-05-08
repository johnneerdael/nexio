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
    val displayHash: String = fields.hydratedHomeDisplayHash(),
    val updatedAtMs: Long,
    val staleAtMs: Long,
    val expiresAtMs: Long,
    val state: HomeItemHydrationState = HomeItemHydrationState.CANONICAL_READY
) {
    init {
        require(displayHash == fields.hydratedHomeDisplayHash()) {
            "displayHash must match fields.hydratedHomeDisplayHash()"
        }
    }

    fun isStale(nowMs: Long): Boolean = nowMs >= staleAtMs

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/**
 * True when two overlays describe the same content. Ignores [HydratedHomeOverlay.updatedAtMs],
 * [HydratedHomeOverlay.staleAtMs], and [HydratedHomeOverlay.expiresAtMs] which are stamped fresh
 * on every rebuild even when nothing else has changed. Use this — not data-class equality — for
 * the apply-seam dedup, the publish gate, and the catalog-signature hash. Otherwise every
 * cache-hit hydration tick mutates the overlay map, fires scheduleUpdateCatalogRows(), and
 * triggers a full Compose recomposition.
 */
fun HydratedHomeOverlay.contentEquals(other: HydratedHomeOverlay): Boolean =
    overlayKey == other.overlayKey &&
        itemKey == other.itemKey &&
        canonicalProvider == other.canonicalProvider &&
        canonicalId == other.canonicalId &&
        imdbId == other.imdbId &&
        contentType == other.contentType &&
        languageTag == other.languageTag &&
        policyVersion == other.policyVersion &&
        fields == other.fields &&
        fieldTrace == other.fieldTrace &&
        displayHash == other.displayHash &&
        state == other.state

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
    val raw = buildString {
        appendLengthPrefixed("title", title.orEmpty())
        appendLengthPrefixed("logo", logo.orEmpty())
        appendLengthPrefixed("description", description.orEmpty())
        appendList("genres", genres)
        appendLengthPrefixed("releaseInfo", releaseInfo.orEmpty())
        appendLengthPrefixed("runtime", runtime.orEmpty())
        appendLengthPrefixed("imdbRating", imdbRating?.toString().orEmpty())
        appendLengthPrefixed("ratingSource", ratingSource?.name.orEmpty())
        appendLengthPrefixed("tomatoesRating", tomatoesRating?.toString().orEmpty())
        appendLengthPrefixed("poster", poster.orEmpty())
        appendLengthPrefixed("posterProviderTag", posterProviderTag.orEmpty())
        appendLengthPrefixed("backdrop", backdrop.orEmpty())
        appendLengthPrefixed("thumbnail", thumbnail.orEmpty())
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))

    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun StringBuilder.appendLengthPrefixed(name: String, value: String) {
    append(name.length)
    append(':')
    append(name)
    append('=')
    append(value.length)
    append(':')
    append(value)
    append(';')
}

private fun StringBuilder.appendList(name: String, values: List<String>) {
    append(name.length)
    append(':')
    append(name)
    append('[')
    append(values.size)
    append(']')
    values.forEach { value ->
        append(value.length)
        append(':')
        append(value)
    }
    append(';')
}
