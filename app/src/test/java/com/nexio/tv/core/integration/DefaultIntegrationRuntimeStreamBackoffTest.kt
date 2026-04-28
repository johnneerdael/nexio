package com.nexio.tv.core.integration

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F-A-01: When [DefaultIntegrationRuntime.openInternal]'s catch branch runs (the
 * stream opener throws), the runtime must engage backoff so subsequent calls for
 * the same provider/scope are blocked.
 *
 * The current implementation only records a FAILED audit phase and returns null;
 * it does NOT call `noteSyntheticNetworkFailure`. This test pins the desired
 * contract and is expected to FAIL until Task 3 fixes the catch branch.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultIntegrationRuntimeStreamBackoffTest {

    @Test
    fun `openInternal exception engages backoff for provider-scope`() = runTest {
        val fixture = realRuntimeFixture()

        val openSpec = IntegrationStreamSpec<String>(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            operationKey = "test:stream:f-a-01",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            open = { throw IOException("network down") }
        )

        runCatching { fixture.runtime.open(openSpec) }

        assertTrue(
            "F-A-01: stream open() failure must engage backoff",
            fixture.backoffManager.isBlocked(
                IntegrationProvider.TMDB,
                IntegrationScope.GlobalContent
            )
        )
    }
}
