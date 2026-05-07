package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.SubtitleStyleSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

        val activeSession = coordinator.start(this, generationProvider = { 11L })
        advanceUntilIdle()
        val session = activeSession.value

        assertEquals(2, session.profileId)
        assertEquals("nl", session.language)
        assertEquals("fr", session.subtitleLanguage)
        assertTrue(session.sessionId.contains("profile:2:runtime"))
    }

    @Test
    fun `profile reset does not clear shared cache owners`() {
        assertFalse(catalogPipelineSource.contains("metadataDiskCacheStore.clear"))
        assertFalse(catalogPipelineSource.contains("artworkDecisionStore.clear"))
        assertFalse(catalogPipelineSource.contains("integrationCache.clear"))
        assertFalse(catalogPipelineSource.contains("runtimeCache.clear"))
    }
}
