package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaFormatUtilsTest {
    @Test
    fun detectsAssSsaBySampleMimeType() {
        assertTrue(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build().isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByCodecString() {
        val format = Format.Builder()
            .setCodecs("avc1.640028, s_text/ass")
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByInitializationHeader() {
        val format = Format.Builder()
            .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray()))
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun ignoresWebVtt() {
        assertFalse(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build().isAssSsaFormat())
    }
}
