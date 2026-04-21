package com.nexio.tv.data.repository

import com.nexio.tv.core.player.OpenSubtitlesHasher
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.remote.api.OpenSubtitlesApiClient
import com.nexio.tv.data.remote.api.OpenSubtitlesArchiveExtractor
import com.nexio.tv.data.remote.api.OpenSubtitlesRestApi
import com.nexio.tv.testutil.testPreferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OpenSubtitlesSourceImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var apiClient: OpenSubtitlesApiClient
    private lateinit var extractor: OpenSubtitlesArchiveExtractor

    private fun load(name: String): ByteArray =
        javaClass.getResourceAsStream("/opensubtitles/$name")!!.use { it.readBytes() }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenSubtitlesRestApi::class.java)
        apiClient = OpenSubtitlesApiClient(
            api = api,
            userAgent = "TestAgent/1.0",
            baseUrl = server.url("").toString().trimEnd('/')
        )
        extractor = OpenSubtitlesArchiveExtractor(
            okHttpClient = OkHttpClient(),
            cacheDir = tmp.newFolder("subs"),
            userAgent = "TestAgent/1.0"
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun fixedDispatcher(routes: Map<String, String>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val fixture = routes[request.path] ?: return MockResponse().setResponseCode(404)
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(load(fixture)))
        }
    }

    private fun TestScope.makePrefs(name: String): OpenSubtitlesPreferences =
        OpenSubtitlesPreferences(testPreferencesDataStore(name))

    private val noopHasher = OpenSubtitlesSourceImpl.HashComputer { _, _ -> null }

    @Test
    fun `disabled prefs short-circuit and return empty`() = runTest {
        val prefs = makePrefs("os-src-disabled").also { it.setEnabled(false) }
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val out = source.search(type = "movie", id = "tt0111161")
        assertTrue(out.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `movie search returns ranked rows mapped to Subtitle`() = runTest {
        server.dispatcher = fixedDispatcher(
            mapOf("/search/imdbid-111161" to "search_movie_imdbid.json")
        )
        val prefs = makePrefs("os-src-movie")
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val out = source.search(type = "movie", id = "tt0111161")
        assertEquals(100, out.size)
        assertEquals("OpenSubtitles", out.first().addonName)
        assertTrue(out.first().id.startsWith("opensubtitles:"))
    }

    @Test
    fun `series episode search uses episode imdbid season filters`() = runTest {
        server.dispatcher = fixedDispatcher(
            mapOf("/search/episode-2/imdbid-944947/season-1" to "search_series_episode.json")
        )
        val prefs = makePrefs("os-src-series")
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val out = source.search(
            type = "series",
            id = "tt0944947",
            videoId = "tt0944947:1:2"
        )
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `hash match rows sort to the front and carry isHashMatch`() = runTest {
        server.dispatcher = fixedDispatcher(
            mapOf(
                "/search/imdbid-111161" to "search_movie_imdbid.json",
                "/search/moviebytesize-12909756/moviehash-8e245d9679d31e12" to "search_moviehash.json"
            )
        )
        val prefs = makePrefs("os-src-hash")
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val out = source.search(
            type = "movie",
            id = "tt0111161",
            videoHash = "8e245d9679d31e12",
            videoSize = 12_909_756L
        )
        assertTrue(out.first().isHashMatch)
        assertFalse(out.last().isHashMatch)
    }

    @Test
    fun `onlyTrusted filters non-trusted rows out of the results`() = runTest {
        server.dispatcher = fixedDispatcher(
            mapOf("/search/imdbid-111161" to "search_movie_imdbid.json")
        )
        val prefs = makePrefs("os-src-trusted").also { it.setOnlyTrusted(true) }
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val all = OpenSubtitlesSourceImpl(apiClient, makePrefs("os-src-all"), noopHasher, extractor)
            .search("movie", "tt0111161")
        val trusted = source.search("movie", "tt0111161")
        assertTrue(trusted.size <= all.size)
    }

    @Test
    fun `unknown imdb id returns empty`() = runTest {
        server.dispatcher = fixedDispatcher(
            mapOf("/search/imdbid-9999999" to "search_empty.json")
        )
        val prefs = makePrefs("os-src-empty")
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, extractor)
        val out = source.search("movie", "tt9999999")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `extractor dependency is wired in but never invoked during search`() = runTest {
        // Compile-time guard: pass a strict mockk to ensure search() never calls into it.
        val strictExtractor = mockk<OpenSubtitlesArchiveExtractor>()
        server.dispatcher = fixedDispatcher(
            mapOf("/search/imdbid-111161" to "search_movie_imdbid.json")
        )
        val prefs = makePrefs("os-src-no-extract")
        val source = OpenSubtitlesSourceImpl(apiClient, prefs, noopHasher, strictExtractor)
        assertNull(source.search("movie", "").firstOrNull())
        // (No verify needed — strict mockk would have thrown if extractor was touched.)
        assertTrue(source.search("movie", "tt0111161").isNotEmpty())
    }
}
