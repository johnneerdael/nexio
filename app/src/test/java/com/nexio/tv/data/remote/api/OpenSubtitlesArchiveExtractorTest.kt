package com.nexio.tv.data.remote.api

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenSubtitlesArchiveExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var extractor: OpenSubtitlesArchiveExtractor

    private fun load(name: String): ByteArray =
        javaClass.getResourceAsStream("/opensubtitles/$name")!!.use { it.readBytes() }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        extractor = OpenSubtitlesArchiveExtractor(
            okHttpClient = OkHttpClient(),
            cacheDir = tmp.newFolder("subs"),
            userAgent = "TestAgent/1.0"
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `plain srt download is written verbatim`() {
        val body = load("sample.srt")
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val out = extractor.download(server.url("/x.srt").toString(), "ID-123")
        assertNotNull(out)
        assertTrue(out!!.name.endsWith(".srt"))
        assertEquals(body.toList(), out.readBytes().toList())
    }

    @Test
    fun `gzipped srt is decompressed`() {
        val expected = load("sample.srt")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-gzip")
                .setBody(Buffer().write(load("sample.srt.gz")))
        )
        val out = extractor.download(server.url("/x.gz").toString(), "ID-456")
        assertNotNull(out)
        assertEquals(expected.toList(), out!!.readBytes().toList())
    }

    @Test
    fun `zip archive yields the inner srt`() {
        val expected = load("sample.srt")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/zip")
                .setBody(Buffer().write(load("sample.zip")))
        )
        val out = extractor.download(server.url("/x.zip").toString(), "ID-789")
        assertNotNull(out)
        assertTrue(out!!.name.endsWith(".srt"))
        assertEquals(expected.toList(), out.readBytes().toList())
    }

    @Test
    fun `non-2xx returns null`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val out = extractor.download(server.url("/missing").toString(), "ID-X")
        assertNull(out)
    }

    @Test
    fun `subtitleId is sanitized into the filename`() {
        server.enqueue(MockResponse().setBody(Buffer().write(load("sample.srt"))))
        val out = extractor.download(server.url("/x.srt").toString(), "../../etc/passwd")
        assertNotNull(out)
        assertTrue(out!!.name.startsWith("os-etcpasswd"))
    }
}
