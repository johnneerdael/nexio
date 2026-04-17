package com.nexio.tv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMinutesTest {

    @Test
    fun `parse runtime minutes accepts plain and decorated values`() {
        assertEquals(49, parseRuntimeMinutes("49"))
        assertEquals(49, parseRuntimeMinutes("49m"))
        assertEquals(49, parseRuntimeMinutes("49 minutes"))
        assertEquals(125, parseRuntimeMinutes("125 min"))
    }

    @Test
    fun `parse runtime minutes rejects missing non numeric and non positive values`() {
        assertNull(parseRuntimeMinutes(null))
        assertNull(parseRuntimeMinutes(""))
        assertNull(parseRuntimeMinutes("unknown"))
        assertNull(parseRuntimeMinutes("0"))
    }

    @Test
    fun `episode runtime wins over series average runtime`() {
        assertEquals(
            64,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = 64,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is missing`() {
        assertEquals(
            49,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = "49 minutes"
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is not positive`() {
        assertEquals(
            49,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = 0,
                seriesRuntime = "49"
            )
        )
    }
}
