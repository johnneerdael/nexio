package com.nexio.tv.data.trailer.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SrvCaptionParserTest {

    @Test
    fun `parses well-formed SRV3 into ordered caption lines`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <timedtext format="3">
              <body>
                <p t="1500" d="2700">Hello world</p>
                <p t="5000" d="2500">Second line</p>
                <p t="9000" d="1000">Third</p>
              </body>
            </timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)

        assertEquals(3, lines.size)
        assertEquals(CaptionLine(offsetMs = 1500, durationMs = 2700, text = "Hello world"), lines[0])
        assertEquals(CaptionLine(offsetMs = 5000, durationMs = 2500, text = "Second line"), lines[1])
        assertEquals(CaptionLine(offsetMs = 9000, durationMs = 1000, text = "Third"), lines[2])
    }

    @Test
    fun `skips paragraphs missing t or d attributes`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000">Valid</p>
              <p t="3000">Missing d</p>
              <p d="2000">Missing t</p>
              <p t="5000" d="1500">Also valid</p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals(2, lines.size)
        assertEquals("Valid", lines[0].text)
        assertEquals("Also valid", lines[1].text)
    }

    @Test
    fun `skips paragraphs with empty text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000"></p>
              <p t="3000" d="2000">Real text</p>
              <p t="5000" d="2000">   </p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        // YoutubeExplode skips empty strings but preserves whitespace-only.
        assertEquals(2, lines.size)
        assertEquals("Real text", lines[0].text)
        assertEquals("   ", lines[1].text)
    }

    @Test
    fun `concatenates s child element text with parent text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000"><s>Hello </s><s>world</s></p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `preserves explicit line breaks inside paragraph text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000">First line<br />Second line</p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals(1, lines.size)
        assertEquals("First line\nSecond line", lines[0].text)
    }

    @Test
    fun `unescapes XML entities in text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000">It&apos;s &quot;done&quot; &amp; ready</p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals("It's \"done\" & ready", lines[0].text)
    }

    @Test
    fun `returns empty list for malformed XML`() {
        val lines = SrvCaptionParser.parse("not actually xml <<<>>>")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `returns empty list for empty input`() {
        assertTrue(SrvCaptionParser.parse("").isEmpty())
    }
}
