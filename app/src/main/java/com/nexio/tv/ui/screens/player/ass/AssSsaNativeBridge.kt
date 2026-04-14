package com.nexio.tv.ui.screens.player.ass

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import java.io.File

@Keep
object AssSsaNativeBridge {
    private const val TAG = "AssSsaNativeBridge"

    private val nativeLoadResult = runCatching {
        System.loadLibrary("assrender_direct")
    }.onFailure { throwable ->
        Log.w(TAG, "ASS/SSA native renderer unavailable; falling back to Media3 subtitles", throwable)
    }

    val nativeAvailable: Boolean = nativeLoadResult.isSuccess
    val nativeLoadError: String? = nativeLoadResult.exceptionOrNull()?.let { throwable ->
        "${throwable::class.java.simpleName}: ${throwable.message}"
    }

    fun configureFontconfig(context: Context): Boolean {
        return runCatching {
            val fontconfigDir = File(context.filesDir, "fontconfig")
            fontconfigDir.mkdirs()
            val cacheDir = File(context.cacheDir, "fontconfig")
            cacheDir.mkdirs()

            File(fontconfigDir, "fonts.conf").writeText(
                """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
<fontconfig>
    <dir>/system/fonts</dir>
    <cachedir>${cacheDir.absolutePath}</cachedir>
    <match target="pattern">
        <edit name="antialias" mode="assign"><bool>true</bool></edit>
        <edit name="hinting" mode="assign"><bool>true</bool></edit>
    </match>
</fontconfig>"""
            )

            Os.setenv("FONTCONFIG_PATH", fontconfigDir.absolutePath, true)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to configure ASS/SSA fontconfig", throwable)
        }.isSuccess
    }

    external fun nativeInit(width: Int, height: Int, fontScale: Float): Long

    external fun nativeLoadHeader(handle: Long, headerData: ByteArray): Int

    external fun nativeAddFont(handle: Long, name: String?, fontData: ByteArray)

    external fun nativeProcessChunk(handle: Long, data: ByteArray, startMs: Long, durationMs: Long)

    external fun nativeProcessData(handle: Long, data: ByteArray)

    external fun nativeRender(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean

    external fun nativeFlush(handle: Long)

    external fun nativeDestroy(handle: Long)
}
