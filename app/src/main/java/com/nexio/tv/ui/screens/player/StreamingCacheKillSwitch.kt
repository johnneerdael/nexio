package com.nexio.tv.ui.screens.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
internal object StreamingCacheKillSwitch {
    internal data class Decision(
        val enabled: Boolean,
        val blockedByKillSwitch: Boolean
    )

    fun shouldEnable(
        requested: Boolean,
        hasRecentLowMemoryOrSignaledExit: Boolean
    ): Boolean {
        return requested && !hasRecentLowMemoryOrSignaledExit
    }

    fun hasRecentLowMemoryOrSignaledExit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager
            .getHistoricalProcessExitReasons(null, 0, 5)
            .any { exit ->
                exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    exit.reason == ApplicationExitInfo.REASON_SIGNALED
            }
    }

    fun evaluate(context: Context, requested: Boolean): Decision {
        val blockedByKillSwitch = hasRecentLowMemoryOrSignaledExit(context)
        return Decision(
            enabled = shouldEnable(
                requested = requested,
                hasRecentLowMemoryOrSignaledExit = blockedByKillSwitch
            ),
            blockedByKillSwitch = blockedByKillSwitch
        )
    }
}
