package com.nexio.tv.data.integration.playback.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class CometProxyHttpResult(
    val code: Int,
    val location: String?,
    val contentType: String?
)

internal interface CometProxyHttpTransport {
    fun execute(url: String, headers: Map<String, String>?): CometProxyHttpResult
}

internal class OkHttpCometProxyHttpTransport(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
) : CometProxyHttpTransport {
    override fun execute(url: String, headers: Map<String, String>?): CometProxyHttpResult {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")

        headers?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                requestBuilder.header(key, value)
            }
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            return CometProxyHttpResult(
                code = response.code,
                location = response.header("Location"),
                contentType = response.header("Content-Type")
            )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
