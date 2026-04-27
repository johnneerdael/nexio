package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.GET

class TmdbApiContractTest {
    @Test
    fun `tmdb api exposes search and stock catalog endpoints`() {
        assertEquals("search/movie", getPath("searchMovies"))
        assertEquals("search/tv", getPath("searchTv"))
        assertEquals("search/multi", getPath("searchMulti"))
        assertEquals("trending/movie/{time_window}", getPath("getTrendingMovies"))
        assertEquals("trending/tv/{time_window}", getPath("getTrendingTv"))
        assertEquals("movie/popular", getPath("getPopularMovies"))
        assertEquals("tv/popular", getPath("getPopularTv"))
        assertEquals("discover/movie", getPath("discoverMovies"))
        assertEquals("discover/tv", getPath("discoverTv"))
    }

    @Test
    fun `trending endpoints expose only time window path and api key plus language queries`() {
        assertEquals(setOf("api_key", "language"), getQueryNames("getTrendingMovies"))
        assertEquals(setOf("time_window"), getPathNames("getTrendingMovies"))
        assertEquals(setOf("api_key", "language"), getQueryNames("getTrendingTv"))
        assertEquals(setOf("time_window"), getPathNames("getTrendingTv"))
    }

    @Test
    fun `search and discover endpoints expose expected query contract`() {
        assertEquals(
            setOf("api_key", "query", "language", "include_adult", "page"),
            getQueryNames("searchMovies")
        )
        assertEquals(
            setOf("api_key", "query", "language", "include_adult", "page"),
            getQueryNames("searchTv")
        )
        assertEquals(
            setOf("api_key", "query", "language", "include_adult", "page"),
            getQueryNames("searchMulti")
        )
        assertEquals(
            setOf(
                "api_key",
                "language",
                "include_adult",
                "include_video",
                "page",
                "sort_by",
                "primary_release_year",
                "primary_release_date.lte",
                "release_date.lte",
                "with_original_language",
                "with_release_type",
                "region"
            ),
            getQueryNames("discoverMovies")
        )
        assertEquals(
            setOf(
                "api_key",
                "language",
                "include_adult",
                "include_null_first_air_dates",
                "page",
                "sort_by",
                "first_air_date_year",
                "first_air_date.lte",
                "with_original_language"
            ),
            getQueryNames("discoverTv")
        )
    }

    @Test
    fun `trending endpoints default to day and omit page query when time window is omitted`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/3/"))
                .addConverterFactory(
                    MoshiConverterFactory.create(
                        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                    )
                )
                .build()
                .create(TmdbApi::class.java)

            server.enqueue(MockResponse().setBody("""{ "page": 1, "results": [] }"""))
            val moviesResponse = api.getTrendingMovies(apiKey = "key")
            assertNotNull(moviesResponse.body())

            val moviesRequest = requireNotNull(server.takeRequest())
            assertTrue(moviesRequest.path!!.contains("/3/trending/movie/day"))
            assertFalse(moviesRequest.path!!.contains("page="))

            server.enqueue(MockResponse().setBody("""{ "page": 1, "results": [] }"""))
            val tvResponse = api.getTrendingTv(apiKey = "key")
            assertNotNull(tvResponse.body())

            val tvRequest = requireNotNull(server.takeRequest())
            assertTrue(tvRequest.path!!.contains("/3/trending/tv/day"))
            assertFalse(tvRequest.path!!.contains("page="))
        } finally {
            server.shutdown()
        }
    }

    private fun getPath(methodName: String): String {
        return TmdbApi::class.java.methods
            .first { it.name == methodName }
            .getAnnotation(GET::class.java)
            ?.value
            ?: error("Missing @GET on $methodName")
    }

    private fun getQueryNames(methodName: String): Set<String> {
        return method(methodName).parameterAnnotations
            .flatten()
            .filterIsInstance<Query>()
            .map { it.value }
            .toSet()
    }

    private fun getPathNames(methodName: String): Set<String> {
        return method(methodName).parameterAnnotations
            .flatten()
            .filterIsInstance<Path>()
            .map { it.value }
            .toSet()
    }

    private fun method(methodName: String) = TmdbApi::class.java.methods.first { it.name == methodName }
}
