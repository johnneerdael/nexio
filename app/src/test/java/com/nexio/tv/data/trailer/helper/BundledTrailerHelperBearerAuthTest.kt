package com.nexio.tv.data.trailer.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledTrailerHelperBearerAuthTest {

    @Test
    fun `helper request carries authorization header instead of cookies`() {
        val request = TrailerHelperRequest(
            youtubeUrl = "https://www.youtube.com/watch?v=test1234567",
            authorizationHeader = "Bearer token",
            pageId = null
        )

        assertEquals("Bearer token", request.authorizationHeader)
        assertEquals(null, request.pageId)
    }
}
