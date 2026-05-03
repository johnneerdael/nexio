package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeChunkedDataSourceFactoryUserAgentTest {
    @Test
    fun `default user agent matches stable web UA`() {
        val factory = YoutubeChunkedDataSourceFactory()

        assertEquals(YOUTUBE_STABLE_WEB_USER_AGENT, factory.effectiveUserAgent)
    }

    @Test
    fun `override user agent is preserved`() {
        val userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"

        val factory = YoutubeChunkedDataSourceFactory(userAgent = userAgent)

        assertEquals(userAgent, factory.effectiveUserAgent)
    }

    @Test
    fun `blank override falls back to default`() {
        val factory = YoutubeChunkedDataSourceFactory(userAgent = "")

        assertEquals(YOUTUBE_STABLE_WEB_USER_AGENT, factory.effectiveUserAgent)
    }
}
