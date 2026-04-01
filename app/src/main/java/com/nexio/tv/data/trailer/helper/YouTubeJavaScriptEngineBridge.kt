package com.nexio.tv.data.trailer.helper

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val JS_ENGINE_TAG = "YouTubeJsEngine"
private const val SOLVER_ASSET_NAME = "yt.solver.android.min.js"
private const val SANDBOX_TIMEOUT_SECONDS = 30L
private const val EVALUATION_TIMEOUT_SECONDS = 60L

@Keep
object YouTubeJavaScriptEngineBridge {
    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    @Volatile
    private var isolate: JavaScriptIsolate? = null

    @JvmStatic
    fun initialize(context: Context): Boolean {
        applicationContext = context.applicationContext
        return runCatching {
            synchronized(this) {
                ensureIsolateLocked()
            }
            true
        }.getOrElse { error ->
            Log.w(JS_ENGINE_TAG, "JavaScriptEngine initialization failed: ${error.message}", error)
            clear()
            false
        }
    }

    @JvmStatic
    fun isReady(): Boolean = isolate != null

    @JvmStatic
    @Synchronized
    fun solveSerialized(requestJson: String): String {
        val readyIsolate = ensureIsolateLocked()
        return readyIsolate.evaluateJavaScriptAsync(
            "androidSolver.solveSerialized(${requestJson.asJavaScriptStringLiteral()})"
        ).get(EVALUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @JvmStatic
    @Synchronized
    fun inspectRuntime(): String {
        val readyIsolate = ensureIsolateLocked()
        return readyIsolate.evaluateJavaScriptAsync(
            "androidSolver.inspectRuntime()"
        ).get(EVALUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun clear() {
        synchronized(this) {
            isolate?.close()
            isolate = null
            sandbox?.close()
            sandbox = null
        }
    }

    @Synchronized
    private fun ensureIsolateLocked(): JavaScriptIsolate {
        isolate?.let { return it }

        check(JavaScriptSandbox.isSupported()) {
            "JavaScriptSandbox is not supported on this device"
        }

        val context = requireNotNull(applicationContext) {
            "YouTubeJavaScriptEngineBridge.initialize(context) must be called before use"
        }

        val connectedSandbox = JavaScriptSandbox.createConnectedInstanceAsync(context)
            .get(SANDBOX_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val evaluateFromFd =
            connectedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD)
        val withoutTransactionLimit = connectedSandbox.isFeatureSupported(
            JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT
        )
        check(withoutTransactionLimit) {
            "JavaScriptSandbox is missing JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT"
        }

        val createdIsolate = connectedSandbox.createIsolate()
        runCatching {
            loadBundle(context, createdIsolate, evaluateFromFd)
        }.getOrElse { error ->
            createdIsolate.close()
            connectedSandbox.close()
            throw error
        }

        sandbox = connectedSandbox
        isolate = createdIsolate
        Log.d(JS_ENGINE_TAG, "JavaScriptEngine ready (evaluateFromFd=$evaluateFromFd)")
        return createdIsolate
    }

    private fun loadBundle(
        context: Context,
        targetIsolate: JavaScriptIsolate,
        evaluateFromFd: Boolean
    ) {
        if (evaluateFromFd) {
            runCatching {
                context.assets.openFd(SOLVER_ASSET_NAME).use { descriptor ->
                    evaluateBundleDescriptor(targetIsolate, descriptor)
                }
            }.onSuccess {
                return
            }.onFailure { error ->
                Log.w(JS_ENGINE_TAG, "Falling back to string asset load: ${error.message}")
            }
        }

        targetIsolate.evaluateJavaScriptAsync(readAssetText(context, SOLVER_ASSET_NAME))
            .get(EVALUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun evaluateBundleDescriptor(
        targetIsolate: JavaScriptIsolate,
        descriptor: AssetFileDescriptor
    ) {
        targetIsolate.evaluateJavaScriptAsync(descriptor)
            .get(EVALUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun readAssetText(context: Context, assetName: String): String {
        context.assets.open(assetName).use { input ->
            return input.readUtf8Text()
        }
    }
}

internal fun String.asJavaScriptStringLiteral(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@asJavaScriptStringLiteral) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

private fun InputStream.readUtf8Text(): String {
    val output = ByteArrayOutputStream()
    copyTo(output)
    return output.toString(StandardCharsets.UTF_8.name())
}
