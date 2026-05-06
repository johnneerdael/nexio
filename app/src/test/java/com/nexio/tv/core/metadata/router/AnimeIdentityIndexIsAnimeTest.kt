package com.nexio.tv.core.metadata.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeIdentityIndexIsAnimeTest {
    private val parsed = ParsedMetadataId(
        scheme = AnimeIdScheme.IMDB,
        value = "tt12343534",
        raw = "tt12343534"
    )

    @Test
    fun `default isAnime is true when resolveKitsuId returns non-null`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = "7442"
        }

        assertTrue(index.isAnime(parsed))
    }

    @Test
    fun `default isAnime is false when resolveKitsuId returns null`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = null
        }

        assertFalse(index.isAnime(parsed))
    }

    @Test
    fun `override can classify anime without resolving canonical kitsu record`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
                throw AssertionError("resolveKitsuId should not be called")
            }

            override suspend fun isAnime(id: ParsedMetadataId): Boolean = true
        }

        assertTrue(index.isAnime(parsed))
    }
}
