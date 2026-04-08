package com.nexio.tv.data.repository

import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TrackingProviderState(
    val storedProvider: TrackingProvider = TrackingProvider.TRAKT,
    val traktConfigured: Boolean = false,
    val simklConfigured: Boolean = false
) {
    val canChoose: Boolean
        get() = traktConfigured && simklConfigured

    val hasAnyConfiguredProvider: Boolean
        get() = traktConfigured || simklConfigured

    val effectiveProvider: TrackingProvider
        get() = when {
            traktConfigured && !simklConfigured -> TrackingProvider.TRAKT
            simklConfigured && !traktConfigured -> TrackingProvider.SIMKL
            else -> storedProvider
        }
}

@Singleton
class TrackingProviderStateRepository @Inject constructor(
    playerSettingsDataStore: PlayerSettingsDataStore,
    traktAuthDataStore: TraktAuthDataStore,
    simklAuthDataStore: SimklAuthDataStore
) {
    val state: Flow<TrackingProviderState> = combine(
        playerSettingsDataStore.playerSettings,
        traktAuthDataStore.isEffectivelyAuthenticated,
        simklAuthDataStore.isEffectivelyAuthenticated
    ) { settings, traktConfigured, simklConfigured ->
        TrackingProviderState(
            storedProvider = settings.trackingProvider,
            traktConfigured = traktConfigured,
            simklConfigured = simklConfigured
        )
    }
}
