package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TvdbCompanyTypeRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Canonical TVDB reference data kinds that map to stable TVDB reference endpoints.
 *
 * Core references are warmed during startup after credential gating (D-06)
 * and refreshed when update events touch reference entity types (D-04).
 */
enum class TvdbReferenceKind(val cacheKey: String, val updateEntityType: String?) {
    ARTWORK_TYPES("artwork_types", "artworktypes"),
    ARTWORK_STATUSES("artwork_statuses", null),
    GENRES("genres", "genres"),
    LANGUAGES("languages", "languages"),
    SERIES_STATUSES("series_statuses", null),
    CONTENT_RATINGS("content_ratings", "content_ratings"),
    SEASON_TYPES("season_types", "seasontypes"),
    SOURCE_TYPES("source_types", "sourcetypes"),
    ENTITY_TYPES("entity_types", "entity_types"),
    COMPANY_TYPES("company_types", "company_types")
}

/**
 * All reference kinds that should be warmed during app-start catch-up (D-06).
 */
val CORE_REFERENCE_KINDS: Set<TvdbReferenceKind> = TvdbReferenceKind.entries.toSet()

/**
 * Maps TVDB update entity type strings to [TvdbReferenceKind].
 * Used by [TvdbCacheInvalidator] to route update events to reference refresh.
 */
private val UPDATE_ENTITY_TYPE_TO_KIND: Map<String, TvdbReferenceKind> = buildMap {
    for (kind in TvdbReferenceKind.entries) {
        kind.updateEntityType?.let { put(it, kind) }
    }
}

/**
 * Result of warming all core reference data kinds.
 */
data class TvdbReferenceWarmResult(
    val success: Boolean,
    val succeededKinds: Set<TvdbReferenceKind>,
    val failedKinds: Set<TvdbReferenceKind>
)

/**
 * Result of refreshing a single reference data kind.
 */
data class TvdbReferenceRefreshResult(
    val success: Boolean,
    val kind: TvdbReferenceKind,
    val staleFallback: Boolean = false,
    val itemCount: Int = 0
)

/**
 * TVDB reference data service with long-lived cache, startup warming,
 * update-driven refresh, and last-known-good fallback (D-04, D-05, D-06).
 *
 * Reference data is stored under `tvdb_ref::` namespaces with schema-version
 * guards. On refresh failure, stale cached values are served and
 * REFERENCE_REFRESH_FAILED is recorded through [TvdbDiagnosticsRecorder].
 *
 * Emits structured logs using [TvdbReliabilityDiagnostic.structuredLogFields] only.
 * Does not log credentials, headers, API keys, PINs, bearer tokens,
 * raw response bodies, or full request URLs.
 */
@Singleton
class TvdbReferenceDataService @Inject constructor(
    private val provider: TvdbIntegrationProvider,
    private val cacheStore: MetadataDiskCacheStore,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder
) {
    companion object {
        private const val TAG = "TvdbRefDataService"
    }

    /**
     * Warms all [CORE_REFERENCE_KINDS] by fetching from TVDB and writing to cache (D-06).
     *
     * Records REFERENCE_REFRESH_SUCCEEDED on overall success or
     * REFERENCE_REFRESH_FAILED per individual kind failure.
     * A warm failure for one kind does not block other kinds.
     */
    suspend fun warmCoreReferences(): TvdbReferenceWarmResult {
        val succeeded = mutableSetOf<TvdbReferenceKind>()
        val failed = mutableSetOf<TvdbReferenceKind>()

        for (kind in CORE_REFERENCE_KINDS) {
            val result = refresh(kind)
            if (result.success) {
                succeeded.add(kind)
            } else {
                failed.add(kind)
            }
        }

        val overallSuccess = failed.isEmpty()
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = if (overallSuccess) {
                TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED
            } else {
                TvdbReliabilityReason.REFERENCE_REFRESH_FAILED
            },
            surface = "reference_data_service",
            message = "Warmed ${succeeded.size}/${CORE_REFERENCE_KINDS.size} reference kinds" +
                if (failed.isNotEmpty()) ", failed: ${failed.joinToString { it.cacheKey }}" else ""
        )
        diagnosticsRecorder.record(diagnostic)
        emitStructuredLog(diagnostic)

        return TvdbReferenceWarmResult(
            success = overallSuccess,
            succeededKinds = succeeded,
            failedKinds = failed
        )
    }

    /**
     * Refreshes a single reference kind from TVDB (D-04).
     *
     * On success, writes the validated records to the `tvdb_ref::` cache and
     * records REFERENCE_REFRESH_SUCCEEDED.
     *
     * On failure, serves stale cached data when present (D-05) and records
     * REFERENCE_REFRESH_FAILED. Never returns raw IDs as labels.
     *
     * Validates each record: ID must be positive (or nonblank for string IDs)
     * and display label/name must be nonblank. If validation fails, does not
     * write the payload and returns stale cache when available (T-10-03-01).
     */
    suspend fun refresh(kind: TvdbReferenceKind): TvdbReferenceRefreshResult {
        return try {
            val records = fetchRecords(kind)
            if (records == null) {
                return handleRefreshFailure(kind, "API returned error or null body")
            }

            val validated = validateRecords(kind, records)
            if (validated.size != records.size) {
                return handleRefreshFailure(kind, "Payload validation failed")
            }

            cacheStore.writeTvdbReference(kind.cacheKey, validated)
            cacheStore.flushPendingWritesForTest()

            val successDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED,
                surface = "reference_data_service",
                message = "Refreshed ${kind.cacheKey} with ${validated.size} records"
            )
            diagnosticsRecorder.record(successDiagnostic)
            emitStructuredLog(successDiagnostic)

            TvdbReferenceRefreshResult(
                success = true,
                kind = kind,
                itemCount = validated.size
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleRefreshFailure(kind, e.message ?: "Unknown error")
        }
    }

    /**
     * Refreshes the reference kind matching the given TVDB update entity type (D-04).
     *
     * Returns null if the entity type does not map to a reference kind.
     * Used by [TvdbCacheInvalidator] to refresh instead of only deleting
     * `tvdb_ref::` keys when reference entity types appear in updates.
     */
    suspend fun refreshForUpdateEntityType(entityType: String): TvdbReferenceRefreshResult? {
        val kind = UPDATE_ENTITY_TYPE_TO_KIND[entityType.trim().lowercase()] ?: return null
        return refresh(kind)
    }

    private suspend fun fetchRecords(kind: TvdbReferenceKind): List<Any>? {
        return when (kind) {
            TvdbReferenceKind.ARTWORK_TYPES,
            TvdbReferenceKind.ARTWORK_STATUSES,
            TvdbReferenceKind.GENRES,
            TvdbReferenceKind.LANGUAGES,
            TvdbReferenceKind.SERIES_STATUSES,
            TvdbReferenceKind.CONTENT_RATINGS,
            TvdbReferenceKind.SEASON_TYPES,
            TvdbReferenceKind.SOURCE_TYPES,
            TvdbReferenceKind.ENTITY_TYPES,
            TvdbReferenceKind.COMPANY_TYPES -> provider.fetchReferenceRecords(kind)
        }
    }

    /**
     * Validates reference records: IDs must be positive (int) or nonblank (string),
     * and display label/name must be nonblank (T-10-03-01).
     */
    private fun validateRecords(kind: TvdbReferenceKind, records: List<Any>): List<Any> {
        return records.filter { record ->
            when (record) {
                is com.nexio.tv.data.remote.api.TvdbArtworkTypeRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbArtworkStatusRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbGenreReferenceRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbLanguageRecord ->
                    !record.id.isNullOrBlank() && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbSeriesStatusRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbContentRatingRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbSeasonTypeReferenceRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbSourceTypeRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is com.nexio.tv.data.remote.api.TvdbEntityTypeRecord ->
                    record.id != null && record.id > 0 && !record.name.isNullOrBlank()
                is TvdbCompanyTypeRecord ->
                    record.companyTypeId != null && record.companyTypeId > 0 && !record.companyTypeName.isNullOrBlank()
                else -> false
            }
        }
    }

    /**
     * Handles a refresh failure by serving stale cached data (D-05) and recording
     * REFERENCE_REFRESH_FAILED (T-10-03-03).
     */
    private suspend fun handleRefreshFailure(
        kind: TvdbReferenceKind,
        errorMessage: String
    ): TvdbReferenceRefreshResult {
        val failDiagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.REFERENCE_REFRESH_FAILED,
            surface = "reference_data_service",
            message = "Failed to refresh ${kind.cacheKey}: $errorMessage"
        )
        diagnosticsRecorder.record(failDiagnostic)
        emitStructuredLog(failDiagnostic)

        // Check for stale cached data (D-05: last-known-good)
        val staleData = cacheStore.readTvdbReference<Any>(kind.cacheKey, Any::class.java)
        return if (staleData != null && staleData.isNotEmpty()) {
            val staleDiagnostic = TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.STALE_CACHE_SERVED,
                surface = "reference_data_service",
                message = "Serving stale ${kind.cacheKey} with ${staleData.size} records"
            )
            diagnosticsRecorder.record(staleDiagnostic)
            emitStructuredLog(staleDiagnostic)
            TvdbReferenceRefreshResult(
                success = false,
                kind = kind,
                staleFallback = true,
                itemCount = staleData.size
            )
        } else {
            TvdbReferenceRefreshResult(
                success = false,
                kind = kind,
                staleFallback = false,
                itemCount = 0
            )
        }
    }

    private fun emitStructuredLog(diagnostic: TvdbReliabilityDiagnostic) {
        val fields = diagnostic.structuredLogFields()
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }
}
