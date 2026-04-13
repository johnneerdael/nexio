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
    private val contentLength: Long = C.LENGTH_UNSET.toLong()
) : DataSource {

    private val transferListeners = mutableListOf<TransferListener>()
    private var openedDataSpec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var resolvedContentLength = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (openedDataSpec != null) {
            close()
        }
        if (session.isClosed()) {
            throw IOException("Disk spool session closed")
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
        }
    }

    override fun close() {
        val dataSpec = openedDataSpec
        if (dataSpec != null) {
            transferListeners.forEach { it.onTransferEnd(this, dataSpec, false) }
        }
        openedDataSpec = null
        position = 0L
        remaining = C.LENGTH_UNSET.toLong()
        resolvedContentLength = C.LENGTH_UNSET.toLong()
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    private fun shouldEndOfInput(): Boolean {
        if (remaining == 0L) return true
        if (session.isClosed()) return true
        val knownContentLength = currentKnownContentLength()
        if (knownContentLength != C.LENGTH_UNSET.toLong() && position >= knownContentLength) return true
        if (position < session.windowStartBytes()) return true
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
        private val contentLength: Long = C.LENGTH_UNSET.toLong()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return DiskSpoolDataSource(session, uri, contentLength)
        }
    }
}
