package com.nexio.tv.instrumentation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.HandlerThread
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP8 — periodic device-health sampler that emits the DEVICE family events
 * defined in spec §B (`memory_snapshot`, `thermal_status_changed`, `low_memory`).
 *
 * Lifetime is tied to the playback-trace MediaSourceSession via [start] /
 * [stop] called from [TransportValidationRuntimeCollector] alongside its own
 * begin/end. The sampler runs on its own [HandlerThread] so it never touches
 * the playback main thread or any of the hot-path threads.
 *
 *  - 1 Hz `memory_snapshot` (Java + native heap, `Debug.MemoryInfo`)
 *  - thermal listener via `PowerManager.addThermalStatusListener` (API 29+)
 *  - `low_memory` poll on each tick via `ActivityManager.MemoryInfo`
 *
 * All emits are best-effort: any failure is swallowed so instrumentation
 * cannot crash playback.
 */
internal class DeviceHealthSampler(private val appContext: Context) {

    private val running = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val activityManager: ActivityManager? =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val powerManager: PowerManager? =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = HandlerThread("PlaybackTrace-DeviceHealth").apply { start() }
        handlerThread = thread
        val handler = android.os.Handler(thread.looper)
        // Thermal listener (API 29+) — explicitly run the callback on the
        // sampler's HandlerThread so it never touches the main thread.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    PlaybackTracer.emit(EventFamily.DEVICE, "thermal_status_changed") {
                        putInt("status", status)
                    }
                }
                val executor = java.util.concurrent.Executor { command -> handler.post(command) }
                powerManager?.addThermalStatusListener(executor, listener)
                thermalListener = listener
            }
        }
        // Periodic memory + low-memory sampling at 1 Hz.
        handler.post(object : Runnable {
            override fun run() {
                if (!running.get()) return
                sampleOnce()
                handler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        })
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                runCatching { powerManager?.removeThermalStatusListener(listener) }
            }
            thermalListener = null
        }
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun sampleOnce() {
        runCatching {
            val javaHeapKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L
            val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            PlaybackTracer.emit(EventFamily.DEVICE, "memory_snapshot") {
                putLong("javaHeapKb", javaHeapKb)
                putLong("nativeHeapKb", nativeHeapKb)
                putInt("dalvikPss", mi.dalvikPss)
                putInt("nativePss", mi.nativePss)
                putInt("otherPss", mi.otherPss)
                putInt("totalPss", mi.totalPss)
            }
        }
        runCatching {
            val mem = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(mem)
            if (mem.lowMemory) {
                PlaybackTracer.emit(EventFamily.DEVICE, "low_memory") {
                    putLong("availMem", mem.availMem)
                    putLong("threshold", mem.threshold)
                }
            }
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS: Long = 1_000L
    }
}
