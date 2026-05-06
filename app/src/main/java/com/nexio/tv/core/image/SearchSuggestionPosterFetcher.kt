package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath

class SearchSuggestionPosterFetcher(
    private val model: SearchSuggestionPosterModel,
    private val registry: SearchSuggestionPosterRegistry,
    private val transport: PosterTransport
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val url = registry.resolve(model) ?: return null
        val result = runCatching { transport.execute(url) }.getOrNull() ?: return null
        val bytes = result.body?.takeIf { result.isSuccessful } ?: return null
        return SourceResult(
            source = createTempFileSource(bytes),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK
        )
    }

    private fun createTempFileSource(bytes: ByteArray): ImageSource {
        val file = File.createTempFile("search-suggestion-poster-", ".img")
        file.writeBytes(bytes)
        return ImageSource(
            file = file.toOkioPath(),
            closeable = Closeable { file.delete() }
        )
    }

    @Singleton
    class Factory @Inject constructor(
        private val registry: SearchSuggestionPosterRegistry,
        private val transport: PosterTransport
    ) : Fetcher.Factory<SearchSuggestionPosterModel> {
        override fun create(
            data: SearchSuggestionPosterModel,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher =
            SearchSuggestionPosterFetcher(
                model = data,
                registry = registry,
                transport = transport
            )
    }
}
