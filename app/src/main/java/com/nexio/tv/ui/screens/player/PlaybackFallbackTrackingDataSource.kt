package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal class PlaybackFallbackTrackingDataSource(
    private val upstream: DataSource,
    private val coordinator: StreamingRangeCoordinator,
    private val defaultFallbackBytes: Long = DEFAULT_FALLBACK_BYTES
) : DataSource {
    private var ownershipToken: String? = null

    override fun open(dataSpec: DataSpec): Long {
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            dataSpec.position + defaultFallbackBytes
        } else {
            dataSpec.position + dataSpec.length
        }
        ownershipToken = coordinator.markFallbackOwned(dataSpec.position, end)
        try {
            return upstream.open(dataSpec)
        } catch (error: Throwable) {
            ownershipToken?.let(coordinator::clearFallbackOwnership)
            ownershipToken = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            ownershipToken?.let(coordinator::clearFallbackOwnership)
            ownershipToken = null
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_BYTES = 16L * 1024L * 1024L
    }
}
