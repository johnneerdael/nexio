package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridBenchmarkStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebridBenchmarkServiceTest {

    @Test
    fun `service rejects a second benchmark while one is already active`() = runTest {
        val service = buildService(
            activeTransport = neverEndingTransport(),
            scope = backgroundScope
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        assertFalse(service.start(DebridBenchmarkProvider.PREMIUMIZE))
    }

    @Test
    fun `cancel stops the active benchmark without persisting a result`() = runTest {
        val transport = HangingTransport()
        val service = buildService(
            activeTransport = transport,
            scope = backgroundScope
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        transport.started.await()

        service.cancel()
        transport.canceled.await()

        assertTrue(transport.wasCanceled)
        assertEquals(DebridBenchmarkRuntimeState.Idle, service.activeState.value)
        coVerify(exactly = 0) { store().saveLatest(any()) }
    }

    @Test
    fun `service persists completed benchmark results`() = runTest {
        val summary = DebridBenchmarkSummary(
            startupTimeMs = 4_000L,
            sustainedThroughputMbps = 123.5,
            transferredBytes = 600.mb,
            elapsedMs = 130.seconds
        )
        val service = buildService(
            activeTransport = CompletedTransport(summary),
            scope = backgroundScope,
            nowMs = { 42_000L }
        )
        val persisted = CompletableDeferred<DebridBenchmarkResult>()

        coEvery { store().saveLatest(any()) } answers {
            persisted.complete(firstArg())
            Unit
        }

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        assertEquals(
            DebridBenchmarkResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                measuredAtMs = 42_000L,
                summary = summary,
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED
            ),
            persisted.await()
        )

        coVerify(exactly = 1) {
            store().saveLatest(
                DebridBenchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    measuredAtMs = 42_000L,
                    summary = summary,
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED
                )
            )
        }
    }

    @Test
    fun `backgrounding the app cancels the active benchmark`() = runTest {
        val transport = HangingTransport()
        val service = buildService(
            activeTransport = transport,
            scope = backgroundScope
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        transport.started.await()

        service.onAppBackgrounded()
        transport.canceled.await()

        assertTrue(transport.wasCanceled)
        assertEquals(DebridBenchmarkRuntimeState.Idle, service.activeState.value)
    }

    @Test
    fun `service reports no playable library item outcomes`() = runTest {
        val service = buildService(
            activeTransport = CompletedTransport(DebridBenchmarkSummary()),
            scope = backgroundScope,
            resolveCandidate = { null }
        )
        val reported = CompletableDeferred<DebridBenchmarkOutcome>()

        backgroundScope.launch {
            reported.complete(service.outcomes.first())
        }

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))

        assertEquals(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM
            ),
            reported.await()
        )
    }

    private lateinit var benchmarkStore: DebridBenchmarkStore

    private fun buildService(
        activeTransport: DebridBenchmarkTransport,
        scope: kotlinx.coroutines.CoroutineScope,
        resolveCandidate: (DebridBenchmarkProvider) -> DebridBenchmarkCandidate? = ::candidate,
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): DebridBenchmarkService {
        val resolver = mockk<DebridBenchmarkCandidateResolver>()
        benchmarkStore = mockk(relaxed = true)

        coEvery { resolver.resolve(any()) } answers {
            resolveCandidate(firstArg())
        }
        coEvery { benchmarkStore.latestResult(any()) } returns emptyFlow()

        return DebridBenchmarkService(
            resolver = resolver,
            store = benchmarkStore,
            transport = activeTransport,
            scope = scope,
            nowMs = nowMs
        )
    }

    private fun store(): DebridBenchmarkStore = benchmarkStore

    private fun neverEndingTransport(): DebridBenchmarkTransport {
        return object : DebridBenchmarkTransport {
            override suspend fun run(
                candidate: DebridBenchmarkCandidate,
                observer: DebridBenchmarkObserver
            ): DebridBenchmarkTransportResult {
                awaitCancellation()
            }
        }
    }

    private inner class HangingTransport : DebridBenchmarkTransport {
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        var wasCanceled = false

        override suspend fun run(
            candidate: DebridBenchmarkCandidate,
            observer: DebridBenchmarkObserver
        ): DebridBenchmarkTransportResult {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                wasCanceled = true
                canceled.complete(Unit)
            }
        }
    }

    private class CompletedTransport(
        private val summary: DebridBenchmarkSummary
    ) : DebridBenchmarkTransport {
        override suspend fun run(
            candidate: DebridBenchmarkCandidate,
            observer: DebridBenchmarkObserver
        ): DebridBenchmarkTransportResult {
            observer.onSummaryUpdated(summary)
            return DebridBenchmarkTransportResult(
                summary = summary,
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED
            )
        }
    }

    private fun candidate(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = "https://example.com/${provider.storageKey}.mkv",
            headers = emptyMap(),
            filename = null,
            sourceSizeBytes = null
        )
    }

    private val Int.mb: Long
        get() = this * 1024L * 1024L

    private val Int.seconds: Long
        get() = this * 1_000L
}
