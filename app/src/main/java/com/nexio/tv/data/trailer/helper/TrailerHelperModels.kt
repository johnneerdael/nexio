package com.nexio.tv.data.trailer.helper

data class TrailerHelperRequest(
    val youtubeUrl: String,
    val cookieHeader: String,
    val timeoutMs: Long = 30_000L
)

data class TrailerHelperPlaybackResult(
    val videoUrl: String,
    val audioUrl: String? = null,
    val expiresAtEpochMs: Long? = null
)

enum class TrailerHelperFailureReason {
    RuntimeMissing,
    CookieMissing,
    Timeout,
    ProcessFailed,
    ParseFailed
}

sealed interface TrailerHelperResult {
    data class Playback(val playback: TrailerHelperPlaybackResult) : TrailerHelperResult
    data class Failure(
        val reason: TrailerHelperFailureReason,
        val stderrExcerpt: String? = null,
        val exitCode: Int? = null
    ) : TrailerHelperResult
}
