package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobbleRejectionReporter @Inject constructor() {
    fun reportRejection(
        contentId: String,
        reason: ScrobbleRejectionReason,
        provider: TrackingProvider,
    ) {
        Log.w(LOG_TAG, "scrobble.rejected provider=$provider reason=$reason contentId=$contentId")
    }

    companion object {
        private const val LOG_TAG = "ScrobbleRejection"
    }
}
