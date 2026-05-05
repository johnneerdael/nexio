package com.nexio.tv.core.image

import android.net.Uri
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath

class IntegrationPosterFetcher(
    private val request: IntegrationPosterRequest,
    private val options: coil.request.Options,
    private val rpdbProvider: RpdbIntegrationProvider,
    private val topPostersProvider: TopPostersIntegrationProvider,
    private val fallbackTransport: PosterTransport,
    private val diskCache: DiskCache? = null
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = premiumBytes() ?: fallbackBytes() ?: return null
        val source = writeDiskCache(bytes) ?: createTempFileSource(bytes)
        return SourceResult(
            source = source,
            mimeType = request.mimeType ?: "image/jpeg",
            dataSource = DataSource.NETWORK
        )
    }

    private suspend fun premiumBytes(): ByteArray? =
        when (request) {
            is PosterIntegrationRequest -> when (request.provider) {
                IntegrationProvider.RPDB -> rpdbProvider.fetchPoster(request)
                IntegrationProvider.TOP_POSTERS -> topPostersProvider.fetchPoster(request)
                else -> null
            }
            is TopPostersThumbnailRequest -> topPostersProvider.fetchThumbnail(request)
        }

    private fun fallbackBytes(): ByteArray? {
        val fallbackUrl = (request as? PosterIntegrationRequest)
            ?.fallbackUrl
            ?.trim()
            ?.takeIf(::isSafeFallbackUrl)
            ?: return null
        val result = runCatching { fallbackTransport.execute(fallbackUrl) }.getOrNull() ?: return null
        return result.body?.takeIf { result.isSuccessful }
    }

    private fun isSafeFallbackUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("integration-poster://", ignoreCase = true)) return false

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?: return false
        return host != "api.ratingposterdb.com" && host != "api.top-posters.com"
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun writeDiskCache(bytes: ByteArray): ImageSource? {
        val cache = diskCache ?: return null
        if (!options.diskCachePolicy.writeEnabled) return null
        val key = options.diskCacheKey?.takeIf { it.isNotBlank() } ?: return null
        val editor = cache.openEditor(key) ?: return null
        return try {
            cache.fileSystem.write(editor.metadata) {
                writeUtf8("")
            }
            cache.fileSystem.write(editor.data) {
                write(bytes)
            }
            val snapshot = editor.commitAndOpenSnapshot() ?: return null
            ImageSource(
                file = snapshot.data,
                fileSystem = cache.fileSystem,
                diskCacheKey = key,
                closeable = snapshot
            )
        } catch (error: Throwable) {
            runCatching { editor.abort() }
            null
        }
    }

    private fun createTempFileSource(bytes: ByteArray): ImageSource {
        val file = File.createTempFile("integration-poster-", ".img")
        file.writeBytes(bytes)
        return ImageSource(
            file = file.toOkioPath(),
            closeable = Closeable { file.delete() }
        )
    }

    @Singleton
    class Factory @Inject constructor(
        private val rpdbProvider: RpdbIntegrationProvider,
        private val topPostersProvider: TopPostersIntegrationProvider,
        private val fallbackTransport: PosterTransport
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val request = IntegrationPosterRequest.fromModel(data.toString()) ?: return null
            return IntegrationPosterFetcher(
                request = request,
                options = options,
                rpdbProvider = rpdbProvider,
                topPostersProvider = topPostersProvider,
                fallbackTransport = fallbackTransport,
                diskCache = imageLoader.diskCache
            )
        }
    }
}
