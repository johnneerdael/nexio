package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridConfigBenchmarkStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebridConfigBenchmarkServiceTest {

    @Test
    fun `service runs all matrix profiles against the same candidate`() = runTest {
        val candidate = candidate()
        val executedCandidates = mutableListOf<DebridBenchmarkCandidate>()
        val persisted = CompletableDeferred<DebridConfigBenchmarkResult>()
        val store = mockk<DebridConfigBenchmarkStore>(relaxed = true)
        coEvery { store.latestResult(any()) } returns emptyFlow()
        coEvery { store.saveLatest(any()) } answers {
            persisted.complete(firstArg())
            Unit
        }
        val service = buildService(
            store = store,
            scope = backgroundScope,
            resolvedCandidate = candidate,
            runProfile = { resolved, _, _ ->
                executedCandidates += resolved
                successTransportResult(400.0)
            }
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        val result = persisted.await()

        assertEquals(9, executedCandidates.size)
        assertTrue(executedCandidates.all { it.directUrl == candidate.directUrl })
        assertEquals(9, result.profiles.size)
    }

    @Test
    fun `best profile is highest average throughput among successful runs only`() = runTest {
        val persisted = CompletableDeferred<DebridConfigBenchmarkResult>()
        val store = mockk<DebridConfigBenchmarkStore>(relaxed = true)
        coEvery { store.latestResult(any()) } returns emptyFlow()
        coEvery { store.saveLatest(any()) } answers {
            persisted.complete(firstArg())
            Unit
        }
        val service = buildService(
            store = store,
            scope = backgroundScope,
            runProfile = { _, snapshot, _ ->
                when {
                    snapshot.parallelConnectionCount == 4 && snapshot.parallelChunkSizeMb == 16 ->
                        successTransportResult(700.0)
                    else ->
                        successTransportResult(400.0)
                }
            }
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        val result = persisted.await()

        assertEquals(4, result.summary.bestProfile?.parallelConnectionCount)
        assertEquals(16, result.summary.bestProfile?.chunkSizeMb)
    }

    @Test
    fun `session with no successful profiles persists without a best profile summary`() = runTest {
        val persisted = CompletableDeferred<DebridConfigBenchmarkResult>()
        val store = mockk<DebridConfigBenchmarkStore>(relaxed = true)
        coEvery { store.latestResult(any()) } returns emptyFlow()
        coEvery { store.saveLatest(any()) } answers {
            persisted.complete(firstArg())
            Unit
        }
        val service = buildService(
            store = store,
            scope = backgroundScope,
            memoryGate = DebridConfigBenchmarkMemoryGate(benchmarkBudgetMb = 1, reservedBufferMb = 50),
            runProfile = { _, _, _ -> failedTransportResult("Should not execute") }
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        val result = persisted.await()

        assertNull(result.summary.bestProfile)
        assertEquals(0, result.summary.successfulProfileCount)
        assertEquals(9, result.profiles.size)
    }

    private fun buildService(
        store: DebridConfigBenchmarkStore,
        scope: CoroutineScope,
        resolvedCandidate: DebridBenchmarkCandidate = candidate(),
        memoryGate: DebridConfigBenchmarkMemoryGate = DebridConfigBenchmarkMemoryGate(
            benchmarkBudgetMb = 256,
            reservedBufferMb = 50
        ),
        runProfile: suspend (
            DebridBenchmarkCandidate,
            DebridBenchmarkTransportConfigSnapshot,
            Long
        ) -> DebridConfigBenchmarkTransportResult
    ): DebridConfigBenchmarkService {
        val resolver = mockk<DebridBenchmarkCandidateResolver>()
        val transport = mockk<OptimizedBenchmarkTransport>()
        coEvery { resolver.resolve(any()) } returns DebridBenchmarkCandidateResolution.Candidate(resolvedCandidate)
        coEvery { transport.runConfigProfile(any(), any(), any(), any()) } coAnswers {
            runProfile(firstArg(), secondArg(), arg(3))
        }
        return DebridConfigBenchmarkService(
            resolver = resolver,
            store = store,
            transport = transport,
            memoryGate = memoryGate,
            scope = scope,
            nowMs = { 42L },
            executionGate = DebridBenchmarkExecutionGate()
        )
    }

    private fun candidate(): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            directUrl = "https://host/file",
            headers = emptyMap(),
            filename = "Example.mkv",
            sourceSizeBytes = 2L * 1024L * 1024L * 1024L
        )
    }

    private fun successTransportResult(averageMbps: Double): DebridConfigBenchmarkTransportResult {
        return DebridConfigBenchmarkTransportResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 100L,
                sustainedThroughputMbps = averageMbps,
                transferredBytes = 1_000_000_000L,
                elapsedMs = 30_000L
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 3,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = true,
                bytesTransferred = 1_000_000_000L,
                elapsedMs = 30_000L
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 2,
                parallelChunkSizeMb = 8
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
    }

    private fun failedTransportResult(reason: String): DebridConfigBenchmarkTransportResult {
        return DebridConfigBenchmarkTransportResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 100L,
                sustainedThroughputMbps = null,
                transferredBytes = 128L,
                elapsedMs = 1_000L
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 3,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
                averageThroughputMbps = null,
                derivedAverageThroughputMbps = null,
                actionable = false,
                bytesTransferred = 128L,
                elapsedMs = 1_000L
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 2,
                parallelChunkSizeMb = 8
            ),
            terminationReason = DebridBenchmarkTerminationReason.FAILED,
            failure = DebridBenchmarkTransportFailure(message = reason)
        )
    }
}
