package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Test

class CipherOperationTest {

    @Test
    fun `swap exchanges char at index 0 with char at index`() {
        // "abcdef" with index=3 → swap chars[0]='a' with chars[3]='d' → "dbcaef"
        assertEquals("dbcaef", SwapCipherOperation(3).decipher("abcdef"))
    }

    @Test
    fun `splice drops first N chars`() {
        // "abcdef" with index=2 → "cdef"
        assertEquals("cdef", SpliceCipherOperation(2).decipher("abcdef"))
    }

    @Test
    fun `reverse mirrors the string`() {
        assertEquals("fedcba", ReverseCipherOperation.decipher("abcdef"))
    }
}
