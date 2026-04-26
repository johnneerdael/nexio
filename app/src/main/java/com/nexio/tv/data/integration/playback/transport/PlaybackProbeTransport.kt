package com.nexio.tv.data.integration.playback.transport

import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val PLAYBACK_PROBE_TIMEOUT_MS = 3_000
private const val OPEN_SUBTITLES_HASH_TIMEOUT_MS = 8_000

sealed interface PlaybackProbeResult {
    data object Success : PlaybackProbeResult

    data class HttpError(val statusCode: Int) : PlaybackProbeResult

    data object Missing : PlaybackProbeResult
}

interface OpenSubtitlesHashTransport {
    fun contentLength(url: String, headers: Map<String, String>): Long?

    fun readRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long): ByteArray
}

open class PlaybackProbeTransport @Inject constructor() : OpenSubtitlesHashTransport {
    open fun redirectLocationForHead(url: String): String? {
        val connection = openConnection(url).apply {
            instanceFollowRedirects = false
            requestMethod = "HEAD"
        }
        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 300..399) {
                null
            } else {
                connection.getHeaderField("Location")
            }
        } finally {
            connection.disconnect()
        }
    }

    open fun rangeProbe(url: String): PlaybackProbeResult {
        val rangeStart = 0L
        val rangeEnd = 1L
        val connection = openConnection(url).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
        }
        return try {
            val statusCode = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range")
            return when {
                statusCode == HttpURLConnection.HTTP_NOT_FOUND -> PlaybackProbeResult.Missing
                isHonoredRangeResponse(statusCode, contentRange, rangeStart, rangeEnd) -> PlaybackProbeResult.Success
                else -> PlaybackProbeResult.HttpError(statusCode)
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun contentLength(url: String, headers: Map<String, String>): Long? {
        val connection = openConnection(
            url = url,
            headers = headers,
            method = "HEAD",
            connectTimeoutMs = OPEN_SUBTITLES_HASH_TIMEOUT_MS,
            readTimeoutMs = OPEN_SUBTITLES_HASH_TIMEOUT_MS
        )
        return try {
            connection.connect()
            connection.contentLengthLong.takeIf { it > 0 }
        } finally {
            connection.disconnect()
        }
    }

    override fun readRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        endInclusive: Long
    ): ByteArray {
        val expectedLength = (endInclusive - start + 1).coerceAtLeast(0).toInt()
        val connection = openConnection(
            url = url,
            headers = headers,
            method = "GET",
            connectTimeoutMs = OPEN_SUBTITLES_HASH_TIMEOUT_MS,
            readTimeoutMs = OPEN_SUBTITLES_HASH_TIMEOUT_MS
        ).apply {
            setRequestProperty("Range", "bytes=$start-$endInclusive")
        }
        return try {
            connection.connect()
            val statusCode = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range")
            if (!isHonoredRangeResponse(statusCode, contentRange, start, endInclusive)) {
                throw IOException(
                    "Range request not honored for $url: status=$statusCode contentRange=$contentRange"
                )
            }
            connection.inputStream.use { input ->
                input.readUpTo(expectedLength)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PLAYBACK_PROBE_TIMEOUT_MS
            readTimeout = PLAYBACK_PROBE_TIMEOUT_MS
        }
    }

    private fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
    }
}

private fun isHonoredRangeResponse(
    statusCode: Int,
    contentRange: String?,
    start: Long,
    endInclusive: Long
): Boolean {
    if (statusCode != HttpURLConnection.HTTP_PARTIAL) return false
    val matchesContentRange = CONTENT_RANGE_REGEX.matchEntire(contentRange?.trim().orEmpty())
        ?.let { match ->
            match.groupValues[1].toLongOrNull() == start &&
                match.groupValues[2].toLongOrNull() == endInclusive
        }
        ?: false
    return matchesContentRange
}

private fun InputStream.readUpTo(expectedLength: Int): ByteArray {
    val output = ByteArray(expectedLength)
    var offset = 0
    while (offset < expectedLength) {
        val read = read(output, offset, expectedLength - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == output.size) output else output.copyOf(offset)
}

private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(?:\d+|\*)""")
