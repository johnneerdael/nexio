package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationBackoffManagerTest {
    @Test
    fun `http 429 persists provider block using retry after`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 12_000L,
            reason = "Retry-After"
        )

        val entry = fixture.backoffDao.get("TMDB", "global")
        assertTrue(entry != null)
        assertTrue((entry?.blockedUntilEpochMs ?: 0L) >= 12_000L)
    }
}
