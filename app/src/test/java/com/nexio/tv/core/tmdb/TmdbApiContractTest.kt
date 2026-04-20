package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import org.junit.Assert.assertEquals
import org.junit.Test
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

    private fun getPath(methodName: String): String {
        return TmdbApi::class.java.methods
            .first { it.name == methodName }
            .getAnnotation(GET::class.java)
            ?.value
            ?: error("Missing @GET on $methodName")
    }
}
