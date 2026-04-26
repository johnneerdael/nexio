package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okio.Buffer

class IntegrationPosterFetcher(
    private val request: PosterIntegrationRequest,
    private val options: coil.request.Options,
    private val rpdbProvider: RpdbIntegrationProvider,
    private val topPostersProvider: TopPostersIntegrationProvider
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = when (request.provider) {
            IntegrationProvider.RPDB -> rpdbProvider.fetchPoster(request)
            IntegrationProvider.TOP_POSTERS -> topPostersProvider.fetchPoster(request)
            else -> null
        } ?: return null
        val file = File.createTempFile("integration-poster-", ".img")
        file.writeBytes(bytes)
        val source = createImageSource(bytes, file)
        return SourceResult(
            source = source,
            mimeType = request.mimeType ?: "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    private fun createImageSource(bytes: ByteArray, file: File): ImageSource {
        val factory = Class.forName("coil.decode.ImageSources")
        val create = factory.getMethod("create", okio.BufferedSource::class.java, File::class.java)
        return create.invoke(null, Buffer().write(bytes), file) as ImageSource
    }

    @Singleton
    class Factory @Inject constructor(
        private val rpdbProvider: RpdbIntegrationProvider,
        private val topPostersProvider: TopPostersIntegrationProvider
    ) : Fetcher.Factory<String> {
        override fun create(
            data: String,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val request = PosterIntegrationRequest.fromModel(data) ?: return null
            return IntegrationPosterFetcher(
                request = request,
                options = options,
                rpdbProvider = rpdbProvider,
                topPostersProvider = topPostersProvider
            )
        }
    }
}
