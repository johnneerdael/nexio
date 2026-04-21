package com.nexio.tv.core.player

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenSubtitlesHasherTest {

    private lateinit var server: MockWebServer

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/opensubtitles/hash_testfile.bin")!!.use { it.readBytes() }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `computes canonical oshash for 1MB reference vector via range requests`() = runTest {
        val allBytes = fixtureBytes()
        val totalSize = allBytes.size.toLong()
        require(totalSize == 1_048_576L) { "fixture size mismatch: $totalSize" }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse().setHeader("Content-Length", totalSize.toString())
                    "GET" -> {
                        val range = request.getHeader("Range") ?: error("expected Range header")
                        val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
                            .destructured.let { (a, b) -> a.toInt() to b.toInt() }
                        val slice = allBytes.copyOfRange(from, to + 1)
                        MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Length", slice.size.toString())
                            .setBody(Buffer().write(slice))
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val result = OpenSubtitlesHasher.compute(
            url = server.url("/video.mkv").toString(),
            headers = emptyMap()
        )

        assertNotNull(result)
        assertEquals("e7e2e71e035b137f", result!!.hash)
        assertEquals(1_048_576L, result.fileSize)
    }

    @Test
    fun `returns null when Content-Length missing`() = runTest {
        server.enqueue(MockResponse())
        val result = OpenSubtitlesHasher.compute(server.url("/x").toString(), emptyMap())
        assertNull(result)
    }

    @Test
    fun `returns null when file smaller than 128KB`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", "131071"))
        val result = OpenSubtitlesHasher.compute(server.url("/small").toString(), emptyMap())
        assertNull(result)
    }
}
