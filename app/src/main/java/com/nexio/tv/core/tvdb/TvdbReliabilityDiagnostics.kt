package com.nexio.tv.core.tvdb

/**
 * Typed reason codes for TVDB reliability diagnostics (D-10, D-11).
 *
 * Every provider choice, fallback, update/reference status, stale cache,
 * invalid credential, date-only gating, poster override, and skipped TMDB TV
 * fetch is represented as a typed reason so user-facing status, Debug detail,
 * and structured logs share one vocabulary.
 */
enum class TvdbReliabilityReason(val code: String) {
    TVDB_PROVIDER_CHOSEN("tvdb_provider_chosen"),
    EXPLICIT_FALLBACK("explicit_fallback"),
    MISSING_AIRS_TIME("missing_airs_time"),
    DATE_ONLY_GATING("date_only_gating"),
    POSTER_RATINGS_OVERRIDE("poster_ratings_override"),
    TMDB_TV_FETCH_SKIPPED("tmdb_tv_fetch_skipped"),
    UPDATE_REFRESH_STARTED("update_refresh_started"),
    UPDATE_REFRESH_SUCCEEDED("update_refresh_succeeded"),
    UPDATE_REFRESH_FAILED("update_refresh_failed"),
    REFERENCE_REFRESH_SUCCEEDED("reference_refresh_succeeded"),
    REFERENCE_REFRESH_FAILED("reference_refresh_failed"),
    STALE_CACHE_SERVED("stale_cache_served"),
    INVALID_CREDENTIALS("invalid_credentials"),
    NETWORK_CALL_BLOCKED("network_call_blocked"),
    UNKNOWN_UPDATE_EVENT("unknown_update_event")
}

/**
 * A single TVDB reliability diagnostic event.
 *
 * Producers create instances with raw messages; consumers must call [sanitized]
 * before persisting or logging to strip secret-looking values.
 */
data class TvdbReliabilityDiagnostic(
    val reason: TvdbReliabilityReason,
    val surface: String,
    val tvdbId: Int? = null,
    val entityType: String? = null,
    val fallbackProvider: String? = null,
    val message: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis()
)

/**
 * Patterns that match secret-looking key=value or key: value content.
 * Keys matched (case-insensitive): authorization, bearer, apikey, api_key, apiKey, pin, token.
 *
 * The first pattern handles `key[:=] value` (and `Authorization: Bearer TOKEN` as one match).
 * The second pattern handles standalone `bearer TOKEN` without a separator.
 */
private val SECRET_KEY_VALUE_PATTERN = Regex(
    """(?i)(authorization|apikey|api_key|pin|token)\s*[:=]\s*\S+(\s+\S+)?"""
)
private val SECRET_BEARER_PATTERN = Regex(
    """(?i)bearer\s+\S+"""
)

/**
 * Returns a copy with secret-looking values in [message] replaced by `[redacted]`.
 * Matches case-insensitively on authorization, bearer, apikey, api_key, apiKey, pin, and token.
 */
fun TvdbReliabilityDiagnostic.sanitized(): TvdbReliabilityDiagnostic {
    if (message == null) return this
    var redacted = SECRET_KEY_VALUE_PATTERN.replace(message) { match ->
        val key = match.groupValues[1]
        "$key=[redacted]"
    }
    redacted = SECRET_BEARER_PATTERN.replace(redacted) { "bearer=[redacted]" }
    return copy(message = redacted)
}

/**
 * Returns a user-facing status line for reasons that should be shown in TVDB settings.
 * Returns null for reasons that are only relevant to Debug detail or structured logs.
 */
fun TvdbReliabilityDiagnostic.userStatusLine(): String? = when (reason) {
    TvdbReliabilityReason.INVALID_CREDENTIALS -> "TVDB credentials need attention"
    TvdbReliabilityReason.STALE_CACHE_SERVED -> "TVDB served cached data while refresh is unavailable"
    TvdbReliabilityReason.UPDATE_REFRESH_FAILED -> "TVDB cache refresh failed"
    TvdbReliabilityReason.REFERENCE_REFRESH_FAILED -> "TVDB reference refresh failed"
    else -> null
}

/**
 * Shared recorder contract for TVDB reliability diagnostics (D-10).
 *
 * Later update, reference, fallback, and credential producers must depend on
 * this interface and emit structured logs through the sanitized contract rather
 * than freeform strings.
 */
interface TvdbDiagnosticsRecorder {
    suspend fun record(diagnostic: TvdbReliabilityDiagnostic)
}

/**
 * Returns a map of non-secret structured log fields from the sanitized diagnostic.
 *
 * Calls [sanitized] first, then includes only reason, surface, and non-null
 * optional fields. Does not include request URLs, headers, API keys, PINs,
 * bearer tokens, or Authorization values.
 */
fun TvdbReliabilityDiagnostic.structuredLogFields(): Map<String, String> {
    val safe = sanitized()
    return buildMap {
        put("reason", safe.reason.code)
        put("surface", safe.surface)
        safe.tvdbId?.let { put("tvdb_id", it.toString()) }
        safe.entityType?.let { put("entity_type", it) }
        safe.fallbackProvider?.let { put("fallback_provider", it) }
        safe.message?.let { put("message", it) }
        put("occurred_at_ms", safe.occurredAtMs.toString())
    }
}
