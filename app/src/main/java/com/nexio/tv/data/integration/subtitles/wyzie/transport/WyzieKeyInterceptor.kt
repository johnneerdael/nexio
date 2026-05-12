package com.nexio.tv.data.integration.subtitles.wyzie.transport

import com.nexio.tv.BuildConfig
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appends the built-in Wyzie API key (from `local.properties` → `BuildConfig.WYZIE_API_KEY`)
 * to every outgoing query.
 *
 * If the key is blank at build time the interceptor short-circuits with a synthetic 401
 * response so the calling provider's HTTP-error path runs naturally. There is no longer
 * a user-facing override — the key ships with the build.
 */
@Singleton
class WyzieKeyInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = BuildConfig.WYZIE_API_KEY
        if (key.isNullOrBlank()) {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Wyzie key not configured at build time")
                .body("".toResponseBody(null))
                .build()
        }
        val newUrl = request.url.newBuilder().addQueryParameter("key", key).build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
