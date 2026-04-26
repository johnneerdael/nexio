package com.nexio.tv.data.integration.github.transport

import java.io.InputStream
import javax.inject.Inject
import okhttp3.OkHttpClient
import okhttp3.Request

data class GitHubAssetDownloadTransportResult(
    val inputStream: InputStream,
    val totalBytes: Long?,
    val close: () -> Unit
)

class GitHubAssetDownloadTransport @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    fun openDownloadStream(url: String): GitHubAssetDownloadTransportResult {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            response.close()
            error("Download failed: HTTP ${response.code}")
        }

        val responseBody = response.body ?: run {
            response.close()
            throw IllegalStateException("Download failed: empty body")
        }

        return GitHubAssetDownloadTransportResult(
            inputStream = responseBody.byteStream(),
            totalBytes = responseBody.contentLength().takeIf { it >= 0L },
            close = response::close
        )
    }
}
