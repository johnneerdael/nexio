package com.nexio.animemap.parse

import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.RangeRule
import org.w3c.dom.Element
import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory
import java.io.StringReader

data class ExpandedMappingList(
    val ranges: List<RangeRule>,
    val explicitMaps: List<ExplicitMap>
)

class MappingListExpander {

    fun expand(mappingListXml: String): ExpandedMappingList {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(mappingListXml)))
        val root = doc.documentElement ?: return ExpandedMappingList(emptyList(), emptyList())

        val ranges = mutableListOf<RangeRule>()
        val explicit = mutableListOf<ExplicitMap>()

        val children = root.getElementsByTagName("mapping")
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val anidbSeason = el.getAttribute("anidbseason").trim().toIntOrNull() ?: continue
            val targetProvider = when {
                el.hasAttribute("tvdbseason") -> "TVDB"
                el.hasAttribute("tmdbseason") -> "TMDB"
                else -> continue
            }
            val targetSeasonAttr = if (targetProvider == "TVDB") "tvdbseason" else "tmdbseason"
            val targetSeason = el.getAttribute(targetSeasonAttr).trim().toIntOrNull() ?: continue

            val start = el.getAttribute("start").trim().toIntOrNull()
            val end = el.getAttribute("end").trim().toIntOrNull()
            val offsetText = el.getAttribute("offset").trim()
            val offset = offsetText.toIntOrNull()

            if (start != null && offset != null) {
                ranges += RangeRule(
                    sourceSeason = anidbSeason,
                    startEpisode = start,
                    endEpisode = end,
                    targetProvider = targetProvider,
                    targetSeason = targetSeason,
                    offset = offset
                )
            }

            val inline = el.textContent?.trim().orEmpty()
            if (inline.isNotEmpty()) {
                explicit += parseInline(inline, anidbSeason, targetProvider, targetSeason)
            }
        }

        return ExpandedMappingList(ranges, explicit)
    }

    private fun parseInline(
        inline: String,
        sourceSeason: Int,
        targetProvider: String,
        targetSeason: Int
    ): List<ExplicitMap> {
        return inline.split(';')
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split('-')
                if (parts.size != 2) return@mapNotNull null
                val src = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val tgt = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                ExplicitMap(
                    sourceSeason = sourceSeason,
                    sourceEpisode = src,
                    targetProvider = targetProvider,
                    targetSeason = targetSeason,
                    targetEpisode = tgt
                )
            }
    }
}
