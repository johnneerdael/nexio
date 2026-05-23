package com.nexio.tv.data.trailer.potoken

/**
 * Result of a successful WEB-client poToken generation.
 *
 * YouTube currently requires the GVS/streaming poToken to be content-bound:
 * generated against the video ID. The same video-bound token is sent in the
 * InnerTube player body and appended as `pot` on `googlevideo.com` URLs.
 */
data class PoTokenResult(
    val visitorData: String,
    val playerRequestPoToken: String,
    val streamingDataPoToken: String?
)
