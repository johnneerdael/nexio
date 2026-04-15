package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TvdbValidationStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "TvdbProviderFallback"

sealed class TvdbProviderDecision {
    data object UseTvdb : TvdbProviderDecision()
    data class UseFallback(val reason: String) : TvdbProviderDecision()
}

@Singleton
class TvdbProviderFallback @Inject constructor(
    private val settingsDataStore: TvdbSettingsDataStore
) {
    suspend fun decide(): TvdbProviderDecision {
        val settings = settingsDataStore.settings.first()
        return when {
            !settings.enabled || !settings.configured -> TvdbProviderDecision.UseFallback(REASON_NOT_CONFIGURED)
            settings.validationStatus == TvdbValidationStatus.VALID -> TvdbProviderDecision.UseTvdb
            settings.validationStatus == TvdbValidationStatus.INVALID ->
                TvdbProviderDecision.UseFallback(REASON_INVALID_CREDENTIALS)
            else -> TvdbProviderDecision.UseFallback(REASON_AUTH_UNAVAILABLE)
        }
    }

    suspend fun recordFallback(reason: String) {
        val sanitizedReason = sanitizeFallbackReason(reason)
        settingsDataStore.saveValidationFailure(
            status = TvdbValidationStatus.FALLBACK_ACTIVE,
            lastFailure = sanitizedReason
        )
        Log.w(TAG, "TVDB fallback: $sanitizedReason")
    }

    private fun sanitizeFallbackReason(reason: String): String {
        return when (reason) {
            REASON_NOT_CONFIGURED,
            REASON_INVALID_CREDENTIALS,
            REASON_AUTH_UNAVAILABLE,
            REASON_SERIES_NOT_FOUND -> reason
            else -> REASON_AUTH_UNAVAILABLE
        }
    }

    companion object {
        const val REASON_NOT_CONFIGURED = "not_configured"
        const val REASON_INVALID_CREDENTIALS = "invalid_credentials"
        const val REASON_AUTH_UNAVAILABLE = "auth_unavailable"
        const val REASON_SERIES_NOT_FOUND = "series_not_found"
    }
}
