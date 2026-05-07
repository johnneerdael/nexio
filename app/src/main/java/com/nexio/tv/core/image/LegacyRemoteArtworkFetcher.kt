package com.nexio.tv.core.image

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toOkioPath

class LegacyRemoteArtworkFetcher(
    private val model: LegacyRemoteArtworkModel,
    private val transport: PosterTransport,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        emitTrace(
            eventType = "legacy_remote_artwork.fetch_start",
            payload = mapOf(
                "imageType" to model.imageType.name
            )
        )
        val result = try {
            transport.execute(model.url.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emitFailure(
                reason = "transport_exception",
                statusCode = null,
                extra = mapOf("errorClass" to error::class.java.name)
            )
            return null
        }
        if (!result.isSuccessful) {
            emitFailure(reason = "http_failure", statusCode = result.statusCode)
            return null
        }
        val bytes = result.body
        if (bytes == null) {
            emitFailure(reason = "null_body", statusCode = result.statusCode)
            return null
        }
        emitTrace(
            eventType = "legacy_remote_artwork.fetch_success",
            payload = mapOf(
                "reason" to "success",
                "statusCode" to result.statusCode,
                "byteCount" to bytes.size
            )
        )
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

    private fun emitFailure(
        reason: String,
        statusCode: Int?,
        extra: Map<String, Any?> = emptyMap()
    ) {
        emitTrace(
            eventType = "legacy_remote_artwork.fetch_failed",
            payload = mapOf(
                "reason" to reason,
                "statusCode" to statusCode
            ) + extra
        )
    }

    private fun emitTrace(eventType: String, payload: Map<String, Any?>) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = traceSink.activeTraceSessionId() ?: "legacy-remote-artwork",
                sequence = traceSequence.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }

    @Singleton
    class Factory @Inject constructor(
        private val transport: PosterTransport,
        private val traceSink: RuntimeTraceSink
    ) : Fetcher.Factory<LegacyRemoteArtworkModel> {
        override fun create(
            data: LegacyRemoteArtworkModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher =
            LegacyRemoteArtworkFetcher(
                model = data,
                transport = transport,
                traceSink = traceSink
            )
    }

    private companion object {
        val traceSequence = AtomicLong(0L)
    }
}
