package com.nexio.tv.data.trailer.helper

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_ACCEPT_LANGUAGE
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_ORIGIN
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_REFERER
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val TAG = "BundledTrailerHelper"

fun parseHelperStdout(stdout: String): TrailerHelperPlaybackResult {
    val trimmed = stdout.trim()
    require(trimmed.isNotBlank()) { "Helper output was empty" }

    if (trimmed.startsWith("{")) {
        val videoUrl = extractJsonString(trimmed, "videoUrl").orEmpty().trim()
        require(videoUrl.isNotBlank()) { "Missing videoUrl in helper JSON" }
        val audioUrl = extractJsonString(trimmed, "audioUrl").orEmpty().trim().ifBlank { null }
        val expiresAtEpochMs = extractJsonLong(trimmed, "expiresAtEpochMs")?.takeIf { it > 0L }
        return TrailerHelperPlaybackResult(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            expiresAtEpochMs = expiresAtEpochMs
        )
    }

    val lines = trimmed.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    require(lines.isNotEmpty()) { "Helper output contained no URLs" }

    val videoUrl = lines.first()
    val audioUrl = lines.getOrNull(1)
    val expiresAtEpochMs = deriveExpiryFromUrl(videoUrl) ?: audioUrl?.let(::deriveExpiryFromUrl)
    return TrailerHelperPlaybackResult(
        videoUrl = videoUrl,
        audioUrl = audioUrl,
        expiresAtEpochMs = expiresAtEpochMs
    )
}

internal fun buildTrailerHelperInvocationArgs(request: TrailerHelperRequest): Array<Any?> {
    return arrayOf(
        request.youtubeUrl,
        request.authorizationHeader,
        request.pageId,
        request.authUser,
        YOUTUBE_STABLE_WEB_USER_AGENT,
        YOUTUBE_STABLE_ACCEPT_LANGUAGE,
        YOUTUBE_STABLE_ORIGIN,
        YOUTUBE_STABLE_REFERER
    )
}

@Singleton
class BundledTrailerHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun resolve(request: TrailerHelperRequest): TrailerHelperResult = withContext(Dispatchers.IO) {
        if (request.authorizationHeader.isBlank()) {
            Log.w(TAG, "Embedded trailer helper skipped for blank authorization header")
            return@withContext TrailerHelperResult.Failure(
                reason = TrailerHelperFailureReason.AuthorizationMissing
            )
        }

        if (!YouTubeJavaScriptEngineBridge.initialize(context)) {
            Log.w(TAG, "Embedded trailer helper JS engine unavailable for ${summarizeUrl(request.youtubeUrl)}")
            return@withContext TrailerHelperResult.Failure(
                reason = TrailerHelperFailureReason.RuntimeMissing
            )
        }
        Log.d(
            TAG,
            "Resolving ${summarizeUrl(request.youtubeUrl)} via embedded helper " +
                "(runtime=android_js_engine)"
        )

        val stdout = withTimeoutOrNull(request.timeoutMs) {
            runCatching {
                val python = Python.getInstance()
                python.getModule("nexio_trailer_helper")
                    .callAttr(
                        "resolve_youtube_playback",
                        *buildTrailerHelperInvocationArgs(request)
                    )
                    .toString()
            }.getOrElse { error ->
                val excerpt = when (error) {
                    is PyException -> error.message
                    else -> error.message
                }
                Log.w(TAG, "Embedded trailer helper failed: ${error.message}", error)
                return@withTimeoutOrNull TrailerHelperResult.Failure(
                    reason = TrailerHelperFailureReason.ProcessFailed,
                    stderrExcerpt = excerpt
                )
            }
        }
        when (stdout) {
            null -> {
                Log.w(TAG, "Embedded trailer helper timed out for ${summarizeUrl(request.youtubeUrl)}")
                return@withContext TrailerHelperResult.Failure(
                    reason = TrailerHelperFailureReason.Timeout
                )
            }
            is TrailerHelperResult.Failure -> return@withContext stdout
            !is String -> {
                return@withContext TrailerHelperResult.Failure(
                    reason = TrailerHelperFailureReason.ParseFailed,
                    stderrExcerpt = "Embedded trailer helper returned no output."
                )
            }
        }

        val parsed = runCatching { parseHelperStdout(stdout) }.getOrElse { error ->
            Log.w(TAG, "Embedded trailer helper parse failed for ${summarizeUrl(request.youtubeUrl)}: ${error.message}")
            return@withContext TrailerHelperResult.Failure(
                reason = TrailerHelperFailureReason.ParseFailed,
                stderrExcerpt = error.message
            )
        }

        Log.d(
            TAG,
            "Embedded trailer helper resolved ${summarizeUrl(request.youtubeUrl)} " +
                "(videoHost=${hostOf(parsed.videoUrl)}, audioPresent=${!parsed.audioUrl.isNullOrBlank()}, expiresAt=${parsed.expiresAtEpochMs})"
        )

        TrailerHelperResult.Playback(parsed)
    }
}

private fun deriveExpiryFromUrl(url: String): Long? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val expireSeconds = uri.rawQuery
        ?.split('&')
        ?.asSequence()
        ?.mapNotNull { part ->
            val keyValue = part.split('=', limit = 2)
            val key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8)
            if (key != "expire") {
                return@mapNotNull null
            }
            val rawValue = keyValue.getOrNull(1) ?: return@mapNotNull null
            URLDecoder.decode(rawValue, StandardCharsets.UTF_8).toLongOrNull()
        }
        ?.firstOrNull()
        ?: return null
    return expireSeconds * 1000L
}

private fun extractJsonString(json: String, key: String): String? {
    val pattern = Regex("""\"$key\"\s*:\s*\"((?:\\.|[^\\"])*)\"""")
    val match = pattern.find(json) ?: return null
    return match.groupValues[1]
        .replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
}

private fun extractJsonLong(json: String, key: String): Long? {
    val pattern = Regex("""\"$key\"\s*:\s*(-?\d+)""")
    val match = pattern.find(json) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private fun summarizeUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host.orEmpty()
    val path = uri.path.orEmpty()
    return "$host$path"
}

private fun hostOf(url: String): String {
    return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
}
