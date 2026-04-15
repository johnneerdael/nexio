package com.nexio.tv.core.tvdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class TvdbAirAvailabilityCalculatorTest {
    private val calculator = TvdbAirAvailabilityCalculator()

    @Test
    fun `parseAirsTime accepts tvdb formats`() {
        assertEquals(LocalTime.of(20, 0), calculator.parseAirsTime("20:00"))
        assertEquals(LocalTime.of(20, 0), calculator.parseAirsTime("8:00 PM"))
        assertEquals(LocalTime.of(20, 0), calculator.parseAirsTime("8pm"))
    }

    @Test
    fun `computeAvailability converts eastern source instant to device timezone`() {
        val result = calculator.computeAvailability(
            episodeAiredDate = "2026-04-14",
            seriesTiming = TvdbSeriesTiming(
                airsTime = "8pm",
                originalCountry = "US"
            ),
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertEquals(Instant.parse("2026-04-15T00:00:00Z").toEpochMilli(), result.instantMs)
        assertEquals(TvdbAirAvailabilityPrecision.EXACT_INSTANT, result.precision)
        assertEquals("America/New_York", result.sourceZoneId)
        assertEquals("2026-04-15T02:00+02:00[Europe/Amsterdam]", result.deviceLocalDateTime)
        assertNull(result.diagnosticReason)
    }

    @Test
    fun `computeAvailability uses amazon prime gmt default without airsTime`() {
        val result = calculator.computeAvailability(
            episodeAiredDate = "2026-04-14",
            seriesTiming = TvdbSeriesTiming(
                airsTime = null,
                originalCountry = null,
                platformName = "Amazon Prime Video"
            ),
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertEquals(Instant.parse("2026-04-14T00:00:00Z").toEpochMilli(), result.instantMs)
        assertEquals(TvdbAirAvailabilityPrecision.EXACT_INSTANT, result.precision)
        assertEquals("UTC", result.sourceZoneId)
        assertEquals("amazon_prime_video_00_00_gmt", result.sourcePolicy)
        assertNull(result.diagnosticReason)
    }

    @Test
    fun `computeAvailability falls back to date only for invalid airsTime`() {
        val result = calculator.computeAvailability(
            episodeAiredDate = "2026-04-14",
            seriesTiming = TvdbSeriesTiming(
                airsTime = "not tonight",
                originalCountry = "US"
            ),
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertNull(result.instantMs)
        assertEquals(TvdbAirAvailabilityPrecision.DATE_ONLY, result.precision)
        assertEquals(TvdbAirAvailabilityDiagnosticReason.INVALID_TIME, result.diagnosticReason)
        assertEquals("invalid_time", result.diagnosticReason?.code)
    }

    @Test
    fun `computeAvailability falls back to date only when timezone policy is missing`() {
        val result = calculator.computeAvailability(
            episodeAiredDate = "2026-04-14",
            seriesTiming = TvdbSeriesTiming(
                airsTime = "8pm",
                originalCountry = null
            ),
            deviceZoneId = ZoneId.of("Europe/Amsterdam")
        )

        assertNull(result.instantMs)
        assertEquals(TvdbAirAvailabilityPrecision.DATE_ONLY, result.precision)
        assertEquals(TvdbAirAvailabilityDiagnosticReason.MISSING_TIMEZONE_POLICY, result.diagnosticReason)
        assertEquals("missing_timezone_policy", result.diagnosticReason?.code)
    }
}
