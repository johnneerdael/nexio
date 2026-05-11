package com.nexio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `pickNewer returns higher version when a is newer`() {
        assertEquals("v0.57", VersionUtils.pickNewer("v0.57", "v0.56-ea1"))
    }

    @Test
    fun `pickNewer returns higher version when b is newer`() {
        assertEquals("v0.57", VersionUtils.pickNewer("v0.56-ea1", "v0.57"))
    }

    @Test
    fun `pickNewer returns b when a is null`() {
        assertEquals("v0.56", VersionUtils.pickNewer(null, "v0.56"))
    }

    @Test
    fun `pickNewer returns a when b is null`() {
        assertEquals("v0.56", VersionUtils.pickNewer("v0.56", null))
    }

    @Test
    fun `pickNewer returns null when both null`() {
        assertNull(VersionUtils.pickNewer(null, null))
    }

    @Test
    fun `pickNewer returns b on equal versions`() {
        assertEquals("v0.57", VersionUtils.pickNewer("v0.57", "v0.57"))
    }
}
