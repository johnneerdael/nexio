package com.nexio.tv.data.integration.playback.transport

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource

internal fun diskSpoolRequestBuilder(
    url: String,
    requestHeaders: Map<String, String>
): Request.Builder {
    val builder = Request.Builder().url(url)
    requestHeaders.forEach { (key, value) ->
        if (!key.equals("Range", ignoreCase = true)) {
            builder.header(key, value)
        }
    }
    return builder
}

internal interface DiskSpoolHttpTransport {
    fun <T> execute(
        url: String,
        requestHeaders: Map<String, String>,
        rangeHeader: String,
        isCancelled: () -> Boolean,
        monitorName: String,
        interruptedMessage: String,
        block: (DiskSpoolHttpResponse) -> T
    ): T
}

internal interface DiskSpoolHttpResponse : Closeable {
    val code: Int
    val isSuccessful: Boolean

    fun header(name: String): String?

    fun bodySource(): BufferedSource?
}

internal class OkHttpDiskSpoolHttpTransport @Inject constructor(
    @Named("playback") private val okHttpClient: OkHttpClient
) : DiskSpoolHttpTransport {
    override fun <T> execute(
        url: String,
        requestHeaders: Map<String, String>,
        rangeHeader: String,
        isCancelled: () -> Boolean,
        monitorName: String,
        interruptedMessage: String,
        block: (DiskSpoolHttpResponse) -> T
    ): T {
        val request = diskSpoolRequestBuilder(url, requestHeaders)
            .header("Range", rangeHeader)
            .build()
        val call = okHttpClient.newCall(request)
        if (isCancelled()) {
            call.cancel()
            throw IOException(interruptedMessage)
        }

        val callFinished = AtomicBoolean(false)
        val cancellationMonitor = Thread {
            while (!callFinished.get()) {
                if (isCancelled()) {
                    call.cancel()
                    return@Thread
                }
                try {
                    Thread.sleep(25L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        cancellationMonitor.isDaemon = true
        cancellationMonitor.name = monitorName
        cancellationMonitor.start()

        try {
            call.execute().use { response ->
                if (isCancelled()) {
                    call.cancel()
                    throw IOException(interruptedMessage)
                }
                return block(OkHttpDiskSpoolHttpResponse(response))
            }
        } finally {
            callFinished.set(true)
            cancellationMonitor.interrupt()
        }
    }
}

private class OkHttpDiskSpoolHttpResponse(
    private val response: okhttp3.Response
) : DiskSpoolHttpResponse {
    override val code: Int
        get() = response.code

    override val isSuccessful: Boolean
        get() = response.isSuccessful

    override fun header(name: String): String? = response.header(name)

    override fun bodySource(): BufferedSource? = response.body?.source()

    override fun close() {
        response.close()
    }
}
