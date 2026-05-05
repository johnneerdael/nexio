package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingListExpanderTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun `range rule for one piece tvdb season 21 starts at episode 892 with offset minus 891`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val r = expanded.ranges.first { it.targetProvider == "TVDB" && it.targetSeason == 21 }
        assertEquals(1, r.sourceSeason)
        assertEquals(892, r.startEpisode)
        assertEquals(1085, r.endEpisode)
        assertEquals(-891, r.offset)
    }

    @Test fun `open ended range has null endEpisode`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val r = expanded.ranges.first { it.targetProvider == "TVDB" && it.targetSeason == 23 }
        assertEquals(1156, r.startEpisode)
        assertEquals(null, r.endEpisode)
        assertEquals(-1155, r.offset)
    }

    @Test fun `tmdb ranges are emitted alongside tvdb ranges`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val tmdbCount = expanded.ranges.count { it.targetProvider == "TMDB" }
        val tvdbCount = expanded.ranges.count { it.targetProvider == "TVDB" }
        assertTrue("TMDB ranges expected", tmdbCount >= 2)
        assertTrue("TVDB ranges expected", tvdbCount >= 3)
    }

    @Test fun `inline explicit mappings produce explicit map entries`() {
        val expanded = MappingListExpander().expand(fixture("one-piece.xml"))
        val em = expanded.explicitMaps.first {
            it.sourceSeason == 0 && it.sourceEpisode == 1 && it.targetProvider == "TVDB"
        }
        assertEquals(0, em.targetSeason)
        assertEquals(27, em.targetEpisode)
    }

    @Test fun `inline explicit mapping target zero is preserved`() {
        val expanded = MappingListExpander().expand(fixture("chobits.xml"))
        val em = expanded.explicitMaps.first {
            it.sourceSeason == 0 && it.sourceEpisode == 1 && it.targetProvider == "TVDB"
        }
        assertEquals(0, em.targetEpisode)
    }
}
