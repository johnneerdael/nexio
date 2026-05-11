package com.nexio.tv.data.trailer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InAppYouTubeExtractorVerifyContentLengthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns content length on successful HEAD + tail-byte verify`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1024"))
        server.enqueue(MockResponse().setResponseCode(206))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(1024L, length)
    }

    @Test
    fun `returns null when HEAD returns 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }

    @Test
    fun `returns null when tail-byte GET returns 404`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1024"))
        server.enqueue(MockResponse().setResponseCode(404))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }

    @Test
    fun `returns null when HEAD has no Content-Length header`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }
}
