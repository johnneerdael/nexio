package com.nexio.tv.data.integration.subtitles.wyzie.transport

import com.nexio.tv.data.local.WyzieSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appends the Wyzie API key to every outgoing query.
 *
 * If the key is blank/null at request time the interceptor short-circuits with a synthetic
 * 401 response so the calling provider's HTTP-error path runs naturally. In practice the
 * repository skips the call entirely when the key is absent (silent degrade); this interceptor
 * is the safety net for direct/test invocations.
 */
@Singleton
class WyzieKeyInterceptor @Inject constructor(
    private val settingsDataStore: WyzieSettingsDataStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = runBlocking { settingsDataStore.settings.first().apiKey }
        if (key.isNullOrBlank()) {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Wyzie key not configured")
                .body("".toResponseBody(null))
                .build()
        }
        val newUrl = request.url.newBuilder().addQueryParameter("key", key).build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
