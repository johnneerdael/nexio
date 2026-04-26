package com.nexio.tv.data.repository.benchmark

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

data class DebridBenchmarkTransportProfileResult(
    val summary: DebridBenchmarkSummary,
    val profile: DebridBenchmarkTransportProfile,
    val terminationReason: DebridBenchmarkTerminationReason,
    val failure: DebridBenchmarkTransportFailure? = null
)
