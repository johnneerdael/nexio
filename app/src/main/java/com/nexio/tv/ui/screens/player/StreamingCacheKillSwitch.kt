package com.nexio.tv.ui.screens.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
internal object StreamingCacheKillSwitch {
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
}
