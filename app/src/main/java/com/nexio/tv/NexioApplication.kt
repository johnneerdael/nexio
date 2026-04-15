package com.nexio.tv

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nexio.tv.core.image.ImageCacheTtlWorker
import com.nexio.tv.core.sync.StartupSyncService
import com.nexio.tv.core.tvdb.TvdbUpdateCoordinator
import com.nexio.tv.core.tvdb.TvdbUpdateTrigger
import com.nexio.tv.ui.screens.player.ObsoletePlaybackCacheCleanup
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NexioApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tvdbUpdateCoordinator: TvdbUpdateCoordinator
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        appScope.launch {
            ObsoletePlaybackCacheCleanup.cleanup(cacheDir)
            retainPosterCacheOnStartup()
            ImageCacheTtlWorker.evictExpiredEntries(this@NexioApplication)
        }

        val ttlWorkRequest = PeriodicWorkRequestBuilder<ImageCacheTtlWorker>(
            repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ImageCacheTtlWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            ttlWorkRequest
        )

        // Schedule periodic TVDB update checks and run startup catch-up
        tvdbUpdateCoordinator.schedulePeriodicUpdates(WorkManager.getInstance(this))
        appScope.launch {
            tvdbUpdateCoordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)
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
