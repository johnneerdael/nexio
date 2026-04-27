package com.nexio.tv.data.integration.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class KitsuLocalizationPolicyTest {
    @Test
    fun `kitsu title fallback prefers requested then english then canonical`() {
        val policy = LocalizationPolicy.kitsu("en-US")
        val title = selectKitsuTitle(
            policy = policy,
            titles = mapOf(
                "ja_jp" to "日本語タイトル",
                "en" to "English title"
            ),
            canonicalTitle = "Canonical title",
            romanizedTitle = "Romanized title"
        )

        assertEquals("English title", title)
    }

    @Test
    fun `kitsu title fallback returns selected field trace`() {
        val policy = LocalizationPolicy.kitsu("nl-NL")
        val selected = selectKitsuTitleField(
            policy = policy,
            titles = mapOf(
                "ja_jp" to "日本語タイトル",
                "en" to "English title"
            ),
            canonicalTitle = "Canonical title",
            romanizedTitle = "Romanized title"
        )

        assertEquals("English title", selected?.value)
        assertEquals("en", selected?.language?.providerCode)
        assertEquals(FallbackRole.LANGUAGE_FALLBACK, selected?.fallbackRole)
    }

    @Test
    fun `kitsu title fallback uses canonical before romanized`() {
        val policy = LocalizationPolicy.kitsu("nl-NL")
        val title = selectKitsuTitle(
            policy = policy,
            titles = mapOf("ja_jp" to "日本語タイトル"),
            canonicalTitle = "Canonical title",
            romanizedTitle = "Romanized title"
        )

        assertEquals("Canonical title", title)
    }

    @Test
    fun `kitsu synopsis treats placeholders as missing`() {
        assertEquals(
            "Fallback description",
            selectKitsuSynopsis(
                policy = LocalizationPolicy.kitsu("nl-NL"),
                synopsis = "No synopsis available.",
                description = "Fallback description"
            )
        )
    }

    @Test
    fun `kitsu synopsis fallback returns selected field trace`() {
        val selected = selectKitsuSynopsisField(
            policy = LocalizationPolicy.kitsu("nl-NL"),
            synopsis = "No synopsis available.",
            description = "Canonical synopsis"
        )

        assertEquals("Canonical synopsis", selected?.value)
        assertEquals("en", selected?.language?.providerCode)
        assertEquals(FallbackRole.CANONICAL, selected?.fallbackRole)
        assertEquals("missing_or_placeholder", selected?.rejectedCandidates?.first()?.reason)
    }
}
