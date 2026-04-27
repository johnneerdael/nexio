package com.nexio.tv.core.trace

enum class TraceVerdict { PASS, PASS_WITH_WARNINGS, FAIL }

data class TraceValidationFailure(
    val ruleId: String,
    val message: String,
    val sequence: Long
) {
    init {
        require(ruleId.isNotBlank()) { "TraceValidationFailure.ruleId must not be blank" }
        require(sequence >= 0L) { "TraceValidationFailure.sequence must be >= 0" }
    }
}

data class TraceValidationWarning(
    val ruleId: String,
    val message: String,
    val sequence: Long
) {
    init {
        require(ruleId.isNotBlank()) { "TraceValidationWarning.ruleId must not be blank" }
        require(sequence >= 0L) { "TraceValidationWarning.sequence must be >= 0" }
    }
}

data class TraceValidationReport(
    val verdict: TraceVerdict,
    val failures: List<TraceValidationFailure>,
    val warnings: List<TraceValidationWarning>,
    val totalEvents: Long,
    val httpEvents: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val staleHits: Long,
    val routeDecisions: Long
)
