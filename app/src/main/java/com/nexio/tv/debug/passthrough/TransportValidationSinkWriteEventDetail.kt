package com.nexio.tv.debug.passthrough

import kotlinx.serialization.Serializable

@Serializable
data class TransportValidationSinkWriteEventDetail(
    val requestedBytes: Int? = null,
    val pendingRemainderId: Long? = null,
    val packetId: Long? = null,
    val ownership: String? = null,
    val firstOffsetBytes: Int? = null,
    val offsetBytes: Int? = null,
    val bytesRemaining: Int? = null,
    val lastWriteBytes: Int? = null,
    val pendingRemainderLastProgressUs: Long? = null,
    val sinceLastSuccessfulWriteMs: Long? = null,
    val playbackHeadDeltaFrames: Long? = null,
    val bufferFitDeltaFrames: Long? = null,
    val pendingRemainderRetryCount: Int? = null,
    val retryCount: Int? = null,
    val retryEligibleReason: String? = null,
    val retryReason: String? = null,
    val startupActive: Boolean? = null,
    val startupCompleted: Boolean? = null,
    val nativeOutputStarted: Boolean? = null,
    val nativeStartupReady: Boolean? = null,
    val nativeRemainderOwnership: String? = null,
    val meaningfulWriteCount: Long? = null,
    val handoffEligible: Boolean? = null,
    val handoffTriggered: Boolean? = null,
    val selectedPath: String? = null,
)

internal fun TransportValidationSinkWriteEventDetail.hasRetryInstrumentation(): Boolean =
    pendingRemainderId != null ||
        packetId != null ||
        ownership != null ||
        firstOffsetBytes != null ||
        offsetBytes != null ||
        bytesRemaining != null ||
        pendingRemainderLastProgressUs != null ||
        pendingRemainderRetryCount != null ||
        retryEligibleReason != null ||
        retryReason != null ||
        startupActive != null ||
        startupCompleted != null ||
        nativeOutputStarted != null ||
        nativeStartupReady != null ||
        nativeRemainderOwnership != null ||
        handoffEligible != null ||
        handoffTriggered != null ||
        selectedPath != null

internal fun parseTransportValidationSinkWriteEventDetail(
    detail: String?,
): TransportValidationSinkWriteEventDetail {
    if (detail.isNullOrBlank()) {
        return TransportValidationSinkWriteEventDetail()
    }
    val parts =
        detail
            .split(' ')
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }.toMap()
    return TransportValidationSinkWriteEventDetail(
        requestedBytes = parts["requestedBytes"]?.toIntOrNull(),
        pendingRemainderId = parts["pendingRemainderId"]?.toLongOrNull(),
        packetId = parts["packetId"]?.toLongOrNull(),
        ownership = parts["ownership"],
        firstOffsetBytes = parts["firstOffsetBytes"]?.toIntOrNull(),
        offsetBytes = parts["offsetBytes"]?.toIntOrNull(),
        bytesRemaining = parts["bytesRemaining"]?.toIntOrNull(),
        lastWriteBytes = parts["lastWriteBytes"]?.toIntOrNull(),
        pendingRemainderLastProgressUs = parts["pendingRemainderLastProgressUs"]?.toLongOrNull(),
        sinceLastSuccessfulWriteMs = parts["sinceLastSuccessfulWriteMs"]?.toLongOrNull(),
        playbackHeadDeltaFrames = parts["playbackHeadDeltaFrames"]?.toLongOrNull(),
        bufferFitDeltaFrames = parts["bufferFitDeltaFrames"]?.toLongOrNull(),
        pendingRemainderRetryCount = parts["pendingRemainderRetryCount"]?.toIntOrNull(),
        retryCount = parts["retryCount"]?.toIntOrNull(),
        retryEligibleReason = parts["retryEligibleReason"] ?: parts["pendingRemainderRetryEligibleReason"],
        retryReason = parts["retryReason"] ?: parts["retryEligibleReason"] ?: parts["pendingRemainderRetryEligibleReason"],
        startupActive = parts["startupActive"]?.toBooleanStrictOrNull(),
        startupCompleted = parts["startupCompleted"]?.toBooleanStrictOrNull(),
        nativeOutputStarted = parts["nativeOutputStarted"]?.toBooleanStrictOrNull(),
        nativeStartupReady = parts["nativeStartupReady"]?.toBooleanStrictOrNull(),
        nativeRemainderOwnership = parts["nativeRemainderOwnership"],
        meaningfulWriteCount = parts["meaningfulWriteCount"]?.toLongOrNull(),
        handoffEligible = parts["handoffEligible"]?.toBooleanStrictOrNull(),
        handoffTriggered = parts["handoffTriggered"]?.toBooleanStrictOrNull(),
        selectedPath = parts["selectedPath"],
    )
}
