package com.nexio.tv.data.integration.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataProviderTargetIdsTest {
    @Test
    fun `numeric provider ids strip matching provider prefix`() {
        assertEquals(1399, MetadataProviderTargetIds.tvdbInt("tvdb:1399"))
        assertEquals(550, MetadataProviderTargetIds.tmdbInt("tmdb:550"))
    }

    @Test
    fun `kitsu provider ids strip matching provider prefix without requiring numeric conversion`() {
        assertEquals("7442", MetadataProviderTargetIds.kitsu("kitsu:7442"))
    }

    @Test
    fun `provider id parsing rejects mismatched prefixes`() {
        assertNull(MetadataProviderTargetIds.tvdbInt("tmdb:1399"))
        assertNull(MetadataProviderTargetIds.tmdbInt("tvdb:550"))
        assertNull(MetadataProviderTargetIds.kitsu("mal:21"))
    }
}
