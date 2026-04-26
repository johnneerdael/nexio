package com.nexio.tv.data.integration.addon.transport

import com.nexio.tv.data.remote.api.StreamSearchRequestTag
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonStreamRequestCancellerTest {
    @Test
    fun `only cancels matching tagged addon stream calls`() {
        val okHttpClient = mockk<OkHttpClient>()
        val dispatcher = mockk<Dispatcher>()
        val runningStreamCall = mockk<Call>(relaxed = true)
        val queuedStreamCall = mockk<Call>(relaxed = true)
        val metaCall = mockk<Call>(relaxed = true)

        every { okHttpClient.dispatcher } returns dispatcher
        every { dispatcher.runningCalls() } returns mutableListOf(runningStreamCall, metaCall)
        every { dispatcher.queuedCalls() } returns mutableListOf(queuedStreamCall)
        every { runningStreamCall.request() } returns Request.Builder()
            .url("https://addon-a.example/stream/movie/tt123.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-a"))
            .build()
        every { queuedStreamCall.request() } returns Request.Builder()
            .url("https://addon-b.example/stream/movie/tt456.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-b"))
            .build()
        every { metaCall.request() } returns Request.Builder()
            .url("https://addon-c.example/meta/movie/tt789.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-a"))
            .build()

        val cancelledCount = AddonStreamRequestCanceller(okHttpClient)
            .cancelActiveStreamRequests("request-a")

        assertEquals(1, cancelledCount)
        verify(exactly = 1) { runningStreamCall.cancel() }
        verify(exactly = 0) { queuedStreamCall.cancel() }
        verify(exactly = 0) { metaCall.cancel() }
    }
}
