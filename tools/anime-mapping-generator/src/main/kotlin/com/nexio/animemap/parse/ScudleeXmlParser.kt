package com.nexio.animemap.parse

import com.nexio.animemap.model.SeasonMarkerWire
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringReader
import java.io.StringWriter
import org.xml.sax.InputSource

data class ScudleeAnimeEntry(
    val anidb: String,
    val tvdb: String? = null,
    val tmdbTv: String? = null,
    val tmdbMovie: String? = null,
    val imdb: String? = null,
    val tvdbSeason: String? = null,
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val name: String? = null,
    val mappingListXml: String? = null
)

class ScudleeXmlParser {

    fun parse(xml: String): List<ScudleeAnimeEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement ?: return emptyList()
        val nodes = root.getElementsByTagName("anime")
        val out = mutableListOf<ScudleeAnimeEntry>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val entry = toEntry(el) ?: continue
            out += entry
        }
        return out
    }

    private fun toEntry(el: Element): ScudleeAnimeEntry? {
        val anidb = el.getAttribute("anidbid").trim()
        if (anidb.isEmpty()) return null

        val tvdbRaw = el.getAttribute("tvdbid").trim().takeIf { it.isNotEmpty() }
        val tvdbNumeric = tvdbRaw?.toIntOrNull()?.toString()
        val tvdbSeasonAttr = SeasonMarkerWire.normalize(el.getAttribute("defaulttvdbseason"))
            ?: when (tvdbRaw) {
                "hentai" -> "hentai"
                "unknown" -> "unknown"
                else -> null
            }

        return ScudleeAnimeEntry(
            anidb = anidb,
            tvdb = tvdbNumeric,
            tmdbTv = el.getAttribute("tmdbtv").trim().takeIf { it.isNotEmpty() },
            tmdbMovie = el.getAttribute("tmdbid").trim().takeIf { it.isNotEmpty() },
            imdb = el.getAttribute("imdbid").trim().takeIf { it.isNotEmpty() },
            tvdbSeason = tvdbSeasonAttr,
            tmdbSeason = SeasonMarkerWire.normalize(el.getAttribute("tmdbseason")),
            tvdbEpisodeOffset = el.getAttribute("episodeoffset").trim().toIntOrNull(),
            tmdbEpisodeOffset = el.getAttribute("tmdboffset").trim().toIntOrNull(),
            name = textOfChild(el, "name"),
            mappingListXml = childAsXmlString(el, "mapping-list")
        )
    }

    private fun textOfChild(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        val text = list.item(0).textContent?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun childAsXmlString(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        val node: Node = list.item(0)
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(node), StreamResult(writer))
        return writer.toString().takeIf { it.isNotBlank() }
    }
}
