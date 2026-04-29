package com.nexio.tv.data.integration.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-C-03 pin: ID prefix parser must handle mal/anilist/anidb (anime prefixes) and imdb.
 * Mismatched prefixes return null (not the bare value) to avoid false matches.
 */
class MetadataProviderTargetIdsAnimePrefixTest {

    @Test
    fun `mal parses mal-prefixed values`() {
        assertEquals("31964", MetadataProviderTargetIds.mal("mal:31964"))
        assertEquals("1", MetadataProviderTargetIds.mal("MAL:1"))  // case-insensitive prefix
    }

    @Test
    fun `mal returns null for non-mal prefixes`() {
        assertNull(MetadataProviderTargetIds.mal("tmdb:550"))
        assertNull(MetadataProviderTargetIds.mal("anilist:1"))
        assertNull(MetadataProviderTargetIds.mal(""))
        assertNull(MetadataProviderTargetIds.mal(null))
    }

    @Test
    fun `anilist parses anilist-prefixed values`() {
        assertEquals("1", MetadataProviderTargetIds.anilist("anilist:1"))
    }

    @Test
    fun `anilist returns null for non-anilist prefixes`() {
        assertNull(MetadataProviderTargetIds.anilist("mal:1"))
        assertNull(MetadataProviderTargetIds.anilist("anidb:5"))
    }

    @Test
    fun `anidb parses anidb-prefixed values`() {
        assertEquals("5", MetadataProviderTargetIds.anidb("anidb:5"))
    }

    @Test
    fun `imdb parses tt-prefixed values without colon`() {
        assertEquals("tt0111161", MetadataProviderTargetIds.imdb("tt0111161"))
    }

    @Test
    fun `imdb parses imdb-prefixed values with colon`() {
        assertEquals("tt0111161", MetadataProviderTargetIds.imdb("imdb:tt0111161"))
    }

    @Test
    fun `imdb returns null for non-imdb`() {
        assertNull(MetadataProviderTargetIds.imdb("tmdb:550"))
    }
}
