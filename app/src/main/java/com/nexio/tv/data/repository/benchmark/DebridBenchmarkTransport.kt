package com.nexio.tv.data.repository.benchmark

import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okio.Buffer

interface DebridBenchmarkTransport {
    suspend fun run(
        candidate: DebridBenchmarkCandidate,
        observer: DebridBenchmarkObserver
    ): DebridBenchmarkTransportResult
}

fun interface DebridBenchmarkObserver {
    fun onSummaryUpdated(summary: DebridBenchmarkSummary)
}

data class DebridBenchmarkTransportResult(
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason
)

class DirectDiscardBenchmarkTransport @Inject constructor(
    @Named("benchmark") private val okHttpClient: OkHttpClient
) : DebridBenchmarkTransport {

    override suspend fun run(
        candidate: DebridBenchmarkCandidate,
        observer: DebridBenchmarkObserver
    ): DebridBenchmarkTransportResult = withContext(Dispatchers.IO) {
        val requestStartedAtNs = System.nanoTime()
        val request = Request.Builder()
            .url(candidate.directUrl)
            .get()
            .apply {
                candidate.headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .build()
        val call = okHttpClient.newCall(request)
        val cancellationRegistration = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }

        try {
            val response = call.execute()
            response.use {
                streamResponse(
                    response = response,
                    observer = observer,
                    requestStartedAtNs = requestStartedAtNs
                )
            }
        } catch (error: InterruptedIOException) {
            val terminationReason = if (call.isCanceled()) {
                DebridBenchmarkTerminationReason.CANCELED
            } else {
                DebridBenchmarkTerminationReason.TIMEOUT
            }
            DebridBenchmarkTransportResult(
                summary = DebridBenchmarkSummary(),
                terminationReason = terminationReason
            )
        } catch (_: Exception) {
            val terminationReason = if (call.isCanceled()) {
                DebridBenchmarkTerminationReason.CANCELED
            } else {
                DebridBenchmarkTerminationReason.FAILED
            }
            DebridBenchmarkTransportResult(
                summary = DebridBenchmarkSummary(),
                terminationReason = terminationReason
            )
        } finally {
            cancellationRegistration?.dispose()
        }
    }

    private fun nanosToMillis(durationNs: Long): Long {
        return durationNs / 1_000_000L
    }

    private fun streamResponse(
        response: Response,
        observer: DebridBenchmarkObserver,
        requestStartedAtNs: Long
    ): DebridBenchmarkTransportResult {
        if (!response.isSuccessful) {
            return DebridBenchmarkTransportResult(
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.FAILED
            )
        }

        val body = response.body ?: return DebridBenchmarkTransportResult(
            summary = DebridBenchmarkSummary(),
            terminationReason = DebridBenchmarkTerminationReason.FAILED
        )
        val source = body.source()
        val discardBuffer = Buffer()
        var firstByteAtNs: Long? = null
        var totalBytesRead = 0L
        val aggregator = DebridBenchmarkAggregator()

        while (true) {
            val read = source.read(discardBuffer, DISCARD_BUFFER_BYTES)
            if (read == -1L) {
                return DebridBenchmarkTransportResult(
                    summary = aggregator.summary(),
                    terminationReason = DebridBenchmarkTerminationReason.FAILED
                )
            }

            discardBuffer.clear()
            totalBytesRead += read

            val nowNs = System.nanoTime()
            if (firstByteAtNs == null) {
                firstByteAtNs = nowNs
            }

            val requestSummary = aggregator.recordTransferSample(
                bytesRead = totalBytesRead,
                requestStartedAtMs = nanosToMillis(requestStartedAtNs),
                firstByteAtMs = nanosToMillis(firstByteAtNs),
                sampleAtMs = nanosToMillis(nowNs)
            )
            observer.onSummaryUpdated(requestSummary)

            if (aggregator.shouldComplete()) {
                return DebridBenchmarkTransportResult(
                    summary = requestSummary,
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED
                )
            }
        }
    }

    companion object {
        private const val DISCARD_BUFFER_BYTES = 8_192L
    }
}

internal class DebridBenchmarkAggregator(
    private val requiredTransferredBytes: Long = 500L * 1024L * 1024L,
    private val requiredElapsedMs: Long = 120_000L
) {
    private var transferredBytes = 0L
    private var elapsedMs = 0L
    private var startupTimeMs: Long? = null

    fun recordSample(
        bytesRead: Long,
        elapsedMs: Long,
        startupTimeMs: Long? = this.startupTimeMs
    ): DebridBenchmarkSummary {
        this.transferredBytes = bytesRead.coerceAtLeast(0L)
        this.elapsedMs = elapsedMs.coerceAtLeast(0L)
        if (startupTimeMs != null) {
            this.startupTimeMs = startupTimeMs.coerceAtLeast(0L)
        }
        return summary()
    }

    fun recordTransferSample(
        bytesRead: Long,
        requestStartedAtMs: Long,
        firstByteAtMs: Long,
        sampleAtMs: Long
    ): DebridBenchmarkSummary {
        return recordSample(
            bytesRead = bytesRead,
            elapsedMs = (sampleAtMs - requestStartedAtMs).coerceAtLeast(0L),
            startupTimeMs = (firstByteAtMs - requestStartedAtMs).coerceAtLeast(0L)
        )
    }

    fun shouldComplete(): Boolean {
        return transferredBytes >= requiredTransferredBytes &&
            elapsedMs >= requiredElapsedMs
    }

    fun summary(): DebridBenchmarkSummary {
        val streamingDurationMs = startupTimeMs?.let { elapsedMs - it }?.takeIf { it > 0L }
        return DebridBenchmarkSummary(
            startupTimeMs = startupTimeMs,
            sustainedThroughputMbps = streamingDurationMs?.let {
                transferredBytes.toDouble() * 8.0 / it.toDouble() / 1_000.0
            },
            transferredBytes = transferredBytes,
            elapsedMs = elapsedMs
        )
    }
}
