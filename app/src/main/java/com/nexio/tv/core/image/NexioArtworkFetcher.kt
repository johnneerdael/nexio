package com.nexio.tv.core.image

import android.net.Uri
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
import okio.Path.Companion.toOkioPath

class NexioArtworkFetcher(
    private val assetKey: ArtworkAssetKey?,
    private val decisionKey: ArtworkDecisionKey?,
    private val repository: ArtworkAssetRepository
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val result = when {
            assetKey != null -> {
                val file = repository.getExistingFile(assetKey) ?: return null
                ArtworkFetchFile(file = file, mimeType = null)
            }
            decisionKey != null -> {
                val materialized = repository.getOrFetchDecision(decisionKey) ?: return null
                ArtworkFetchFile(file = materialized.localFile, mimeType = materialized.mimeType)
            }
            else -> return null
        }
        val source = createImageSource(result.file)
        return SourceResult(
            source = source,
            mimeType = result.mimeType,
            dataSource = DataSource.DISK
        )
    }

    private fun createImageSource(file: java.io.File): ImageSource {
        return ImageSource(file = file.toOkioPath())
    }

    private data class ArtworkFetchFile(
        val file: java.io.File,
        val mimeType: String?
    )

    @Singleton
    class Factory @Inject constructor(
        private val repository: ArtworkAssetRepository
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val model = data.toString()
            parseAssetKey(model)?.let { assetKey ->
                return NexioArtworkFetcher(
                    assetKey = assetKey,
                    decisionKey = null,
                    repository = repository
                )
            }
            parseDecisionKey(model)?.let { decisionKey ->
                return NexioArtworkFetcher(
                    assetKey = null,
                    decisionKey = decisionKey,
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
