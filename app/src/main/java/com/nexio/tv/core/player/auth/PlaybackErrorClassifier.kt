package com.nexio.tv.core.player.auth

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * Maps playback failures to user-actionable categories while keeping the
 * recovery policy in the shared player auth layer.
 */
object PlaybackErrorClassifier {
    sealed class Classification(val userMessage: String) {
        data object LinkExpired : Classification(
            "Stream link expired or was revoked. Try selecting another source."
        )

        data object Forbidden : Classification(
            "Stream blocked by the debrid host. Try selecting another source."
        )

        data object Generic : Classification(
            "Playback failed. Try selecting another source."
        )
    }

    fun classify(exception: PlaybackException): Classification {
        var cause: Throwable? = exception.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    AuthFailureCodes.UNAUTHORIZED,
                    AuthFailureCodes.GONE -> Classification.LinkExpired
                    AuthFailureCodes.FORBIDDEN -> Classification.Forbidden
                    else -> Classification.Generic
                }
            }
            cause = cause.cause
        }
        return Classification.Generic
    }
}
