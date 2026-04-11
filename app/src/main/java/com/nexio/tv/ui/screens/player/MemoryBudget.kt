package com.nexio.tv.ui.screens.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal class MemoryBudget(context: Context) {
    val heapLimitBytes: Long = Runtime.getRuntime().maxMemory()
    val memoryClassBytes: Long
    val largeMemoryClassBytes: Long
    val sampleQueueBudgetBytes: Long
    val effectiveSampleQueueBytes: Long
    val effectiveHeapBytes: Long = heapLimitBytes

    init {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        memoryClassBytes = activityManager?.memoryClass?.toLong()?.times(1024L * 1024L) ?: 0L
        largeMemoryClassBytes = activityManager?.largeMemoryClass?.toLong()?.times(1024L * 1024L) ?: 0L

        val reservedBytes = 80L * 1024L * 1024L +
            2L * 1024L * 1024L +
            5L * 1024L * 1024L +
            30L * 1024L * 1024L
        sampleQueueBudgetBytes = (heapLimitBytes - reservedBytes)
            .coerceIn(MIN_SAMPLE_QUEUE_BYTES, MAX_SAMPLE_QUEUE_BYTES)

        effectiveSampleQueueBytes = if (hasRecentLowMemoryExit(context)) {
            sampleQueueBudgetBytes * 7L / 10L
        } else {
            sampleQueueBudgetBytes
        }
    }

    fun fillWorkerWithinBudget(activeConnections: Int): Boolean {
        val allowedBytes = 2L * 1024L * 1024L
        val connectionCount = activeConnections.coerceAtLeast(0).toLong()
        return connectionCount * FILL_WORKER_READ_BUFFER_BYTES <= allowedBytes
    }

    private fun hasRecentLowMemoryExit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return activityManager
            .getHistoricalProcessExitReasons(null, 0, 5)
            .any { exit -> exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
    }

    companion object {
        const val FILL_WORKER_READ_BUFFER_BYTES = 512 * 1024
        const val MIN_SAMPLE_QUEUE_BYTES = 32L * 1024L * 1024L
        const val MAX_SAMPLE_QUEUE_BYTES = 350L * 1024L * 1024L
    }
}
