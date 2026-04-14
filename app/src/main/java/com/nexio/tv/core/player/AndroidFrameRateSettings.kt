package com.nexio.tv.core.player

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
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
        return statusFromPlatformPreference(readPlatformPreference(context))
    }

    private fun readPlatformPreference(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val preference = runCatching {
                displayManager?.matchContentFrameRateUserPreference
            }.getOrNull()
            if (preference != null) {
                return preference
            }
        }

        return runCatching {
            Settings.Secure.getInt(context.contentResolver, MATCH_CONTENT_FRAME_RATE)
        }.getOrNull()
    }

    private fun statusFromPlatformPreference(value: Int?): Status {
        return when (value) {
            0 -> Status.Disabled
            1 -> Status.SeamlessOnly
            2 -> Status.Always
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
        return canRequestFrameRate(readStatus(context))
    }

    fun canRequestFrameRate(context: Context): Boolean {
        return canRequestFrameRate(readStatus(context))
    }

    private fun canRequestFrameRate(status: Status): Boolean {
        return status == Status.SeamlessOnly || status == Status.Always
    }

    fun displaySettingsIntent(): Intent {
        return Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun statusLabelForTests(status: Status): String = statusLabel(status)
    internal fun statusFromPlatformPreferenceForTests(value: Int?): Status =
        statusFromPlatformPreference(value)

    internal fun canRequestFrameRateForTests(status: Status): Boolean = canRequestFrameRate(status)
}
