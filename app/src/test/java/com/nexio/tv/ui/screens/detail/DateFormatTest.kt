package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateFormatTest {

    @Test
    fun `movie release info prefers full date when iso date is available`() {
        val formatted = formatPrimaryReleaseInfo(
            contentType = ContentType.MOVIE,
            releaseInfo = "2023-03-15",
            locale = Locale.US,
            patternResolver = { "d MMM yyyy" }
        )

        assertEquals("15 Mar 2023", formatted)
    }

    @Test
    fun `movie release info falls back to year when date cannot be formatted`() {
        val formatted = formatPrimaryReleaseInfo(
            contentType = ContentType.MOVIE,
            releaseInfo = "Released in 1999"
        )

        assertEquals("1999", formatted)
    }

    @Test
    fun `series release info stays year based`() {
        val formatted = formatPrimaryReleaseInfo(
            contentType = ContentType.SERIES,
            releaseInfo = "2023-03-15"
        )

        assertEquals("2023", formatted)
    }

    @Test
    fun `blank release info returns null`() {
        assertNull(formatPrimaryReleaseInfo(ContentType.MOVIE, "   "))
    }
}
