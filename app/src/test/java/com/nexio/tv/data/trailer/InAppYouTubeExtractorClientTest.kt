package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppYouTubeExtractorClientTest {

    @Test
    fun `iOS client is the highest-priority HLS client`() {
        val firstByPriority = CLIENTS_FOR_TEST.minByOrNull { it.priority }
        assertEquals("ios", firstByPriority?.key)
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
    fun `Android UA matches NewPipe template`() {
        val android = CLIENTS_FOR_TEST.first { it.key == "android" }
        assertEquals(
            "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
            android.userAgent
        )
    }

    @Test
    fun `iOS context block carries clientName clientVersion deviceModel`() {
        val ios = CLIENTS_FOR_TEST.first { it.key == "ios" }
        assertEquals("IOS", ios.context["clientName"])
        assertEquals("21.03.2", ios.context["clientVersion"])
        assertEquals("iPhone16,2", ios.context["deviceModel"])
    }

    @Test
    fun `client list contains exactly ios then android`() {
        assertEquals(listOf("ios", "android"), CLIENTS_FOR_TEST.sortedBy { it.priority }.map { it.key })
    }
}
