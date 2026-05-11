package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Test

class CipherManifestTest {

    @Test
    fun `decipher applies operations in order`() {
        // Input "abcdef" → splice(1) → "bcdef" → reverse → "fedcb" → swap(2) → "defcb"
        val manifest = CipherManifest(
            signatureTimestamp = "19999",
            operations = listOf(
                SpliceCipherOperation(1),
                ReverseCipherOperation,
                SwapCipherOperation(2)
            )
        )
        assertEquals("defcb", manifest.decipher("abcdef"))
    }

    @Test
    fun `empty operations list returns input unchanged`() {
        val manifest = CipherManifest(signatureTimestamp = "1", operations = emptyList())
        assertEquals("xyz", manifest.decipher("xyz"))
    }
}
