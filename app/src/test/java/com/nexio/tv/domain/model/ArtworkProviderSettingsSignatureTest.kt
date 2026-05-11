package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkProviderSettingsSignatureTest {
    @Test fun `identical settings produce identical signatures`() {
        val a = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        val b = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        assertEquals(a.toSettingsSignature(), b.toSettingsSignature())
    }

    @Test fun `different poster providers produce different signatures`() {
        val rpdb = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        val default = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.DEFAULT
            )
        )
        assertNotEquals(rpdb.toSettingsSignature(), default.toSettingsSignature())
    }

    @Test fun `signature includes defaults table version`() {
        val signature = ArtworkProviderSettings().toSettingsSignature()
        assertEquals(true, signature.contains("v="))
    }

    @Test fun `api keys do NOT affect signature (irrelevant to routing)`() {
        val a = ArtworkProviderSettings(rpdbApiKey = "key-a")
        val b = ArtworkProviderSettings(rpdbApiKey = "key-b")
        assertEquals(a.toSettingsSignature(), b.toSettingsSignature())
    }
}
