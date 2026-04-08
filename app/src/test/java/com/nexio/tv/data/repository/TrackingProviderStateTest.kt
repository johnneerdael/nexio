package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProviderStateTest {

    @Test
    fun `single Trakt configuration forces Trakt`() {
        val state = TrackingProviderState(
            storedProvider = TrackingProvider.SIMKL,
            traktConfigured = true,
            simklConfigured = false
        )

        assertFalse(state.canChoose)
        assertTrue(state.hasAnyConfiguredProvider)
        assertEquals(TrackingProvider.TRAKT, state.effectiveProvider)
    }

    @Test
    fun `single SIMKL configuration forces SIMKL`() {
        val state = TrackingProviderState(
            storedProvider = TrackingProvider.TRAKT,
            traktConfigured = false,
            simklConfigured = true
        )

        assertFalse(state.canChoose)
        assertTrue(state.hasAnyConfiguredProvider)
        assertEquals(TrackingProvider.SIMKL, state.effectiveProvider)
    }

    @Test
    fun `dual configuration honors stored provider`() {
        val state = TrackingProviderState(
            storedProvider = TrackingProvider.SIMKL,
            traktConfigured = true,
            simklConfigured = true
        )

        assertTrue(state.canChoose)
        assertEquals(TrackingProvider.SIMKL, state.effectiveProvider)
    }
}
