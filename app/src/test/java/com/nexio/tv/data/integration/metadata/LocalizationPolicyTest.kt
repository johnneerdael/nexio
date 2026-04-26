package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationPolicyTest {
    @Test
    fun `tvdb policy normalizes english fallback to eng and dutch request to nld`() {
        val policy = LocalizationPolicy.tvdb(requestedLanguage = "nl-NL")

        assertEquals(MetadataPrimaryProvider.TVDB, policy.provider)
        assertEquals("nld", policy.requestedLanguage.providerCode)
        assertEquals("eng", policy.fallbackLanguage.providerCode)
        assertEquals(listOf("nld", "eng"), policy.languageChain().map { it.providerCode })
        assertFalse(policy.allowProviderFallbackForMissingLocalizedFields)
    }

    @Test
    fun `tvdb english request deduplicates language chain`() {
        val policy = LocalizationPolicy.tvdb(requestedLanguage = "en-US")

        assertEquals(listOf("eng"), policy.languageChain().map { it.providerCode })
        assertTrue(policy.requestedIsFallback)
    }

    @Test
    fun `tmdb policy normalizes fallback to en-US`() {
        val policy = LocalizationPolicy.tmdb(requestedLanguage = "nl-NL")

        assertEquals(MetadataPrimaryProvider.TMDB, policy.provider)
        assertEquals("nl-NL", policy.requestedLanguage.providerCode)
        assertEquals("en-US", policy.fallbackLanguage.providerCode)
        assertEquals(listOf("nl-NL", "en-US"), policy.languageChain().map { it.providerCode })
    }

    @Test
    fun `kitsu policy uses field level fallback without provider fallback`() {
        val policy = LocalizationPolicy.kitsu(requestedLanguage = "en-US")

        assertEquals(MetadataPrimaryProvider.KITSU, policy.provider)
        assertEquals("en", policy.fallbackLanguage.providerCode)
        assertEquals("policy:${LocalizationPolicy.CURRENT_VERSION}", policy.cachePolicyPart())
        assertFalse(policy.allowProviderFallbackForMissingLocalizedFields)
    }
}
