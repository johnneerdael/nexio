package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.GET

class TmdbApiContractTest {
    @Test
    fun `tmdb api exposes search and stock catalog endpoints`() {
        assertEquals("search/movie", getPath("searchMovies"))
        assertEquals("search/tv", getPath("searchTv"))
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
