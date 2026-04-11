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
    @Volatile
    private var cacheKey: String? = null

    val cacheDirectory: File
        get() = File(appContext.cacheDir, cacheDirectoryName)

    @get:VisibleForTesting
    val hasCacheInstance: Boolean
        get() = cache != null

    fun getOrCreateCache(): SimpleCache {
        synchronized(lock) {
            cache?.let { return it }
            val key = cacheDirectory.absolutePath
            val acquired = synchronized(sharedLock) {
                val entry = sharedCaches.getOrPut(key) {
                    CacheEntry(
                        cache = SimpleCache(
                            cacheDirectory,
                            LeastRecentlyUsedCacheEvictor(maxCacheBytes),
                            StandaloneDatabaseProvider(appContext)
                        )
                    )
                }
                entry.refCount++
                cacheKey = key
                entry.cache
            }
            cache = acquired
            return acquired
        }
    }

    fun release() {
        synchronized(lock) {
            val key = cacheKey ?: return
            cache = null
            cacheKey = null

            val cacheToRelease = synchronized(sharedLock) {
                val entry = sharedCaches[key] ?: return
                entry.refCount--
                if (entry.refCount <= 0) {
                    sharedCaches.remove(key)
                    entry.cache
                } else {
                    null
                }
            }
            cacheToRelease?.release()
        }
    }

    companion object {
        const val DEFAULT_CACHE_DIRECTORY_NAME = "stream-cache"
        const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L

        private val sharedLock = Any()
        private val sharedCaches = LinkedHashMap<String, CacheEntry>()
    }

    private data class CacheEntry(
        val cache: SimpleCache,
        var refCount: Int = 0
    )
}
