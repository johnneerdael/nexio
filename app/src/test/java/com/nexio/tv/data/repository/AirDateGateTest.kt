package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDateGateTest {

    // ── isAired ────────────────────────────────────────────────────────────────

    @Test
    fun `airDateFallbackOrder - firstAiredMs positive wins over tmdbAirDate`() {
        val nowMs = 1_000L
        // firstAiredMs says "already aired" — tmdbAirDate says future; firstAiredMs wins
        assertTrue(
            AirDateGate.isAired(
                firstAiredMs = 500L,
                tmdbAirDate = "2099-01-01",
                nowMs = nowMs
            )
        )
        // firstAiredMs says "not yet aired" — tmdbAirDate says past; firstAiredMs wins
        assertFalse(
            AirDateGate.isAired(
                firstAiredMs = 2_000L,
                tmdbAirDate = "1970-01-01",
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `airDateFallbackOrder - falls back to tmdbAirDate when firstAiredMs is zero`() {
        val nowMs = 1_735_689_600_000L // 2025-01-01 midnight UTC
        // tmdbAirDate in the past → aired
        assertTrue(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = "2024-06-15",
                nowMs = nowMs
            )
        )
        // tmdbAirDate in the future → not aired
        assertFalse(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = "2026-06-15",
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `date-only fallback does not publish same-day TVDB episodes before the day has fully elapsed`() {
        val amsterdamMorningMay13 = 1_778_639_340_000L // 2026-05-13T02:29:00Z / 04:29 Amsterdam

        assertFalse(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = "2026-05-13",
                nowMs = amsterdamMorningMay13
            )
        )
    }

    @Test
    fun `timestamp fallback preserves exact instant instead of truncating to date`() {
        val oneMinuteBeforeAirtime = 1_778_716_740_000L // 2026-05-13T23:59:00Z

        assertFalse(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = "2026-05-14T00:00:00Z",
                nowMs = oneMinuteBeforeAirtime
            )
        )
    }

    @Test
    fun `airDateFallbackOrder - returns true when both firstAiredMs and tmdbAirDate are unknown`() {
        assertTrue(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = null,
                nowMs = 1_000L
            )
        )
        assertTrue(
            AirDateGate.isAired(
                firstAiredMs = 0L,
                tmdbAirDate = "",
                nowMs = 1_000L
            )
        )
        assertTrue(
            AirDateGate.isAired(
                firstAiredMs = -1L,
                tmdbAirDate = null,
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `isAired - exact boundary firstAiredMs equals nowMs is treated as aired`() {
        val t = 5_000L
        assertTrue(AirDateGate.isAired(firstAiredMs = t, tmdbAirDate = null, nowMs = t))
    }

    @Test
    fun `isAired exact tvdb availability wins over provider firstAiredMs`() {
        val exactTvdbAvailabilityMs = 1_776_211_200_000L // 2026-04-15T00:00:00Z
        val futureProviderFirstAiredMs = exactTvdbAvailabilityMs + 86_400_000L

        assertTrue(
            AirDateGate.isAired(
                availabilityInstantMs = exactTvdbAvailabilityMs,
                firstAiredMs = futureProviderFirstAiredMs,
                tmdbAirDate = "2026-04-16",
                nowMs = exactTvdbAvailabilityMs
            )
        )
    }

    // ── soonestPendingMs ───────────────────────────────────────────────────────

    @Test
    fun `unairedEntryIsScheduled - returns smallest future air date ms among unaired entries`() {
        val nowMs = 1_000L
        val entries = listOf(
            entry(firstAiredMs = 500L),   // aired
            entry(firstAiredMs = 2_000L), // unaired — soonest
            entry(firstAiredMs = 3_000L)  // unaired
        )

        val result = AirDateGate.soonestPendingMs(
            entries = entries,
            firstAiredMsSelector = { it.firstAiredMs },
            nowMs = nowMs
        )

        assertEquals(2_000L, result)
    }

    @Test
    fun `unairedEntryIsScheduled - returns null when all entries are aired`() {
        val nowMs = 10_000L
        val entries = listOf(
            entry(firstAiredMs = 1_000L),
            entry(firstAiredMs = 5_000L)
        )

        val result = AirDateGate.soonestPendingMs(
            entries = entries,
            firstAiredMsSelector = { it.firstAiredMs },
            nowMs = nowMs
        )

        assertNull(result)
    }

    @Test
    fun `unairedEntryIsScheduled - returns null for empty list`() {
        assertNull(
            AirDateGate.soonestPendingMs(
                entries = emptyList<TestEntry>(),
                firstAiredMsSelector = { it.firstAiredMs },
                nowMs = 1_000L
            )
        )
    }

    // ── timer stability (T1) logic ─────────────────────────────────────────────
    // The timer churn-prevention is validated in ContinueWatchingTimelineAirDateTest.
    // Here we verify that soonestPendingMs is stable across identical inputs.

    @Test
    fun `soonestPendingMs is stable across repeated calls with same inputs`() {
        val nowMs = 1_000L
        val entries = listOf(entry(firstAiredMs = 5_000L), entry(firstAiredMs = 9_000L))

        val first = AirDateGate.soonestPendingMs(entries, { it.firstAiredMs }, nowMs = nowMs)
        val second = AirDateGate.soonestPendingMs(entries, { it.firstAiredMs }, nowMs = nowMs)

        assertEquals(first, second)
        assertEquals(5_000L, first)
    }

    @Test
    fun `soonestPendingMs falls back to tmdbAirDate when firstAiredMs is zero`() {
        val nowMs = 1_000_000L
        val result = AirDateGate.soonestPendingMs(
            entries = listOf(TestEntry(0L)),
            firstAiredMsSelector = { it.firstAiredMs },
            tmdbAirDateSelector = { "2099-12-31" },
            nowMs = nowMs
        )

        assertTrue("tmdbAirDate fallback must produce a future epoch value", result != null && result > nowMs)
    }

    @Test
    fun `soonestPendingMs prefers parsed tmdbAirDate when firstAiredMs is zero and no firstAired date exists`() {
        val nowMs = 1_000_000L
        val futureEntry = TestEntry(0L)
        val pastEntry = TestEntry(0L)

        val result = AirDateGate.soonestPendingMs(
            entries = listOf(futureEntry, pastEntry),
            firstAiredMsSelector = { it.firstAiredMs },
            tmdbAirDateSelector = { entry ->
                if (entry === futureEntry) "2099-12-31" else "1970-01-01"
            },
            nowMs = nowMs
        )

        assertTrue("Result must be non-null for at least one future tmdbAirDate", result != null)
        assertTrue("Result should reflect the future date", result!! > nowMs)
    }

    @Test
    fun `soonestPendingMs prefers exact tvdb availability over provider firstAiredMs`() {
        val nowMs = 1_000L
        val exactEntry = TestEntry(firstAiredMs = 5_000L, availabilityInstantMs = 2_000L)
        val providerOnlyEntry = TestEntry(firstAiredMs = 3_000L)

        val result = AirDateGate.soonestPendingMs(
            entries = listOf(exactEntry, providerOnlyEntry),
            firstAiredMsSelector = { it.firstAiredMs },
            availabilityInstantMsSelector = { it.availabilityInstantMs },
            nowMs = nowMs
        )

        assertEquals(2_000L, result)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private data class TestEntry(
        val firstAiredMs: Long,
        val availabilityInstantMs: Long? = null
    )

    private fun entry(firstAiredMs: Long) = TestEntry(firstAiredMs)
}
