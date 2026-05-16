package com.nexio.tv.core.integration

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GsonCodecStreamingTest {

    private data class Nested(val value: Int)
    private data class Sample(
        val name: String,
        val tags: List<String?>,
        val optional: String? = null,
        val nested: Nested
    )

    private val codec = gsonCodec<Sample>()

    @Test
    fun `round-trip preserves content`() {
        val original = Sample(
            name = "Fight Club",
            tags = listOf("drama", "thriller"),
            optional = "extra",
            nested = Nested(value = 42)
        )
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes non-ASCII utf-8 body`() {
        // Japanese + emoji to exercise the UTF-8 multi-byte decoding path.
        val json = """{"name":"鬼滅の刃 🔥","tags":["anime"],"nested":{"value":1}}"""
        val decoded = codec.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals("鬼滅の刃 🔥", decoded.name)
        assertEquals(listOf("anime" as String?), decoded.tags)
        assertEquals(1, decoded.nested.value)
    }

    @Test
    fun `decodes body with utf-8 BOM`() {
        // BOM = EF BB BF; gson.fromJson(String) silently tolerates a leading BOM,
        // and the streaming InputStreamReader(Charsets.UTF_8) path does too.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """{"name":"bom-prefixed","tags":[],"nested":{"value":0}}""".toByteArray(Charsets.UTF_8)
        val withBom = bom + json
        val decoded = codec.decode(withBom)
        assertEquals("bom-prefixed", decoded.name)
    }

    @Test
    fun `accepts trailing whitespace`() {
        val json = """{"name":"trailing-ws","tags":[],"nested":{"value":0}}    """
        val decoded = codec.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals("trailing-ws", decoded.name)
    }

    @Test
    fun `accepts trailing comma but parses it as null element`() {
        // Gson's fromJson(String) and default JsonReader in lenient mode
        // accept trailing commas but parse them as null array elements.
        // The streaming codec must maintain this behavior.
        val withTrailingComma = """{"name":"x","tags":["a","b",],"nested":{"value":0}}"""
        val decoded = codec.decode(withTrailingComma.toByteArray(Charsets.UTF_8))
        assertEquals("x", decoded.name)
        // Trailing comma becomes a null element in the array
        assertEquals(listOf("a", "b", null), decoded.tags)
        assertEquals(0, decoded.nested.value)
    }
}
