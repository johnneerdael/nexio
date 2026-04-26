package com.nexio.tv.core.player.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFailureCodesTest {

    @Test
    fun `matches recognises 401 403 410`() {
        assertTrue(AuthFailureCodes.matches(401))
        assertTrue(AuthFailureCodes.matches(403))
        assertTrue(AuthFailureCodes.matches(410))
    }

    @Test
    fun `matches rejects transient 5xx codes which belong to TransientFailureCodes`() {
        assertFalse(AuthFailureCodes.matches(502))
        assertFalse(AuthFailureCodes.matches(503))
        assertFalse(AuthFailureCodes.matches(504))
    }

    @Test
    fun `ALL contains exactly the three auth codes`() {
        assertEquals(setOf(401, 403, 410), AuthFailureCodes.ALL)
    }
}
