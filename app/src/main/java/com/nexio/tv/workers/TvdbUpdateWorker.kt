package com.nexio.tv.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexio.tv.core.tvdb.TvdbUpdateCoordinator
import com.nexio.tv.core.tvdb.TvdbUpdateCoordinatorResult
import com.nexio.tv.core.tvdb.TvdbUpdateTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager periodic worker for TVDB update checks (D-03).
 *
 * Delegates to [TvdbUpdateCoordinator.catchUpUpdates] with [TvdbUpdateTrigger.WORKER].
 *
 * Returns [Result.success] for both success and blocked-invalid-credentials outcomes
 * to prevent WorkManager retry storms (T-10-02-01). Returns [Result.retry] only for
 * transient network/update failures so WorkManager's backoff policy handles retries.
 */
@HiltWorker
class TvdbUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: TvdbUpdateCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "TVDB update worker starting")

        return when (val result = coordinator.catchUpUpdates(TvdbUpdateTrigger.WORKER)) {
            is TvdbUpdateCoordinatorResult.Success -> {
                Log.d(TAG, "TVDB update worker succeeded: processed=${result.processedCount}")
                Result.success()
            }
            is TvdbUpdateCoordinatorResult.BlockedInvalidCredentials -> {
                // Return success to avoid retry storm (T-10-02-01).
                // Invalid credentials won't self-fix through retries.
                Log.d(TAG, "TVDB update worker blocked: invalid credentials, returning success to avoid retry storm")
                Result.success()
            }
            is TvdbUpdateCoordinatorResult.Failed -> {
                // Transient failure -- let WorkManager retry with backoff
                Log.d(TAG, "TVDB update worker failed: ${result.error}, scheduling retry")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "TvdbUpdateWorker"
    }
}
