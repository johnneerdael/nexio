package com.nexio.tv.data.trailer.potoken

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.nexio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PoTokenProviderImpl @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) : PoTokenProvider {

    private val mutex = Mutex()
    private var generator: PoTokenGenerator? = null
    private var visitorData: String? = null
    private var visitorDataClientName: String? = null
    private var visitorDataClientId: String? = null
    private var visitorDataClientVersion: String? = null
    private var visitorDataClientScreen: String? = null
    private var visitorDataEmbedUrl: String? = null
    @Volatile private var webViewBadImpl = false

    private val supportsWebView: Boolean by lazy {
        runCatching { CookieManager.getInstance() }.isSuccess
    }

    override suspend fun getWebClientPoToken(
        videoId: String,
        webClientName: String,
        webClientId: String,
        webClientVersion: String,
        webClientScreen: String?,
        embedUrl: String?
    ): PoTokenResult? = withContext(NonCancellable) {
        if (!supportsWebView || webViewBadImpl) return@withContext null

        try {
            val (poTokenGenerator, resolvedVisitorData, recreated) = ensureFresh(
                webClientName = webClientName,
                webClientId = webClientId,
                webClientVersion = webClientVersion,
                webClientScreen = webClientScreen,
                embedUrl = embedUrl
            )
            val poToken = try {
                poTokenGenerator.generatePoToken(videoId)
            } catch (t: Throwable) {
                if (recreated) throw t
                val (retryGenerator, retryVisitorData) = recreate(
                    webClientName = webClientName,
                    webClientId = webClientId,
                    webClientVersion = webClientVersion,
                    webClientScreen = webClientScreen,
                    embedUrl = embedUrl
                )
                val retryToken = retryGenerator.generatePoToken(videoId)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "poToken retry videoId=$videoId visitor=$retryVisitorData")
                }
                return@withContext PoTokenResult(retryVisitorData, retryToken, retryToken)
            }
            Log.d(TAG, "poToken ready videoId=$videoId webClientVersion=$webClientVersion")
            PoTokenResult(resolvedVisitorData, poToken, poToken)
        } catch (t: Throwable) {
            if (t is BadWebViewException) webViewBadImpl = true
            Log.w(TAG, "getWebClientPoToken failed: ${t.message}")
            null
        }
    }

    private suspend fun ensureFresh(
        webClientName: String,
        webClientId: String,
        webClientVersion: String,
        webClientScreen: String?,
        embedUrl: String?
    ): Triple<PoTokenGenerator, String, Boolean> =
        mutex.withLock {
            val current = generator
            val cachedVisitorData = visitorData
            if (
                current != null &&
                !current.isExpired() &&
                !cachedVisitorData.isNullOrBlank() &&
                visitorDataClientName == webClientName &&
                visitorDataClientId == webClientId &&
                visitorDataClientVersion == webClientVersion &&
                visitorDataClientScreen == webClientScreen &&
                visitorDataEmbedUrl == embedUrl
            ) {
                return@withLock Triple(current, cachedVisitorData, false)
            }

            val (newGenerator, newVisitorData) = recreateLocked(
                webClientName = webClientName,
                webClientId = webClientId,
                webClientVersion = webClientVersion,
                webClientScreen = webClientScreen,
                embedUrl = embedUrl
            )
            Triple(newGenerator, newVisitorData, true)
        }

    private suspend fun recreate(
        webClientName: String,
        webClientId: String,
        webClientVersion: String,
        webClientScreen: String?,
        embedUrl: String?
    ): Pair<PoTokenGenerator, String> =
        mutex.withLock {
            recreateLocked(
                webClientName = webClientName,
                webClientId = webClientId,
                webClientVersion = webClientVersion,
                webClientScreen = webClientScreen,
                embedUrl = embedUrl
            )
        }

    private suspend fun recreateLocked(
        webClientName: String,
        webClientId: String,
        webClientVersion: String,
        webClientScreen: String?,
        embedUrl: String?
    ): Pair<PoTokenGenerator, String> {
        val old = generator
        if (old != null) {
            withContext(Dispatchers.Main) { old.close() }
        }

        val newVisitorData = fetchVisitorData(
            webClientName = webClientName,
            webClientId = webClientId,
            webClientVersion = webClientVersion,
            webClientUserAgent = PoTokenWebView.WEB_USER_AGENT,
            webClientScreen = webClientScreen,
            embedUrl = embedUrl
        )
        val newGenerator = PoTokenWebView.newPoTokenGenerator(applicationContext)
        generator = newGenerator
        visitorData = newVisitorData
        visitorDataClientName = webClientName
        visitorDataClientId = webClientId
        visitorDataClientVersion = webClientVersion
        visitorDataClientScreen = webClientScreen
        visitorDataEmbedUrl = embedUrl
        return newGenerator to newVisitorData
    }

    companion object {
        private const val TAG = "PoTokenProviderImpl"
    }
}
