package com.nexio.tv.data.integration.addon.transport

import com.nexio.tv.data.remote.api.StreamSearchRequestTag
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class AddonStreamRequestCanceller @Inject constructor(
    @Named("addonStreams") private val okHttpClient: OkHttpClient
) {
    fun cancelActiveStreamRequests(requestId: String): Int {
        val calls = okHttpClient.dispatcher.runningCalls() + okHttpClient.dispatcher.queuedCalls()
        var cancelledCount = 0
        calls.forEach { call ->
            val request = call.request()
            val tag = request.tag(StreamSearchRequestTag::class.java)
            val isStreamRequest = request.url.encodedPath.contains("/stream/")
            if (isStreamRequest && tag?.requestId == requestId) {
                cancelledCount += 1
                call.cancel()
            }
        }
        return cancelledCount
    }
}
