package com.nexio.tv.data.trailer.helper

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTrailerCookieStoreTest {

    @Test
    fun `parseCookieHeader keeps youtube auth cookies`() {
        val records = parseCookieHeader(
            header = "SID=abc; HSID=def; PREF=lang=en",
            domain = ".youtube.com"
        )

        assertTrue(records.any { it.name == "SID" && it.value == "abc" })
        assertTrue(records.any { it.name == "HSID" && it.value == "def" })
        assertTrue(records.any { it.domain == ".youtube.com" })
    }
}
