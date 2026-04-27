package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.ByteArrayIntegrationCacheStore
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.InMemoryIntegrationProviderBackoffDao
import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSingleFlight
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.ProviderRequestGate
import com.nexio.tv.core.integration.RecordingIntegrationAuditSink
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeOperationTraceTest {
    @Test
    fun `runtime call emits operation_start and operation_finish`() = runTest {
        val registry = defaultIntegrationPolicyRegistry()
        val traceSink = RecordingTraceSink()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = ByteArrayIntegrationCacheStore(),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = traceSink
        )

        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:550:en",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            call = { IntegrationCallResult.Success("ok") }
        )

        runtime.call(spec)

        val types = traceSink.events.map { it.eventType }
        assertTrue("expected runtime.operation_start in $types", types.contains("runtime.operation_start"))
        assertTrue("expected runtime.operation_finish in $types", types.contains("runtime.operation_finish"))

        val starts = traceSink.events.filter { it.eventType == "runtime.operation_start" }
        val finishes = traceSink.events.filter { it.eventType == "runtime.operation_finish" }
        val startId = (starts.first().payload as Map<*, *>)["runtimeOperationId"]
        val finishId = (finishes.first().payload as Map<*, *>)["runtimeOperationId"]
        assertEquals("ids match", startId, finishId)
    }
}
