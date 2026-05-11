package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppYouTubeExtractorClientTest {
    @Test
    fun `client list contains tv then ios then android`() {
        assertEquals(listOf("tv", "ios", "android"), CLIENTS_FOR_TEST.sortedBy { it.priority }.map { it.key })
    }

    @Test
    fun `android_vr is no longer in the client list`() {
        assertTrue(CLIENTS_FOR_TEST.none { it.key == "android_vr" })
    }

    @Test
    fun `iOS UA matches NewPipe template`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }

        assertTrue(ios.userAgent.startsWith("com.google.ios.youtube/21.03.2("))
        assertTrue(ios.userAgent.contains("iPhone16,2"))
        assertTrue(ios.userAgent.contains("CPU iOS 18_7_2 like Mac OS X"))
        assertTrue(ios.userAgent.endsWith("US)"))
    }

    @Test
    fun `lookupClientUserAgent returns null for unknown key`() {
        assertEquals(null, lookupClientUserAgentForTest("missing"))
    }

    @Test
    fun `lookupClientUserAgent returns iOS UA when key is ios`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }

        assertEquals(ios.userAgent, lookupClientUserAgentForTest("ios"))
    }
}
