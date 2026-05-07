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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toOkioPath

class LegacyRemoteArtworkFetcher(
    private val model: LegacyRemoteArtworkModel,
    private val transport: PosterTransport,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink,
    private val sourceFactory: (ByteArray) -> ImageSource = ::createTempFileSource
) : Fetcher {
    private val traceSequence = AtomicLong(0L)

    override suspend fun fetch(): FetchResult? {
        trace(
            eventType = "legacy_remote_artwork.fetch_start",
            payload = baseTracePayload()
        )
        val result = try {
            transport.execute(model.url.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            trace(
                eventType = "legacy_remote_artwork.fetch_failed",
                payload = baseTracePayload() + mapOf(
                    "reason" to "transport_exception",
                    "errorClass" to error::class.java.name
                )
            )
            return null
        }
        if (!result.isSuccessful) {
            trace(
                eventType = "legacy_remote_artwork.fetch_failed",
                payload = baseTracePayload() + mapOf(
                    "reason" to "http_failure",
                    "statusCode" to result.statusCode
                )
            )
            return null
        }
        val bytes = result.body ?: run {
            trace(
                eventType = "legacy_remote_artwork.fetch_failed",
                payload = baseTracePayload() + mapOf(
                    "reason" to "null_body",
                    "statusCode" to result.statusCode
                )
            )
            return null
        }
        val source = try {
            sourceFactory(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            trace(
                eventType = "legacy_remote_artwork.fetch_failed",
                payload = baseTracePayload() + mapOf(
                    "reason" to "source_creation_failed",
                    "errorClass" to error::class.java.name
                )
            )
            throw error
        }
        trace(
            eventType = "legacy_remote_artwork.fetch_success",
            payload = baseTracePayload() + mapOf(
                "reason" to "success",
                "statusCode" to result.statusCode,
                "byteCount" to bytes.size
            )
        )
        return SourceResult(
            source = source,
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK
        )
    }

    private fun baseTracePayload(): Map<String, Any?> =
        mapOf(
            "imageType" to model.imageType.name,
            "modelKeyHash" to model.key.sha256(),
            "urlHash" to model.url.value.sha256()
        )

    private fun trace(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = LOGCAT_ONLY_TRACE_SESSION_ID,
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
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }
}

private fun createTempFileSource(bytes: ByteArray): ImageSource {
    val file = File.createTempFile("legacy-remote-artwork-", ".img")
    file.writeBytes(bytes)
    return ImageSource(
        file = file.toOkioPath(),
        closeable = Closeable { file.delete() }
    )
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
