package com.nexio.tv.instrumentation

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Per-range HTTP context propagated onto OkHttp [Request] tags for WP5.
 *
 * The range worker records the context in [RangeContextHolder] immediately
 * around `OkHttpDataSource.open(...)`; [PlaybackRangeContextCallFactory] then
 * tags the OkHttp request that Media3 builds internally. This keeps the
 * playback OkHttp listener scoped to actual PRDS range attempts without
 * modifying Media3's public DataSource surface.
 */
data class RangeContext(
    val chunkIndex: Long,
    val lane: String,
    val attempt: Int,
    val requestStart: Long,
    val requestEndExclusive: Long,
    val chunkStart: Long,
    val chunkEndExclusive: Long,
    val expectedBytes: Int,
    val resumeOffsetBytes: Int
)

internal object RangeContextHolder {
    private val current = ThreadLocal<RangeContext?>()

    fun current(): RangeContext? = current.get()

    inline fun <T> withContext(context: RangeContext, block: () -> T): T {
        current.set(context)
        try {
            return block()
        } finally {
            current.remove()
        }
    }
}

/**
 * Tags OkHttp calls built by Media3's OkHttpDataSource with the current
 * [RangeContext]. The delegate [OkHttpClient] remains the traced playback
 * client, so its dispatcher, connection pool, and EventListener are unchanged.
 */
class PlaybackRangeContextCallFactory(
    private val delegate: OkHttpClient
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val context = RangeContextHolder.current()
        val taggedRequest = if (context != null && request.tag(RangeContext::class.java) == null) {
            request.newBuilder()
                .tag(RangeContext::class.java, context)
                .build()
        } else {
            request
        }
        return delegate.newCall(taggedRequest)
    }
}
