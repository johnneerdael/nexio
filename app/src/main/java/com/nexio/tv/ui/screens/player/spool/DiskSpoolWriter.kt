package com.nexio.tv.ui.screens.player.spool

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource

internal class DiskSpoolWriter(
    private val okHttpClient: OkHttpClient,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val chunkBytes: Int = 18 * 1024 * 1024,
    private val ioBufferBytes: Int = 512 * 1024,
    @Suppress("UNUSED_PARAMETER")
    parallelConnections: Int = 1,
    private val startupPriorityBytes: Long = 100L * 1024L * 1024L,
    private val ioBufferFactory: (Int) -> ByteArray = { ByteArray(it) },
    private val adaptiveHeadroomBytes: Long = DEFAULT_ADAPTIVE_HEADROOM_BYTES,
    private val idlePollMs: Long = DEFAULT_IDLE_POLL_MS
) {
    data class SourceMetadata(
        val contentLength: Long,
        val supportsRanges: Boolean
    )

    fun probe(
        url: String,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted }
    ): SourceMetadata {
        val callerThread = Thread.currentThread()
        fun isCancelled(): Boolean = callerThread.isInterrupted || !shouldContinue()

        if (isCancelled()) {
            throw IOException("Interrupted before disk spool metadata probe")
        }
        val request = requestBuilder(url)
            .header("Range", "bytes=0-0")
            .build()
        val call = okHttpClient.newCall(request)
        if (isCancelled()) {
            call.cancel()
            throw IOException("Interrupted before disk spool metadata probe")
        }

        return executeCancellable(
            call = call,
            isCancelled = ::isCancelled,
            monitorName = "DiskSpoolProbeCancellation",
            interruptedMessage = "Interrupted during disk spool metadata probe"
        ) { response ->
            if (isCancelled()) {
                call.cancel()
                throw IOException("Interrupted during disk spool metadata probe")
            }
            response.body?.source()?.let { source ->
                try {
                    source.readByte()
                } catch (_: EOFException) {
                    // Ignore empty probe bodies.
                }
            }
            if (isCancelled()) {
                call.cancel()
                throw IOException("Interrupted during disk spool metadata probe")
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

            SourceMetadata(
                contentLength = contentLength,
                supportsRanges = supportsRanges
            )
        }
    }

    private fun probeForSession(url: String, session: DiskSpoolSession): SourceMetadata? {
        val callerThread = Thread.currentThread()
        return try {
            probe(url) {
                !session.isClosed() && !callerThread.isInterrupted
            }
        } catch (throwable: IOException) {
            if (session.isClosed() || callerThread.isInterrupted) {
                null
            } else {
                throw throwable
            }
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
        if (session.isClosed() || Thread.currentThread().isInterrupted) {
            return
        }

        val metadata = probeForSession(url, session) ?: return
        if (session.isClosed() || Thread.currentThread().isInterrupted) {
            return
        }
        if (!metadata.supportsRanges || metadata.contentLength <= 0L) {
            throw IOException("Unable to spool $url: supportsRanges=${metadata.supportsRanges}, contentLength=${metadata.contentLength}")
        }

        session.setSourceMetadata(metadata.contentLength, metadata.supportsRanges)
        Log.d(
            TAG,
            "Starting disk spool download length=${metadata.contentLength} target=$targetFrontierBytes"
        )

        val bridge = SessionAdapter(session)
        val ioBuffer = ioBufferFactory(ioBufferBytes)
        val startupTargetBytes = minOf(
            startupPriorityBytes.coerceAtLeast(0L),
            targetFrontierBytes,
            metadata.contentLength
        )
        if (startupTargetBytes > bridge.contiguousFrontierBytes()) {
            downloadSequentially(
                url = url,
                bridge = bridge,
                targetFrontierBytes = startupTargetBytes,
                contentLength = metadata.contentLength,
                ioBuffer = ioBuffer
            )
        }

        val maxFrontier = minOf(targetFrontierBytes, metadata.contentLength)
        while (!session.isClosed() && !Thread.currentThread().isInterrupted) {
            val target = session.adaptiveTargetFrontierBytes(
                maxFrontierBytes = maxFrontier,
                startupPrebufferBytes = startupPriorityBytes,
                headroomBytes = adaptiveHeadroomBytes
            )
            val frontier = bridge.contiguousFrontierBytes()
            if (frontier >= target) {
                if (frontier >= maxFrontier) return
                val priority = bridge.consumePriorityPosition()
                if (priority >= 0L) {
                    if (!rebaseTo(bridge, priority)) {
                        return
                    }
                    continue
                }
                try {
                    Thread.sleep(idlePollMs.coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            downloadSequentially(
                url = url,
                bridge = bridge,
                targetFrontierBytes = target,
                contentLength = metadata.contentLength,
                ioBuffer = ioBuffer
            )
        }
    }

    private fun downloadSequentially(
        url: String,
        bridge: SessionBridge,
        targetFrontierBytes: Long,
        contentLength: Long,
        ioBuffer: ByteArray
    ) {
        var cursor = bridge.contiguousFrontierBytes()
        while (
            !bridge.isClosed() &&
            !Thread.currentThread().isInterrupted &&
            cursor < targetFrontierBytes &&
            cursor < contentLength
        ) {
            val priority = bridge.consumePriorityPosition()
            if (priority >= 0L) {
                if (!rebaseTo(bridge, priority)) {
                    return
                }
                cursor = priority
                if (cursor >= targetFrontierBytes) {
                    return
                }
            }

            val endInclusive = minOf(cursor + chunkBytes - 1L, targetFrontierBytes - 1L, contentLength - 1L)
            cursor = downloadRangeIntoSession(url, cursor, endInclusive, bridge, ioBuffer)
        }
    }

    internal fun downloadRangeIntoSession(
        source: BufferedSource,
        start: Long,
        endInclusive: Long,
        session: SessionBridge,
        buffer: ByteArray
    ): Long {
        var cursor = start
        while (!session.isClosed() && !Thread.currentThread().isInterrupted && cursor <= endInclusive) {
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
        session: SessionBridge,
        ioBuffer: ByteArray
    ): Long {
        val callerThread = Thread.currentThread()
        fun isCancelled(): Boolean = session.isClosed() || callerThread.isInterrupted

        var attempt = 1
        while (attempt <= 4 && !isCancelled()) {
            try {
                val request = requestBuilder(url)
                    .header("Range", "bytes=$start-$endInclusive")
                    .build()
                val call = okHttpClient.newCall(request)
                if (isCancelled()) {
                    call.cancel()
                    return start
                }

                return executeCancellable(
                    call = call,
                    isCancelled = ::isCancelled,
                    monitorName = "DiskSpoolRangeCancellation",
                    interruptedMessage = "Interrupted during range $start-$endInclusive"
                ) { response ->
                    if (isCancelled()) {
                        call.cancel()
                        return@executeCancellable start
                    }
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected response ${response.code} for range $start-$endInclusive")
                    }

                    val source = response.body?.source()
                        ?: throw IOException("Missing response body for range $start-$endInclusive")
                    downloadRangeIntoSession(source, start, endInclusive, session, ioBuffer)
                }
            } catch (throwable: IOException) {
                if (isCancelled()) {
                    return start
                }
                if (attempt >= 4) {
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

    private fun <T> executeCancellable(
        call: Call,
        isCancelled: () -> Boolean,
        monitorName: String,
        interruptedMessage: String,
        block: (Response) -> T
    ): T {
        if (isCancelled()) {
            call.cancel()
            throw IOException(interruptedMessage)
        }

        val callFinished = AtomicBoolean(false)
        val cancellationMonitor = Thread {
            while (!callFinished.get()) {
                if (isCancelled()) {
                    call.cancel()
                    return@Thread
                }
                try {
                    Thread.sleep(25L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        cancellationMonitor.isDaemon = true
        cancellationMonitor.name = monitorName
        cancellationMonitor.start()

        try {
            call.execute().use { response ->
                if (isCancelled()) {
                    call.cancel()
                    throw IOException(interruptedMessage)
                }
                return block(response)
            }
        } finally {
            callFinished.set(true)
            cancellationMonitor.interrupt()
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        return builder
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
        private const val DEFAULT_ADAPTIVE_HEADROOM_BYTES = 256L * 1024L * 1024L
        private const val DEFAULT_IDLE_POLL_MS = 250L
    }
}
