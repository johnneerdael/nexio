package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import javax.inject.Inject
import javax.inject.Singleton
import okio.buffer
import okio.source

class NexioArtworkFetcher(
    private val assetKey: ArtworkAssetKey?,
    private val options: coil.request.Options,
    private val repository: ArtworkAssetRepository
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val key = assetKey ?: return null
        val file = repository.getExistingFile(key) ?: return null
        val source = createImageSource(file)
        return SourceResult(
            source = source,
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    private fun createImageSource(file: java.io.File): ImageSource {
        val factory = Class.forName("coil.decode.ImageSources")
        val create = factory.getMethod("create", okio.BufferedSource::class.java, java.io.File::class.java)
        return create.invoke(null, file.source().buffer(), file) as ImageSource
    }

    @Singleton
    class Factory @Inject constructor(
        private val repository: ArtworkAssetRepository
    ) : Fetcher.Factory<String> {
        override fun create(
            data: String,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            parseAssetKey(data)?.let { assetKey ->
                return NexioArtworkFetcher(
                    assetKey = assetKey,
                    options = options,
                    repository = repository
                )
            }
            parseDecisionKey(data)?.let {
                return NexioArtworkFetcher(
                    assetKey = null,
                    options = options,
                    repository = repository
                )
            }
            return null
        }

        private fun parseAssetKey(data: String): ArtworkAssetKey? {
            val key = data.removePrefixOrNull(ASSET_URI_PREFIX)?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { ArtworkAssetKey(key) }.getOrNull()
        }

        private fun parseDecisionKey(data: String): ArtworkDecisionKey? {
            val key = data.removePrefixOrNull(DECISION_URI_PREFIX)?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { ArtworkDecisionKey(key) }.getOrNull()
        }

        private fun String.removePrefixOrNull(prefix: String): String? =
            takeIf { it.startsWith(prefix) }?.removePrefix(prefix)

        private companion object {
            const val ASSET_URI_PREFIX = "nexio-artwork://asset/"
            const val DECISION_URI_PREFIX = "nexio-artwork://decision/"
        }
    }
}
