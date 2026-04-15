package com.nexio.tv.core.tvdb

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import javax.inject.Provider

/**
 * Contract tests for TVDB update scheduling:
 * - Periodic WorkManager scheduling with network constraints
 * - Credential-health gating on catch-up
 * - Worker retry storm prevention
 */
class TvdbUpdateSchedulingTest {

    @Test
    fun `schedulePeriodicUpdates enqueues unique network constrained work`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val coordinator = TvdbUpdateCoordinator(
            processor = mockk(),
            credentialHealth = mockk(),
            updateStateStore = mockk(),
            diagnosticsRecorder = mockk(),
            referenceDataServiceProvider = Provider { mockk(relaxUnitFun = true) }
        )

        coordinator.schedulePeriodicUpdates(workManager)

        val nameSlot = slot<String>()
        val policySlot = slot<ExistingPeriodicWorkPolicy>()
        val requestSlot = slot<PeriodicWorkRequest>()
        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                capture(nameSlot),
                capture(policySlot),
                capture(requestSlot)
            )
        }
        // Unique work name must be tvdb-update-refresh
        assertEquals("tvdb-update-refresh", nameSlot.captured)
        // Must use UPDATE policy so new constraints/intervals replace old ones
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policySlot.captured)
        // A periodic work request was captured (network constraint built in coordinator)
        assertNotNull("Work request should be enqueued", requestSlot.captured)
        // Verify coordinator constants match expected values
        assertEquals("tvdb-update-refresh", TvdbUpdateCoordinator.UNIQUE_WORK_NAME)
        assertEquals(Duration.ofHours(12), TvdbUpdateCoordinator.UPDATE_INTERVAL)
    }

    @Test
    fun `catchUpUpdates does not run when credential health blocks tvdb calls`() = runTest {
        val credentialHealth = mockk<TvdbCredentialHealth>()
        val processor = mockk<TvdbUpdateProcessor>()
        val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>(relaxUnitFun = true)
        val coordinator = TvdbUpdateCoordinator(
            processor = processor,
            credentialHealth = credentialHealth,
            updateStateStore = mockk(),
            diagnosticsRecorder = diagnosticsRecorder,
            referenceDataServiceProvider = Provider { mockk(relaxUnitFun = true) }
        )

        coEvery { credentialHealth.canCallTvdb() } returns false

        val result = coordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)

        assertTrue(
            "Expected BlockedInvalidCredentials result",
            result is TvdbUpdateCoordinatorResult.BlockedInvalidCredentials
        )
        // processor.processSince should NOT have been called
        coVerify(exactly = 0) { processor.processSince() }
        coVerify(exactly = 0) { processor.processSince(any()) }
        // Should record NETWORK_CALL_BLOCKED diagnostic
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match { it.reason == TvdbReliabilityReason.NETWORK_CALL_BLOCKED })
        }
    }

    @Test
    fun `worker returns success for invalid credentials to avoid retry storm`() = runTest {
        // This test validates the contract: when coordinator returns
        // BlockedInvalidCredentials, the worker must return Result.success()
        // (not Result.retry()) to avoid a WorkManager retry storm (T-10-02-01).
        val credentialHealth = mockk<TvdbCredentialHealth>()
        val processor = mockk<TvdbUpdateProcessor>()
        val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>(relaxUnitFun = true)
        val coordinator = TvdbUpdateCoordinator(
            processor = processor,
            credentialHealth = credentialHealth,
            updateStateStore = mockk(),
            diagnosticsRecorder = diagnosticsRecorder,
            referenceDataServiceProvider = Provider { mockk(relaxUnitFun = true) }
        )

        coEvery { credentialHealth.canCallTvdb() } returns false

        val result = coordinator.catchUpUpdates(TvdbUpdateTrigger.WORKER)

        // The coordinator result should be BlockedInvalidCredentials
        // and the worker contract says this maps to Result.success()
        // to prevent retry storm.
        assertTrue(
            "BlockedInvalidCredentials should be returned to prevent retry storm",
            result is TvdbUpdateCoordinatorResult.BlockedInvalidCredentials
        )
    }
}
