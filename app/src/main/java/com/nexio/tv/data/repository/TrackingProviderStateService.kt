package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    val canReadEffectiveProvider: Boolean
        get() = hasAuthenticatedProvider

    fun isProviderAuthenticated(provider: TrackingProvider): Boolean {
        return when (provider) {
            TrackingProvider.TRAKT -> traktAuthenticated
            TrackingProvider.SIMKL -> simklAuthenticated
        }
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingProviderStateService @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary
) {
    val state: Flow<EffectiveTrackingProviderState> = combineTrackingState(
        profileManager.activeProfileId.flatMapLatest(::authStateForProfile)
    )

    fun stateForProfile(profileId: Int): Flow<EffectiveTrackingProviderState> =
        combineTrackingState(authStateForProfile(profileId))

    suspend fun currentState(): EffectiveTrackingProviderState = state.first()

    suspend fun currentState(profileId: Int): EffectiveTrackingProviderState =
        stateForProfile(profileId).first()

    fun currentProfileId(): Int = profileManager.activeProfileId.value

    private fun combineTrackingState(authStateFlow: Flow<TrackingAuthState>): Flow<EffectiveTrackingProviderState> = combine(
        playerSettingsDataStore.playerSettings,
        authStateFlow
    ) { settings, authState ->
        val effectiveProvider = if (authState.hasAnyAuthenticatedProvider) {
            when {
                authState.traktAuthenticated && !authState.simklAuthenticated -> TrackingProvider.TRAKT
                authState.simklAuthenticated && !authState.traktAuthenticated -> TrackingProvider.SIMKL
                authState.traktAuthenticated && authState.simklAuthenticated -> settings.trackingProvider
                else -> selectedProvider(settings.trackingProvider)
            }
        } else {
            settings.trackingProvider
        }
        EffectiveTrackingProviderState(
            storedProvider = settings.trackingProvider,
            effectiveProvider = effectiveProvider,
            traktAuthenticated = authState.traktAuthenticated,
            simklAuthenticated = authState.simklAuthenticated
        )
    }

    private fun selectedProvider(provider: TrackingProvider): TrackingProvider = provider

    private fun authStateForProfile(profileId: Int): Flow<TrackingAuthState> {
        return when (val route = profileModeRouter.routeFor(profileId)) {
            ProfileModeRoute.DefaultLegacyRoute -> authStateForRoutedProfile(profileModeRouter.defaultLegacyProfileId())
            is ProfileModeRoute.SecondaryProfileRoute -> {
                val traktProfileId = profileBoundary.authRoute(route, TrackingProvider.TRAKT).profileId
                val simklProfileId = profileBoundary.authRoute(route, TrackingProvider.SIMKL).profileId
                authStateForRoutedProfile(traktProfileId, simklProfileId)
            }
            is ProfileModeRoute.InvalidProfileRoute -> flowOf(TrackingAuthState())
        }
    }

    private fun authStateForRoutedProfile(
        traktProfileId: Int,
        simklProfileId: Int = traktProfileId
    ): Flow<TrackingAuthState> {
        return combine(
            traktAuthDataStore.stateForProfile(traktProfileId).map { it.isAuthenticated },
            simklAuthDataStore.stateForProfile(simklProfileId).map { it.isAuthenticated }
        ) { traktAuthenticated, simklAuthenticated ->
            TrackingAuthState(
                traktAuthenticated = traktAuthenticated,
                simklAuthenticated = simklAuthenticated
            )
        }
    }

    private data class TrackingAuthState(
        val traktAuthenticated: Boolean = false,
        val simklAuthenticated: Boolean = false
    ) {
        val hasAnyAuthenticatedProvider: Boolean
            get() = traktAuthenticated || simklAuthenticated
    }
}
