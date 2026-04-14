package com.nexio.tv.core.player

import android.content.Context
import android.content.Intent
import android.provider.Settings

object AndroidFrameRateSettings {
    private const val MATCH_CONTENT_FRAME_RATE = "match_content_frame_rate"

    enum class Status {
        Unknown,
        Disabled,
        SeamlessOnly,
        Always
    }

    fun readStatus(context: Context): Status {
        val value = runCatching {
            Settings.Secure.getInt(context.contentResolver, MATCH_CONTENT_FRAME_RATE, 0)
        }.getOrDefault(0)
        return when (value) {
            0 -> Status.Disabled
            1 -> Status.SeamlessOnly
            2 -> Status.Always
            3 -> Status.Always
            else -> Status.Unknown
        }
    }

    fun statusLabel(status: Status): String {
        return when (status) {
            Status.Unknown -> "Android: unknown"
            Status.Disabled -> "Android: disabled"
            Status.SeamlessOnly -> "Android: seamless only"
            Status.Always -> "Android: enabled"
        }
    }

    fun canRequestResolutionSwitch(context: Context): Boolean {
        return readStatus(context) != Status.Disabled
    }

    fun displaySettingsIntent(): Intent {
        return Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun statusLabelForTests(status: Status): String = statusLabel(status)
}
