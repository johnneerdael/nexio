package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionCodecStringsTest {

    @Test
    fun `parses hevc dolby vision profiles`() {
        assertEquals(5, resolveDolbyVisionProfileFromCodecString("dvhe.05.06"))
        assertEquals(7, resolveDolbyVisionProfileFromCodecString("dvh1.07.06"))
    }

    @Test
    fun `parses avc and av1 dolby vision profiles`() {
        assertEquals(9, resolveDolbyVisionProfileFromCodecString("dvav.09.01"))
        assertEquals(9, resolveDolbyVisionProfileFromCodecString("dva1.09.01"))
        assertEquals(10, resolveDolbyVisionProfileFromCodecString("dav1.10.09"))
    }

    @Test
    fun `parses supplemental dolby vision codec from comma separated codecs`() {
        assertEquals(10, resolveDolbyVisionProfileFromCodecString("av01.0.13M.10,dav1.10.09"))
    }

    @Test
    fun `ignores non dolby vision codecs and malformed profiles`() {
        assertNull(resolveDolbyVisionProfileFromCodecString("av01.0.13M.10"))
        assertNull(resolveDolbyVisionProfileFromCodecString("hevc"))
        assertNull(resolveDolbyVisionProfileFromCodecString("dav1.profile10.09"))
        assertNull(resolveDolbyVisionProfileFromCodecString(null))
    }
}
