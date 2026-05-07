@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexio.tv.ui.screens.home

import android.content.Context
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

class HomeProfileSessionCoordinator internal constructor(
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val profileBoundary: ProfileBoundary,
    private val localeTags: Flow<String?>,
    private val playerSettings: Flow<PlayerSettings>,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        profileManager: ProfileManager,
        profileModeRouter: ProfileModeRouter,
        profileBoundary: ProfileBoundary,
        playerSettingsDataStore: PlayerSettingsDataStore,
        @ApplicationContext context: Context
    ) : this(
        profileManager = profileManager,
        profileModeRouter = profileModeRouter,
        profileBoundary = profileBoundary,
        localeTags = AppLocaleResolver.observeStoredLocaleTag(context)
            .onStart { emit(AppLocaleResolver.getStoredLocaleTag(context)) },
        playerSettings = playerSettingsDataStore.playerSettings,
        nowMs = { System.currentTimeMillis().coerceAtLeast(1L) }
    )

    internal fun start(scope: CoroutineScope, generationProvider: () -> Long): StateFlow<HomeProfileSession> {
        val initialInputs = bootstrapSessionInputs(profileManager.activeProfileSession.value)
        val initialSession = createSession(initialInputs, generationProvider)
        val sessions = profileManager.activeProfileSession.flatMapLatest { profileSession ->
            combine(
                localeTags,
                playerSettings
                    .map<PlayerSettings, PlayerSettings?> { it }
                    .onStart { emit(null) }
            ) { _, settings ->
                if (settings == null) {
                    bootstrapSessionInputs(profileSession)
                } else {
                    sessionInputs(
                        profileSession = profileSession,
                        settings = settings
                    )
                }
            }
        }.runningFold(initialInputs to initialSession) { (_, previousSession), inputs ->
            if (previousSession.matches(inputs)) {
                inputs to previousSession
            } else {
                inputs to createSession(inputs, generationProvider)
            }
        }.map { (_, session) ->
            session
        }
        return sessions.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = initialSession
        )
    }

    private fun bootstrapSessionInputs(profileSession: ActiveProfileSession): HomeProfileSessionInputs {
        return HomeProfileSessionInputs(
            profileSession = profileSession,
            language = profileBoundary.currentLanguageTag(),
            subtitleLanguage = null
        )
    }

    private fun sessionInputs(
        profileSession: ActiveProfileSession,
        settings: PlayerSettings
    ): HomeProfileSessionInputs {
        val subtitleLanguage = settings.subtitleStyle.preferredLanguage
            .takeUnless { it.equals("none", ignoreCase = true) }
        return HomeProfileSessionInputs(
            profileSession = profileSession,
            language = profileBoundary.currentLanguageTag(),
            subtitleLanguage = subtitleLanguage
        )
    }

    private fun createSession(
        inputs: HomeProfileSessionInputs,
        generationProvider: () -> Long
    ): HomeProfileSession {
        val profileId = inputs.profileSession.profileId
        val generation = generationProvider()
        val sessionId = "home-${inputs.profileSession.sessionId}:$generation"
        val startedAtMs = nowMs()
        return when (val route = profileModeRouter.routeFor(profileId)) {
            ProfileModeRoute.DefaultLegacyRoute -> HomeProfileSession.DefaultLegacy(
                generation = generation,
                sessionId = sessionId,
                language = inputs.language,
                subtitleLanguage = inputs.subtitleLanguage,
                startedAtMs = startedAtMs
            )
            is ProfileModeRoute.SecondaryProfileRoute -> HomeProfileSession.Secondary(
                profileId = profileId,
                generation = generation,
                sessionId = sessionId,
                language = inputs.language,
                subtitleLanguage = inputs.subtitleLanguage,
                startedAtMs = startedAtMs,
                boundaryContext = profileBoundary.contextFor(route)
            )
            is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active home profile id ${route.profileId}")
        }
    }

    private fun HomeProfileSession.matches(inputs: HomeProfileSessionInputs): Boolean {
        return profileId == inputs.profileSession.profileId &&
            sessionId.startsWith("home-${inputs.profileSession.sessionId}:") &&
            language == inputs.language &&
            subtitleLanguage == inputs.subtitleLanguage
    }

    private data class HomeProfileSessionInputs(
        val profileSession: ActiveProfileSession,
        val language: String,
        val subtitleLanguage: String?
    )
}
