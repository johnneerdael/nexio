package com.nexio.tv.data.repository

import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class EffectiveTrackingProviderState(
    val storedProvider: TrackingProvider = TrackingProvider.TRAKT,
    val effectiveProvider: TrackingProvider = TrackingProvider.TRAKT,
    val traktAuthenticated: Boolean = false,
    val simklAuthenticated: Boolean = false
) {
    val hasAuthenticatedProvider: Boolean
        get() = traktAuthenticated || simklAuthenticated
}

@Singleton
class TrackingProviderStateService @Inject constructor(
    playerSettingsDataStore: PlayerSettingsDataStore,
    traktAuthDataStore: TraktAuthDataStore,
    simklAuthDataStore: SimklAuthDataStore
) {
    val state: Flow<EffectiveTrackingProviderState> = combine(
        playerSettingsDataStore.playerSettings,
        traktAuthDataStore.isEffectivelyAuthenticated,
        simklAuthDataStore.isEffectivelyAuthenticated
    ) { settings, traktAuthenticated, simklAuthenticated ->
        val effectiveProvider = when {
            traktAuthenticated && !simklAuthenticated -> TrackingProvider.TRAKT
            simklAuthenticated && !traktAuthenticated -> TrackingProvider.SIMKL
            else -> settings.trackingProvider
        }
        EffectiveTrackingProviderState(
            storedProvider = settings.trackingProvider,
            effectiveProvider = effectiveProvider,
            traktAuthenticated = traktAuthenticated,
            simklAuthenticated = simklAuthenticated
        )
    }

    suspend fun currentState(): EffectiveTrackingProviderState = state.first()
}
