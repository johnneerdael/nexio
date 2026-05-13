package com.nexio.tv.core.artwork.fanarttv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvAvailabilityTest {
    @Test
    fun `available when key is non-blank`() {
        val r = FanartTvAvailability.from("abc123")
        assertTrue(r is FanartTvAvailability.Available)
        assertEquals("abc123", (r as FanartTvAvailability.Available).apiKey)
    }

    @Test
    fun `disabled when key is empty`() {
        assertEquals(FanartTvAvailability.Disabled("no_build_config_key"), FanartTvAvailability.from(""))
    }

    @Test
    fun `disabled when key is blank whitespace`() {
        assertEquals(FanartTvAvailability.Disabled("no_build_config_key"), FanartTvAvailability.from("   "))
    }
}
