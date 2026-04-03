package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import io.mockk.coEvery
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
        coEvery { directTransport.runProfile(any(), any(), any()) } returns directResult
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
        assertEquals(directProfile, result.failureDetails?.direct)
        assertEquals(optimizedProfile, result.failureDetails?.optimized)
    }

    @Test
    fun `stability winner prefers playback stable optimized transport over lower cv direct transport`() = runTest {
        val directTransport = mockk<DirectProfileBenchmarkTransport>()
        val optimizedTransport = mockk<OptimizedBenchmarkTransport>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val deviceCapabilitySnapshotProvider = mockk<DeviceCapabilitySnapshotProvider>()
        val playerSettings = PlayerSettings(
            iecPackerTruehdPassthroughEnabled = false,
            iecPackerDtshdCoreFallbackEnabled = true
        )

        val directResult = DebridBenchmarkTransportProfileResult(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 180L,
                sustainedThroughputMbps = 420.0,
                transferredBytes = 4_000_000_000L,
                elapsedMs = 120_000L
            ),
            profile = profile(
                startupMs = 180L,
                averageMbps = 420.0,
                p10Mbps = 300.0,
                actionable = true,
                throughputCv = 0.11,
                stallCount = 1,
                maxReadGapMs = 2_900L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
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

        coEvery { directTransport.runProfile(any(), any(), any()) } returns directResult
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
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, result.result?.comparison?.stabilityWinner)
        verify(exactly = 1) { deviceCapabilitySnapshotProvider.capture(playerSettings) }
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
                actionable = actionable
            ),
            rawSamples = DebridBenchmarkRawSamples()
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
