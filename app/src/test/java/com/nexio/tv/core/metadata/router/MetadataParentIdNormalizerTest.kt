package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataParentIdNormalizerTest {
    @Test
    fun `tt12343534_1_1 normalizes to parent tt12343534`() {
        assertEquals("tt12343534", MetadataParentIdNormalizer.parentIdOf("tt12343534:1:1"))
    }

    @Test
    fun `kitsu episode id normalizes to parent kitsu id`() {
        assertEquals("kitsu:7442", MetadataParentIdNormalizer.parentIdOf("kitsu:7442:1:1"))
    }

    @Test
    fun `mal episode id normalizes to parent mal id`() {
        assertEquals("mal:21", MetadataParentIdNormalizer.parentIdOf("mal:21:1:1"))
    }

    @Test
    fun `anilist episode id normalizes to parent anilist id`() {
        assertEquals("anilist:113415", MetadataParentIdNormalizer.parentIdOf("anilist:113415:1:1"))
    }

    @Test
    fun `anidb episode id normalizes to parent anidb id`() {
        assertEquals("anidb:69", MetadataParentIdNormalizer.parentIdOf("anidb:69:1:1"))
    }

    @Test
    fun `parent ids are unchanged for existing provider ids`() {
        assertEquals("kitsu:7442", MetadataParentIdNormalizer.parentIdOf("kitsu:7442"))
        assertEquals("tt12343534", MetadataParentIdNormalizer.parentIdOf("tt12343534"))
    }

    @Test
    fun `provider object ids are preserved`() {
        assertEquals("tmdb:person:1234", MetadataParentIdNormalizer.parentIdOf("tmdb:person:1234"))
        assertEquals("tvdb:company:42", MetadataParentIdNormalizer.parentIdOf("tvdb:company:42"))
    }

    @Test
    fun `blank inputs return empty string`() {
        assertEquals("", MetadataParentIdNormalizer.parentIdOf(""))
        assertEquals("", MetadataParentIdNormalizer.parentIdOf("   "))
    }

    @Test
    fun `unknown scheme preserved as-is`() {
        assertEquals("garbage-id", MetadataParentIdNormalizer.parentIdOf("garbage-id"))
    }
}
