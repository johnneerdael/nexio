package com.nexio.tv.ui.screens.detail

import android.util.Log
import com.nexio.tv.BuildConfig

internal const val DETAIL_FOCUS_LOG_TAG = "DetailFocus"

internal fun detailFocusDebug(message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching {
        Log.d(DETAIL_FOCUS_LOG_TAG, message)
    }
}
