package com.nexio.tv.data.trailer.captions

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal object SrvCaptionParser {

    fun parse(srv3Xml: String): List<CaptionLine> {
        if (srv3Xml.isBlank()) return emptyList()
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(srv3Xml))
            }
            parseDocument(parser)
        } catch (e: XmlPullParserException) {
            emptyList()
        }
    }

    private fun parseDocument(parser: XmlPullParser): List<CaptionLine> {
        val out = mutableListOf<CaptionLine>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "p") {
                    val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                    val d = parser.getAttributeValue(null, "d")?.toLongOrNull()
                    val text = readParagraphText(parser)
                    if (t != null && d != null && text.isNotEmpty()) {
                        out += CaptionLine(offsetMs = t, durationMs = d, text = text)
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            return emptyList()
        }
        return out
    }

    /**
     * Reads the text content of a `<p>` element, concatenating any nested
     * text — including children like `<s>` used by YouTube ASR captions
     * for word-level timing. Parser enters at START_TAG of `<p>` and
     * exits at its END_TAG.
     */
    private fun readParagraphText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> builder.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return builder.toString()
            }
        }
        return builder.toString()
    }
}
