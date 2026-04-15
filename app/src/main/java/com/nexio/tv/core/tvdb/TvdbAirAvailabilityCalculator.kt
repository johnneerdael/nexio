package com.nexio.tv.core.tvdb

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class TvdbAirAvailabilityCalculator @Inject constructor() {
    fun parseAirsTime(raw: String?): LocalTime? {
        val normalized = raw
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace(".", "")
            ?.replace(WHITESPACE, " ")
            ?.uppercase(Locale.US)
            ?: return null

        return AIR_TIME_FORMATTERS.firstNotNullOfOrNull { formatter ->
            runCatching { LocalTime.parse(normalized, formatter) }.getOrNull()
        }
    }

    fun computeAvailability(
        episodeAiredDate: String?,
        seriesTiming: TvdbSeriesTiming,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): TvdbAirAvailability {
        val date = episodeAiredDate
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
            ?: return unknownAvailability()

        val rawAirsTime = seriesTiming.airsTime?.trim()?.takeIf { it.isNotBlank() }
        val sourcePolicy = resolveSourcePolicy(seriesTiming)
        val time = when {
            rawAirsTime != null -> parseAirsTime(rawAirsTime) ?: return dateOnlyAvailability(
                sourcePolicy = sourcePolicy,
                diagnosticReason = TvdbAirAvailabilityDiagnosticReason.INVALID_TIME
            )
            sourcePolicy?.defaultTime != null -> sourcePolicy.defaultTime
            else -> return dateOnlyAvailability(
                sourcePolicy = sourcePolicy,
                diagnosticReason = TvdbAirAvailabilityDiagnosticReason.MISSING_AIRS_TIME
            )
        }
        val resolvedPolicy = sourcePolicy ?: return dateOnlyAvailability(
            sourcePolicy = null,
            diagnosticReason = TvdbAirAvailabilityDiagnosticReason.MISSING_TIMEZONE_POLICY
        )

        val instant = ZonedDateTime.of(date, time, resolvedPolicy.zoneId).toInstant()
        return TvdbAirAvailability(
            instantMs = instant.toEpochMilli(),
            precision = TvdbAirAvailabilityPrecision.EXACT_INSTANT,
            sourceZoneId = resolvedPolicy.zoneId.id,
            sourcePolicy = resolvedPolicy.name,
            deviceLocalDateTime = instant.atZone(deviceZoneId).toString(),
            diagnosticReason = null
        )
    }

    private fun resolveSourcePolicy(seriesTiming: TvdbSeriesTiming): TvdbAirSourcePolicy? {
        val platform = seriesTiming.platformName?.normalizeKey()
        when (platform) {
            "amazon prime video" -> return TvdbAirSourcePolicy(
                zoneId = UTC_ZONE,
                defaultTime = LocalTime.MIDNIGHT,
                name = "amazon_prime_video_00_00_gmt"
            )
            in EASTERN_03_00_PLATFORMS -> return TvdbAirSourcePolicy(
                zoneId = EASTERN_ZONE,
                defaultTime = LocalTime.of(3, 0),
                name = "streaming_03_00_eastern"
            )
            "hulu", "apple tv+" -> return TvdbAirSourcePolicy(
                zoneId = EASTERN_ZONE,
                defaultTime = LocalTime.MIDNIGHT,
                name = "streaming_00_00_eastern"
            )
        }

        val countryKey = seriesTiming.originalCountry?.normalizeCountryKey()
        if (countryKey in US_COUNTRY_KEYS) {
            return TvdbAirSourcePolicy(
                zoneId = EASTERN_ZONE,
                defaultTime = null,
                name = "us_network_eastern"
            )
        }

        val zoneId = COUNTRY_ZONE_IDS[countryKey] ?: return null
        return TvdbAirSourcePolicy(
            zoneId = ZoneId.of(zoneId),
            defaultTime = null,
            name = "country_${countryKey!!.lowercase(Locale.US)}"
        )
    }

    private fun unknownAvailability(): TvdbAirAvailability {
        return TvdbAirAvailability(
            instantMs = null,
            precision = TvdbAirAvailabilityPrecision.UNKNOWN,
            sourceZoneId = null,
            sourcePolicy = null,
            deviceLocalDateTime = null,
            diagnosticReason = null
        )
    }

    private fun dateOnlyAvailability(
        sourcePolicy: TvdbAirSourcePolicy?,
        diagnosticReason: TvdbAirAvailabilityDiagnosticReason
    ): TvdbAirAvailability {
        return TvdbAirAvailability(
            instantMs = null,
            precision = TvdbAirAvailabilityPrecision.DATE_ONLY,
            sourceZoneId = sourcePolicy?.zoneId?.id,
            sourcePolicy = sourcePolicy?.name,
            deviceLocalDateTime = null,
            diagnosticReason = diagnosticReason
        )
    }

    private data class TvdbAirSourcePolicy(
        val zoneId: ZoneId,
        val defaultTime: LocalTime?,
        val name: String
    )

    private fun String.normalizeKey(): String {
        return trim()
            .replace(".", "")
            .replace(WHITESPACE, " ")
            .lowercase(Locale.US)
    }

    private fun String.normalizeCountryKey(): String {
        val normalized = trim().replace(".", "").replace(WHITESPACE, " ")
        val upper = normalized.uppercase(Locale.US)
        return COUNTRY_ALIASES[upper] ?: upper
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val AIR_TIME_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("H:mm", Locale.US),
            DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("ha", Locale.US),
            DateTimeFormatter.ofPattern("h a", Locale.US)
        )
        val EASTERN_ZONE: ZoneId = ZoneId.of("America/New_York")
        val UTC_ZONE: ZoneId = ZoneId.of("UTC")
        val EASTERN_03_00_PLATFORMS = setOf(
            "netflix",
            "disney+",
            "hbo max",
            "paramount+",
            "amc+",
            "allblk",
            "bet+",
            "peacock"
        )
        val US_COUNTRY_KEYS = setOf("US", "USA", "UNITED STATES")
        val COUNTRY_ALIASES = mapOf(
            "UNITED STATES" to "US",
            "UNITED KINGDOM" to "GB",
            "GREAT BRITAIN" to "GB",
            "CANADA" to "CA",
            "AUSTRALIA" to "AU",
            "JAPAN" to "JP",
            "SOUTH KOREA" to "KR",
            "KOREA" to "KR",
            "FRANCE" to "FR",
            "GERMANY" to "DE",
            "SPAIN" to "ES",
            "ITALY" to "IT",
            "NETHERLANDS" to "NL",
            "SWEDEN" to "SE",
            "NORWAY" to "NO",
            "DENMARK" to "DK",
            "FINLAND" to "FI",
            "BRAZIL" to "BR",
            "MEXICO" to "MX",
            "INDIA" to "IN"
        )
        val COUNTRY_ZONE_IDS = mapOf(
            "GB" to "Europe/London",
            "CA" to "America/Toronto",
            "AU" to "Australia/Sydney",
            "JP" to "Asia/Tokyo",
            "KR" to "Asia/Seoul",
            "FR" to "Europe/Paris",
            "DE" to "Europe/Berlin",
            "ES" to "Europe/Madrid",
            "IT" to "Europe/Rome",
            "NL" to "Europe/Amsterdam",
            "SE" to "Europe/Stockholm",
            "NO" to "Europe/Oslo",
            "DK" to "Europe/Copenhagen",
            "FI" to "Europe/Helsinki",
            "BR" to "America/Sao_Paulo",
            "MX" to "America/Mexico_City",
            "IN" to "Asia/Kolkata"
        )
    }
}
