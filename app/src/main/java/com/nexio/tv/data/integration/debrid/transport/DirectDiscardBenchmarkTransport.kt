package com.nexio.tv.data.integration.debrid.transport

import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidate
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkMetricsCollector
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkObserver
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransport
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportResult
import com.nexio.tv.ui.screens.player.PlayerTransportTelemetry
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

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
        PlayerTransportTelemetry.logThrottled("okhttp.depth.benchmark", 1000L, mapOf(
            "maxRequestsPerHost" to okHttpClient.dispatcher.maxRequestsPerHost,
            "queued" to okHttpClient.dispatcher.queuedCallsCount(),
            "running" to okHttpClient.dispatcher.runningCallsCount()
        ))
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
        var previousReadAtNs: Long? = null
        var totalBytesRead = 0L
        val collector = DebridBenchmarkMetricsCollector()

        while (true) {
            val read = source.read(discardBuffer, DISCARD_BUFFER_BYTES)
            if (read == -1L) {
                return DebridBenchmarkTransportResult(
                    summary = collector.currentSummary(),
                    terminationReason = DebridBenchmarkTerminationReason.FAILED
                )
            }

            discardBuffer.clear()
            totalBytesRead += read

            val nowNs = System.nanoTime()
            if (firstByteAtNs == null) {
                firstByteAtNs = nowNs
                collector.recordStartup(
                    requestStartedAtMs = nanosToMillis(requestStartedAtNs),
                    firstByteAtMs = nanosToMillis(nowNs)
                )
            }
            previousReadAtNs?.let { previous ->
                collector.recordReadGap(nanosToMillis(nowNs - previous))
            }
            previousReadAtNs = nowNs

            collector.recordBytesRead(
                totalBytesRead = totalBytesRead,
                sampleAtMs = nanosToMillis(nowNs)
            )
            val requestSummary = collector.currentSummary()
            observer.onSummaryUpdated(requestSummary)

            if (collector.shouldComplete()) {
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
