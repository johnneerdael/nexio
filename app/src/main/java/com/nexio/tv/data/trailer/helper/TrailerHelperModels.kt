package com.nexio.tv.data.trailer.helper

data class TrailerHelperRequest(
    val youtubeUrl: String,
    val authorizationHeader: String,
    val pageId: String? = null,
    val authUser: String? = null,
    val timeoutMs: Long = 30_000L
) {
    @Deprecated(
        message = "Use authorizationHeader instead.",
        replaceWith = ReplaceWith("authorizationHeader")
    )
    val cookieHeader: String
        get() = authorizationHeader

    @Deprecated(
        message = "Use authorizationHeader, pageId, and authUser instead.",
        replaceWith = ReplaceWith(
            "TrailerHelperRequest(youtubeUrl = youtubeUrl, authorizationHeader = cookieHeader, timeoutMs = timeoutMs)"
        )
    )
    constructor(
        youtubeUrl: String,
        cookieHeader: String,
        timeoutMs: Long = 30_000L
    ) : this(
        youtubeUrl = youtubeUrl,
        authorizationHeader = cookieHeader,
        timeoutMs = timeoutMs
    )
}

data class TrailerHelperPlaybackResult(
    val videoUrl: String,
    val audioUrl: String? = null,
    val expiresAtEpochMs: Long? = null
)

enum class TrailerHelperFailureReason {
    RuntimeMissing,
    AuthorizationMissing,
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
