package com.nexio.tv.domain.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingDisplayFormatterTest {
    @Test
    fun `title rating formatter uses dot decimal under dutch locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))

            assertEquals("8.3", RatingDisplayFormatter.formatTitleRating(8.3))
            assertEquals("8.0", RatingDisplayFormatter.formatTitleRating(8.0f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `aggregate percentage formatter keeps whole numbers compact and decimals locale safe`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))

            assertEquals("87", RatingDisplayFormatter.formatPercentRating(87.0))
            assertEquals("87.5", RatingDisplayFormatter.formatPercentRating(87.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `title rating validator accepts only finite zero through ten values`() {
        assertTrue(RatingValueValidator.validTitleRating(0.0))
        assertTrue(RatingValueValidator.validTitleRating(8.3))
        assertTrue(RatingValueValidator.validTitleRating(10.0))
        assertFalse(RatingValueValidator.validTitleRating(-0.1))
        assertFalse(RatingValueValidator.validTitleRating(10.1))
        assertFalse(RatingValueValidator.validTitleRating(1767427.0))
        assertFalse(RatingValueValidator.validTitleRating(Double.NaN))
        assertFalse(RatingValueValidator.validTitleRating(Double.POSITIVE_INFINITY))
        assertFalse(RatingValueValidator.validTitleRating(null))
    }

    @Test
    fun `percent rating validator accepts only finite zero through one hundred values`() {
        assertTrue(RatingValueValidator.validPercentRating(0.0))
        assertTrue(RatingValueValidator.validPercentRating(87.5))
        assertTrue(RatingValueValidator.validPercentRating(100.0))
        assertFalse(RatingValueValidator.validPercentRating(-0.1))
        assertFalse(RatingValueValidator.validPercentRating(100.1))
        assertFalse(RatingValueValidator.validPercentRating(null as Double?))
        assertFalse(RatingValueValidator.validPercentRating(Double.NaN))
        assertFalse(RatingValueValidator.validPercentRating(Double.POSITIVE_INFINITY))

        assertTrue(RatingValueValidator.validPercentRating(0f))
        assertTrue(RatingValueValidator.validPercentRating(87.5f))
        assertTrue(RatingValueValidator.validPercentRating(100f))
        assertFalse(RatingValueValidator.validPercentRating(-0.1f))
        assertFalse(RatingValueValidator.validPercentRating(100.1f))
        assertFalse(RatingValueValidator.validPercentRating(null as Float?))
        assertFalse(RatingValueValidator.validPercentRating(Float.NaN))
        assertFalse(RatingValueValidator.validPercentRating(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `title rating sanitizer returns null for out of range values`() {
        assertEquals(8.3, RatingValueValidator.sanitizeTitleRating(8.3) ?: 0.0, 0.0)
        assertEquals(8.3f, RatingValueValidator.sanitizeTitleRating(8.3f) ?: 0f, 0f)
        assertNull(RatingValueValidator.sanitizeTitleRating(152596.0))
        assertNull(RatingValueValidator.sanitizeTitleRating(152596f))
    }

    @Test
    fun `percent rating sanitizer returns null for out of range values`() {
        assertEquals(0.0, RatingValueValidator.sanitizePercentRating(0.0) ?: -1.0, 0.0)
        assertEquals(87.5, RatingValueValidator.sanitizePercentRating(87.5) ?: 0.0, 0.0)
        assertEquals(100.0, RatingValueValidator.sanitizePercentRating(100.0) ?: 0.0, 0.0)
        assertNull(RatingValueValidator.sanitizePercentRating(-0.1))
        assertNull(RatingValueValidator.sanitizePercentRating(100.1))
        assertNull(RatingValueValidator.sanitizePercentRating(null))
        assertNull(RatingValueValidator.sanitizePercentRating(Double.NaN))
        assertNull(RatingValueValidator.sanitizePercentRating(Double.POSITIVE_INFINITY))
    }
}
