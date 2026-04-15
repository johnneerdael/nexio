package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TvdbMergeAliasStore
import com.nexio.tv.data.remote.api.TvdbEntityUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps TVDB entity update events to cache invalidation actions.
 *
 * Entity type mapping:
 * - `series` invalidates series metadata and derived season/episode entries for recordId
 * - `episodes` invalidates episode and season entries for seriesId when present, else episode entry for recordId
 * - `seasons` invalidates season/episode entries for seriesId when present
 * - `artwork` invalidates series metadata for seriesId when present
 * - Reference types (artworktypes, genres, languages, content_ratings, seasontypes,
 *   sourcetypes, entity_types, company_types) invalidate matching tvdb_ref:: entries
 * - Unknown or malformed events do not purge all TVDB data
 */
@Singleton
class TvdbCacheInvalidator @Inject constructor(
    private val cacheStore: MetadataDiskCacheStore,
    private val mergeAliasStore: TvdbMergeAliasStore,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder
) {
    companion object {
        private const val TAG = "TvdbCacheInvalidator"

        private val REFERENCE_ENTITY_TYPES = setOf(
            "artworktypes", "genres", "languages", "content_ratings",
            "seasontypes", "sourcetypes", "entity_types", "company_types"
        )
    }

    /**
     * Invalidates cached TVDB data for a created (methodInt=1) or updated (methodInt=2) event.
     */
    suspend fun invalidateChanged(event: TvdbEntityUpdate) {
        val entityType = event.entityType?.trim()?.lowercase()
        val recordId = event.recordId

        if (entityType.isNullOrBlank() || recordId == null || recordId <= 0) {
            recordUnknownEvent(event)
            return
        }

        when (entityType) {
            "series" -> {
                cacheStore.removeTvdbSeriesEntries(recordId)
                cacheStore.removeTvdbEpisodeEntries(recordId)
            }
            "episodes" -> {
                val seriesId = event.seriesId
                if (seriesId != null && seriesId > 0) {
                    cacheStore.removeTvdbEpisodeEntries(seriesId)
                    cacheStore.removeTvdbSeriesEntries(seriesId)
                } else {
                    // Episode-level invalidation without series context
                    Log.d(TAG, "Episode update without seriesId for recordId=$recordId")
                }
            }
            "seasons" -> {
                val seriesId = event.seriesId
                if (seriesId != null && seriesId > 0) {
                    cacheStore.removeTvdbSeriesEntries(seriesId)
                    cacheStore.removeTvdbEpisodeEntries(seriesId)
                }
            }
            "artwork" -> {
                val seriesId = event.seriesId
                if (seriesId != null && seriesId > 0) {
                    cacheStore.removeTvdbSeriesEntries(seriesId)
                }
            }
            in REFERENCE_ENTITY_TYPES -> {
                cacheStore.removeTvdbRefEntries(entityType)
            }
            else -> {
                recordUnknownEvent(event)
            }
        }
    }

    /**
     * Invalidates cached TVDB data for a deleted (methodInt=3) event.
     *
     * For merge events where mergeToId and mergeToEntityType are present,
     * purges old source cache entries, records a source-to-target alias via
     * TvdbMergeAliasStore, and emits a sanitized diagnostic.
     *
     * For delete events without merge target fields, purges source entries
     * and removes any existing alias for that source.
     */
    suspend fun invalidateDeletedOrMerged(event: TvdbEntityUpdate) {
        val entityType = event.entityType?.trim()?.lowercase()
        val recordId = event.recordId

        if (entityType.isNullOrBlank() || recordId == null || recordId <= 0) {
            recordUnknownEvent(event)
            return
        }

        // Purge source cache entries
        invalidateSourceEntries(entityType, recordId, event.seriesId)

        val mergeToId = event.mergeToId
        val mergeToEntityType = event.mergeToEntityType?.trim()?.lowercase()

        if (mergeToId != null && mergeToId > 0 && !mergeToEntityType.isNullOrBlank()) {
            // Merge event: record alias for read-path remapping
            mergeAliasStore.recordAlias(
                fromEntityType = entityType,
                fromId = recordId,
                toEntityType = mergeToEntityType,
                toId = mergeToId
            )
            Log.d(
                TAG,
                "Merge alias recorded: $entityType:$recordId -> $mergeToEntityType:$mergeToId"
            )
        } else {
            // Pure delete: remove any stale alias for this source
            mergeAliasStore.removeAlias(entityType, recordId)
        }
    }

    private fun invalidateSourceEntries(entityType: String, recordId: Int, seriesId: Int?) {
        when (entityType) {
            "series" -> {
                cacheStore.removeTvdbSeriesEntries(recordId)
                cacheStore.removeTvdbEpisodeEntries(recordId)
            }
            "episodes" -> {
                val sid = seriesId?.takeIf { it > 0 }
                if (sid != null) {
                    cacheStore.removeTvdbEpisodeEntries(sid)
                    cacheStore.removeTvdbSeriesEntries(sid)
                }
            }
            "seasons" -> {
                val sid = seriesId?.takeIf { it > 0 }
                if (sid != null) {
                    cacheStore.removeTvdbSeriesEntries(sid)
                    cacheStore.removeTvdbEpisodeEntries(sid)
                }
            }
            "artwork" -> {
                val sid = seriesId?.takeIf { it > 0 }
                if (sid != null) {
                    cacheStore.removeTvdbSeriesEntries(sid)
                }
            }
            in REFERENCE_ENTITY_TYPES -> {
                cacheStore.removeTvdbRefEntries(entityType)
            }
        }
    }

    private suspend fun recordUnknownEvent(event: TvdbEntityUpdate) {
        diagnosticsRecorder.record(
            TvdbReliabilityDiagnostic(
                reason = TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT,
                surface = "update_processor",
                tvdbId = event.recordId,
                entityType = event.entityType,
                message = "Unknown or malformed update event: entityType=${event.entityType}, " +
                    "methodInt=${event.methodInt}, recordId=${event.recordId}"
            )
        )
    }
}
