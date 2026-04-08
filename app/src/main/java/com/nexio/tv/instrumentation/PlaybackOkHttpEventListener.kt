package com.nexio.tv.instrumentation

import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Playback-only OkHttp listener for WP5 range HTTP timing.
 *
 * The listener is installed only on `@Named("playbackTraced")`. It emits only
 * when a request carries a [RangeContext] tag, so non-PRDS playback calls pay a
 * small listener callback/no-op cost but do not write trace records.
 */
class PlaybackOkHttpEventListener : EventListener() {

    override fun callStart(call: Call) {
        emit(call, "range_http_call_start") {
            putString("urlHost", call.request().url.host)
        }
    }

    override fun dnsStart(call: Call, domainName: String) {
        emit(call, "range_http_dns_start") {
            putString("domain", domainName)
        }
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        emit(call, "range_http_dns_end") {
            putString("domain", domainName)
            putInt("addressCount", inetAddressList.size)
        }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        emit(call, "range_http_connect_start") {
            putString("host", inetSocketAddress.hostString)
            putInt("port", inetSocketAddress.port)
            putString("proxyType", proxy.type().name)
        }
    }

    override fun secureConnectStart(call: Call) {
        emit(call, "range_http_secure_connect_start")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        emit(call, "range_http_secure_connect_end") {
            putString("tlsVersion", handshake?.tlsVersion?.javaName)
            putString("cipherSuite", handshake?.cipherSuite?.javaName)
        }
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        emit(call, "range_http_connect_end") {
            putString("host", inetSocketAddress.hostString)
            putInt("port", inetSocketAddress.port)
            putString("protocol", protocol?.toString())
        }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        emit(call, "range_http_connect_failed") {
            putString("host", inetSocketAddress.hostString)
            putInt("port", inetSocketAddress.port)
            putString("protocol", protocol?.toString())
            putString("errorClass", ioe::class.java.name)
            putString("errorMessage", ioe.message)
        }
    }

    override fun requestHeadersStart(call: Call) {
        emit(call, "range_http_request_headers_start")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        emit(call, "range_http_request_headers_end") {
            putString("method", request.method)
        }
    }

    override fun responseHeadersStart(call: Call) {
        emit(call, "range_http_response_headers_start")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        emit(call, "range_http_response_headers_end") {
            putInt("code", response.code)
            putString("protocol", response.protocol.toString())
            putLong("contentLength", response.body?.contentLength() ?: -1L)
        }
    }

    override fun responseBodyStart(call: Call) {
        emit(call, "range_http_response_body_start")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        emit(call, "range_http_response_body_end") {
            putLong("byteCount", byteCount)
        }
    }

    override fun callEnd(call: Call) {
        emit(call, "range_http_call_end")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit(call, "range_http_call_failed") {
            putString("errorClass", ioe::class.java.name)
            putString("errorMessage", ioe.message)
        }
    }

    private inline fun emit(
        call: Call,
        event: String,
        crossinline extra: PayloadBuilder.() -> Unit = {}
    ) {
        if (!PlaybackTracer.enabled) return
        val context = call.request().tag(RangeContext::class.java) ?: return
        PlaybackTracer.emit(EventFamily.RANGE, event) {
            putRangeContext(context)
            putLong("eventAtNanos", SystemClock.elapsedRealtimeNanos())
            extra()
        }
    }

    companion object {
        val FACTORY: EventListener.Factory = EventListener.Factory { PlaybackOkHttpEventListener() }
    }
}

internal fun PayloadBuilder.putRangeContext(context: RangeContext) {
    putLong("chunkIndex", context.chunkIndex)
    putString("lane", context.lane)
    putInt("attempt", context.attempt)
    putLong("requestStart", context.requestStart)
    putLong("requestEndExclusive", context.requestEndExclusive)
    putLong("chunkStart", context.chunkStart)
    putLong("chunkEndExclusive", context.chunkEndExclusive)
    putInt("expectedBytes", context.expectedBytes)
    putInt("resumeOffsetBytes", context.resumeOffsetBytes)
}
