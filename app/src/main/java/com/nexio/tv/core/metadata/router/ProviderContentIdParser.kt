package com.nexio.tv.core.metadata.router

/**
 * Extracts the provider-native id from a possibly-typed contentId string.
 *
 * Supported shapes (provider must match the [provider] argument, case-insensitive):
 * - Typed:    `tmdb:tv:1399`     → "1399"
 * - Typed:    `tmdb:movie:550`   → "550"
 * - Untyped:  `tmdb:1399`        → "1399"
 *
 * Returns null when:
 * - [contentId] is blank,
 * - the leading scheme segment does not match [provider],
 * - the contentId contains only the provider prefix with no payload,
 * - the typed-scope payload is blank (e.g., `tmdb:tv:`),
 * - any indexed segment used to derive the value is blank (e.g., `tmdb::1399`).
 *
 * The returned substring is preserved as-found; callers that need numeric or canonical
 * shape constraints (digits-only, `tt\d+`, ...) must validate after parsing.
 */
internal fun providerNativeIdFromContentId(contentId: String, provider: String): String? {
    val parts = contentId.trim().split(':')
    if (parts.isEmpty()) return null
    if (parts[0].isBlank()) return null
    if (!parts[0].equals(provider, ignoreCase = true)) return null
    if (parts.size < 2) return null
    val isTypedScope = parts[1].equals("tv", ignoreCase = true) ||
        parts[1].equals("movie", ignoreCase = true)
    return when {
        isTypedScope -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        parts[1].isNotBlank() -> parts[1]
        else -> null
    }
}
