package com.nexio.tv.core.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private object PermissiveSslConfig {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        sslContext.socketFactory
    }

    val hostnameVerifier = HostnameVerifier { _, _ -> true }
}

internal fun OkHttpClient.Builder.allowInvalidCertificates(): OkHttpClient.Builder {
    // Intentional: this matches the requested Stremio-like behavior for addon and playback traffic.
    return sslSocketFactory(
        PermissiveSslConfig.sslSocketFactory,
        PermissiveSslConfig.trustManager
    ).hostnameVerifier(PermissiveSslConfig.hostnameVerifier)
}
