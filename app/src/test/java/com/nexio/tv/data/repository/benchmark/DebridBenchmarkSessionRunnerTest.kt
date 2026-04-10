package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkSessionRunnerTest {

    @Test
    fun `session fails instead of returning a skewed completed result when optimized transport fails early`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()

        val directProfile = profile(
            startupMs = 180L,
            averageMbps = 220.0,
            p10Mbps = 180.0,
            actionable = true
        )
        val directResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 180L,
                sustainedThroughputMbps = 220.0,
                transferredBytes = 4_000_000_000L,
                elapsedMs = 120_000L
            ),
            profile = directProfile,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            failure = null
        )
        val optimizedFailure = DebridBenchmarkTransportFailure(
            exceptionClass = "SocketException",
            message = "Connection reset",
            recoverableFailureCount = 3,
            recoverableTimeoutCount = 0
        )
        val optimizedProfile = profile(
            startupMs = 320L,
            averageMbps = 260.0,
            p10Mbps = 210.0,
            actionable = true
        )
        val optimizedResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 320L,
                sustainedThroughputMbps = 260.0,
                transferredBytes = 2_000_000_000L,
                elapsedMs = 60_000L
            ),
            profile = optimizedProfile,
            terminationReason = DebridBenchmarkTerminationReason.FAILED,
            failure = optimizedFailure
        )

        val playerSettings = PlayerSettings()
        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returns optimizedResult
        every { playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        every { deviceCapabilitySnapshotProvider.capture(playerSettings) } returns sampleDeviceSnapshot()

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 1234L }
        )

        val result = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        assertEquals(DebridBenchmarkTerminationReason.FAILED, result.terminationReason)
        assertEquals(optimizedResult.summary, result.summary)
        assertNull(result.result)
        assertNotNull(result.failureDetails)
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, result.failureDetails?.failedTransport)
        assertNull(result.failureDetails?.direct)
        assertEquals(optimizedProfile, result.failureDetails?.optimized)
        coVerify(exactly = 0) { directTransport.runProfile(any(), any(), any()) }
    }

    @Test
    fun `completed benchmark stores optimized profile only`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        val playerSettings = PlayerSettings(
            iecPackerTruehdPassthroughEnabled = false,
            iecPackerDtshdCoreFallbackEnabled = true
        )

        val optimizedResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 150L,
                sustainedThroughputMbps = 780.0,
                transferredBytes = 9_000_000_000L,
                elapsedMs = 120_000L
            ),
            profile = profile(
                startupMs = 150L,
                averageMbps = 780.0,
                p10Mbps = 430.0,
                actionable = true,
                throughputCv = 0.30,
                stallCount = 0,
                maxReadGapMs = 500L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )

        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returns optimizedResult
        every { playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        every { deviceCapabilitySnapshotProvider.capture(playerSettings) } returns sampleDeviceSnapshot()

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 5678L }
        )

        val result = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        assertEquals(DebridBenchmarkTerminationReason.COMPLETED, result.terminationReason)
        assertNull(result.result?.direct)
        assertNull(result.result?.comparison)
        assertNotNull(result.result?.optimized)
        assertEquals(optimizedResult.profile.startup, result.result?.optimized?.startup)
        assertEquals(optimizedResult.profile.seek, result.result?.optimized?.seek)
        assertEquals(
            optimizedResult.profile.sustained,
            result.result?.optimized?.sustained
        )
        assertEquals(
            result.result?.optimized?.decision?.steadyStateSafeBudgetMbps,
            result.result?.optimized?.decision?.safeSustainedBudgetMbps
        )
        verify(exactly = 1) { deviceCapabilitySnapshotProvider.capture(playerSettings) }
        coVerify(exactly = 0) { directTransport.runProfile(any(), any(), any()) }
    }

    @Test
    fun `parallel optimized profile keeps steady state budget above startup floor during brief stalls`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        val playerSettings = PlayerSettings()
        every { playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        every { deviceCapabilitySnapshotProvider.capture(playerSettings) } returns sampleDeviceSnapshot()
        coEvery { directTransport.runProfile(any(), any(), any()) } returns
            DebridBenchmarkTransportProfileResult(
                summary = DebridBenchmarkSummary(
                    startupTimeMs = 180L,
                    sustainedThroughputMbps = 120.0,
                    transferredBytes = 1_000L,
                    elapsedMs = 120_000L
                ),
                profile = profile(180L, 120.0, 100.0, actionable = true),
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED
            )
        val optimizedProfile = profile(
            startupMs = 250L,
            averageMbps = 780.0,
            p10Mbps = 65.536,
            actionable = true,
            throughputCv = 0.18,
            stallCount = 2,
            maxReadGapMs = 4_000L
        ).copy(
            sustained = profile(250L, 780.0, 65.536, actionable = true, throughputCv = 0.18, stallCount = 2, maxReadGapMs = 4_000L).sustained.copy(
                p50ThroughputMbps = 846.0,
                steadyStateReferenceMbps = 780.0,
                cacheAbsorbableDeficitCount = 2,
                cacheDrainingDeficitCount = 0,
                estimatedMinCacheHeadroomMs = 9_000L
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 3,
                parallelChunkSizeMb = 24
            )
        )
        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returns
            DebridBenchmarkTransportProfileResult(
                summary = DebridBenchmarkSummary(
                    startupTimeMs = 250L,
                    sustainedThroughputMbps = 780.0,
                    transferredBytes = 9_000_000_000L,
                    elapsedMs = 120_000L
                ),
                profile = optimizedProfile,
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED
            )

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 5678L }
        )

        val result = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        val decision = result.result?.optimized?.decision
        assertEquals(65.536 * 0.85, decision?.startupSafeBudgetMbps ?: 0.0, 0.001)
        assertTrue((decision?.steadyStateSafeBudgetMbps ?: 0.0) > (decision?.startupSafeBudgetMbps ?: 0.0))
    }

    @Test
    fun `timeout-heavy transport penalties reduce steady state budget compared to clean transport`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { deviceCapabilitySnapshotProvider.capture(any()) } returns sampleDeviceSnapshot()

        val cleanProfile = timeoutPressureProfile(
            maxReadGapMs = 500L,
            recoverableFailureCount = 0,
            recoverableTimeoutCount = 0
        )
        val cleanResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 150L,
                sustainedThroughputMbps = 40.0,
                transferredBytes = 2_000_000_000L,
                elapsedMs = 10_000L
            ),
            profile = cleanProfile,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            failure = null
        )

        val timeoutProfile = timeoutPressureProfile(
            maxReadGapMs = 500L,
            recoverableFailureCount = 8,
            recoverableTimeoutCount = 10
        )
        val timeoutResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 150L,
                sustainedThroughputMbps = 40.0,
                transferredBytes = 2_000_000_000L,
                elapsedMs = 10_000L
            ),
            profile = timeoutProfile,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            failure = null
        )

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 5678L }
        )

        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returnsMany listOf(
            cleanResult,
            timeoutResult
        )

        val cleanSession = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        val timeoutSession = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        val cleanBudget = cleanSession.result?.optimized?.decision?.steadyStateSafeBudgetMbps ?: 0.0
        val cleanStartupBudget = cleanSession.result?.optimized?.decision?.startupSafeBudgetMbps ?: 0.0
        val timeoutBudget = timeoutSession.result?.optimized?.decision?.steadyStateSafeBudgetMbps ?: 0.0

        assertEquals(40.0, cleanBudget, 0.001)
        assertEquals(40.0 * 0.85, cleanStartupBudget, 0.001)
        assertEquals(36.32, timeoutBudget, 0.01)
        assertTrue(timeoutBudget < cleanBudget)
    }

    @Test
    fun `id=3 wireless anchor fixture remains timeout-penalized before remediation`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        val fixture = wirelessRegressionFixtureId3
        val degradedProfile = anchorRegressionProfile(
            fixture = fixture,
            recoverableFailureCount = fixture.recoverableFailureCount,
            recoverableTimeoutCount = fixture.recoverableTimeoutCount,
            maxReadGapMs = fixture.maxReadGapMs,
            cacheAbsorbableDeficitCount = fixture.cacheAbsorbableDeficitCount
        )
        val remediatedProfile = anchorRegressionProfile(
            fixture = fixture,
            recoverableFailureCount = 0,
            recoverableTimeoutCount = 0,
            maxReadGapMs = 500L,
            cacheAbsorbableDeficitCount = 0
        )
        val degradedResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = fixture.startupMs,
                sustainedThroughputMbps = fixture.sustainedThroughputMbps,
                transferredBytes = 2_000_000_000L,
                elapsedMs = fixture.summaryElapsedMs
            ),
            profile = degradedProfile,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
        val remediatedResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = fixture.startupMs,
                sustainedThroughputMbps = fixture.sustainedThroughputMbps,
                transferredBytes = 2_000_000_000L,
                elapsedMs = fixture.summaryElapsedMs
            ),
            profile = remediatedProfile,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )

        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { deviceCapabilitySnapshotProvider.capture(any()) } returns sampleDeviceSnapshot()
        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returnsMany listOf(
            degradedResult,
            remediatedResult
        )

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 5678L }
        )

        val degradedSession = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )
        val remediatedSession = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        val degradedDecision = degradedSession.result?.optimized?.decision
        val remediatedDecision = remediatedSession.result?.optimized?.decision
        val degradedProfileSummary = degradedSession.result?.optimized

        assertEquals(27.8528, degradedDecision?.startupSafeBudgetMbps ?: 0.0, 0.0001)
        assertEquals(19.2267, degradedDecision?.steadyStateSafeBudgetMbps ?: 0.0, 0.05)
        assertTrue((remediatedDecision?.steadyStateSafeBudgetMbps ?: 0.0) > 23.0)
        assertEquals(22, degradedProfileSummary?.sustained?.recoverableTimeoutCount)
        assertEquals(4882L, degradedProfileSummary?.sustained?.maxReadGapMs)
        assertTrue((remediatedDecision?.steadyStateSafeBudgetMbps ?: 0.0) >
            (degradedDecision?.steadyStateSafeBudgetMbps ?: 0.0))
        assertEquals(fixture.parallelChunkSizeMb, degradedProfileSummary?.configSnapshot?.parallelChunkSizeMb)
    }

    @Test
    fun `decision metrics expose whether shadow or legacy path constrained the budget`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        val playerSettings = PlayerSettings()
        every { playerSettingsDataStore.playerSettings } returns flowOf(playerSettings)
        every { deviceCapabilitySnapshotProvider.capture(playerSettings) } returns sampleDeviceSnapshot()

        val baseProfile = profile(
            startupMs = 180L,
            averageMbps = 200.0,
            p10Mbps = 150.0,
            actionable = true
        )
        val frontierEvents = (1..10).map { index ->
            FrontierEvent(
                tMs = index * 1_000L,
                contiguousFrontierBytes = index * 8L * 1024L * 1024L,
                deltaContiguousBytes = 8L * 1024L * 1024L
            )
        }
        val optimizedProfile = baseProfile.copy(
            sustained = baseProfile.sustained.copy(
                steadyStateReferenceMbps = 190.0,
                p50ThroughputMbps = 200.0
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 3,
                parallelChunkSizeMb = 24
            ),
            rawSamples = DebridBenchmarkRawSamples(frontierEvents = frontierEvents)
        )

        coEvery { optimizedTransport.runProfile(any(), any(), any(), any()) } returns
            DebridBenchmarkTransportProfileResult(
                summary = DebridBenchmarkSummary(
                    startupTimeMs = 180L,
                    sustainedThroughputMbps = 200.0,
                    transferredBytes = 2_000_000_000L,
                    elapsedMs = 120_000L
                ),
                profile = optimizedProfile,
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED
            )

        val runner = DebridBenchmarkSessionRunner(
            directTransport = directTransport,
            optimizedTransport = optimizedTransport,
            playerSettingsDataStore = playerSettingsDataStore,
            deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
            nowMs = { 5678L }
        )

        val result = runner.run(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            candidate = candidate(),
            observer = DebridBenchmarkObserver {}
        )

        val decision = result.result?.optimized?.decision
        assertNotNull(decision?.legacyBudgetMbps)
        assertNotNull(decision?.budgetDivergenceRatio)
        assertNotNull(decision?.shadowPlayerResult)
    }

    private fun candidate(): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            directUrl = "https://real-debrid.example/file.mkv",
            headers = emptyMap(),
            filename = "Example.mkv",
            sourceSizeBytes = 80L * 1024L * 1024L * 1024L
        )
    }

    private fun profile(
        startupMs: Long,
        averageMbps: Double,
        p10Mbps: Double,
        actionable: Boolean,
        throughputCv: Double = 0.05,
        stallCount: Int = 0,
        maxReadGapMs: Long = 140L
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 2,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = actionable,
                p10ThroughputMbps = p10Mbps,
                p50ThroughputMbps = averageMbps,
                peakThroughputMbps = averageMbps + 20.0,
                throughputStddevMbps = 8.0,
                throughputCv = throughputCv,
                stallCount = stallCount,
                maxReadGapMs = maxReadGapMs,
                bytesTransferred = 2_048L,
                elapsedMs = 120_000L
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = 180L,
                seekTtfbP95Ms = 240L,
                seekTtfbP99Ms = 320L,
                seekTtfbStddevMs = 16.0,
                seekFailRate = 0.0
            ),
            decision = DebridBenchmarkTransportDecisionMetrics(
                safeSustainedBudgetMbps = if (actionable) p10Mbps * 0.85 else null,
                startupSafeBudgetMbps = if (actionable) p10Mbps * 0.85 else null,
                steadyStateSafeBudgetMbps = if (actionable) p10Mbps * 0.85 else null,
                actionable = actionable
            ),
            rawSamples = DebridBenchmarkRawSamples()
        )
    }

    private fun timeoutPressureProfile(
        maxReadGapMs: Long,
        recoverableFailureCount: Int,
        recoverableTimeoutCount: Int
    ): DebridBenchmarkTransportProfile {
        val baseProfile = profile(
            startupMs = 150L,
            averageMbps = 40.0,
            p10Mbps = 40.0,
            actionable = true,
            throughputCv = 0.05,
            stallCount = 2,
            maxReadGapMs = maxReadGapMs,
        )

        return baseProfile.copy(
            sustained = baseProfile.sustained.copy(
                steadyStateReferenceMbps = 40.0,
                p50ThroughputMbps = 40.0,
                cacheAbsorbableDeficitCount = 0,
                cacheDrainingDeficitCount = 0,
                recoverableFailureCount = recoverableFailureCount,
                recoverableTimeoutCount = recoverableTimeoutCount,
                maxReadGapMs = maxReadGapMs
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 24,
                chunkWaitTimeoutMs = 6_000L
            )
        )
    }

    private data class WirelessRegressionAnchorFixture(
        val startupMs: Long,
        val sustainedThroughputMbps: Double,
        val parallelChunkSizeMb: Int,
        val parallelConnectionCount: Int,
        val throughputCv: Double,
        val stallCount: Int,
        val maxReadGapMs: Long,
        val recoverableFailureCount: Int,
        val recoverableTimeoutCount: Int,
        val cacheAbsorbableDeficitCount: Int,
        val summaryElapsedMs: Long
    )

    private val wirelessRegressionFixtureId3 = WirelessRegressionAnchorFixture(
        startupMs = 816L,
        sustainedThroughputMbps = 34.957636,
        parallelChunkSizeMb = 24,
        parallelConnectionCount = 3,
        throughputCv = 1.55,
        stallCount = 4,
        maxReadGapMs = 4882L,
        recoverableFailureCount = 22,
        recoverableTimeoutCount = 22,
        cacheAbsorbableDeficitCount = 2,
        summaryElapsedMs = 120_000L
    )

    private fun anchorRegressionProfile(
        fixture: WirelessRegressionAnchorFixture,
        recoverableFailureCount: Int,
        recoverableTimeoutCount: Int,
        maxReadGapMs: Long,
        cacheAbsorbableDeficitCount: Int
    ): DebridBenchmarkTransportProfile {
        val baseProfile = profile(
            startupMs = fixture.startupMs,
            averageMbps = fixture.sustainedThroughputMbps,
            p10Mbps = 32.768,
            actionable = true,
            throughputCv = fixture.throughputCv,
            stallCount = fixture.stallCount,
            maxReadGapMs = maxReadGapMs
        )

        return baseProfile.copy(
            sustained = baseProfile.sustained.copy(
                steadyStateReferenceMbps = fixture.sustainedThroughputMbps,
                p50ThroughputMbps = fixture.sustainedThroughputMbps,
                cacheAbsorbableDeficitCount = cacheAbsorbableDeficitCount,
                cacheDrainingDeficitCount = 0,
                recoverableFailureCount = recoverableFailureCount,
                recoverableTimeoutCount = recoverableTimeoutCount,
                maxReadGapMs = maxReadGapMs,
                elapsedMs = fixture.summaryElapsedMs
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = fixture.parallelConnectionCount,
                parallelChunkSizeMb = fixture.parallelChunkSizeMb
            )
        )
    }

    private fun sampleDeviceSnapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "Shield",
            manufacturer = "NVIDIA",
            sdkInt = 35,
            displayHdrTypes = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(true, false, true),
                hevc = CodecSupport(true, false, true),
                av1 = CodecSupport(false, true, false),
                dolbyVision = CodecSupport(true, false, true)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                ac3 = AudioEncodingSupport(true, true),
                eac3 = AudioEncodingSupport(true, true),
                truehd = AudioEncodingSupport(true, true),
                dts = AudioEncodingSupport(true, true),
                dtshd = AudioEncodingSupport(true, true)
            ),
            capturedAtMs = 1L
        )
    }
}
