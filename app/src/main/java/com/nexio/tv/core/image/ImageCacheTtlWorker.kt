package com.nexio.tv.core.image

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File
import java.util.concurrent.TimeUnit

class ImageCacheTtlWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ImageCacheTtl"
        const val WORK_NAME = "image_cache_ttl_cleanup"
        val TTL_MS = TimeUnit.DAYS.toMillis(10)

        /**
         * Synchronous cleanup for use on startup. Evicts expired entries
         * from Coil's DiskCache directory.
         */
        @OptIn(ExperimentalCoilApi::class)
        fun evictExpiredEntries(context: Context) {
            val diskCache = context.imageLoader.diskCache ?: return
            val cacheDir = diskCache.directory.toFile()
            evictExpiredFromDir(cacheDir)
        }

        private fun evictExpiredFromDir(cacheDir: File) {
            val now = System.currentTimeMillis()
            var evictedCount = 0
            cacheDir.listFiles()?.forEach { file ->
                // Skip DiskLruCache journal and metadata files
                if (file.name == "journal" || file.name == "journal.tmp" ||
                    file.name == "journal.bkp" || file.isDirectory
                ) {
                    return@forEach
                }
                if (now - file.lastModified() > TTL_MS) {
                    if (file.delete()) {
                        evictedCount++
                    }
                }
            }
            if (evictedCount > 0) {
                Log.d(TAG, "Evicted $evictedCount expired image cache entries")
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun doWork(): Result {
        val diskCache = applicationContext.imageLoader.diskCache ?: return Result.success()
        val cacheDir = diskCache.directory.toFile()
        evictExpiredFromDir(cacheDir)
        return Result.success()
    }
}
