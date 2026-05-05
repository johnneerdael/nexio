package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.OverlayEntry
import com.nexio.animemap.model.OverlayPatch
import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.model.RangeRuleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayApplierTest {

    private val baseIdentity = mapOf(
        "11469" to IdentityRecord(kitsu = "11469", anidb = "11739", tvdb = "305074", tvdbSeason = "1"),
        "12" to IdentityRecord(kitsu = "12", anidb = "69", tvdb = "81797", tvdbSeason = "a", hasMappingRules = true)
    )
    private val baseMapping = mapOf(
        "69" to EpisodeMappingRecord(
            anidb = "69", tvdbSeriesId = "81797",
            ranges = listOf(RangeRule(1, 1086, 1155, "TVDB", 22, -1085))
        )
    )

    @Test fun `patch identity updates named field only`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "11739", mode = "patch-identity", reason = "test",
                patch = OverlayPatch(tvdbSeason = "9")
            ))
        )
        assertEquals("9", out.identity.getValue("11469").tvdbSeason)
        assertEquals("305074", out.identity.getValue("11469").tvdb)
    }

    @Test fun `patch identity fails when target record missing`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(
                    anidb = "99999", mode = "patch-identity", reason = "missing",
                    patch = OverlayPatch(tvdbSeason = "1")
                ))
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("99999"))
        }
    }

    @Test fun `patch mapping remove then add replaces a rule`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "69", mode = "patch-mapping", reason = "test",
                patch = OverlayPatch(
                    removeRanges = listOf(RangeRuleKey(1, 1086, "TVDB")),
                    addRanges = listOf(RangeRule(1, 1086, 1200, "TVDB", 22, -1085))
                )
            ))
        )
        val ranges = out.episodeMapping.getValue("69").ranges
        assertEquals(1, ranges.size)
        assertEquals(1200, ranges.single().endEpisode)
    }

    @Test fun `replace target identity creates new record`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(
                anidb = "33333", mode = "replace", target = "identity", reason = "new entry",
                record = mapOf(
                    "kitsu" to "77777",
                    "anidb" to "33333",
                    "tvdb" to "999999",
                    "tvdbSeason" to "1"
                )
            ))
        )
        val rec = out.identity.getValue("77777")
        assertEquals("33333", rec.anidb)
        assertEquals("1", rec.tvdbSeason)
    }

    @Test fun `drop removes from both maps`() {
        val out = OverlayApplier().apply(
            identity = baseIdentity, episodeMapping = baseMapping,
            entries = listOf(OverlayEntry(anidb = "69", mode = "drop", reason = "test"))
        )
        assertNull(out.identity["12"])
        assertNull(out.episodeMapping["69"])
    }

    @Test fun `duplicate anidb mode pair fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(
                    OverlayEntry(anidb = "11739", mode = "patch-identity", reason = "first",
                        patch = OverlayPatch(tvdbSeason = "1")),
                    OverlayEntry(anidb = "11739", mode = "patch-identity", reason = "duplicate",
                        patch = OverlayPatch(tvdbSeason = "2"))
                )
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test fun `empty reason fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(anidb = "11739", mode = "drop", reason = ""))
            )
            error("expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test fun `unknown mode fails`() {
        try {
            OverlayApplier().apply(
                identity = baseIdentity, episodeMapping = baseMapping,
                entries = listOf(OverlayEntry(anidb = "11739", mode = "rewrite", reason = "x"))
            )
            error("expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mode"))
        }
    }
}
