@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        var currentProfileSessionId = profileManager.activeProfileSession.value.sessionId
        var currentSession = createSession(
            profileSession = profileManager.activeProfileSession.value,
            settings = initialPlayerSettings(),
            generationProvider = generationProvider,
            previousProfileSessionId = null,
            previousSession = null
        )
        val sessions = combine(
            profileManager.activeProfileSession,
            localeTags,
            playerSettings
        ) { profileSession, _, settings ->
            createSession(
                profileSession = profileSession,
                settings = settings,
                generationProvider = generationProvider,
                previousProfileSessionId = currentProfileSessionId,
                previousSession = currentSession
            ).also {
                currentProfileSessionId = profileSession.sessionId
                currentSession = it
            }
        }
        return LazyHomeProfileSessionStateFlow(
            scope = scope,
            initialValue = currentSession,
            sessions = sessions
        )
    }

    private fun initialPlayerSettings(): PlayerSettings {
        return runBlocking {
            playerSettings.first()
        }
    }

    private fun createSession(
        profileSession: ActiveProfileSession,
        settings: PlayerSettings,
        generationProvider: () -> Long,
        previousProfileSessionId: String?,
        previousSession: HomeProfileSession?
    ): HomeProfileSession {
        val profileId = profileSession.profileId
        val language = profileBoundary.currentLanguageTag()
        val subtitleLanguage = settings.subtitleStyle.preferredLanguage
            .takeUnless { it.equals("none", ignoreCase = true) }
        if (
            previousSession != null &&
            previousSession.profileId == profileId &&
            previousProfileSessionId == profileSession.sessionId &&
            previousSession.language == language &&
            previousSession.subtitleLanguage == subtitleLanguage
        ) {
            return previousSession
        }
        val generation = generationProvider()
        val sessionId = "home-${profileSession.sessionId}:$generation"
        val startedAtMs = nowMs()
        return when (val route = profileModeRouter.routeFor(profileId)) {
            ProfileModeRoute.DefaultLegacyRoute -> HomeProfileSession.DefaultLegacy(
                generation = generation,
                sessionId = sessionId,
                language = language,
                subtitleLanguage = subtitleLanguage,
                startedAtMs = startedAtMs
            )
            is ProfileModeRoute.SecondaryProfileRoute -> HomeProfileSession.Secondary(
                profileId = profileId,
                generation = generation,
                sessionId = sessionId,
                language = language,
                subtitleLanguage = subtitleLanguage,
                startedAtMs = startedAtMs,
                boundaryContext = profileBoundary.contextFor(route)
            )
            is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active home profile id ${route.profileId}")
        }
    }

    private class LazyHomeProfileSessionStateFlow(
        private val scope: CoroutineScope,
        initialValue: HomeProfileSession,
        private val sessions: Flow<HomeProfileSession>
    ) : StateFlow<HomeProfileSession> {
        private val state = MutableStateFlow(initialValue)
        private var collectionJob: Job? = null

        override val replayCache: List<HomeProfileSession>
            get() = state.replayCache

        override val value: HomeProfileSession
            get() = state.value

        override suspend fun collect(collector: FlowCollector<HomeProfileSession>): Nothing {
            if (collectionJob == null) {
                collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var baseline = true
                    sessions.collect { session ->
                        if (baseline) {
                            baseline = false
                        } else {
                            state.value = session
                        }
                    }
                }
            }
            state.collect(collector)
        }
    }
}
