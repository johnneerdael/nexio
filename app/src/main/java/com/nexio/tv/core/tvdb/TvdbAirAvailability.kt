package com.nexio.tv.core.tvdb

enum class TvdbAirAvailabilityPrecision { EXACT_INSTANT, DATE_ONLY, UNKNOWN }

enum class TvdbAirAvailabilityDiagnosticReason(val code: String) {
    MISSING_AIRS_TIME("missing_airs_time"),
    INVALID_TIME("invalid_time"),
    MISSING_TIMEZONE_POLICY("missing_timezone_policy"),
    REFRESH_FAILURE("refresh_failure")
}

data class TvdbSeriesTiming(
    val airsTime: String?,
    val originalCountry: String?,
    val originalNetwork: String? = null,
    val latestNetwork: String? = null,
    val platformName: String? = null
)

data class TvdbAirAvailability(
    val instantMs: Long?,
    val precision: TvdbAirAvailabilityPrecision,
    val sourceZoneId: String?,
    val sourcePolicy: String?,
    val deviceLocalDateTime: String?,
    val diagnosticReason: TvdbAirAvailabilityDiagnosticReason?
)
