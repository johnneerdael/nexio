package com.nexio.tv.core.tvdb

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nexio.tv.data.local.TvdbUpdateStateStore
import com.nexio.tv.workers.TvdbUpdateWorker
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trigger source for TVDB update processing.
 */
enum class TvdbUpdateTrigger { STARTUP, WORKER }

/**
 * Result of a coordinated TVDB update attempt.
 */
sealed class TvdbUpdateCoordinatorResult {
    data class Success(val processedCount: Int, val skippedCount: Int) : TvdbUpdateCoordinatorResult()
    data object BlockedInvalidCredentials : TvdbUpdateCoordinatorResult()
    data class Failed(val error: String?) : TvdbUpdateCoordinatorResult()
}

/**
 * Orchestrates TVDB update processing with credential gating and diagnostics (D-03).
 *
 * Wraps [TvdbUpdateProcessor] with credential-health checks via [TvdbCredentialHealth]
 * and records coordinator-level diagnostics through [TvdbDiagnosticsRecorder].
 *
 * Both app-start catch-up and periodic WorkManager work route through this coordinator
 * to ensure consistent credential gating and diagnostic recording.
 *
 * Emits structured logs using [TvdbReliabilityDiagnostic.structuredLogFields] only.
 * Does not log Authorization headers, API keys, PINs, bearer tokens, raw responses,
 * or full request URLs.
 */
@Singleton
class TvdbUpdateCoordinator @Inject constructor(
    private val processor: TvdbUpdateProcessor,
    private val credentialHealth: TvdbCredentialHealth,
    private val updateStateStore: TvdbUpdateStateStore,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder
) {
    companion object {
        private const val TAG = "TvdbUpdateCoordinator"
        const val UNIQUE_WORK_NAME = "tvdb-update-refresh"
        val UPDATE_INTERVAL: Duration = Duration.ofHours(12)
    }

    /**
     * Runs a catch-up update cycle from the current cursor.
     *
     * Checks [TvdbCredentialHealth.canCallTvdb] before calling the processor.
     * If credentials are invalid, records NETWORK_CALL_BLOCKED and returns
     * [TvdbUpdateCoordinatorResult.BlockedInvalidCredentials] without calling
     * the processor.
     *
     * On success, marks credentials as valid. On processor failure, returns
     * [TvdbUpdateCoordinatorResult.Failed].
     */
    suspend fun catchUpUpdates(trigger: TvdbUpdateTrigger): TvdbUpdateCoordinatorResult {
        // Record that we're starting a refresh
        val startDiagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_STARTED,
            surface = "update_coordinator",
            message = "Catch-up update triggered by ${trigger.name}"
        )
        diagnosticsRecorder.record(startDiagnostic)
        emitStructuredLog(startDiagnostic)

        // Credential-health gate
        if (!credentialHealth.canCallTvdb()) {
            val blockedDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.NETWORK_CALL_BLOCKED,
                surface = "update_coordinator",
                message = "TVDB update blocked: credentials invalid or not configured"
            )
            diagnosticsRecorder.record(blockedDiagnostic)
            emitStructuredLog(blockedDiagnostic)
            return TvdbUpdateCoordinatorResult.BlockedInvalidCredentials
        }

        // Delegate to processor
        val result = processor.processSince()

        return if (result.success) {
            credentialHealth.markValid()
            val successDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_SUCCEEDED,
                surface = "update_coordinator",
                message = "Processed ${result.processedCount} events, skipped ${result.skippedCount}"
            )
            diagnosticsRecorder.record(successDiagnostic)
            emitStructuredLog(successDiagnostic)
            TvdbUpdateCoordinatorResult.Success(
                processedCount = result.processedCount,
                skippedCount = result.skippedCount
            )
        } else {
            val failDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
                surface = "update_coordinator",
                message = "Update processing failed: ${result.error}"
            )
            diagnosticsRecorder.record(failDiagnostic)
            emitStructuredLog(failDiagnostic)
            TvdbUpdateCoordinatorResult.Failed(result.error)
        }
    }

    /**
     * Schedules periodic TVDB update work via WorkManager.
     *
     * Uses [ExistingPeriodicWorkPolicy.UPDATE] with the unique name
     * [UNIQUE_WORK_NAME] and requires [NetworkType.CONNECTED].
     * The repeat interval is [UPDATE_INTERVAL] (12 hours).
     */
    fun schedulePeriodicUpdates(workManager: WorkManager) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<TvdbUpdateWorker>(UPDATE_INTERVAL)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(TAG, "Scheduled periodic TVDB updates with ${UPDATE_INTERVAL.toHours()}h interval")
    }

    private fun emitStructuredLog(diagnostic: TvdbReliabilityDiagnostic) {
        val fields = diagnostic.structuredLogFields()
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }
}
