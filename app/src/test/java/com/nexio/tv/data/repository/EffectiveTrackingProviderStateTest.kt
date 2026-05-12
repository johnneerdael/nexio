package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveTrackingProviderStateTest {

    @Test
    fun `activeProviders contains both when both authed`() {
        val state = EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = true,
        )
        assertEquals(setOf(TrackingProvider.TRAKT, TrackingProvider.SIMKL), state.activeProviders)
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
        )
        assertEquals(emptySet<TrackingProvider>(), state.activeProviders)
    }
}
