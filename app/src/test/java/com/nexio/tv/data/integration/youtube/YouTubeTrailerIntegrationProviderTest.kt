package com.nexio.tv.data.integration.youtube

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransport
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportCall
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransportResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTrailerIntegrationProviderTest {
    @Test
    fun `fetch routes youtube trailer requests through runtime owned provider`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<YouTubeTrailerTransport>()
        val specSlot = slot<IntegrationCallSpec<YouTubeTrailerTransportResponse>>()
        val call = YouTubeTrailerTransportCall(
            url = "https://www.youtube.com/watch?v=abc123def45&hl=en",
            method = "GET",
            headers = mapOf("accept" to "*/*")
        )

        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationCallSpec<YouTubeTrailerTransportResponse>>().call()
            }
        }
        every { transport.execute(call) } returns YouTubeTrailerTransportResponse(
            statusCode = 200,
            isSuccessful = true,
            statusText = "OK",
            url = call.url,
            body = "<html>watch</html>"
        )

        val provider = YouTubeTrailerIntegrationProvider(runtime, transport)
        val result = provider.fetch(call)

        assertTrue(result is IntegrationCallResult.Success<*>)
        assertEquals(
            "<html>watch</html>",
            (result as IntegrationCallResult.Success<YouTubeTrailerTransportResponse>).value.body
        )
        assertEquals(IntegrationProvider.YOUTUBE_TRAILER, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("youtube:trailer"), specSlot.captured.scope)
    }

    @Test
    fun `probe routes through runtime and preserves transport boolean`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<YouTubeTrailerTransport>()
        val specSlot = slot<IntegrationCallSpec<Boolean>>()

        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationCallSpec<Boolean>>().call()
            }
        }
        every {
            transport.probe(
                url = "https://rr1---sn.example.googlevideo.com/videoplayback?id=123",
                headers = mapOf("range" to "bytes=0-0")
            )
        } returns true

        val provider = YouTubeTrailerIntegrationProvider(runtime, transport)
        val result = provider.probe(
            url = "https://rr1---sn.example.googlevideo.com/videoplayback?id=123",
            headers = mapOf("range" to "bytes=0-0")
        )

        assertTrue(result)
        assertEquals(IntegrationProvider.YOUTUBE_TRAILER, specSlot.captured.provider)
    }
}
