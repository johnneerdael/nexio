package com.nexio.tv.core.player.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientFailureCodesTest {

    @Test
    fun `matches recognises 502 503 504`() {
        assertTrue(TransientFailureCodes.matches(502))
        assertTrue(TransientFailureCodes.matches(503))
        assertTrue(TransientFailureCodes.matches(504))
    }

    @Test
    fun `matches rejects auth failure codes`() {
        assertFalse(TransientFailureCodes.matches(401))
        assertFalse(TransientFailureCodes.matches(403))
        assertFalse(TransientFailureCodes.matches(410))
    }

    @Test
    fun `matches rejects success and other 5xx`() {
        assertFalse(TransientFailureCodes.matches(200))
        assertFalse(TransientFailureCodes.matches(206))
        assertFalse(TransientFailureCodes.matches(500))
        assertFalse(TransientFailureCodes.matches(505))
        assertFalse(TransientFailureCodes.matches(599))
    }

    @Test
    fun `ALL contains exactly the three transient codes`() {
        assertEquals(setOf(502, 503, 504), TransientFailureCodes.ALL)
    }
}
