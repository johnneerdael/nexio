package com.nexio.tv.data.integration.posters.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

data class PosterTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val body: ByteArray?
)

class PosterTransport @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    fun execute(url: String): PosterTransportResult {
        return okHttpClient.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build()
        ).execute().use { response ->
            PosterTransportResult(
                statusCode = response.code,
                isSuccessful = response.isSuccessful,
                body = response.body?.bytes()
            )
        }
    }
}
