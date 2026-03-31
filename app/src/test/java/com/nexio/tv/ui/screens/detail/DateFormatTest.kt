package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DateFormatTest {

    @Test
    fun `formatReleaseDate uses resolved pattern for timestamp input`() {
        val formatted = formatReleaseDate(
            isoDate = "1994-09-22T00:00:00.000Z",
            locale = Locale.US,
            patternResolver = { "d MMMM yyyy" }
        )

        assertEquals("22 September 1994", formatted)
    }

    @Test
    fun `formatReleaseDate uses resolved pattern for plain date input`() {
        val formatted = formatReleaseDate(
            isoDate = "1994-09-22",
            locale = Locale.US,
            patternResolver = { "yyyy/MM/dd" }
        )

        assertEquals("1994/09/22", formatted)
    }
}
