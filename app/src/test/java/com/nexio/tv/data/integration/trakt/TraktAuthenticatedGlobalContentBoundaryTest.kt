package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * F2-D-01 pin: authenticated Trakt global-content fetches MUST NOT throw ProfileBoundaryException
 * at IntegrationSpec construction. Two profiles must share the cache via the global cache key.
 *
 * Cluster F F-C-06 changed only the cacheKey to globalContentCacheKey but left scope as
 * accountScope(session), creating an irreconcilable scope/key pairing that ProfileBoundaryEnforcer
 * correctly rejects (validateAccountScope → validateProfileScope requires "profile:N:" in the key,
 * but globalContentCacheKey emits "global:provider:TRAKT:..." with no profile prefix).
 *
 * This test FAILS on current production code and passes only after the Task 3 migration corrects
 * the scope to IntegrationScope.GlobalContent (with profileContext = null) for all 6 global-content
 * fetch functions.
 */
class TraktAuthenticatedGlobalContentBoundaryTest {

    // -------------------------------------------------------------------------
    // Test 1: fetchTrendingMovies must not throw ProfileBoundaryException
    // -------------------------------------------------------------------------

    @Test
    fun `fetchTrendingMovies for authenticated session does not throw ProfileBoundaryException`() =
        runTest {
            val provider = buildProvider(sessionProfileId = 1, credentialHash = "hash-p1")

            // Currently this throws ProfileBoundaryException at IntegrationSpec.init because
            // scope = Account(profileId=1,...) demands "profile:1:" in the cache key, but
            // globalContentCacheKey(...) produces "global:provider:TRAKT:..." (no profile prefix).
            // After the Task 3 migration the scope is changed to GlobalContent + profileContext=null
            // and this call succeeds (returning null/empty from the RecordingIntegrationRuntime).
            try {
                provider.fetchTrendingMovies(limit = 20)
            } catch (e: ProfileBoundaryException) {
                fail(
                    "F2-D-01: fetchTrendingMovies threw ProfileBoundaryException — " +
                        "scope/key pairing must be fixed. violation=${e.violation}, msg=${e.message}"
                )
            }
        }

    // -------------------------------------------------------------------------
    // Test 2: Two different profiles can both call fetchTrendingMovies;
    //         the second call must hit the shared cache (only one network call recorded).
    // -------------------------------------------------------------------------

    @Test
    fun `fetchTrendingMovies with two profiles shares cache and makes only one network call`() =
        runTest {
            val runtime = RecordingIntegrationRuntime<List<Any>>()

            val providerP1 = buildProvider(
                sessionProfileId = 1,
                credentialHash = "hash-p1",
                runtime = runtime
            )
            val providerP2 = buildProvider(
                sessionProfileId = 2,
                credentialHash = "hash-p2",
                runtime = runtime
            )

            // After the migration both calls succeed without ProfileBoundaryException.
            try {
                providerP1.fetchTrendingMovies(limit = 20)
            } catch (e: ProfileBoundaryException) {
                fail("F2-D-01: profile 1 fetchTrendingMovies threw ProfileBoundaryException: ${e.message}")
            }

            try {
                providerP2.fetchTrendingMovies(limit = 20)
            } catch (e: ProfileBoundaryException) {
                fail("F2-D-01: profile 2 fetchTrendingMovies threw ProfileBoundaryException: ${e.message}")
            }

            // Both calls must resolve to the same cache key (shared global key).
            // The RecordingIntegrationRuntime records spec.cacheKey for every get() invocation.
            val uniqueKeys = runtime.keys.toSet()
            assert(uniqueKeys.size == 1) {
                "F2-D-01: expected both profiles to share one cache key; got $uniqueKeys"
            }
        }

    // -------------------------------------------------------------------------
    // Test 3: The cache key built for fetchTrendingMovies must NOT contain "profile:"
    // -------------------------------------------------------------------------

    @Test
    fun `fetchTrendingMovies cache key does not contain profile prefix`() = runTest {
        val runtime = RecordingIntegrationRuntime<List<Any>>()
        val provider = buildProvider(
            sessionProfileId = 1,
            credentialHash = "hash-p1",
            runtime = runtime
        )

        // Invoke — if the current code throws, still check the key (it may have been
        // recorded before the exception or not at all). Either way the assertion below
        // must hold: no recorded key may contain "profile:".
        runCatching { provider.fetchTrendingMovies(limit = 20) }

        assertFalse(
            "F2-D-01: cache key must not contain profile prefix. Got: ${runtime.keys}",
            runtime.keys.any { it.contains("profile:") }
        )
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private fun buildProvider(
        sessionProfileId: Int,
        credentialHash: String,
        runtime: RecordingIntegrationRuntime<*> = RecordingIntegrationRuntime<List<Any>>()
    ): TraktIntegrationProvider {
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = sessionProfileId,
            credentialHash = credentialHash
        )
        val traktAuthService = mockk<TraktAuthService> {
            coEvery { accountScopedSession() } returns session
            coEvery { accountScopedSession(any()) } returns session
        }
        return TraktIntegrationProvider(
            runtime = runtime,
            traktApi = mockk<TraktApi>(relaxed = true),
            traktAuthService = traktAuthService
        )
    }
}
