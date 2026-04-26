package com.nexio.tv.core.tvdb

import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbMaintenanceRuntimeTest {
    @Test
    fun `tvdb startup maintenance enters runtime as maintenance work`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = TvdbUpdateCoordinatorResult.Success(0, 0)
        )
        val provider = TvdbIntegrationProvider(
            runtime = runtime,
            tvdbApi = mockk<TvdbApi>(),
            tvdbAuthService = mockk<TvdbAuthService>()
        )

        val result = provider.runMaintenanceUpdate(
            triggerKey = "startup",
            codec = gsonCodec<TvdbUpdateCoordinatorResult>()
        ) {
            TvdbUpdateCoordinatorResult.Success(0, 0)
        }

        assertTrue(result is TvdbUpdateCoordinatorResult.Success)
        assertEquals(listOf("tvdb:updates:startup"), runtime.keys)
    }
}
