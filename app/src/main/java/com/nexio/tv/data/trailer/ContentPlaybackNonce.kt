package com.nexio.tv.data.trailer

import java.security.SecureRandom

/**
 * Mirrors NewPipeExtractor's `generateContentPlaybackNonce`: 16 characters
 * drawn from YouTube's URL-safe alphabet. YouTube's player API uses the
 * `cpn` (content playback nonce) for session bookkeeping; omitting it has
 * been observed to yield restricted streaming data.
 */
private const val CPN_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private const val CPN_LENGTH = 16
private val cpnRandom = SecureRandom()

fun generateContentPlaybackNonce(): String {
    val out = CharArray(CPN_LENGTH)
    for (i in 0 until CPN_LENGTH) {
        out[i] = CPN_ALPHABET[cpnRandom.nextInt(CPN_ALPHABET.length)]
    }
    return String(out)
}
