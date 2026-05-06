package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toOkioPath

class LegacyRemoteArtworkFetcher(
    private val model: LegacyRemoteArtworkModel,
    private val transport: PosterTransport
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val result = try {
            transport.execute(model.url.value)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val bytes = result.body?.takeIf { result.isSuccessful } ?: return null
        return SourceResult(
            source = createTempFileSource(bytes),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK
        )
    }

    private fun createTempFileSource(bytes: ByteArray): ImageSource {
        val file = File.createTempFile("legacy-remote-artwork-", ".img")
        file.writeBytes(bytes)
        return ImageSource(
            file = file.toOkioPath(),
            closeable = Closeable { file.delete() }
        )
    }

    @Singleton
    class Factory @Inject constructor(
        private val transport: PosterTransport
    ) : Fetcher.Factory<LegacyRemoteArtworkModel> {
        override fun create(
            data: LegacyRemoteArtworkModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher =
            LegacyRemoteArtworkFetcher(
                model = data,
                transport = transport
            )
    }
}
