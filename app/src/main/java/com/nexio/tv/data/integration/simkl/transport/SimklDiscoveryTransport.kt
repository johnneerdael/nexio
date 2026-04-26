package com.nexio.tv.data.integration.simkl.transport

import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface SimklDiscoveryTransportResult {
    data class Success(val body: String) : SimklDiscoveryTransportResult

    data class HttpError(val statusCode: Int) : SimklDiscoveryTransportResult

    data class NetworkError(val exception: Exception) : SimklDiscoveryTransportResult

    object Missing : SimklDiscoveryTransportResult
}

class SimklDiscoveryTransport @Inject constructor(
    @Named("simkl") private val okHttpClient: OkHttpClient
) {
    fun fetchDiscoveryBody(url: String): SimklDiscoveryTransportResult {
        val requestUrl = url.toHttpUrlOrNull()
            ?: return SimklDiscoveryTransportResult.Missing

        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SimklDiscoveryTransportResult.HttpError(response.code)
                } else {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        SimklDiscoveryTransportResult.Missing
                    } else {
                        SimklDiscoveryTransportResult.Success(body)
                    }
                }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            SimklDiscoveryTransportResult.NetworkError(exception)
        }
    }
}
