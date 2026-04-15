package com.nexio.tv.core.tvdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbDiagnosticsTest {

    @Test
    fun `fallback diagnostic stores reason and removes credential material`() {
        val recorder = TvdbDiagnosticsRecorder()

        recorder.recordFallback(
            TvdbFallbackDiagnostic(
                reason = TvdbFallbackReason.INVALID_CREDENTIALS,
                remoteId = "tt0944947",
                sanitizedMessage = "401 for key tvdb-key with pin subscriber-pin token tvdb-token"
            )
        )

        val latest = recorder.latestFallback()

        assertEquals(TvdbFallbackReason.INVALID_CREDENTIALS, latest?.reason)
        assertEquals("tt0944947", latest?.remoteId)
        assertFalse(latest?.sanitizedMessage.orEmpty().contains("tvdb-key"))
        assertFalse(latest?.sanitizedMessage.orEmpty().contains("subscriber-pin"))
        assertFalse(latest?.sanitizedMessage.orEmpty().contains("tvdb-token"))
        assertTrue(latest?.sanitizedMessage.orEmpty().contains("credentials"))
    }
}
