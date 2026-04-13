package com.nexio.tv.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamDetailLinesTest {

    private val baseStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

    @Test
    fun `applies text style token at any position and strips marker from display text`() {
        val detailLines = listOf(
            "💾 41.38 GB",
            "[[icon:prime]] Amazon • [[icon:premiumize]] Premiumize",
            "[[text:7:10]]Avatar.Fire.And.Ash.2025.2160p.AMZN.WEB-DL.DDP5.1.mkv",
            "[[icon:hdr10:1.75]] [[icon:ddp:1.75]]"
        )

        assertFalse(shouldCompactFinalFilenameLine(detailLines, 0))
        assertFalse(shouldCompactFinalFilenameLine(detailLines, 1))
        assertFalse(shouldCompactFinalFilenameLine(detailLines, 2))
        assertFalse(shouldCompactFinalFilenameLine(detailLines, 3))
        assertEquals(
            "Avatar.Fire.And.Ash.2025.2160p.AMZN.WEB-DL.DDP5.1.mkv",
            streamDetailLineDisplayText(detailLines[2])
        )

        val compactStyle = streamDetailLineStyle(
            detailLines = detailLines,
            index = 2,
            baseStyle = baseStyle
        )

        assertEquals(7.sp, compactStyle.fontSize)
        assertEquals(10.sp, compactStyle.lineHeight)
    }

    @Test
    fun `does not compact final line when it is not a filename`() {
        val detailLines = listOf(
            "[[icon:prime]] Amazon • [[icon:premiumize]] Premiumize",
            "Cached"
        )

        assertFalse(shouldCompactFinalFilenameLine(detailLines, 1))

        val style = streamDetailLineStyle(
            detailLines = detailLines,
            index = 1,
            baseStyle = baseStyle
        )

        assertEquals(baseStyle.fontSize, style.fontSize)
        assertEquals(baseStyle.lineHeight, style.lineHeight)
    }
}
