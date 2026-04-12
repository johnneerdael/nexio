package com.nexio.tv.ui.screens.player

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

@Suppress("DEPRECATION")
internal class StreamingCacheMemoryPressureMonitor(
    context: Context,
    private val onMemoryPressure: () -> Unit
) : ComponentCallbacks2 {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var started = false

    fun start() {
        synchronized(lock) {
            if (started) return
            appContext.registerComponentCallbacks(this)
            started = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            appContext.unregisterComponentCallbacks(this)
            started = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        onMemoryPressure()
    }

    override fun onTrimMemory(level: Int) {
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            onMemoryPressure()
        }
    }
}
