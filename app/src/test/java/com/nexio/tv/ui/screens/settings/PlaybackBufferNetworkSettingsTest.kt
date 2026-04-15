package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.ProgressivePlaybackDiskMode
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import com.nexio.tv.ui.screens.player.spool.SpoolStoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferNetworkSettingsTest {
    @Test
    fun diskSpoolPolicy_usesSeventyFivePercentOfAvailableStorage() {
        assertEquals(
            1_536,
            resolveDiskSpoolPolicy(2_048L * 1024L * 1024L)
        )
    }

    @Test
    fun diskSpoolPolicy_isUnsupportedBelowMinimumFreeStorage() {
        assertEquals(
            null,
            resolveDiskSpoolPolicy(767L * 1024L * 1024L)
        )
    }

    @Test
    fun diskSpoolPolicy_capsAtTwoGbWhenStorageIsLargeEnough() {
        assertEquals(
            2_048,
            resolveDiskSpoolPolicy(4_096L * 1024L * 1024L)
        )
    }

    @Test
    fun parallelConnectionsSubtitle_warnsWhenDiskSpoolAndParallelAreEnabled() {
        assertEquals(
            ParallelConnectionsSubtitle.WarningForDiskSpool,
            resolveParallelConnectionsSubtitle(
                useParallelConnections = true,
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            )
        )
    }

    @Test
    fun nextDiskSpoolStorageLocation_togglesOnlyWhenExternalIsAvailable() {
        assertEquals(
            DiskSpoolStorageLocation.EXTERNAL,
            nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.BUILTIN, externalAvailable = true)
        )
        assertEquals(
            DiskSpoolStorageLocation.BUILTIN,
            nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL, externalAvailable = true)
        )
        assertEquals(
            DiskSpoolStorageLocation.BUILTIN,
            nextDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL, externalAvailable = false)
        )
    }

    @Test
    fun effectiveDiskSpoolStorageLocation_usesExternalWhenBuiltInIsUnsupported() {
        assertEquals(
            DiskSpoolStorageLocation.EXTERNAL,
            resolveEffectiveDiskSpoolStorageLocation(
                preferred = DiskSpoolStorageLocation.BUILTIN,
                builtInSupported = false,
                externalSupported = true
            )
        )
    }

    @Test
    fun diskSpoolDiagnosticStatus_doesNotDisableSpoolWhenResultIsMissingOrFailed() {
        assertEquals(
            DiskSpoolDiagnosticStatus.NotChecked,
            resolveDiskSpoolDiagnosticStatus(
                result = null,
                probeUiState = DiskSpoolStorageProbeUiState.NotChecked,
                nowMs = 1_776_047_818_725L,
                spoolDirectoryPath = "/cache/player_disk_spool"
            )
        )
    }

    @Test
    fun diskSpoolDiagnosticStatus_prefersRunningAndFailedTransientState() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val passing = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = path
        )

        assertEquals(
            DiskSpoolDiagnosticStatus.Running,
            resolveDiskSpoolDiagnosticStatus(
                result = passing,
                probeUiState = DiskSpoolStorageProbeUiState.Running,
                nowMs = nowMs,
                spoolDirectoryPath = path
            )
        )
        assertEquals(
            DiskSpoolDiagnosticStatus.Failed("not enough free space"),
            resolveDiskSpoolDiagnosticStatus(
                result = passing,
                probeUiState = DiskSpoolStorageProbeUiState.Failed("not enough free space"),
                nowMs = nowMs,
                spoolDirectoryPath = path
            )
        )
    }

    @Test
    fun diskSpoolDiagnosticStatus_reportsUnderLoadMBpsRecommendationAndAutoplayCap() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val result = SpoolStorageProbeResult(
            writeMbps = 1_600.0,
            readMbps = 3_200.0,
            combinedMbps = 1_200.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = path,
            concurrentSequentialWriteMbps = 400.0,
            concurrentSequentialReadMbps = 800.0,
            concurrentRandomWriteMbps = 320.0
        )

        assertEquals(
            DiskSpoolDiagnosticStatus.Measured(
                recommendation = DiskSpoolDiagnosticRecommendation.Recommended,
                autoplayCapMbps = 200,
                underLoadWriteMBps = 50,
                underLoadReadMBps = 100,
                randomWriteMBps = 40,
                p99ReadLatencyMs = 40L,
                maxReadStallMs = 70L
            ),
            resolveDiskSpoolDiagnosticStatus(
                result = result,
                probeUiState = DiskSpoolStorageProbeUiState.NotChecked,
                nowMs = nowMs,
                spoolDirectoryPath = path
            )
        )
    }

    @Test
    fun diskSpoolDiagnosticStatus_rejectsStorageWhenStallsAreHigh() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val result = SpoolStorageProbeResult(
            writeMbps = 1_600.0,
            readMbps = 3_200.0,
            combinedMbps = 1_200.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 900L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = path,
            concurrentSequentialWriteMbps = 400.0,
            concurrentSequentialReadMbps = 800.0,
            concurrentRandomWriteMbps = 320.0
        )

        assertEquals(
            DiskSpoolDiagnosticStatus.Measured(
                recommendation = DiskSpoolDiagnosticRecommendation.NotRecommended,
                autoplayCapMbps = 200,
                underLoadWriteMBps = 50,
                underLoadReadMBps = 100,
                randomWriteMBps = 40,
                p99ReadLatencyMs = 40L,
                maxReadStallMs = 900L
            ),
            resolveDiskSpoolDiagnosticStatus(
                result = result,
                probeUiState = DiskSpoolStorageProbeUiState.NotChecked,
                nowMs = nowMs,
                spoolDirectoryPath = path
            )
        )
    }

    @Test
    fun diskSpoolProbeStatus_reflectsMissingPassedFailedAndStaleResults() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val passing = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = path
        )

        assertEquals(
            DiskSpoolProbeStatus.NotChecked,
            resolveDiskSpoolProbeStatus(
                result = null,
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = 80.0
            )
        )
        assertEquals(
            DiskSpoolProbeStatus.Passed(combinedMbps = 360, p99ReadLatencyMs = 40L),
            resolveDiskSpoolProbeStatus(
                result = passing,
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = 80.0
            )
        )
        assertEquals(
            DiskSpoolProbeStatus.Failed(combinedMbps = 140, p99ReadLatencyMs = 40L),
            resolveDiskSpoolProbeStatus(
                result = passing.copy(writeMbps = 70.0, readMbps = 70.0, combinedMbps = 140.0),
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = 80.0
            )
        )
        assertEquals(
            DiskSpoolProbeStatus.Stale,
            resolveDiskSpoolProbeStatus(
                result = passing.copy(measuredAtMs = nowMs - 8L * 24L * 60L * 60L * 1000L),
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = 80.0
            )
        )
    }

    @Test
    fun diskSpoolProbeStatus_usesUnknownStreamFallbackFloorOverManualCap() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val result = SpoolStorageProbeResult(
            writeMbps = 45.0,
            readMbps = 45.0,
            combinedMbps = 100.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 337_500_000L,
            bytesRead = 337_500_000L,
            spoolDirectoryPath = path
        )

        assertEquals(
            DiskSpoolProbeStatus.Failed(combinedMbps = 100, p99ReadLatencyMs = 40L),
            resolveDiskSpoolProbeStatus(
                result = result,
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = SpoolStoragePolicy.targetBitrateMbps(
                    streamBitrateMbps = null,
                    userCapMbps = 40.0
                )
            )
        )
    }

    @Test
    fun diskSpoolProbeStatus_reportsDisabledWhenModeIsOffEvenIfProbePassed() {
        val path = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        val nowMs = 1_776_047_818_725L
        val passing = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = nowMs - 1_000L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = path
        )

        assertEquals(
            DiskSpoolProbeStatus.Disabled,
            resolveDiskSpoolProbeStatus(
                result = passing,
                progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.OFF,
                nowMs = nowMs,
                spoolDirectoryPath = path,
                targetVideoMbps = 80.0
            )
        )
    }
}
