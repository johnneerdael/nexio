package com.nexio.tv.core.integration

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultIntegrationRuntimeBootBudgetTest {
    @Test
    fun `runtime serves stale when boot budget blocks fresh network`() = runTest {
        val cacheStore = mockk<IntegrationCacheStore>(relaxed = false)
        coEvery { cacheStore.readStale(any<IntegrationSpec<String>>()) } returns "stale-rating"
        coEvery { cacheStore.readFresh(any<IntegrationSpec<String>>()) } returns null
        coEvery { cacheStore.write(any<IntegrationSpec<String>>(), any()) } returns Unit
        coEvery { cacheStore.deleteOwnedMedia(any()) } returns 0
        val governor = BootNetworkGovernor().also { it.beginBootWindow() }
        val runtime = runtime(cacheStore, governor)

        val result = runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.MDBLIST,
                apiShapeId = MDBListApiShapes.RATING_BATCH,
                operationKey = "mdblist.boot-budget-test",
                cacheKey = "mdblist:boot-budget-test",
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(1L, 1_000L),
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                load = { error("network should not start") }
            )
        )

        assertTrue(result is IntegrationFetchResult.Stale)
        assertEquals("stale-rating", (result as IntegrationFetchResult.Stale).value)
    }

    @Test
    fun `runtime blocks background call when boot budget denies provider`() = runTest {
        val cacheStore = mockk<IntegrationCacheStore>(relaxed = true)
        val governor = BootNetworkGovernor().also { it.beginBootWindow() }
        val runtime = runtime(cacheStore, governor)
        var networkStarted = false

        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.CUSTOM_IMDB,
                apiShapeId = CustomImdbApiShapes.TITLE_BULK,
                operationKey = "custom-imdb.boot-budget-test",
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                call = {
                    networkStarted = true
                    IntegrationCallResult.Success("unexpected")
                }
            )
        )

        assertEquals(IntegrationCallResult.Missing, result)
        assertFalseCompat(networkStarted)
    }

    @Test
    fun `runtime allows user visible detail call during boot window`() = runTest {
        val cacheStore = mockk<IntegrationCacheStore>(relaxed = true)
        val governor = BootNetworkGovernor().also { it.beginBootWindow() }
        val runtime = runtime(cacheStore, governor)

        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.CUSTOM_IMDB,
                apiShapeId = CustomImdbApiShapes.TITLE_BULK,
                operationKey = "custom-imdb.detail-test",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = { IntegrationCallResult.Success("ok") }
            )
        )

        assertEquals("ok", (result as IntegrationCallResult.Success).value)
    }

    private fun runtime(
        cacheStore: IntegrationCacheStore,
        governor: BootNetworkGovernor
    ): DefaultIntegrationRuntime {
        val registry = defaultIntegrationPolicyRegistry()
        return DefaultIntegrationRuntime(
            cacheStore = cacheStore,
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            bootNetworkGovernor = governor
        )
    }

    private fun assertFalseCompat(value: Boolean) {
        assertTrue(!value)
    }
}
