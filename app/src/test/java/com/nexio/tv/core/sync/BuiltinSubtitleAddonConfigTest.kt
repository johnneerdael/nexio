package com.nexio.tv.core.sync

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSubtitleAddonConfigTest {

    @Test
    fun `build url mirrors primary and secondary subtitle languages`() {
        val url = buildBuiltinSubtitleAddonInstallUrl(
            preferredLanguage = "nl",
            secondaryPreferredLanguage = "en"
        )

        assertTrue(url.startsWith("$BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL/"))
        assertTrue(url.endsWith("/manifest.json"))

        val payload = decodePayload(url)
        assertEquals(listOf("dutch", "english"), payload["langs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("all", payload["source"]!!.jsonPrimitive.content)
        assertFalse(payload["aiTranslated"]!!.jsonPrimitive.boolean)
        assertFalse(payload["autoAdjustment"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `language mapping deduplicates and falls back to english`() {
        assertEquals(
            listOf("en"),
            subtitleLanguageCodesForBuiltinAddon(preferredLanguage = "none", secondaryPreferredLanguage = "forced")
        )
        assertEquals(
            listOf("nl"),
            subtitleLanguageCodesForBuiltinAddon(preferredLanguage = "nl", secondaryPreferredLanguage = "nl")
        )
    }

    @Test
    fun `explicit language overrides match addon identifiers`() {
        assertEquals("portuguese", mapSubtitleLanguageCodeToAddonId("pt"))
        assertEquals("portuguese-br", mapSubtitleLanguageCodeToAddonId("pt-br"))
        assertEquals("chinese-simplified", mapSubtitleLanguageCodeToAddonId("zh"))
        assertEquals("indonesian", mapSubtitleLanguageCodeToAddonId("id"))
        assertEquals("gujarati", mapSubtitleLanguageCodeToAddonId("gu"))
        assertEquals("punjabi", mapSubtitleLanguageCodeToAddonId("pa"))
    }

    @Test
    fun `managed addon matcher recognizes configured and base urls`() {
        val configuredUrl = buildBuiltinSubtitleAddonInstallUrl(
            preferredLanguage = "nl",
            secondaryPreferredLanguage = "en"
        )

        assertTrue(isBuiltinSubtitleAddonUrl(configuredUrl))
        assertTrue(isBuiltinSubtitleAddonUrl("$BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL/manifest.json"))
        assertFalse(isBuiltinSubtitleAddonUrl("https://opensubtitles-v3.strem.io/manifest.json"))
    }

    private fun decodePayload(url: String) =
        Json.parseToJsonElement(
            String(
                Base64.getUrlDecoder().decode(
                    url.removePrefix("$BUILTIN_SUBTITLE_ADDON_PUBLIC_BASE_URL/")
                        .removeSuffix("/manifest.json")
                ),
                StandardCharsets.UTF_8
            )
        ).jsonObject
}
