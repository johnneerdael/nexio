package com.nexio.tv.data.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OpenSubtitlesApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenSubtitlesApiClient

    private fun loadFixtureBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/opensubtitles/$name")!!
            .use { it.readBytes() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenSubtitlesRestApi::class.java)
        client = OpenSubtitlesApiClient(
            api = api,
            userAgent = "TestAgent/1.0",
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun enqueueFixture(name: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(loadFixtureBytes(name)))
        )
    }

    @Test
    fun `searchByImdb hits imdbid path with sorted filters and parses 100 rows`() = runTest {
        enqueueFixture("search_movie_imdbid.json")
        val results = client.searchByImdb(imdbId = "tt0111161", languages = "eng")
        assertEquals(100, results.size)

        val request = server.takeRequest()
        assertEquals("/search/imdbid-111161/sublanguageid-eng", request.path)
        assertEquals("TestAgent/1.0", request.getHeader("User-Agent"))

        val first = results.first()
        assertEquals("eng", first.languageCode)
        assertTrue(first.downloadUrl.startsWith("https://dl.opensubtitles.org/"))
        assertNull("imdbid match should not surface a hash", first.movieHash)
    }

    @Test
    fun `searchSeriesEpisode builds episode imdbid season sublanguageid in alphabetical order`() = runTest {
        enqueueFixture("search_series_episode.json")
        val results = client.searchSeriesEpisode(
            imdbId = "tt0944947",
            season = 1,
            episode = 2,
            languages = "eng"
        )
        assertTrue(results.isNotEmpty())

        val request = server.takeRequest()
        assertEquals(
            "/search/episode-2/imdbid-944947/season-1/sublanguageid-eng",
            request.path
        )
    }

    @Test
    fun `searchByHash builds moviebytesize moviehash filters and surfaces movieHash`() = runTest {
        enqueueFixture("search_moviehash.json")
        val results = client.searchByHash(
            movieHash = "8E245D9679D31E12",
            movieByteSize = 12_909_756L
        )
        assertTrue(results.isNotEmpty())

        val request = server.takeRequest()
        assertEquals(
            "/search/moviebytesize-12909756/moviehash-8e245d9679d31e12",
            request.path
        )

        val first = results.first()
        assertEquals("8e245d9679d31e12", first.movieHash)
    }

    @Test
    fun `empty fixture yields empty result list`() = runTest {
        enqueueFixture("search_empty.json")
        val results = client.searchByImdb(imdbId = "tt9999999")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-2xx response yields empty list rather than throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream"))
        val results = client.searchByImdb(imdbId = "tt0111161")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `numeric-as-string fields parse correctly into typed fields`() = runTest {
        enqueueFixture("search_movie_imdbid.json")
        val first = client.searchByImdb("tt0111161").first()
        assertNotNull(first.downloads)
        assertTrue(first.downloads!! >= 0)
        assertFalse("download url is non-blank", first.downloadUrl.isBlank())
    }
}
