package com.nexio.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nexio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NexioApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var startupSyncService: StartupSyncService
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runPosterCacheCleanup()
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .bitmapFactoryMaxParallelism(2)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    private fun runPosterCacheCleanup() {
        val imageCacheDir = cacheDir.resolve("image_cache")
        if (!imageCacheDir.exists()) return

        val prefs = getSharedPreferences("poster_cache_gc", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong("last_run_ms", 0L)
        val minIntervalMs = TimeUnit.HOURS.toMillis(6)
        if (now - lastRun < minIntervalMs) return

        val maxAgeMs = TimeUnit.HOURS.toMillis(72)
        val cutoff = now - maxAgeMs
        imageCacheDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                if (file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        prefs.edit().putLong("last_run_ms", now).apply()
    }
}
