package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridBenchmarkStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebridBenchmarkServiceTest {

    @Test
    fun `service rejects a second benchmark while one is already active`() = runTest {
        val service = buildService(
            runSession = { _, _, _ -> awaitCancellation() },
            scope = backgroundScope
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        assertFalse(service.start(DebridBenchmarkProvider.PREMIUMIZE))
    }

    @Test
    fun `cancel stops the active benchmark without persisting a result`() = runTest {
        val sessionStarted = CompletableDeferred<Unit>()
        val service = buildService(
            runSession = { _, _, _ ->
                sessionStarted.complete(Unit)
                awaitCancellation()
            },
            scope = backgroundScope
        )

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        sessionStarted.await()

        service.cancel()

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
        val completedResult = DebridBenchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 42_000L,
            summary = summary,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
        val service = buildService(
            runSession = { _, _, _ ->
                DebridBenchmarkSessionResult(
                    summary = summary,
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                    result = completedResult
                )
            },
            scope = backgroundScope
        )
        val persisted = CompletableDeferred<DebridBenchmarkResult>()

        coEvery { store().saveLatest(any()) } answers {
            persisted.complete(firstArg())
            Unit
        }

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        assertEquals(completedResult, persisted.await())

        coVerify(exactly = 1) {
            store().saveLatest(completedResult)
        }
        verify(exactly = 1) {
            logger().logCompleted(completedResult)
        }
    }

    @Test
    fun `service reports no playable library item outcomes`() = runTest {
        val service = buildService(
            runSession = { _, _, _ ->
                DebridBenchmarkSessionResult(
                    summary = DebridBenchmarkSummary(),
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED
                )
            },
            scope = backgroundScope,
            resolveCandidate = { DebridBenchmarkCandidateResolution.NoPlayableLibraryItem }
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
        verify(exactly = 1) {
            logger().logOutcome(
                DebridBenchmarkProvider.REAL_DEBRID,
                DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM,
                DebridBenchmarkSummary(),
                null
            )
        }
    }

    @Test
    fun `service reports no large download outcomes`() = runTest {
        val service = buildService(
            runSession = { _, _, _ ->
                DebridBenchmarkSessionResult(
                    summary = DebridBenchmarkSummary(),
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED
                )
            },
            scope = backgroundScope,
            resolveCandidate = { DebridBenchmarkCandidateResolution.NoLargeDownload }
        )
        val reported = CompletableDeferred<DebridBenchmarkOutcome>()

        backgroundScope.launch {
            reported.complete(service.outcomes.first())
        }

        assertTrue(service.start(DebridBenchmarkProvider.PREMIUMIZE))

        assertEquals(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD
            ),
            reported.await()
        )
        verify(exactly = 1) {
            logger().logOutcome(
                DebridBenchmarkProvider.PREMIUMIZE,
                DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD,
                DebridBenchmarkSummary(),
                null
            )
        }
    }

    @Test
    fun `service logs failed benchmark outcomes without persisting a result`() = runTest {
        val summary = DebridBenchmarkSummary(
            startupTimeMs = 400L,
            sustainedThroughputMbps = 88.0,
            transferredBytes = 128.mb,
            elapsedMs = 12.seconds
        )
        val failureDetails = DebridBenchmarkFailureDetails(
            candidate = DebridBenchmarkCandidateMetadata(
                filename = "Example.mkv",
                sizeBytes = 20L * 1024L * 1024L * 1024L,
                host = "rd.example.net",
                directUrlFingerprint = "abc123"
            ),
            failedTransport = DebridBenchmarkTransportMode.DIRECT
        )
        val service = buildService(
            runSession = { _, _, _ ->
                DebridBenchmarkSessionResult(
                    summary = summary,
                    terminationReason = DebridBenchmarkTerminationReason.FAILED,
                    failureDetails = failureDetails
                )
            },
            scope = backgroundScope
        )
        val reported = CompletableDeferred<DebridBenchmarkOutcome>()

        backgroundScope.launch {
            reported.complete(service.outcomes.first())
        }

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))

        assertEquals(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = summary,
                terminationReason = DebridBenchmarkTerminationReason.FAILED,
                failureDetails = failureDetails
            ),
            reported.await()
        )
        coVerify(exactly = 0) { store().saveLatest(any()) }
        verify(exactly = 1) {
            logger().logOutcome(
                DebridBenchmarkProvider.REAL_DEBRID,
                DebridBenchmarkTerminationReason.FAILED,
                summary,
                failureDetails
            )
        }
    }

    private lateinit var benchmarkStore: DebridBenchmarkStore
    private lateinit var benchmarkSessionRunner: DebridBenchmarkSessionRunner
    private lateinit var benchmarkResultJsonLogger: BenchmarkResultJsonLogger
    private lateinit var playerSettingsDataStore: PlayerSettingsDataStore

    private fun buildService(
        runSession: suspend (DebridBenchmarkProvider, DebridBenchmarkCandidate, DebridBenchmarkObserver) -> DebridBenchmarkSessionResult,
        scope: kotlinx.coroutines.CoroutineScope,
        resolveCandidate: (DebridBenchmarkProvider) -> DebridBenchmarkCandidateResolution =
            { provider -> DebridBenchmarkCandidateResolution.Candidate(candidate(provider)) }
    ): DebridBenchmarkService {
        val resolver = mockk<DebridBenchmarkCandidateResolver>()
        benchmarkStore = mockk(relaxed = true)
        benchmarkSessionRunner = mockk()
        benchmarkResultJsonLogger = mockk(relaxed = true)
        playerSettingsDataStore = mockk(relaxed = true)

        coEvery { resolver.resolve(any()) } answers {
            resolveCandidate(firstArg())
        }
        coEvery { benchmarkStore.latestResult(any()) } returns emptyFlow()
        coEvery { benchmarkSessionRunner.run(any(), any(), any()) } coAnswers {
            runSession(firstArg(), secondArg(), thirdArg())
        }

        return DebridBenchmarkService(
            resolver = resolver,
            store = benchmarkStore,
            sessionRunner = benchmarkSessionRunner,
            benchmarkResultJsonLogger = benchmarkResultJsonLogger,
            playerSettingsDataStore = playerSettingsDataStore,
            scope = scope,
            nowMs = System::currentTimeMillis,
            executionGate = DebridBenchmarkExecutionGate()
        )
    }

    private fun store(): DebridBenchmarkStore = benchmarkStore
    private fun logger(): BenchmarkResultJsonLogger = benchmarkResultJsonLogger
    private fun playerSettingsStore(): PlayerSettingsDataStore = playerSettingsDataStore

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
