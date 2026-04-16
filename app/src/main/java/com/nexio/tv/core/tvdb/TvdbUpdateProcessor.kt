package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.local.TvdbUpdateStateStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbEntityUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a TVDB update processing run.
 */
data class TvdbUpdateProcessResult(
    val success: Boolean,
    val processedCount: Int = 0,
    val skippedCount: Int = 0,
    val highWatermark: Long? = null,
    val error: String? = null
)

/**
 * Processes TVDB `/updates` pages, invalidates affected TVDB cache keys,
 * and advances the cursor only after successful processing.
 *
 * Uses [TvdbDiagnosticsRecorder] for UPDATE_REFRESH_STARTED/SUCCEEDED/FAILED
 * diagnostics and emits structured Android logs from sanitized fields.
 * Does not log Authorization headers, API keys, PINs, bearer tokens,
 * full URLs, or raw response bodies.
 */
@Singleton
class TvdbUpdateProcessor @Inject constructor(
    private val api: TvdbApi,
    private val stateStore: TvdbUpdateStateStore,
    private val invalidator: TvdbCacheInvalidator,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder,
    private val authService: TvdbAuthService
) {
    companion object {
        private const val TAG = "TvdbUpdateProcessor"
    }

    /**
     * Fetches and processes all TVDB update pages starting from [since].
     *
     * For each valid event:
     * - methodInt 1 (create) or 2 (update) calls [TvdbCacheInvalidator.invalidateChanged]
     * - methodInt 3 (delete) calls [TvdbCacheInvalidator.invalidateDeletedOrMerged]
     *
     * Tracks the high watermark from non-null timeStamp values and calls
     * [TvdbUpdateStateStore.storeSuccessfulCursor] only after all pages and
     * invalidations complete successfully.
     *
     * On exception, records UPDATE_REFRESH_FAILED status and returns failure
     * without cursor advancement.
     */
    /**
     * Convenience overload that fetches the current cursor from the state store.
     */
    suspend fun processSince(): TvdbUpdateProcessResult {
        var cursor = stateStore.currentCursor()
        if (cursor <= 0L) {
            // First run: seed cursor to now (Unix seconds) so TVDB /updates doesn't 400.
            // This skips historical updates, but there is no local cache to invalidate yet.
            cursor = System.currentTimeMillis() / 1000
            stateStore.storeSuccessfulCursor(cursor)
            return TvdbUpdateProcessResult(success = true, processedCount = 0, skippedCount = 0)
        }
        return processSince(cursor)
    }

    suspend fun processSince(since: Long): TvdbUpdateProcessResult {
        // TVDB /updates requires a valid recent Unix timestamp in seconds.
        if (since <= 0L) {
            val seedCursor = System.currentTimeMillis() / 1000
            stateStore.storeSuccessfulCursor(seedCursor)
            Log.d(TAG, "First run: seeded update cursor to $seedCursor, skipping /updates call")
            return TvdbUpdateProcessResult(success = true, processedCount = 0, skippedCount = 0)
        }

        // Record start diagnostic
        val startDiagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_STARTED,
            surface = "update_processor",
            message = "Starting update refresh from cursor $since"
        )
        diagnosticsRecorder.record(startDiagnostic)
        emitStructuredLog(startDiagnostic)

        val bearerToken = authService.bearerToken()
        if (bearerToken == null) {
            val failDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
                surface = "update_processor",
                message = "No bearer token available for update refresh"
            )
            diagnosticsRecorder.record(failDiagnostic)
            emitStructuredLog(failDiagnostic)
            stateStore.recordStatus("failed", "No bearer token available")
            return TvdbUpdateProcessResult(
                success = false,
                error = "No bearer token available"
            )
        }

        try {
            var processedCount = 0
            var skippedCount = 0
            var highWatermark: Long? = null
            var currentPage: Int? = null

            do {
                val response = api.getUpdates(
                    authorization = bearerToken,
                    since = since,
                    type = null,
                    page = currentPage
                )

                if (!response.isSuccessful) {
                    val failDiagnostic = TvdbReliabilityDiagnostic(
                        reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
                        surface = "update_processor",
                        message = "API returned HTTP ${response.code()}"
                    )
                    diagnosticsRecorder.record(failDiagnostic)
                    emitStructuredLog(failDiagnostic)
                    stateStore.recordStatus("failed", "HTTP ${response.code()}")
                    return TvdbUpdateProcessResult(
                        success = false,
                        processedCount = processedCount,
                        skippedCount = skippedCount,
                        error = "HTTP ${response.code()}"
                    )
                }

                val body = response.body() ?: break
                val events = body.data

                for (event in events) {
                    if (!isValidEvent(event)) {
                        skippedCount++
                        // Record unknown update diagnostic for malformed event
                        val unknownDiagnostic = TvdbReliabilityDiagnostic(
                            reason = TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT,
                            surface = "update_processor",
                            tvdbId = event.recordId,
                            entityType = event.entityType,
                            message = "Skipping malformed update event"
                        )
                        diagnosticsRecorder.record(unknownDiagnostic)
                        emitStructuredLog(unknownDiagnostic)
                        continue
                    }

                    when (event.methodInt) {
                        1, 2 -> invalidator.invalidateChanged(event)
                        3 -> invalidator.invalidateDeletedOrMerged(event)
                        else -> {
                            skippedCount++
                            val unknownDiagnostic = TvdbReliabilityDiagnostic(
                                reason = TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT,
                                surface = "update_processor",
                                tvdbId = event.recordId,
                                entityType = event.entityType,
                                message = "Unknown methodInt=${event.methodInt}"
                            )
                            diagnosticsRecorder.record(unknownDiagnostic)
                            emitStructuredLog(unknownDiagnostic)
                            continue
                        }
                    }

                    processedCount++

                    // Track high watermark from non-null timestamps
                    val ts = event.timeStamp
                    if (ts != null && (highWatermark == null || ts > highWatermark)) {
                        highWatermark = ts
                    }
                }

                // Check for next page
                val nextPageUrl = body.links?.next
                currentPage = if (!nextPageUrl.isNullOrBlank()) {
                    extractPageNumber(nextPageUrl) ?: break
                } else {
                    null
                }
            } while (currentPage != null)

            // Advance cursor only after all pages and invalidations complete
            if (highWatermark != null) {
                stateStore.storeSuccessfulCursor(highWatermark)
            }

            stateStore.recordStatus("succeeded")

            val successDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_SUCCEEDED,
                surface = "update_processor",
                message = "Processed $processedCount events, skipped $skippedCount"
            )
            diagnosticsRecorder.record(successDiagnostic)
            emitStructuredLog(successDiagnostic)

            return TvdbUpdateProcessResult(
                success = true,
                processedCount = processedCount,
                skippedCount = skippedCount,
                highWatermark = highWatermark
            )
        } catch (e: Exception) {
            val failDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
                surface = "update_processor",
                message = "Update processing failed: ${e.message}"
            )
            diagnosticsRecorder.record(failDiagnostic)
            emitStructuredLog(failDiagnostic)
            stateStore.recordStatus("failed", e.message)

            return TvdbUpdateProcessResult(
                success = false,
                error = e.message
            )
        }
    }

    /**
     * Validates that an update event has the minimum required fields.
     * Events with null entityType, methodInt, or recordId are considered malformed.
     */
    private fun isValidEvent(event: TvdbEntityUpdate): Boolean {
        return !event.entityType.isNullOrBlank() &&
            event.methodInt != null &&
            event.recordId != null &&
            event.recordId > 0
    }

    /**
     * Extracts the page number from a pagination URL.
     * Falls back to incrementing if URL parsing fails.
     */
    private fun extractPageNumber(nextUrl: String): Int? {
        return runCatching {
            val pageParam = nextUrl.substringAfter("page=", "")
                .substringBefore("&")
                .toIntOrNull()
            pageParam
        }.getOrNull()
    }

    private fun emitStructuredLog(diagnostic: TvdbReliabilityDiagnostic) {
        val fields = diagnostic.structuredLogFields()
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }
}
