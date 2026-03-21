package com.nexio.tv.debug.passthrough

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class OkHttpTransportValidationAssetFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : TransportValidationAssetFetcher {
    override fun fetchString(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Validator manifest request failed url=$url code=${response.code}"
            }
            return response.body?.string()
                ?: error("Validator manifest body missing url=$url")
        }
    }

    override fun fetchToFile(url: String, targetFile: File) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Validator asset request failed url=$url code=${response.code}"
            }
            val body = response.body ?: error("Validator asset body missing url=$url")
            targetFile.outputStream().buffered().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
    }
}
