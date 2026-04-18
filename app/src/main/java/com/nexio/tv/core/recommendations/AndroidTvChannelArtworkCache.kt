package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import android.util.Log
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink

private const val CHANNEL_ARTWORK_CACHE_TAG = "AndroidTvChannelArtwork"

@Singleton
class AndroidTvChannelArtworkCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun cachedPosterUri(item: MetaPreview): Uri? {
        val request = posterRequest(context, item) ?: return null
        val targetFile = AndroidTvChannelArtwork.posterFile(context, request.diskCacheKey)
        if (targetFile.isFile && targetFile.length() > 0L) {
            return request.localUri
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                fetchWithCoil(request)
                copyCoilDiskCacheEntry(
                    diskCacheKey = request.diskCacheKey,
                    targetFile = targetFile
                )
                request.localUri.takeIf { targetFile.isFile && targetFile.length() > 0L }
            }.onFailure { error ->
                Log.w(
                    CHANNEL_ARTWORK_CACHE_TAG,
                    "Failed to prepare Android TV channel poster item=${item.id}",
                    error
                )
            }.getOrNull()
        }
    }

    private suspend fun fetchWithCoil(request: PosterRequest) {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(request.remoteUrl)
                .diskCacheKey(request.diskCacheKey)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun copyCoilDiskCacheEntry(
        diskCacheKey: String,
        targetFile: File
    ) {
        val diskCache = context.imageLoader.diskCache ?: return
        diskCache.openSnapshot(diskCacheKey)?.use { snapshot ->
            copyPathToFile(snapshot.data, targetFile)
        }
    }

    private fun copyPathToFile(sourcePath: Path, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileSystem.SYSTEM.source(sourcePath).buffer().use { source ->
            tempFile.sink().buffer().use { sink ->
                sink.writeAll(source)
            }
        }
        if (tempFile.length() > 0L) {
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }
    }

    data class PosterRequest(
        val remoteUrl: String,
        val diskCacheKey: String,
        val localUri: Uri
    )

    companion object {
        fun posterRequest(context: Context, item: MetaPreview): PosterRequest? {
            val posterUrl = item.poster?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val diskCacheKey = ArtworkImageCacheKeys.poster(
                itemId = item.id,
                providerTag = item.posterProviderTag,
                posterUrl = posterUrl
            )
            return PosterRequest(
                remoteUrl = posterUrl,
                diskCacheKey = diskCacheKey,
                localUri = AndroidTvChannelArtwork.posterUri(context, diskCacheKey)
            )
        }
    }
}
