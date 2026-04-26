package com.nexio.tv.data.integration.youtube.transport

import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import javax.inject.Inject
import javax.inject.Named
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpYouTubeTrailerTransport @Inject constructor(
    @Named("youtubeTrailer.main") private val mainClient: OkHttpClient,
    @Named("youtubeTrailer.probe") private val probeClient: OkHttpClient
) : YouTubeTrailerTransport {
    override fun execute(call: YouTubeTrailerTransportCall): YouTubeTrailerTransportResponse {
        val okHttpRequest = Request.Builder()
            .url(call.url)
            .headers(buildHeaders(call.headers))
            .method(
                call.method.uppercase(),
                call.requestBody()
            )
            .build()

        return mainClient.newCall(okHttpRequest).execute().use { response ->
            YouTubeTrailerTransportResponse(
                statusCode = response.code,
                isSuccessful = response.isSuccessful,
                statusText = response.message,
                url = response.request.url.toString(),
                body = response.body?.string().orEmpty()
            )
        }
    }

    override fun probe(url: String, headers: Map<String, String>): Boolean {
        val request = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))
            .get()
            .build()

        return probeClient.newCall(request).execute().use { response ->
            response.code == 200
        }
    }

    private fun YouTubeTrailerTransportCall.requestBody() =
        when (method.uppercase()) {
            "POST", "PUT", "DELETE" -> body.orEmpty().toRequestBody(DEFAULT_MEDIA_TYPE)
            else -> null
        }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
        }
        return headers.build()
    }

    private companion object {
        val DEFAULT_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
