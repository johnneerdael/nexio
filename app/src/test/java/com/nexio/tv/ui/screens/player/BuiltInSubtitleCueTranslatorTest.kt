package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInSubtitleCueTranslatorTest {
    private fun translator(): BuiltInSubtitleCueTranslator {
        return BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )
    }

    @Test
    fun configurationTokenIsDisabledForTranscodedAssSsaTracks() {
        val transcodedAssFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()

        assertNull(translator().getConfigurationToken(transcodedAssFormat))
    }

    @Test
    fun configurationTokenIsStillEnabledForNonAssTextTracks() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()

        assertNotNull(translator().getConfigurationToken(format))
    }
}
