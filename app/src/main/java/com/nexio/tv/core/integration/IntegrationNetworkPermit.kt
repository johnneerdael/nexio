package com.nexio.tv.core.integration

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import okhttp3.Interceptor
import okhttp3.Response

data class IntegrationNetworkPermit(
    val traceId: String,
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val workClass: IntegrationWorkClass,
    val cachePolicy: String
)

object IntegrationNetworkPermitContext {
    private val currentPermit = ThreadLocal<IntegrationNetworkPermit?>()

    fun current(): IntegrationNetworkPermit? = currentPermit.get()

    fun contextElement(permit: IntegrationNetworkPermit): ThreadContextElement<IntegrationNetworkPermit?> =
        currentPermit.asContextElement(permit)
}

class IntegrationHostClassifier(
    private val inScopeHosts: Set<String>
) {
    fun isInScope(host: String): Boolean = inScopeHosts.any { host == it || host.endsWith(".$it") }

    companion object {
        fun default(): IntegrationHostClassifier = IntegrationHostClassifier(
            setOf(
                "api.themoviedb.org",
                "api4.thetvdb.com",
                "kitsu.io",
                "api.trakt.tv",
                "api.simkl.com",
                "api.real-debrid.com",
                "www.premiumize.me",
                "api.torbox.app",
                "easydebrid.com",
                "api.mdblist.com",
                "www.omdbapi.com",
                "api.ratingposterdb.com",
                "api.top-posters.com",
                "api.aniskip.com",
                "api.animeskip.com",
                "api.github.com"
            )
        )
    }
}

class IntegrationNetworkPermitInterceptor(
    private val hostClassifier: IntegrationHostClassifier,
    private val mode: Mode
) : Interceptor {
    enum class Mode {
        AUDIT_ONLY,
        ENFORCE
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!hostClassifier.isInScope(host)) {
            return chain.proceed(request)
        }

        val permit = request.tag(IntegrationNetworkPermit::class.java)
            ?: IntegrationNetworkPermitContext.current()

        if (permit == null && mode == Mode.ENFORCE) {
            throw IllegalStateException(
                "In-scope provider network call without IntegrationRuntime permit: ${request.method} ${request.url}"
            )
        }

        return chain.proceed(request)
    }
}
