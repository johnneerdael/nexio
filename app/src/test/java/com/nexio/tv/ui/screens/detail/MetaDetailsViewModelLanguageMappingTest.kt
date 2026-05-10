package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.DetailAdvancedMetadata
import com.nexio.tv.domain.model.LocalizationDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for Bug A from the 2026-05-10 dossier:
 * the silent fallback to localization.selectedLanguage must not leak the
 * user's UI locale into the show's production-language slot.
 */
class MetaDetailsViewModelLanguageMappingTest {

    @Test
    fun `null production language stays null after merge`() {
        val advanced = DetailAdvancedMetadata(language = null)
        val localization = LocalizationDisplayState(
            requestedLanguage = "nld",
            selectedLanguage = "nld",
            fallbackReason = null
        )

        val merged = mergeProductionLanguageForTest(
            advancedLanguage = advanced.language,
            selectedLanguage = localization.selectedLanguage,
            existing = null
        )

        assertNull(
            "production language must be null when advanced.language is null; " +
                "the silent fallback to selectedLanguage was the bug",
            merged
        )
    }

    @Test
    fun `non-null advanced language wins`() {
        val merged = mergeProductionLanguageForTest(
            advancedLanguage = "eng",
            selectedLanguage = "nld",
            existing = "ita"
        )
        assertEquals("eng", merged)
    }

    @Test
    fun `existing language preserved when both new sources are null`() {
        val merged = mergeProductionLanguageForTest(
            advancedLanguage = null,
            selectedLanguage = null,
            existing = "eng"
        )
        assertEquals("eng", merged)
    }
}
