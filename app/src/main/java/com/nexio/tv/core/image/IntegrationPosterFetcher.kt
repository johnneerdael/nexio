package com.nexio.tv.core.image

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.io.File
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okio.Buffer

class IntegrationPosterFetcher(
    private val request: PosterIntegrationRequest,
    private val options: coil.request.Options,
    private val rpdbProvider: RpdbIntegrationProvider,
    private val topPostersProvider: TopPostersIntegrationProvider,
    private val fallbackTransport: PosterTransport
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = premiumBytes() ?: fallbackBytes() ?: return null
        val file = File.createTempFile("integration-poster-", ".img")
        file.writeBytes(bytes)
        val source = createImageSource(bytes, file)
        return SourceResult(
            source = source,
            mimeType = request.mimeType ?: "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    private suspend fun premiumBytes(): ByteArray? =
        when (request.provider) {
            IntegrationProvider.RPDB -> rpdbProvider.fetchPoster(request)
            IntegrationProvider.TOP_POSTERS -> topPostersProvider.fetchPoster(request)
            else -> null
    }

    private fun fallbackBytes(): ByteArray? {
        val fallbackUrl = request.fallbackUrl?.trim()?.takeIf(::isSafeFallbackUrl) ?: return null
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

    private fun createImageSource(bytes: ByteArray, file: File): ImageSource {
        val factory = Class.forName("coil.decode.ImageSources")
        val create = factory.getMethod("create", okio.BufferedSource::class.java, File::class.java)
        return create.invoke(null, Buffer().write(bytes), file) as ImageSource
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
            val request = PosterIntegrationRequest.fromModel(data.toString()) ?: return null
            return IntegrationPosterFetcher(
                request = request,
                options = options,
                rpdbProvider = rpdbProvider,
                topPostersProvider = topPostersProvider,
                fallbackTransport = fallbackTransport
            )
        }
    }
}
