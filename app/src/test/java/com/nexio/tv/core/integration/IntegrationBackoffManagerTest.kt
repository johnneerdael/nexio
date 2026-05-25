package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.nexio.tv.data.integration.mdblist.MDBListRateLimitGuard

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

    @Test
    fun `noteHttpFailure with positive jitterMs computes a value within expected range without throwing`() = runTest {
        val dao = InMemoryIntegrationProviderBackoffDao()
        val manager = IntegrationBackoffManager(
            dao = dao,
            baseMs = 1_000L,
            capMs = 30_000L,
            jitterMs = 250L
        )

        manager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.GlobalContent,
            statusCode = 503,
            retryAfterMs = null,
            reason = "test"
        )

        val now = System.currentTimeMillis()
        val stored = dao.get("TMDB", IntegrationScope.GlobalContent.storageKey)
            ?: error("expected one backoff entry to be persisted")
        val blockMs = stored.blockedUntilEpochMs - now
        val expectedMin = 1_000L - 250L  // baseMs - jitterMs
        val expectedMax = 1_000L + 250L  // baseMs + jitterMs
        assertTrue(
            "blockMs $blockMs should be within [$expectedMin, $expectedMax] (±100ms drift tolerance)",
            blockMs in (expectedMin - 100L)..(expectedMax + 100L)
        )
    }

    @Test
    fun `mdblist daily limit response records at least twenty four hour provider backoff`() = runTest {
        val dao = InMemoryIntegrationProviderBackoffDao()
        val guard = MDBListRateLimitGuard(
            IntegrationBackoffManager(
                dao = dao,
                baseMs = 1_000L,
                capMs = 30_000L,
                jitterMs = 0L
            )
        )
        val before = System.currentTimeMillis()

        guard.noteResponse(Response.error<Unit>(429, """{"error":"Daily API limit exceeded!"}""".toResponseBody()))

        val stored = dao.get("MDBLIST", "provider-config:mdblist:daily-api-limit")
            ?: error("expected MDBList daily backoff to be persisted")
        assertTrue(stored.blockedUntilEpochMs - before >= 24L * 60L * 60L * 1000L)
    }
}
