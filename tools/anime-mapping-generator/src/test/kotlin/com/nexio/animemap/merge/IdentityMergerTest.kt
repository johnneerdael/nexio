package com.nexio.animemap.merge

import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.parse.ExpandedMappingList
import com.nexio.animemap.parse.IdentityFragment
import com.nexio.animemap.parse.ScudleeAnimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityMergerTest {

    @Test fun `identity record is keyed by Kitsu and carries Fribb identifiers`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", tmdb = "65930",
                    imdb = "tt5626028", mal = "31964", anilist = "21459", sourceType = "TV")
            ),
            scudlee = emptyMap()
        )
        val rec = out.identity.getValue("11469")
        assertEquals("11469", rec.kitsu)
        assertEquals("305074", rec.tvdb)
        assertEquals("11739", rec.anidb)
    }

    @Test fun `scudlee tvdbSeason wins when fribb lacks season`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", sourceType = "TV")
            ),
            scudlee = mapOf("11739" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
                expanded = null
            ))
        )
        assertEquals("1", out.identity.getValue("11469").tvdbSeason)
    }

    @Test fun `fribb tvdbSeasonHint is used when scudlee absent`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(
                IdentityFragment(anidb = "13485", kitsu = "13881", tvdb = "305074",
                    sourceType = "TV", tvdbSeasonHint = "3", tmdbSeasonHint = "3")
            ),
            scudlee = emptyMap()
        )
        val rec = out.identity.getValue("13881")
        assertEquals("3", rec.tvdbSeason)
        assertEquals("3", rec.tmdbSeason)
    }

    @Test fun `episode mapping record is created when expanded mapping list present`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "69", kitsu = "12", tvdb = "81797", sourceType = "TV")),
            scudlee = mapOf("69" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "69", tvdb = "81797", tvdbSeason = "a"),
                expanded = ExpandedMappingList(
                    ranges = listOf(RangeRule(1, 892, 1085, "TVDB", 21, -891)),
                    explicitMaps = emptyList()
                )
            ))
        )
        val mapping = out.episodeMapping.getValue("69")
        assertEquals("81797", mapping.tvdbSeriesId)
        assertEquals(1, mapping.ranges.size)
        assertEquals(true, out.identity.getValue("12").hasMappingRules)
    }

    @Test fun `kitsu only fragment with no scudlee match still produces identity record`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "99999", kitsu = "55555", sourceType = "TV")),
            scudlee = emptyMap()
        )
        val rec = out.identity["55555"]
        assertNotNull(rec)
        assertNull(rec!!.tvdbSeason)
        assertEquals(false, rec.hasMappingRules)
    }

    @Test fun `evidence trail names both sources when both contributed`() {
        val merger = IdentityMerger()
        val out = merger.merge(
            fribb = listOf(IdentityFragment(anidb = "11739", kitsu = "11469", tvdb = "305074", sourceType = "TV")),
            scudlee = mapOf("11739" to MergedScudlee(
                entry = ScudleeAnimeEntry(anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
                expanded = null
            ))
        )
        val ev = out.identity.getValue("11469").evidence
        assertTrue("evidence should mention fribb", ev.any { it.startsWith("fribb.") })
        assertTrue("evidence should mention scudlee", ev.any { it.startsWith("scudlee.") })
    }
}
