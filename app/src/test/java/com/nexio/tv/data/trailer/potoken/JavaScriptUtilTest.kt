package com.nexio.tv.data.trailer.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaScriptUtilTest {
    @Test
    fun `u8ToBase64 round-trips abc`() {
        assertEquals("YWJj", u8ToBase64("97,98,99"))
    }

    @Test
    fun `u8ToBase64 produces URL-safe output for high bytes`() {
        val out = u8ToBase64("248,239,191")
        assertTrue(!out.contains("+") && !out.contains("/"))
    }

    @Test
    fun `stringToU8 produces Uint8Array literal`() {
        assertEquals("new Uint8Array([97,98,99])", stringToU8("abc"))
    }

    @Test
    fun `parseIntegrityTokenData extracts base64 token and ttl`() {
        val (u8Literal, ttl) = parseIntegrityTokenData("""["YWJj",43200]""")
        assertEquals("new Uint8Array([97,98,99])", u8Literal)
        assertEquals(43200L, ttl)
    }
}
