package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
internal class StreamingCacheProvider(
    context: Context,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val cacheDirectoryName: String = DEFAULT_CACHE_DIRECTORY_NAME,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cache: SimpleCache? = null

    val cacheDirectory: File
        get() = File(appContext.cacheDir, cacheDirectoryName)

    @get:VisibleForTesting
    val hasCacheInstance: Boolean
        get() = cache != null

    fun getOrCreateCache(): SimpleCache {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val created = SimpleCache(
                cacheDirectory,
                LeastRecentlyUsedCacheEvictor(maxCacheBytes),
                StandaloneDatabaseProvider(appContext)
            )
            cache = created
            return created
        }
    }

    fun release() {
        synchronized(lock) {
            cache?.release()
            cache = null
        }
    }

    companion object {
        const val DEFAULT_CACHE_DIRECTORY_NAME = "stream-cache"
        const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L
    }
}
