package com.nexio.tv.data.integration.tvdb

import com.nexio.tv.core.tvdb.asTvMetadataEnrichmentForCanonicalRoute
import com.nexio.tv.core.tvdb.stubCanonicalDocumentWithLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * High-level seam test for the canonical-route short-circuit. We assert that
 * when the canonical document already supplies a `language`, the
 * `ProviderLocalizedMetadataResolver` short-circuit propagates it through
 * `TvMetadataEnrichment.language` into the `ResolvedMetadataDocument`.
 *
 * This is the path that fires when the metadata router decides not to
 * re-fetch (cache hit, no localization request). Per the 2026-05-10 dossier,
 * it currently strips `language`.
 */
class TvdbMetadataServiceOriginalLanguageTest {
    @Test
    fun `canonical-route enrichment carries language from upstream document`() {
        // Build a ResolvedMetadataDocument with a known production language
        // (mirrors what the canonical route reads).
        val canonical = stubCanonicalDocumentWithLanguage("eng")

        val enrichment = canonical.asTvMetadataEnrichmentForCanonicalRoute()

        assertEquals("eng", enrichment.language)
    }
}
