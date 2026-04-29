package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TraceRedactorAuthHeaderParityTest {

    private val redactor = TraceRedactor()

    @Test
    fun `simkl api key header is redacted`() {
        assertHeaderRedacted("simkl-api-key", "secret-simkl")
    }

    @Test
    fun `trakt api key header is redacted`() {
        assertHeaderRedacted("trakt-api-key", "secret-trakt")
    }

    @Test
    fun `simkl client id header is redacted`() {
        assertHeaderRedacted("simkl-client-id", "client-12345")
    }

    @Test
    fun `tvdb api key header is redacted`() {
        assertHeaderRedacted("x-tvdb-apikey", "tvdb-secret")
    }

    @Test
    fun `oauth code json key is redacted`() {
        assertJsonKeyRedacted("code", "OAUTH_CODE_VALUE")
    }

    @Test
    fun `oauth client_id json key is redacted`() {
        assertJsonKeyRedacted("client_id", "client-id-value")
    }

    private fun assertHeaderRedacted(headerName: String, headerValue: String) {
        val redacted = redactor.redactHeaders(mapOf(headerName to headerValue))
        assertEquals("<redacted>", redacted[headerName])
    }

    private fun assertJsonKeyRedacted(key: String, value: String) {
        val json = """{"$key":"$value"}"""
        val redacted = redactor.redactJsonBody(json)
        assertTrue("expected redacted token in $redacted", redacted.contains("<redacted>"))
        assertFalse("plaintext value $value must not appear in $redacted", redacted.contains(value))
    }
}
