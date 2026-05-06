package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceRedactorTest {
    private val r = TraceRedactor()

    @Test
    fun `URL api_key query is redacted`() {
        val redacted = r.redactUrl("https://api.themoviedb.org/3/movie/550?api_key=ABC123&language=en")
        assertTrue(redacted.contains("api_key=<redacted>"))
        assertFalse(redacted.contains("ABC123"))
    }

    @Test
    fun `URL access_token and refresh_token are redacted`() {
        val redacted = r.redactUrl("https://x/auth?access_token=AT_SECRET&refresh_token=RT_SECRET")
        assertFalse(redacted.contains("AT_SECRET"))
        assertFalse(redacted.contains("RT_SECRET"))
    }

    @Test
    fun `RPDB path credential is redacted`() {
        val redacted = r.redactUrl(
            "https://api.ratingposterdb.com/rpdb-secret/imdb/poster-default/tt0137523.jpg"
        )

        assertEquals(
            "https://api.ratingposterdb.com/<redacted>/imdb/poster-default/tt0137523.jpg",
            redacted
        )
        assertFalse(redacted.contains("rpdb-secret"))
    }

    @Test
    fun `Top Posters path credential is redacted and query redaction still applies`() {
        val redacted = r.redactUrl(
            "https://api.top-posters.com/top-secret/tvdb/thumbnail/1399/S1E2.jpg?api_key=QUERY_SECRET&blur=false"
        )

        assertEquals(
            "https://api.top-posters.com/<redacted>/tvdb/thumbnail/1399/S1E2.jpg?api_key=<redacted>&blur=false",
            redacted
        )
        assertFalse(redacted.contains("top-secret"))
        assertFalse(redacted.contains("QUERY_SECRET"))
    }

    @Test
    fun `non provider path segment is not redacted`() {
        val redacted = r.redactUrl("https://image.tmdb.org/t/p/w500/poster.jpg")

        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", redacted)
    }

    @Test
    fun `Authorization header value is redacted`() {
        val out = r.redactHeaders(mapOf("Authorization" to "Bearer SECRET"))
        assertEquals("<redacted>", out["Authorization"])
    }

    @Test
    fun `case-insensitive header name match`() {
        val out = r.redactHeaders(mapOf("authorization" to "Bearer X"))
        assertEquals("<redacted>", out["authorization"])
    }

    @Test
    fun `JSON body access_token field is redacted`() {
        val body = """{"access_token":"SECRET","other":"keep"}"""
        val out = r.redactJsonBody(body)
        assertFalse(out.contains("SECRET"))
        assertTrue(out.contains("\"keep\""))
    }
}
