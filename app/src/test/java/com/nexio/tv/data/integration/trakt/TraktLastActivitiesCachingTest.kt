package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktLastActivitiesCachingTest {

    @Test
    fun getLastActivities_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
        val activities = TraktLastActivitiesResponseDto(all = "2026-05-04T10:00:00Z")
        val fixture = byteArrayRuntimeFixture()

        val traktApi = mockk<TraktApi> {
            coEvery { getLastActivities(any()) } returns Response.success(activities)
        }
        val provider = buildProvider(traktApi = traktApi, runtimeFixture = fixture)

        val first = unwrap(provider.getLastActivities())
        val second = unwrap(provider.getLastActivities())

        assertEquals("2026-05-04T10:00:00Z", first?.all)
        assertEquals("2026-05-04T10:00:00Z", second?.all)
        coVerify(exactly = 1) { traktApi.getLastActivities(any()) }
    }

    @Test
    fun invalidateLastActivities_then_read_hits_wire_again() = runBlocking {
        val first = TraktLastActivitiesResponseDto(all = "2026-05-04T10:00:00Z")
        val second = TraktLastActivitiesResponseDto(all = "2026-05-04T10:30:00Z")
        val fixture = byteArrayRuntimeFixture()

        val traktApi = mockk<TraktApi> {
            coEvery { getLastActivities(any()) } returnsMany listOf(
                Response.success(first),
                Response.success(second)
            )
        }
        val provider = buildProvider(traktApi = traktApi, runtimeFixture = fixture)

        val before = unwrap(provider.getLastActivities())
        provider.invalidateLastActivities()
        val after = unwrap(provider.getLastActivities())

        assertEquals("2026-05-04T10:00:00Z", before?.all)
        assertEquals("2026-05-04T10:30:00Z", after?.all)
        coVerify(exactly = 2) { traktApi.getLastActivities(any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun <T> unwrap(result: IntegrationCallResult<T>): T? =
        if (result is IntegrationCallResult.Success) result.value else null

    private fun buildProvider(
        traktApi: TraktApi,
        runtimeFixture: com.nexio.tv.core.integration.ByteArrayRuntimeFixture
    ): TraktIntegrationProvider {
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = 1,
            credentialHash = "hash-p1"
        )
        val traktAuthService = mockk<TraktAuthService> {
            coEvery { accountScopedSession() } returns session
            coEvery { accountScopedSession(any()) } returns session
            coEvery {
                executeAuthorizedRequestWithinRuntimeCall(any(), any<suspend (String) -> Response<TraktLastActivitiesResponseDto>>())
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<TraktLastActivitiesResponseDto>>()
                block("Bearer hash-p1")
            }
        }
        return TraktIntegrationProvider(
            runtime = runtimeFixture.runtime,
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            cacheStore = runtimeFixture.cacheStore
        )
    }
}
