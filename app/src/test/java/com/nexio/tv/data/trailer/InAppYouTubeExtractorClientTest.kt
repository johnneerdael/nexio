package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppYouTubeExtractorClientTest {
    @Test
    fun `active client list matches NewPipeExtractor default stream flow`() {
        assertEquals(
            listOf("android"),
            CLIENTS_FOR_TEST.sortedBy { it.priority }.map { it.key }
        )
    }

    @Test
    fun `android client matches NewPipe GAPIS shape`() {
        val android = CLIENTS_FOR_TEST.first { it.key == "android" }

        assertEquals("ANDROID", android.context["clientName"])
        assertEquals("21.03.36", android.context["clientVersion"])
        assertEquals("WATCH", android.context["clientScreen"])
        assertEquals("Android", android.context["osName"])
        assertEquals("16", android.context["osVersion"])
        assertEquals(36, android.context["androidSdkVersion"])
        assertEquals("en-US", android.context["hl"])
        assertEquals("US", android.context["gl"])
        assertTrue(android.userAgent.contains("com.google.android.youtube/21.03.36"))
        assertTrue(android.userAgent.contains("Android 15"))
    }

    @Test
    fun `iOS and web embedded are not active stream clients by default`() {
        assertTrue(CLIENTS_FOR_TEST.none { it.key == "ios" })
        assertTrue(CLIENTS_FOR_TEST.none { it.key == "web_embedded" })
    }

    @Test
    fun `lookupClientUserAgent returns null for unknown key`() {
        assertEquals(null, lookupClientUserAgentForTest("missing"))
    }

    @Test
    fun `lookupClientUserAgent returns null for inactive iOS key`() {
        assertEquals(null, lookupClientUserAgentForTest("ios"))
    }
}
