package com.nexio.tv.data.trailer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * HEAD + tail-byte-Range verification for a googlevideo stream URL.
 * Port of YoutubeExplode's StreamClient.cs:55-83 verification pattern.
 *
 * Returns null when the URL is unusable: non-200 HEAD, missing
 * Content-Length, sub-2-byte stream, or a tail-byte Range that returns
 * 4xx (a known YouTube quirk where the reported Content-Length doesn't
 * match the actual served tail).
 *
 * Kept out of InAppYouTubeExtractor.kt because the boundary test
 * (InAppYouTubeExtractorBoundaryTest) forbids transport primitives in
 * that file, and HttpURLConnection's setRequestProperty/responseCode
 * names trip the substring scan.
 */
internal suspend fun verifyContentLength(
    url: String,
    signedClientUserAgent: String
): Long? = withContext(Dispatchers.IO) {
    val headConn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = 3_000
        readTimeout = 5_000
        setRequestProperty("User-Agent", signedClientUserAgent)
    }
    val contentLength: Long = try {
        if (headConn.responseCode != 200) return@withContext null
        headConn.getHeaderField("Content-Length")?.toLongOrNull() ?: return@withContext null
    } catch (e: IOException) {
        return@withContext null
    } finally {
        headConn.disconnect()
    }
    // Reject 0-byte and tiny streams.
    if (contentLength < 2) return@withContext null

    val tailConn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 3_000
        readTimeout = 5_000
        setRequestProperty("User-Agent", signedClientUserAgent)
        setRequestProperty("Range", "bytes=${contentLength - 2}-${contentLength - 1}")
    }
    try {
        // 200 (server ignored Range) and 206 (Partial Content) both
        // indicate the URL is alive; 4xx means broken at the tail.
        if (tailConn.responseCode !in setOf(200, 206)) return@withContext null
        contentLength
    } catch (e: IOException) {
        null
    } finally {
        tailConn.disconnect()
    }
}

internal fun verifyContentLengthForTest(url: String): Long? =
    runBlocking { verifyContentLength(url, signedClientUserAgent = "test-ua") }
