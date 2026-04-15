package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.tvdb.TvdbDiagnosticsRecorder
import com.nexio.tv.core.tvdb.TvdbReliabilityDiagnostic
import com.nexio.tv.core.tvdb.TvdbReliabilityReason
import com.nexio.tv.core.tvdb.sanitized
import com.nexio.tv.core.tvdb.structuredLogFields
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvdbDiagnosticsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvdb_diagnostics"
)

/**
 * Current snapshot of TVDB reliability diagnostic state.
 *
 * Each field holds the sanitized message from the most recent diagnostic
 * event for that category. Later UI projection consumes this without
 * changing producer wiring.
 */
data class TvdbDiagnosticsSnapshot(
    val lastProviderDecision: String? = null,
    val lastFallbackReason: String? = null,
    val lastUpdateRefreshStatus: String? = null,
    val lastReferenceRefreshStatus: String? = null,
    val lastAirtimeReason: String? = null,
    val lastPosterReason: String? = null,
    val lastTmdbSkipReason: String? = null,
    val lastInvalidCredentialStatus: String? = null,
    val lastStaleCacheStatus: String? = null
)

/**
 * Concrete [TvdbDiagnosticsRecorder] backed by Preferences DataStore.
 *
 * Stores a bounded current snapshot of the most recent diagnostic event
 * per category. All writes go through [sanitized] before persistence or
 * logging to ensure no secrets leak.
 */
@Singleton
class TvdbDiagnosticsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) : TvdbDiagnosticsRecorder {

    private val dataStore = context.tvdbDiagnosticsDataStore

    private val lastProviderDecisionKey = stringPreferencesKey("last_provider_decision")
    private val lastFallbackReasonKey = stringPreferencesKey("last_fallback_reason")
    private val lastUpdateRefreshStatusKey = stringPreferencesKey("last_update_refresh_status")
    private val lastReferenceRefreshStatusKey = stringPreferencesKey("last_reference_refresh_status")
    private val lastAirtimeReasonKey = stringPreferencesKey("last_airtime_reason")
    private val lastPosterReasonKey = stringPreferencesKey("last_poster_reason")
    private val lastTmdbSkipReasonKey = stringPreferencesKey("last_tmdb_skip_reason")
    private val lastInvalidCredentialStatusKey = stringPreferencesKey("last_invalid_credential_status")
    private val lastStaleCacheStatusKey = stringPreferencesKey("last_stale_cache_status")

    val snapshot: Flow<TvdbDiagnosticsSnapshot> = dataStore.data.map { prefs ->
        TvdbDiagnosticsSnapshot(
            lastProviderDecision = prefs[lastProviderDecisionKey],
            lastFallbackReason = prefs[lastFallbackReasonKey],
            lastUpdateRefreshStatus = prefs[lastUpdateRefreshStatusKey],
            lastReferenceRefreshStatus = prefs[lastReferenceRefreshStatusKey],
            lastAirtimeReason = prefs[lastAirtimeReasonKey],
            lastPosterReason = prefs[lastPosterReasonKey],
            lastTmdbSkipReason = prefs[lastTmdbSkipReasonKey],
            lastInvalidCredentialStatus = prefs[lastInvalidCredentialStatusKey],
            lastStaleCacheStatus = prefs[lastStaleCacheStatusKey]
        )
    }

    override suspend fun record(diagnostic: TvdbReliabilityDiagnostic) {
        val sanitized = diagnostic.sanitized()
        val statusText = sanitized.message ?: sanitized.reason.code

        // Emit structured Android log from sanitized fields only
        val fields = sanitized.structuredLogFields()
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })

        dataStore.edit { prefs ->
            when (sanitized.reason) {
                TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN ->
                    prefs[lastProviderDecisionKey] = statusText

                TvdbReliabilityReason.EXPLICIT_FALLBACK ->
                    prefs[lastFallbackReasonKey] = statusText

                TvdbReliabilityReason.UPDATE_REFRESH_STARTED,
                TvdbReliabilityReason.UPDATE_REFRESH_SUCCEEDED,
                TvdbReliabilityReason.UPDATE_REFRESH_FAILED ->
                    prefs[lastUpdateRefreshStatusKey] = statusText

                TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED,
                TvdbReliabilityReason.REFERENCE_REFRESH_FAILED ->
                    prefs[lastReferenceRefreshStatusKey] = statusText

                TvdbReliabilityReason.MISSING_AIRS_TIME,
                TvdbReliabilityReason.DATE_ONLY_GATING ->
                    prefs[lastAirtimeReasonKey] = statusText

                TvdbReliabilityReason.POSTER_RATINGS_OVERRIDE ->
                    prefs[lastPosterReasonKey] = statusText

                TvdbReliabilityReason.TMDB_TV_FETCH_SKIPPED ->
                    prefs[lastTmdbSkipReasonKey] = statusText

                TvdbReliabilityReason.INVALID_CREDENTIALS ->
                    prefs[lastInvalidCredentialStatusKey] = statusText

                TvdbReliabilityReason.STALE_CACHE_SERVED ->
                    prefs[lastStaleCacheStatusKey] = statusText

                TvdbReliabilityReason.NETWORK_CALL_BLOCKED,
                TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT -> {
                    // Logged but not persisted to bounded snapshot
                }
            }
        }
    }

    companion object {
        private const val TAG = "TvdbReliability"
    }
}
