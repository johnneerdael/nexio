package com.nexio.tv.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDetailLinesTest {

    private val baseStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

    @Test
    fun `compacts only final filename line`() {
        val detailLines = listOf(
            "[[icon:hdr10]] [[icon:ddp]]",
            "💾 41.38 GB",
            "📄 Avatar.Fire.And.Ash.2025.2160p.AMZN.WEB-DL.DDP5.1.mkv"
        )

        assertFalse(shouldCompactFinalFilenameLine(detailLines, 0))
        assertFalse(shouldCompactFinalFilenameLine(detailLines, 1))
        assertTrue(shouldCompactFinalFilenameLine(detailLines, 2))

        val compactStyle = streamDetailLineStyle(
            detailLines = detailLines,
            index = 2,
            baseStyle = baseStyle
        )

        assertEquals(10.sp, compactStyle.fontSize)
        assertEquals(14.sp, compactStyle.lineHeight)
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
