package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebufferGateTest {

    @Test
    fun `fresh gate is not paused and ageMs is MAX_VALUE`() {
        var fakeNow = 0L
        val gate = RebufferGate(ttlMs = 2_000L, nowMsProvider = { fakeNow })
        assertFalse(gate.isPaused())
        assertEquals(Long.MAX_VALUE, gate.ageMs())
    }

    @Test
    fun `after notifyRebuffer at t0, paused at t500, ageMs is 500`() {
        var fakeNow = 0L
        val gate = RebufferGate(ttlMs = 2_000L, nowMsProvider = { fakeNow })
        gate.notifyRebuffer()
        fakeNow = 500L
        assertTrue(gate.isPaused())
        assertEquals(500L, gate.ageMs())
    }

    @Test
    fun `after TTL expires, gate is no longer paused`() {
        var fakeNow = 0L
        val gate = RebufferGate(ttlMs = 2_000L, nowMsProvider = { fakeNow })
        gate.notifyRebuffer()
        fakeNow = 2_500L
        assertFalse(gate.isPaused())
    }

    @Test
    fun `custom TTL paused within window, not paused after`() {
        var fakeNow = 0L
        val gate = RebufferGate(ttlMs = 500L, nowMsProvider = { fakeNow })
        gate.notifyRebuffer()
        fakeNow = 100L
        assertTrue(gate.isPaused())
        fakeNow = 600L
        assertFalse(gate.isPaused())
    }

    @Test
    fun `multiple notifyRebuffer calls use most recent timestamp`() {
        var fakeNow = 0L
        val gate = RebufferGate(ttlMs = 2_000L, nowMsProvider = { fakeNow })
        gate.notifyRebuffer()           // t=0
        fakeNow = 1_500L
        gate.notifyRebuffer()           // t=1500 — resets the clock
        fakeNow = 3_000L                // 1500ms after second notify, still within TTL
        assertTrue(gate.isPaused())
        fakeNow = 3_600L                // 2100ms after second notify, past TTL
        assertFalse(gate.isPaused())
    }
}
