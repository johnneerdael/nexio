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

    fun shouldEnable(
        requested: Boolean,
        latestBadExitTimestampMs: Long,
        manualEnableTimestampMs: Long
    ): Boolean {
        return requested && latestBadExitTimestampMs <= manualEnableTimestampMs
    }

    fun latestBadExitTimestampMs(context: Context): Long {
        if (Build.VERSION.SDK_INT < 30) return 0L
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return 0L
        return activityManager
            .getHistoricalProcessExitReasons(null, 0, 5)
            .filter { exit ->
                exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    exit.reason == ApplicationExitInfo.REASON_SIGNALED
            }
            .maxOfOrNull { exit -> exit.timestamp }
            ?: 0L
    }

    fun hasRecentLowMemoryOrSignaledExit(context: Context): Boolean {
        return latestBadExitTimestampMs(context) > 0L
    }

    fun evaluate(
        context: Context,
        requested: Boolean,
        manualEnableTimestampMs: Long = 0L
    ): Decision {
        val latestBadExitTimestampMs = latestBadExitTimestampMs(context)
        val blockedByKillSwitch = requested && latestBadExitTimestampMs > manualEnableTimestampMs
        return Decision(
            enabled = shouldEnable(
                requested = requested,
                latestBadExitTimestampMs = latestBadExitTimestampMs,
                manualEnableTimestampMs = manualEnableTimestampMs
            ),
            blockedByKillSwitch = blockedByKillSwitch
        )
    }
}
