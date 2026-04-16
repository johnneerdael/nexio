package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.metadata.MetadataProviderConfig
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TvdbValidationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential validity status for TVDB network-call gating.
 */
enum class TvdbCredentialStatus { UNKNOWN, VALID, INVALID }

/**
 * Current health state of TVDB credentials.
 *
 * Stores only status and a sanitized error text.
 * Does not store API key, PIN, bearer token, or Authorization header.
 */
data class TvdbCredentialHealthState(
    val status: TvdbCredentialStatus = TvdbCredentialStatus.UNKNOWN,
    val lastError: String? = null
)

/**
 * Tracks TVDB credential health to gate network calls (D-03, D-08).
 *
 * When credentials become invalid, [canCallTvdb] returns false to prevent
 * retry storms. Records [TvdbReliabilityReason.INVALID_CREDENTIALS] via
 * [TvdbDiagnosticsRecorder] on invalidation. Does not store API key, PIN,
 * bearer token, or Authorization header values.
 */
@Singleton
class TvdbCredentialHealth @Inject constructor(
    private val settingsDataStore: TvdbSettingsDataStore,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder,
    private val metadataApiKeyResolver: MetadataApiKeyResolver? = null
) {
    private val _state = MutableStateFlow(TvdbCredentialHealthState())

    /**
     * Observable credential health state.
     */
    val state: Flow<TvdbCredentialHealthState> = _state.asStateFlow()

    /**
     * Marks credentials as valid after a successful TVDB API call.
     */
    suspend fun markValid() {
        _state.value = TvdbCredentialHealthState(
            status = TvdbCredentialStatus.VALID,
            lastError = null
        )
    }

    /**
     * Marks credentials as invalid and records an INVALID_CREDENTIALS diagnostic.
     *
     * The [error] parameter must be a sanitized, user-safe message.
     * Does not store API key, PIN, bearer token, or Authorization header.
     */
    suspend fun markInvalid(error: String?) {
        _state.value = TvdbCredentialHealthState(
            status = TvdbCredentialStatus.INVALID,
            lastError = error
        )
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.INVALID_CREDENTIALS,
            surface = "credential_health",
            message = error ?: "TVDB credentials marked invalid"
        )
        diagnosticsRecorder.record(diagnostic)
        val fields = diagnostic.structuredLogFields()
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    /**
     * Returns true if TVDB network calls should be allowed.
     *
     * Returns false when:
     * - Credentials are marked INVALID (previous auth failure)
     * - TVDB is not enabled in settings
     * - API key is blank (not configured)
     *
     * Returns true when status is UNKNOWN (first run) or VALID.
     */
    suspend fun canCallTvdb(): Boolean {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) {
            return false
        }

        val credential = metadataApiKeyResolver?.tvdbCredential()
            ?: MetadataProviderConfig.resolveCredential(
                customApiKey = settings.apiKey,
                builtInApiKey = MetadataProviderConfig.builtInTvdbApiKey(),
                pin = settings.subscriberPin
            )
        if (credential.missing) {
            return false
        }

        // Check in-memory health state
        return _state.value.status != TvdbCredentialStatus.INVALID
    }

    companion object {
        private const val TAG = "TvdbCredentialHealth"
    }
}
