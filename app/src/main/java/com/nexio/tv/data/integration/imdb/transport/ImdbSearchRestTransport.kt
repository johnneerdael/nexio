package com.nexio.tv.data.integration.imdb.transport

import com.nexio.tv.data.remote.api.ImdbSearchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

sealed interface ImdbSearchRestTransportResult {
    data class Success(
        val statusCode: Int,
        val body: String
    ) : ImdbSearchRestTransportResult

    data class HttpError(
        val statusCode: Int
    ) : ImdbSearchRestTransportResult

    data class NetworkError(
        val exception: Exception
    ) : ImdbSearchRestTransportResult
}

interface ImdbSearchRestTransport {
    suspend fun search(
        restBaseUrl: String,
        query: String,
        types: Set<String>,
        apiKey: String,
        limit: Int
    ): ImdbSearchRestTransportResult
}

class OkHttpImdbSearchRestTransport(
    okHttpClient: OkHttpClient
) : ImdbSearchRestTransport {
    private val restClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(ImdbSearchService.REST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ImdbSearchService.REST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(ImdbSearchService.REST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun search(
        restBaseUrl: String,
        query: String,
        types: Set<String>,
        apiKey: String,
        limit: Int
    ): ImdbSearchRestTransportResult {
        val request = buildRequest(restBaseUrl, query, types, apiKey, limit)

        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
            val call = restClient.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }

            try {
                val response = call.execute()
                response.use {
                    if (!it.isSuccessful) {
                        cont.resume(ImdbSearchRestTransportResult.HttpError(it.code))
                        return@suspendCancellableCoroutine
                    }

                    val body = it.body?.string().orEmpty()
                    cont.resume(ImdbSearchRestTransportResult.Success(it.code, body))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (cont.isActive) {
                    cont.resume(ImdbSearchRestTransportResult.NetworkError(exception))
                }
            }
            }
        }
    }

    private fun buildRequest(
        restBaseUrl: String,
        query: String,
        types: Set<String>,
        apiKey: String,
        limit: Int
    ): Request {
        val requestUrl = restBaseUrl.trimEnd('/')
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("titles/search")
            .addQueryParameter("q", query)
            .addQueryParameter("types", types.joinToString(","))
            .addQueryParameter("limit", limit.toString())
            .build()

        return Request.Builder()
            .url(requestUrl)
            .header("X-API-Key", apiKey)
            .header("Accept", "application/json")
            .build()
    }
}
