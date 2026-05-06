package com.nexio.animemap.emit

import com.nexio.animemap.model.IdentityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBuilderTest {

    @Test fun `byTvdb is List of Kitsu IDs sharing the same tvdb`() {
        val identity = mapOf(
            "11469" to IdentityRecord(kitsu = "11469", anidb = "11739", tvdb = "305074"),
            "13881" to IdentityRecord(kitsu = "13881", anidb = "13485", tvdb = "305074"),
            "12" to IdentityRecord(kitsu = "12", anidb = "69", tvdb = "81797")
        )
        val ix = IndexBuilder().build(identity)
        val mha = ix.byTvdb["305074"]!!
        assertEquals(setOf("11469", "13881"), mha.toSet())
        assertEquals(listOf("12"), ix.byTvdb["81797"])
    }

    @Test fun `byMal byAnilist byAnidb are single value`() {
        val identity = mapOf(
            "11469" to IdentityRecord(
                kitsu = "11469", anidb = "11739", tvdb = "305074",
                mal = "31964", anilist = "21459", imdb = "tt5626028"
            )
        )
        val ix = IndexBuilder().build(identity)
        assertEquals("11469", ix.byMal["31964"])
        assertEquals("11469", ix.byAnilist["21459"])
        assertEquals("11469", ix.byAnidb["11739"])
    }

    @Test fun `byImdb groups Kitsu IDs sharing the same imdb`() {
        val identity = mapOf(
            "11469" to IdentityRecord(kitsu = "11469", anidb = "11739", tvdb = "305074", imdb = "tt5626028"),
            "13881" to IdentityRecord(kitsu = "13881", anidb = "13485", tvdb = "305074", imdb = "tt5626028"),
            "12" to IdentityRecord(kitsu = "12", anidb = "69", tvdb = "81797", imdb = "tt0388629")
        )
        val ix = IndexBuilder().build(identity)
        val mha = ix.byImdb["tt5626028"]!!
        assertEquals(setOf("11469", "13881"), mha.toSet())
        assertEquals(listOf("12"), ix.byImdb["tt0388629"])
    }

    @Test fun `tmdb routing splits by mediaType`() {
        val identity = mapOf(
            "100" to IdentityRecord(kitsu = "100", anidb = "1", tmdb = "111", mediaType = "series"),
            "200" to IdentityRecord(kitsu = "200", anidb = "2", tmdb = "222", mediaType = "movie")
        )
        val ix = IndexBuilder().build(identity)
        assertTrue("series tmdb in byTmdbTv", ix.byTmdbTv["111"]?.contains("100") == true)
        assertEquals("200", ix.byTmdbMovie["222"])
    }
}
