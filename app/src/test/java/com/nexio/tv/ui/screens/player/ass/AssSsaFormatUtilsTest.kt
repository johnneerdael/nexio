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
    fun detectsAssSsaByExplicitAssMimeTypeAlias() {
        assertTrue(Format.Builder().setSampleMimeType("text/x-ass").build().isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByCodecString() {
        val format = Format.Builder()
            .setCodecs("avc1.640028, s_text/ass")
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaBySsaCodecString() {
        val format = Format.Builder()
            .setCodecs("s_text/ssa")
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByXssaCodecString() {
        val format = Format.Builder()
            .setCodecs("text/x-ssa")
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
    fun detectsAssSsaByUtf16LeInitializationHeader() {
        val format = Format.Builder()
            .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray(Charsets.UTF_16LE)))
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun detectsAssSsaByUtf16BeInitializationHeader() {
        val format = Format.Builder()
            .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray(Charsets.UTF_16BE)))
            .build()

        assertTrue(format.isAssSsaFormat())
    }

    @Test
    fun ignoresWebVttEvenWithAssLikeInitializationData() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray()))
            .build()

        assertFalse(format.isAssSsaFormat())
    }
}
