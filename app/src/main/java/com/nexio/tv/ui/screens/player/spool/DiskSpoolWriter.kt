package com.nexio.tv.ui.screens.player.spool

import android.util.Log
import java.io.EOFException
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource

internal class DiskSpoolWriter(
    private val okHttpClient: OkHttpClient,
    private val chunkBytes: Int = 18 * 1024 * 1024,
    private val ioBufferBytes: Int = 512 * 1024
) {
    data class SourceMetadata(
        val contentLength: Long,
        val supportsRanges: Boolean
    )

    fun probe(url: String): SourceMetadata {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            response.body?.source()?.let { source ->
                try {
                    source.readByte()
                } catch (_: EOFException) {
                    // Ignore empty probe bodies.
                }
            }

            val contentRange = response.header("Content-Range")
            val contentLength = contentRange
                ?.substringAfter('/')
                ?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: -1L
            val supportsRanges = response.code == 206 ||
                contentRange != null ||
                response.header("Accept-Ranges")
                    ?.contains("bytes", ignoreCase = true) == true

            return SourceMetadata(
                contentLength = contentLength,
                supportsRanges = supportsRanges
            )
        }
    }

    internal interface SessionBridge {
        fun isClosed(): Boolean
        fun contiguousFrontierBytes(): Long
        fun consumePriorityPosition(): Long
        fun rebaseTo(position: Long)
        fun writeRange(start: Long, bytes: ByteArray, length: Int)
    }

    fun downloadUntil(
        url: String,
        session: DiskSpoolSession,
        targetFrontierBytes: Long
    ) {
        val metadata = probe(url)
        if (!metadata.supportsRanges || metadata.contentLength <= 0L) {
            throw IOException("Unable to spool $url: supportsRanges=${metadata.supportsRanges}, contentLength=${metadata.contentLength}")
        }

        session.setSourceMetadata(metadata.contentLength, metadata.supportsRanges)
        Log.d(
            TAG,
            "Starting disk spool download length=${metadata.contentLength} target=$targetFrontierBytes"
        )

        val bridge = SessionAdapter(session)
        var cursor = bridge.contiguousFrontierBytes()
        while (
            !bridge.isClosed() &&
            !Thread.currentThread().isInterrupted &&
            cursor < targetFrontierBytes &&
            cursor < metadata.contentLength
        ) {
            val priority = bridge.consumePriorityPosition()
            if (priority >= 0L) {
                if (!rebaseTo(bridge, priority)) {
                    return
                }
                cursor = priority
            }

            val endInclusive = minOf(cursor + chunkBytes - 1L, metadata.contentLength - 1L)
            cursor = downloadRangeIntoSession(url, cursor, endInclusive, bridge)
        }
    }

    internal fun downloadRangeIntoSession(
        source: BufferedSource,
        start: Long,
        endInclusive: Long,
        session: SessionBridge
    ): Long {
        var cursor = start
        val buffer = ByteArray(ioBufferBytes)
        while (!session.isClosed() && cursor <= endInclusive) {
            val priority = session.consumePriorityPosition()
            if (priority >= 0L) {
                if (!rebaseTo(session, priority)) {
                    return cursor
                }
                Log.d(TAG, "Rebasing spool window to priority position $priority")
                return priority
            }

            val maxRead = minOf(buffer.size.toLong(), endInclusive - cursor + 1L).toInt()
            val read = source.read(buffer, 0, maxRead)
            if (read <= 0) {
                return cursor
            }

            if (!writeRange(session, cursor, buffer, read)) {
                return cursor
            }
            cursor += read.toLong()
        }
        return cursor
    }

    private fun downloadRangeIntoSession(
        url: String,
        start: Long,
        endInclusive: Long,
        session: SessionBridge
    ): Long {
        var attempt = 1
        while (attempt <= 4 && !session.isClosed()) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$endInclusive")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected response ${response.code} for range $start-$endInclusive")
                    }

                    val source = response.body?.source()
                        ?: throw IOException("Missing response body for range $start-$endInclusive")
                    return downloadRangeIntoSession(source, start, endInclusive, session)
                }
            } catch (throwable: IOException) {
                if (session.isClosed() || attempt >= 4) {
                    throw throwable
                }
                try {
                    Thread.sleep(minOf(50L * attempt, 250L))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while retrying range $start-$endInclusive", interrupted)
                }
                attempt++
            }
        }
        return start
    }

    private fun rebaseTo(session: SessionBridge, position: Long): Boolean {
        return try {
            session.rebaseTo(position)
            true
        } catch (throwable: IllegalStateException) {
            if (session.isClosed()) {
                false
            } else {
                throw throwable
            }
        }
    }

    private fun writeRange(
        session: SessionBridge,
        start: Long,
        buffer: ByteArray,
        read: Int
    ): Boolean {
        return try {
            session.writeRange(start, buffer, read)
            true
        } catch (throwable: IllegalStateException) {
            if (session.isClosed()) {
                false
            } else {
                throw throwable
            }
        }
    }

    private class SessionAdapter(
        private val session: DiskSpoolSession
    ) : SessionBridge {
        override fun isClosed(): Boolean = session.isClosed()

        override fun contiguousFrontierBytes(): Long = session.contiguousFrontierBytes()

        override fun consumePriorityPosition(): Long = session.consumePriorityPosition()

        override fun rebaseTo(position: Long) {
            session.rebaseTo(position)
        }

        override fun writeRange(start: Long, bytes: ByteArray, length: Int) {
            session.writeRange(start, bytes, length)
        }
    }

    private companion object {
        const val TAG = "DiskSpoolWriter"
    }
}
