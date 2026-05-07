package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.SubtitleStyleSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class HomeProfileSessionLifecycleContractTest {
    private val homeProfileSessionSource =
        File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt").readText()
    private val coordinatorSourceFile =
        File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSessionCoordinator.kt")
    private val homeViewModelSource =
        File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
    private val catalogPipelineSource =
        File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt").readText()

    @Test
    fun `home profile session has stable session identity fields`() {
        assertTrue(homeProfileSessionSource.contains("val sessionId: String"))
        assertTrue(homeProfileSessionSource.contains("val profileSessionKey: String"))
        assertTrue(homeProfileSessionSource.contains("val startedAtMs: Long"))
        assertTrue(homeProfileSessionSource.contains("val language: String"))
        assertTrue(homeProfileSessionSource.contains("val subtitleLanguage: String?"))
    }

    @Test
    fun `home view model exposes active home profile session as state flow`() {
        assertTrue(homeViewModelSource.contains("homeProfileSessionCoordinator.start"))
        assertTrue(homeViewModelSource.contains("val activeHomeProfileSession: StateFlow<HomeProfileSession>"))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSessionSnapshot = session"))
    }

    @Test
    fun `profile session snapshot is assigned before profile scoped reset`() {
        val assignIndex = homeViewModelSource.indexOf("activeHomeProfileSessionSnapshot = session")
        val resetIndex = homeViewModelSource.indexOf("resetProfileScopedHomeState(\"home_session:")

        assertTrue(assignIndex >= 0)
        assertTrue(resetIndex >= 0)
        assertTrue(assignIndex < resetIndex)
    }

    @Test
    fun `profile scoped reset is keyed by profile session identity not settings session id`() {
        assertTrue(homeViewModelSource.contains("val previousSession = activeHomeProfileSessionSnapshot"))
        assertTrue(homeViewModelSource.contains("shouldResetProfileScopedHomeState("))
        assertTrue(homeViewModelSource.contains("previousSession.profileSessionKey != nextSession.profileSessionKey"))
        assertFalse(homeViewModelSource.contains(".distinctUntilChangedBy { it.profileSessionKey }"))
        assertFalse(homeViewModelSource.contains(".distinctUntilChangedBy { it.sessionId }"))
    }

    @Test
    fun `bootstrap to settings emission for same profile session does not require profile reset`() {
        val bootstrapSession = HomeProfileSession.DefaultLegacy(
            generation = 1L,
            sessionId = "home-profile:1:runtime:1",
            profileSessionKey = "profile:1:profile:1:runtime:1",
            language = "en",
            subtitleLanguage = null,
            startedAtMs = 1L
        )
        val settingsSession = bootstrapSession.copy(
            generation = 2L,
            sessionId = "home-profile:1:runtime:2",
            subtitleLanguage = "fr"
        )
        val switchedSession = HomeProfileSession.Secondary(
            profileId = 2,
            generation = 3L,
            sessionId = "home-profile:2:runtime:3",
            profileSessionKey = "profile:2:profile:2:runtime:2",
            language = "en",
            subtitleLanguage = null,
            startedAtMs = 2L,
            boundaryContext = com.nexio.tv.core.profile.SecondaryProfileRuntimeContext(
                profileId = 2,
                languageTag = "en",
                generation = 2L
            )
        )

        assertFalse(shouldResetProfileScopedHomeState(bootstrapSession, settingsSession))
        assertTrue(shouldResetProfileScopedHomeState(settingsSession, switchedSession))
    }

    @Test
    fun `home profile session uses profile language and subtitle language`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 2,
                sessionId = "profile:2:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(2)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "nl"
            every { contextFor(ProfileModeRoute.SecondaryProfileRoute(2)) } returns
                com.nexio.tv.core.profile.SecondaryProfileRuntimeContext(
                    profileId = 2,
                    languageTag = "nl",
                    generation = 7L
                )
        }
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("nl"),
            playerSettings = flowOf(
                PlayerSettings(
                    subtitleStyle = SubtitleStyleSettings(
                        preferredLanguage = "fr",
                        secondaryPreferredLanguage = "de"
                    )
                )
            ),
            nowMs = { 1234L }
        )

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { 11L })
            advanceUntilIdle()
            val session = activeSession.value

            assertEquals(2, session.profileId)
            assertEquals("nl", session.language)
            assertEquals("fr", session.subtitleLanguage)
            assertTrue(session.sessionId.contains("profile:2:runtime"))
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `home profile session bootstrap does not claim default subtitle before settings emit`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "en"
        }
        val playerSettings = MutableSharedFlow<PlayerSettings>()
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("en"),
            playerSettings = playerSettings,
            nowMs = { 1234L }
        )

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { 11L })
            advanceUntilIdle()

            assertEquals(1, activeSession.value.profileId)
            assertEquals("en", activeSession.value.language)
            assertEquals(null, activeSession.value.subtitleLanguage)

            playerSettings.emit(
                PlayerSettings(
                    subtitleStyle = SubtitleStyleSettings(
                        preferredLanguage = "fr",
                        secondaryPreferredLanguage = "de"
                    )
                )
            )
            advanceUntilIdle()

            assertEquals("fr", activeSession.value.subtitleLanguage)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `home profile session does not carry previous profile subtitle into next profile session`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "en"
            every { contextFor(ProfileModeRoute.SecondaryProfileRoute(2)) } returns
                com.nexio.tv.core.profile.SecondaryProfileRuntimeContext(
                    profileId = 2,
                    languageTag = "en",
                    generation = 2L
                )
        }
        val playerSettings = MutableSharedFlow<PlayerSettings>()
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("en"),
            playerSettings = playerSettings,
            nowMs = { 1234L }
        )

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { 11L })
            advanceUntilIdle()

            playerSettings.emit(
                PlayerSettings(
                    subtitleStyle = SubtitleStyleSettings(
                        preferredLanguage = "en"
                    )
                )
            )
            advanceUntilIdle()

            assertEquals(1, activeSession.value.profileId)
            assertEquals("en", activeSession.value.subtitleLanguage)

            activeProfileSession.value = com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 2,
                sessionId = "profile:2:runtime",
                sessionOrdinal = 2L,
                startedAtMs = 200L
            )
            advanceUntilIdle()

            assertEquals(2, activeSession.value.profileId)
            assertTrue(activeSession.value.sessionId.contains("profile:2:runtime"))
            assertEquals(null, activeSession.value.subtitleLanguage)

            playerSettings.emit(
                PlayerSettings(
                    subtitleStyle = SubtitleStyleSettings(
                        preferredLanguage = "fr"
                    )
                )
            )
            advanceUntilIdle()

            assertEquals(2, activeSession.value.profileId)
            assertEquals("fr", activeSession.value.subtitleLanguage)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `home profile session updates on first profile session change after start`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "en"
            every { contextFor(ProfileModeRoute.SecondaryProfileRoute(2)) } returns
                com.nexio.tv.core.profile.SecondaryProfileRuntimeContext(
                    profileId = 2,
                    languageTag = "en",
                    generation = 2L
                )
        }
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("en"),
            playerSettings = flowOf(PlayerSettings()),
            nowMs = { 1234L }
        )

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { 11L })
            activeProfileSession.value = com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 2,
                sessionId = "profile:2:runtime",
                sessionOrdinal = 2L,
                startedAtMs = 200L
            )
            advanceUntilIdle()

            assertEquals(2, activeSession.value.profileId)
            assertTrue(activeSession.value.sessionId.contains("profile:2:runtime"))
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `home profile session updates when session ordinal changes with reused runtime id`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "en"
        }
        val playerSettings = MutableSharedFlow<PlayerSettings>()
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("en"),
            playerSettings = playerSettings,
            nowMs = { 1234L }
        )

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { 11L })
            advanceUntilIdle()
            val initialKey = activeSession.value.profileSessionKey

            activeProfileSession.value = com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 2L,
                startedAtMs = 200L
            )
            advanceUntilIdle()

            assertEquals(1, activeSession.value.profileId)
            assertTrue(activeSession.value.sessionId.contains("profile:1:runtime"))
            assertTrue(activeSession.value.profileSessionKey.endsWith(":2"))
            assertFalse(initialKey == activeSession.value.profileSessionKey)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `settings updates reuse generation while profile session identity changes advance generation`() = runTest {
        val activeProfileSession = MutableStateFlow(
            com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 1L,
                startedAtMs = 100L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        }
        val profileBoundary = mockk<ProfileBoundary> {
            every { currentLanguageTag() } returns "en"
        }
        val playerSettings = MutableSharedFlow<PlayerSettings>()
        val coordinator = HomeProfileSessionCoordinator(
            profileManager = profileManager,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = profileBoundary,
            localeTags = flowOf("en"),
            playerSettings = playerSettings,
            nowMs = { 1234L }
        )
        var nextGeneration = 10L

        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val activeSession = coordinator.start(sessionScope, generationProvider = { nextGeneration++ })
            advanceUntilIdle()
            val bootstrapGeneration = activeSession.value.generation
            val bootstrapKey = activeSession.value.profileSessionKey

            playerSettings.emit(
                PlayerSettings(
                    subtitleStyle = SubtitleStyleSettings(
                        preferredLanguage = "fr"
                    )
                )
            )
            advanceUntilIdle()

            assertEquals(bootstrapKey, activeSession.value.profileSessionKey)
            assertEquals("fr", activeSession.value.subtitleLanguage)
            assertEquals(bootstrapGeneration, activeSession.value.generation)

            activeProfileSession.value = com.nexio.tv.core.integration.ActiveProfileSession(
                profileId = 1,
                sessionId = "profile:1:runtime",
                sessionOrdinal = 2L,
                startedAtMs = 200L
            )
            advanceUntilIdle()

            assertTrue(activeSession.value.profileSessionKey.endsWith(":2"))
            assertTrue(activeSession.value.generation > bootstrapGeneration)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `profile reset does not clear shared cache owners`() {
        assertFalse(catalogPipelineSource.contains("metadataDiskCacheStore.clear"))
        assertFalse(catalogPipelineSource.contains("artworkDecisionStore.clear"))
        assertFalse(catalogPipelineSource.contains("integrationCache.clear"))
        assertFalse(catalogPipelineSource.contains("runtimeCache.clear"))
    }
}
