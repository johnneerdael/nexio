package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataRequestNormalizerTest {
    private val normalizer = MetadataRequestNormalizer()

    @Test
    fun `IMDb episode id normalizes to parent IMDb id`() {
        val request = MetadataRequest(
            contentId = "tt12343534:1:1",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("tt12343534", normalized.parentId)
        assertEquals("tt12343534:1:1", normalized.originalContentId)
    }

    @Test
    fun `Kitsu episode id normalizes to parent Kitsu id`() {
        val request = MetadataRequest(
            contentId = "kitsu:7442:1:1",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("kitsu:7442", normalized.parentId)
    }

    @Test
    fun `provider title id remains unchanged`() {
        val request = MetadataRequest(
            contentId = "tmdb:550",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("tmdb:550", normalized.parentId)
    }

    @Test
    fun `blank id is preserved as blank and recorded as unsupported`() {
        val request = MetadataRequest(
            contentId = "   ",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext()
        )

        val normalized = normalizer.normalize(request)

        assertEquals("", normalized.parentId)
        assertEquals(MetadataMediaKind.MOVIE, normalized.mediaKind)
    }
}
