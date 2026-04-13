package com.nexio.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nexio.tv.core.sync.StartupSyncService
import com.nexio.tv.ui.screens.player.ObsoletePlaybackCacheCleanup
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NexioApplication : Application(), ImageLoaderFactory {
    @Inject lateinit var startupSyncService: StartupSyncService
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        appScope.launch {
            ObsoletePlaybackCacheCleanup.cleanup(cacheDir)
            retainPosterCacheOnStartup()
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

    private fun retainPosterCacheOnStartup() {
        // Home snapshots can reference older poster URLs after a cold start.
        // Let Coil's size-bounded disk cache and metadata-driven evictions decide what to drop.
    }
}
