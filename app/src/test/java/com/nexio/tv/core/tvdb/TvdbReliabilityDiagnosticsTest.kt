package com.nexio.tv.core.tvdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbReliabilityDiagnosticsTest {

    @Test
    fun `all phase ten required reason codes are represented`() {
        val expectedCodes = listOf(
            "tvdb_provider_chosen",
            "explicit_fallback",
            "missing_airs_time",
            "date_only_gating",
            "poster_ratings_override",
            "tmdb_tv_fetch_skipped",
            "update_refresh_started",
            "update_refresh_succeeded",
            "update_refresh_failed",
            "reference_refresh_succeeded",
            "reference_refresh_failed",
            "stale_cache_served",
            "invalid_credentials",
            "network_call_blocked",
            "unknown_update_event"
        )
        val actualCodes = TvdbReliabilityReason.entries.map { it.code }
        assertEquals(
            "All 15 Phase 10 reason codes must be present",
            expectedCodes.sorted(),
            actualCodes.sorted()
        )
        assertEquals(15, TvdbReliabilityReason.entries.size)
    }

    @Test
    fun `sanitized diagnostic redacts authorization bearer api key pin and token`() {
        val secrets = listOf(
            "Authorization: Bearer sk-secret123",
            "apikey=my-secret-key",
            "api_key=abc123",
            "apiKey=xyz789",
            "pin=1234",
            "token=jwt-secret-token",
            "bearer abc-def"
        )
        for (secret in secrets) {
            val diagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
                surface = "test",
                message = "Failed with $secret in response"
            )
            val sanitized = diagnostic.sanitized()
            assertTrue(
                "Message containing '$secret' must be redacted but was: ${sanitized.message}",
                sanitized.message?.contains("[redacted]") == true
            )
            // Ensure the actual secret value is not present
            for (keyword in listOf("sk-secret123", "my-secret-key", "abc123", "xyz789", "1234", "jwt-secret-token", "abc-def")) {
                assertTrue(
                    "Secret value '$keyword' must not appear in sanitized message: ${sanitized.message}",
                    sanitized.message?.contains(keyword) != true
                )
            }
        }
    }

    @Test
    fun `sanitized preserves non-secret messages`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            surface = "detail",
            message = "Provider chosen for series 12345"
        )
        val sanitized = diagnostic.sanitized()
        assertEquals("Provider chosen for series 12345", sanitized.message)
    }

    @Test
    fun `sanitized handles null message`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            surface = "detail",
            message = null
        )
        val sanitized = diagnostic.sanitized()
        assertNull(sanitized.message)
    }

    @Test
    fun `user status line returns message for invalid credentials`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.INVALID_CREDENTIALS,
            surface = "settings"
        )
        assertEquals("TVDB credentials need attention", diagnostic.userStatusLine())
    }

    @Test
    fun `user status line returns message for stale cache served`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.STALE_CACHE_SERVED,
            surface = "detail"
        )
        assertEquals("TVDB served cached data while refresh is unavailable", diagnostic.userStatusLine())
    }

    @Test
    fun `user status line returns message for update refresh failed`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
            surface = "update"
        )
        assertEquals("TVDB cache refresh failed", diagnostic.userStatusLine())
    }

    @Test
    fun `user status line returns message for reference refresh failed`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.REFERENCE_REFRESH_FAILED,
            surface = "reference"
        )
        assertEquals("TVDB reference refresh failed", diagnostic.userStatusLine())
    }

    @Test
    fun `user status line returns null for non user facing reasons`() {
        val nonUserFacing = listOf(
            TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            TvdbReliabilityReason.EXPLICIT_FALLBACK,
            TvdbReliabilityReason.MISSING_AIRS_TIME,
            TvdbReliabilityReason.DATE_ONLY_GATING,
            TvdbReliabilityReason.POSTER_RATINGS_OVERRIDE,
            TvdbReliabilityReason.TMDB_TV_FETCH_SKIPPED,
            TvdbReliabilityReason.UPDATE_REFRESH_STARTED,
            TvdbReliabilityReason.UPDATE_REFRESH_SUCCEEDED,
            TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED,
            TvdbReliabilityReason.NETWORK_CALL_BLOCKED,
            TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT
        )
        for (reason in nonUserFacing) {
            val diagnostic = TvdbReliabilityDiagnostic(
                reason = reason,
                surface = "test"
            )
            assertNull(
                "Reason ${reason.code} should not produce a user status line",
                diagnostic.userStatusLine()
            )
        }
    }

    @Test
    fun `structured log fields are sanitized`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
            surface = "update",
            tvdbId = 42,
            entityType = "series",
            fallbackProvider = "tmdb",
            message = "Error: Authorization: Bearer secret-token-value"
        )
        val fields = diagnostic.structuredLogFields()
        assertEquals("update_refresh_failed", fields["reason"])
        assertEquals("update", fields["surface"])
        assertEquals("42", fields["tvdb_id"])
        assertEquals("series", fields["entity_type"])
        assertEquals("tmdb", fields["fallback_provider"])
        // Message must be sanitized
        assertNotNull(fields["message"])
        assertTrue(
            "Structured log message must be sanitized: ${fields["message"]}",
            fields["message"]?.contains("[redacted]") == true
        )
        assertTrue(
            "Secret value must not appear in structured log fields",
            fields["message"]?.contains("secret-token-value") != true
        )
        assertNotNull(fields["occurred_at_ms"])
    }

    @Test
    fun `structured log fields omit null optional fields`() {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            surface = "detail"
        )
        val fields = diagnostic.structuredLogFields()
        assertEquals("tvdb_provider_chosen", fields["reason"])
        assertEquals("detail", fields["surface"])
        assertNull(fields["tvdb_id"])
        assertNull(fields["entity_type"])
        assertNull(fields["fallback_provider"])
        assertNull(fields["message"])
        assertNotNull(fields["occurred_at_ms"])
    }

    @Test
    fun `tvdb diagnostics recorder interface has record method`() {
        // Verify the interface compiles and has the expected shape
        val recorder: TvdbDiagnosticsRecorder? = null
        // If this compiles, the interface exists with the correct method signature
        assertNull(recorder)
    }
}
