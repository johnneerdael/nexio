package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SignatureCipherDecoderTest {

    @Test
    fun `decodes a sample signatureCipher into a playable URL`() {
        // Manifest that applies reverse → splice(2) → swap(3)
        // Input signature: "abcdef" → "fedcba" → "dcba" → "acbd"
        //   swap(3) on "dcba": chars[0]='d', chars[3]='a' → swap → "acbd"
        val manifest = CipherManifest(
            signatureTimestamp = "12345",
            operations = listOf(
                ReverseCipherOperation,
                SpliceCipherOperation(2),
                SwapCipherOperation(3)
            )
        )

        // URL-encoded `s=abcdef&sp=sig&url=https%3A%2F%2Fexample.com%2Fplay%3Fa%3D1`
        val signatureCipher = "s=abcdef&sp=sig&url=https%3A%2F%2Fexample.com%2Fplay%3Fa%3D1"
        val decoded = SignatureCipherDecoder.decode(signatureCipher, manifest)

        assertNotNull("expected non-null decoded URL", decoded)
        assertTrue("expected sig=acbd present, got: $decoded", decoded!!.contains("sig=acbd"))
        assertTrue("expected base url preserved, got: $decoded", decoded.startsWith("https://example.com/play"))
        assertTrue("expected a=1 preserved, got: $decoded", decoded.contains("a=1"))
    }

    @Test
    fun `returns null when signatureCipher is missing required fields`() {
        val manifest = CipherManifest("1", emptyList())
        assertEquals(null, SignatureCipherDecoder.decode("only=garbage", manifest))
    }
}
