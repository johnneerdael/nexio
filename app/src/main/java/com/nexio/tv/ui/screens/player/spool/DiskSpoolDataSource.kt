package com.nexio.tv.ui.screens.player.spool

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
internal class DiskSpoolDataSource(
    private val session: DiskSpoolSession,
    private val uri: Uri,
    private val contentLength: Long = C.LENGTH_UNSET.toLong(),
    private val randomAccessFallbackFactory: DataSource.Factory? = null,
    private val randomAccessBypassDistanceBytes: Long = RANDOM_ACCESS_BYPASS_DISTANCE_BYTES
) : DataSource {

    private val transferListeners = mutableListOf<TransferListener>()
    private var openedDataSpec: DataSpec? = null
    private var fallbackDataSource: DataSource? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var resolvedContentLength = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        fallbackDataSource?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (openedDataSpec != null || fallbackDataSource != null) {
            close()
        }
        if (session.isClosed()) {
            throw IOException("Disk spool session closed")
        }
        if (shouldBypassSpool(dataSpec.position)) {
            return openFallback(dataSpec)
        }

        openedDataSpec = dataSpec
        position = dataSpec.position
        resolvedContentLength = when {
            contentLength != C.LENGTH_UNSET.toLong() -> contentLength
            session.contentLengthBytes() != C.LENGTH_UNSET.toLong() -> session.contentLengthBytes()
            else -> C.LENGTH_UNSET.toLong()
        }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            resolvedContentLength != C.LENGTH_UNSET.toLong() -> (resolvedContentLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        transferListeners.forEach { it.onTransferInitializing(this, dataSpec, false) }
        transferListeners.forEach { it.onTransferStart(this, dataSpec, false) }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fallbackDataSource?.let { return it.read(buffer, offset, length) }

        while (true) {
            if (shouldEndOfInput()) {
                return C.RESULT_END_OF_INPUT
            }

            val readLength = if (remaining == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                minOf(length.toLong(), remaining).toInt()
            }

            val read = session.read(position, buffer, offset, readLength)
            if (read > 0) {
                position += read.toLong()
                if (remaining != C.LENGTH_UNSET.toLong()) {
                    remaining -= read.toLong()
                }

                val dataSpec = openedDataSpec ?: return read
                transferListeners.forEach { it.onBytesTransferred(this, dataSpec, false, read) }
                return read
            }

            if (read == 0) {
                continue
            }

            val fallback = openFallbackForCurrentPosition()
            if (fallback != null) {
                return fallback.read(buffer, offset, length)
            }
        }
    }

    override fun close() {
        fallbackDataSource?.close()
        fallbackDataSource = null
        val dataSpec = openedDataSpec
        if (dataSpec != null) {
            transferListeners.forEach { it.onTransferEnd(this, dataSpec, false) }
        }
        openedDataSpec = null
        position = 0L
        remaining = C.LENGTH_UNSET.toLong()
        resolvedContentLength = C.LENGTH_UNSET.toLong()
    }

    override fun getUri(): Uri? = fallbackDataSource?.uri ?: uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return fallbackDataSource?.responseHeaders ?: emptyMap()
    }

    private fun shouldBypassSpool(requestedPosition: Long): Boolean {
        if (randomAccessFallbackFactory == null) return false
        if (requestedPosition < session.windowStartBytes()) return true

        val frontier = session.contiguousFrontierBytes()
        val bypassDistance = randomAccessBypassDistanceBytes.coerceAtLeast(0L)
        return requestedPosition > frontier + bypassDistance
    }

    private fun openFallbackForCurrentPosition(): DataSource? {
        if (randomAccessFallbackFactory == null) return null
        val dataSpec = openedDataSpec ?: return null
        val fallbackSpec = dataSpec.buildUpon()
            .setPosition(position)
            .setLength(remaining)
            .build()
        endSpoolTransfer()
        return openFallbackDataSource(fallbackSpec)
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        openFallbackDataSource(dataSpec)
        openedDataSpec = null
        return remaining
    }

    private fun openFallbackDataSource(dataSpec: DataSpec): DataSource {
        val fallback = randomAccessFallbackFactory?.createDataSource() ?: error("Missing fallback factory")
        transferListeners.forEach(fallback::addTransferListener)
        fallbackDataSource = fallback
        val fallbackLength = fallback.open(dataSpec)
        position = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fallbackLength != C.LENGTH_UNSET.toLong() -> fallbackLength
            else -> C.LENGTH_UNSET.toLong()
        }
        return fallback
    }

    private fun endSpoolTransfer() {
        val dataSpec = openedDataSpec ?: return
        transferListeners.forEach { it.onTransferEnd(this, dataSpec, false) }
        openedDataSpec = null
    }

    private fun shouldEndOfInput(): Boolean {
        if (remaining == 0L) return true
        if (session.isClosed()) return true
        val knownContentLength = currentKnownContentLength()
        if (knownContentLength != C.LENGTH_UNSET.toLong() && position >= knownContentLength) return true
        return false
    }

    private fun currentKnownContentLength(): Long {
        return when {
            contentLength != C.LENGTH_UNSET.toLong() -> contentLength
            session.contentLengthBytes() != C.LENGTH_UNSET.toLong() -> session.contentLengthBytes()
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    internal class Factory(
        private val session: DiskSpoolSession,
        private val uri: Uri,
        private val contentLength: Long = C.LENGTH_UNSET.toLong(),
        private val randomAccessFallbackFactory: DataSource.Factory? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return DiskSpoolDataSource(
                session = session,
                uri = uri,
                contentLength = contentLength,
                randomAccessFallbackFactory = randomAccessFallbackFactory
            )
        }
    }

    private companion object {
        const val RANDOM_ACCESS_BYPASS_DISTANCE_BYTES = 64L * 1024L * 1024L
    }
}
