package com.nexio.tv.data.trailer.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import com.nexio.tv.BuildConfig
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
internal class PoTokenWebView private constructor(
    context: Context,
    private var generatorContinuation: CancellableContinuation<PoTokenGenerator>?
) : PoTokenGenerator {

    private val webView = WebView(context.applicationContext)
    private val poTokenContinuations = mutableMapOf<String, CancellableContinuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        onInitializationError(exception)
    }
    private lateinit var expirationInstant: Instant

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            safeBrowsingEnabled = false
            userAgentString = WEB_USER_AGENT
            blockNetworkLoads = true
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.message().contains("Uncaught")) {
                    val message = "\"${consoleMessage.message()}\", source: " +
                        "${consoleMessage.sourceId()} (${consoleMessage.lineNumber()})"
                    val error = BadWebViewException(message)
                    onInitializationError(error)
                    failAllPending(error)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun loadHtmlAndObtainBotguard(context: Context) {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            withContext(Dispatchers.Main) {
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html.replaceFirst(
                        "</script>",
                        "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                    ),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val responseBody = botGuardPost(
                url = "https://www.youtube.com/api/jnn/v1/Create",
                data = listOf(BOTGUARD_REQUEST_KEY),
                userAgent = WEB_USER_AGENT
            )
            val parsedChallengeData = parseChallengeData(responseBody)
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    """try {
                        data = $parsedChallengeData
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, "Initialization error from JavaScript: $error")
        onInitializationError(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val responseBody = botGuardPost(
                url = "https://www.youtube.com/api/jnn/v1/GenerateIT",
                data = listOf(BOTGUARD_REQUEST_KEY, botguardResponse),
                userAgent = WEB_USER_AGENT
            )
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
            expirationInstant = Instant.now().plusSeconds((expirationTimeInSeconds - 600).coerceAtLeast(60))

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    generatorContinuation?.resume(this@PoTokenWebView)
                    generatorContinuation = null
                }
            }
        }
    }

    override suspend fun generatePoToken(identifier: String): String {
        return suspendCancellableCoroutine { continuation ->
            synchronized(poTokenContinuations) {
                poTokenContinuations[identifier] = continuation
            }
            continuation.invokeOnCancellation {
                synchronized(poTokenContinuations) {
                    poTokenContinuations.remove(identifier)
                }
            }
            val u8Identifier = stringToU8(identifier)
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        if (BuildConfig.DEBUG) Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        popPending(identifier)?.resumeWithException(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            popPending(identifier)?.resumeWithException(t)
            return
        }
        popPending(identifier)?.resume(poToken)
    }

    override fun isExpired(): Boolean =
        !::expirationInstant.isInitialized || Instant.now().isAfter(expirationInstant)

    @MainThread
    override fun close() = with(webView) {
        clearHistory()
        clearCache(true)
        loadUrl("about:blank")
        onPause()
        removeAllViews()
        destroy()
    }

    private fun onInitializationError(error: Throwable) {
        Handler(Looper.getMainLooper()).post {
            runCatching { close() }
            generatorContinuation?.resumeWithException(error)
            generatorContinuation = null
        }
    }

    private fun popPending(identifier: String): CancellableContinuation<String>? =
        synchronized(poTokenContinuations) { poTokenContinuations.remove(identifier) }

    private fun failAllPending(error: Throwable) {
        synchronized(poTokenContinuations) {
            for (continuation in poTokenContinuations.values) {
                continuation.resumeWithException(error)
            }
            poTokenContinuations.clear()
        }
    }

    companion object : PoTokenGenerator.Factory {
        private const val TAG = "PoTokenWebView"
        internal const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        override suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator {
            return suspendCancellableCoroutine { continuation ->
                Handler(Looper.getMainLooper()).post {
                    val poTokenWebView = PoTokenWebView(context, continuation)
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { poTokenWebView.close() }
                    }
                    poTokenWebView.loadHtmlAndObtainBotguard(context)
                }
            }
        }
    }
}
