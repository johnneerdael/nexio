package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveTrackingProviderStateTest {

    @Test
    fun `activeProviders contains both when both authed`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = true,
            mdbListAuthenticated = true,
        )
        assertEquals(setOf(TrackingProvider.TRAKT, TrackingProvider.SIMKL, TrackingProvider.MDBLIST), state.activeProviders)
        assertTrue(state.hasAuthenticatedProvider)
        assertTrue(state.canReadEffectiveProvider)
    }

    @Test
    fun `activeProviders contains only Trakt when only Trakt authed`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = false,
        )
        assertEquals(setOf(TrackingProvider.TRAKT), state.activeProviders)
    }

    @Test
    fun `activeProviders contains only Simkl when only Simkl authed`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = false,
            simklAuthenticated = true,
        )
        assertEquals(setOf(TrackingProvider.SIMKL), state.activeProviders)
    }

    @Test
    fun `activeProviders is empty when no provider authed`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = false,
            simklAuthenticated = false,
            mdbListAuthenticated = false,
        )
        assertEquals(emptySet<TrackingProvider>(), state.activeProviders)
    }

    @Test
    fun `MDBList-only auth is active for scrobble but not readable for progress surfaces`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = false,
            simklAuthenticated = false,
            mdbListAuthenticated = true,
        )
        assertEquals(setOf(TrackingProvider.MDBLIST), state.activeProviders)
        assertTrue(state.hasAuthenticatedProvider)
        assertFalse(state.canReadEffectiveProvider)
    }
}
