# Home Profile Session Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix profile switching so profile-scoped Home collectors restart for the active profile session, Continue Watching cannot permanently hold the full Home screen behind a spinner, and shared metadata/artwork/runtime caches remain cache-first and shared.

**Architecture:** Standardize the existing `HomeProfileSession` as the single active Home session object and expose it as a `StateFlow`. Profile-owned Home streams use `activeHomeProfileSession.flatMapLatest`, tag emissions with the session, and ignore stale emissions. The session reads language from the existing profile-aware locale boundary and subtitle language from profile-scoped player settings. Shared systems such as installed add-ons, metadata cache, artwork cache, identity mapping, and runtime caches do not restart or clear on profile switch.

**Tech Stack:** Kotlin, Android ViewModel, Kotlin Flow, Jetpack Compose, JUnit, MockK, Turbine where existing tests already use it.

---

## Scope Check

This plan handles the Home profile-session lifecycle bug and its direct architectural guardrails. It does not redesign Trakt/Simkl scrobble ownership or the global IntegrationRuntime cache, because those are separate subsystems already covered by profile-boundary work; this plan adds regression tests that ensure Home does not clear shared cache owners while fixing profile-owned collectors.

## File Structure

- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt`
  - Add `sessionId`, `startedAtMs`, `language`, and `subtitleLanguage` to the existing Home session model.
  - Keep `DefaultLegacy` and `Secondary` so current routing code stays recognizable.
- Create `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSessionCoordinator.kt`
  - Combine `ProfileManager.activeProfileSession`, profile-aware locale changes, and profile-scoped `PlayerSettingsDataStore.playerSettings`.
  - Emit a new Home session when profile, language, or subtitle settings change.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - Expose `activeHomeProfileSession` as a `StateFlow<HomeProfileSession>`.
  - Collect session changes and reset profile-scoped Home state after the new session is published.
  - Add small helpers for stale-session checks and readiness updates.
- Create `app/src/main/java/com/nexio/tv/ui/screens/home/HomeInitialReadiness.kt`
  - Holds session-scoped readiness gates and gate helper functions.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
  - Replace the loose `initialContinueWatchingResolved` boolean with session-scoped readiness.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Reset readiness for the new session on profile switch.
  - Preserve shared caches; only clear profile-owned visible Home state.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Add a small pure `continueWatchingProfileScopedEmissions(...)` flow builder for behavior-level tests.
  - Restart Continue Watching collection with `activeHomeProfileSession.flatMapLatest`.
  - Resolve the Continue Watching gate on empty/error/timeout for the current session.
  - Ignore stale profile emissions.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
  - Make Continue Watching non-blocking once catalog/snapshot rows are renderable.
  - Keep row-level Continue Watching readiness available to the UI.
- Modify `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  - Ensure `observeProfileSnapshot(profileId)` emits an initial empty snapshot for the requested profile when no records exist.
- Modify `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
  - Add Home profile-session and gate trace event methods.
- Modify `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Add curated fields for the new Home trace events.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingProfileScopedTest.kt`
  - Add behavior-level tests with fake sessions and fake snapshot flows, then keep source assertions as guardrails.
- Modify `app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt`
  - Add pure renderability/gate tests for “catalog rows render even while Continue Watching is pending.”
- Modify `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceObserveProfileSnapshotTest.kt`
  - Add the initial empty emission contract.
- Modify `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
  - Update source-level contract assertions to the session-driven collector and session-scoped readiness.
- Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt`
  - Add source-level architectural tests that profile-owned Home collectors are session-driven and shared cache fields are not cleared during profile reset.

## Task 1: Add Session-Scoped Readiness Model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeInitialReadiness.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt`

- [ ] **Step 1: Write failing readiness tests**

Add these tests to `HomeScreenRenderabilityTest`:

```kotlin
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape

@Test
fun `continue watching pending does not block renderable modern catalog rows`() {
    val sessionId = "profile:2:test-session"
    val state = HomeUiState(
        homeLayout = HomeLayout.MODERN,
        catalogRows = listOf(contentRow()),
        installedAddonsCount = 3,
        isLoading = false,
        homeReadiness = HomeInitialReadiness.started(
            sessionId = sessionId,
            profileId = 2
        ).markLoading(HomeInitialGate.CONTINUE_WATCHING)
    )

    assertTrue(hasRenderableHomeContent(state))
    assertFalse(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
}

@Test
fun `continue watching pending still gates empty modern home before timeout`() {
    val sessionId = "profile:2:test-session"
    val state = HomeUiState(
        homeLayout = HomeLayout.MODERN,
        catalogRows = emptyList(),
        installedAddonsCount = 3,
        isLoading = false,
        homeReadiness = HomeInitialReadiness.started(
            sessionId = sessionId,
            profileId = 2
        ).markLoading(HomeInitialGate.CONTINUE_WATCHING)
    )

    assertTrue(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
}

@Test
fun `empty modern home with catalog loading still shows spinner even when continue watching resolved`() {
    val sessionId = "profile:2:test-session"
    val state = HomeUiState(
        homeLayout = HomeLayout.MODERN,
        catalogRows = emptyList(),
        installedAddonsCount = 3,
        isLoading = true,
        homeReadiness = HomeInitialReadiness.started(
            sessionId = sessionId,
            profileId = 2
        ).markResolved(HomeInitialGate.CONTINUE_WATCHING, "first_snapshot_empty")
    )

    assertTrue(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
}

@Test
fun `empty modern home with continue watching resolved and no loading shows empty state`() {
    val sessionId = "profile:2:test-session"
    val state = HomeUiState(
        homeLayout = HomeLayout.MODERN,
        catalogRows = emptyList(),
        installedAddonsCount = 3,
        isLoading = false,
        homeReadiness = HomeInitialReadiness.started(
            sessionId = sessionId,
            profileId = 2
        ).markResolved(HomeInitialGate.CONTINUE_WATCHING, "first_snapshot_empty")
    )

    assertFalse(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
}

private fun contentRow(): CatalogRow {
    return CatalogRow(
        addonId = "com.stremio.torrentio.addon",
        addonName = "Torrentio RD",
        addonBaseUrl = "https://torrentio.example",
        catalogId = "torrentio-realdebrid",
        catalogName = "RealDebrid",
        type = ContentType.MOVIE,
        items = listOf(
            com.nexio.tv.domain.model.MetaPreview(
                id = "tt0111161",
                type = ContentType.MOVIE,
                name = "The Shawshank Redemption",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "1994",
                imdbRating = null,
                genres = emptyList()
            )
        ),
        isLoading = false
    )
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeScreenRenderabilityTest'
```

Expected: FAIL because `HomeInitialReadiness`, `HomeInitialGate`, and `shouldShowFullHomeLoadingGate` do not exist.

- [ ] **Step 3: Add the readiness model**

Create `HomeInitialReadiness.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

// This focused fix gates only Continue Watching. Add catalog/profile gates later
// when each gate is wired to real producer resolution events.
enum class HomeInitialGate {
    CONTINUE_WATCHING
}

sealed interface GateStatus {
    data object Pending : GateStatus
    data object Loading : GateStatus
    data class Resolved(val reason: String) : GateStatus
    data class FailedNonBlocking(val reason: String) : GateStatus
}

data class HomeInitialReadiness(
    val sessionId: String,
    val profileId: Int,
    val gates: Map<HomeInitialGate, GateStatus>
) {
    fun markLoading(gate: HomeInitialGate): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.Loading))

    fun markResolved(gate: HomeInitialGate, reason: String): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.Resolved(reason)))

    fun markFailedNonBlocking(gate: HomeInitialGate, reason: String): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.FailedNonBlocking(reason)))

    fun isResolved(gate: HomeInitialGate): Boolean {
        return when (gates[gate]) {
            is GateStatus.Resolved,
            is GateStatus.FailedNonBlocking -> true
            GateStatus.Pending,
            GateStatus.Loading,
            null -> false
        }
    }

    companion object {
        fun started(sessionId: String, profileId: Int): HomeInitialReadiness =
            HomeInitialReadiness(
                sessionId = sessionId,
                profileId = profileId,
                gates = mapOf(
                    HomeInitialGate.CONTINUE_WATCHING to GateStatus.Loading
                )
            )
    }
}

fun shouldShowFullHomeLoadingGate(
    uiState: HomeUiState,
    startupContentGateTimedOut: Boolean
): Boolean {
    if (uiState.error != null || startupContentGateTimedOut) return false
    val hasRenderableContent = hasRenderableHomeContent(uiState)
    if (!hasRenderableContent) return uiState.isLoading ||
        (
            uiState.homeLayout == com.nexio.tv.domain.model.HomeLayout.MODERN &&
                uiState.installedAddonsCount > 0 &&
                !uiState.homeReadiness.isResolved(HomeInitialGate.CONTINUE_WATCHING)
        )
    return false
}
```

Modify `HomeUiState.kt`:

```kotlin
val homeReadiness: HomeInitialReadiness = HomeInitialReadiness.started(
    sessionId = "profile:1:initial",
    profileId = 1
),
```

Remove this property:

```kotlin
val initialContinueWatchingResolved: Boolean = false,
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeScreenRenderabilityTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeInitialReadiness.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt
git commit -m "test: define session-scoped home readiness"
```

## Task 2: Make Home Profile Session Reactive

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSessionCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt`

- [ ] **Step 1: Write failing session contract tests**

Create `HomeProfileSessionLifecycleContractTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
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
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest'
```

Expected: FAIL because `HomeProfileSession` has no `sessionId`, `startedAtMs`, `language`, or `subtitleLanguage`, `HomeProfileSessionCoordinator` does not exist, and `HomeViewModel` has no session `StateFlow`.

- [ ] **Step 3: Extend `HomeProfileSession`**

Modify `HomeProfileSession.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.SecondaryProfileRuntimeContext

internal sealed interface HomeProfileSession {
    val profileId: Int
    val generation: Long
    val sessionId: String
    val language: String
    val subtitleLanguage: String?
    val startedAtMs: Long

    data class DefaultLegacy(
        override val generation: Long,
        override val sessionId: String,
        override val language: String,
        override val subtitleLanguage: String?,
        override val startedAtMs: Long
    ) : HomeProfileSession {
        override val profileId: Int = 1
    }

    data class Secondary(
        override val profileId: Int,
        override val generation: Long,
        override val sessionId: String,
        override val language: String,
        override val subtitleLanguage: String?,
        override val startedAtMs: Long,
        val boundaryContext: SecondaryProfileRuntimeContext
    ) : HomeProfileSession
}
```

- [ ] **Step 4: Add `HomeProfileSessionCoordinator`**

Create `HomeProfileSessionCoordinator.kt`:

```kotlin
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

internal class HomeProfileSessionCoordinator private constructor(
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

    internal constructor(
        profileManager: ProfileManager,
        profileModeRouter: ProfileModeRouter,
        profileBoundary: ProfileBoundary,
        localeTags: Flow<String?>,
        playerSettings: Flow<PlayerSettings>,
        nowMs: () -> Long
    ) : this(
        profileManager = profileManager,
        profileModeRouter = profileModeRouter,
        profileBoundary = profileBoundary,
        localeTags = localeTags,
        playerSettings = playerSettings,
        nowMs = nowMs
    )

    fun start(scope: CoroutineScope, generationProvider: () -> Long): StateFlow<HomeProfileSession> {
        return combine(
            profileManager.activeProfileSession,
            localeTags,
            playerSettings
        ) { profileSession, _, settings ->
            createSession(profileSession, settings, generationProvider)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = createSession(profileManager.activeProfileSession.value, PlayerSettings(), generationProvider)
        )
    }

    private fun createSession(
        profileSession: ActiveProfileSession,
        settings: PlayerSettings,
        generationProvider: () -> Long
    ): HomeProfileSession {
        val profileId = profileSession.profileId
        val generation = generationProvider()
        val language = profileBoundary.currentLanguageTag()
        val subtitleLanguage = settings.subtitleStyle.preferredLanguage
            .takeUnless { it.equals("none", ignoreCase = true) }
        return when (val route = profileModeRouter.routeFor(profileId)) {
            ProfileModeRoute.DefaultLegacyRoute -> HomeProfileSession.DefaultLegacy(
                generation = generation,
                sessionId = "home-${profileSession.sessionId}:$generation",
                language = language,
                subtitleLanguage = subtitleLanguage,
                startedAtMs = nowMs()
            )
            is ProfileModeRoute.SecondaryProfileRoute -> HomeProfileSession.Secondary(
                profileId = profileId,
                generation = generation,
                sessionId = "home-${profileSession.sessionId}:$generation",
                language = language,
                subtitleLanguage = subtitleLanguage,
                startedAtMs = nowMs(),
                boundaryContext = profileBoundary.contextFor(route)
            )
            is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active home profile id ${route.profileId}")
        }
    }
}
```

Keep these invariants intact:

```text
language comes from profileBoundary.currentLanguageTag()
subtitleLanguage comes from profile-scoped PlayerSettingsDataStore.playerSettings
new session emits when profile/language/subtitle changes
```

- [ ] **Step 5: Publish coordinator session changes from `HomeViewModel`**

In `HomeViewModel.kt`, add imports:

```kotlin
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.StateFlow
```

Add constructor dependency:

```kotlin
internal val homeProfileSessionCoordinator: HomeProfileSessionCoordinator,
```

Replace the current mutable session field:

```kotlin
internal var activeHomeProfileSession = startHomeProfileSession(profileManager.activeProfileId.value)
```

with:

```kotlin
val activeHomeProfileSession: StateFlow<HomeProfileSession> =
    homeProfileSessionCoordinator.start(viewModelScope, ::advanceHomeProfileGeneration)
internal var activeHomeProfileSessionSnapshot: HomeProfileSession = activeHomeProfileSession.value

internal fun isCurrentHomeSession(session: HomeProfileSession): Boolean {
    return activeHomeProfileSession.value.sessionId == session.sessionId &&
        isCurrentHomeProfileGeneration(session.generation)
}
```

Replace `observeProfileSwitches()` with collection of the published session flow:

```kotlin
private fun observeProfileSwitches() {
    viewModelScope.launch {
        activeHomeProfileSession
            .drop(1)
            .distinctUntilChangedBy { it.sessionId }
            .collectLatest { session ->
                activeHomeProfileSessionSnapshot = session
                profileSwitchDiskHydrationActive = true
                suppressProfileSwitchRefreshUntilMs = SystemClock.elapsedRealtime() + 5_000L
                resetProfileScopedHomeState("home_session:${session.profileId}")
                try {
                    continueWatchingSnapshotService.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
                    val hasDiskCacheState = loadActiveProfileDiskBackedHomeState(
                        reason = "home_session:${session.profileId}",
                        expectedGeneration = session.generation
                    )
                    if (isCurrentHomeProfileGeneration(session.generation)) {
                        if (hasDiskCacheState) {
                            activateProfileSwitchDiskSnapshotMode(session.generation)
                        } else {
                            clearProfileSwitchDiskSnapshotMode("profile_switch_no_disk_state")
                        }
                        reloadDiskCachedAddonCatalogsForActiveProfileSwitch(allowNetworkRefresh = !hasDiskCacheState)
                    }
                } finally {
                    if (isCurrentHomeProfileGeneration(session.generation)) {
                        profileSwitchDiskHydrationActive = false
                        pendingSerializedHomeRefreshReason = null
                        startupRefreshPending = false
                    }
                }
            }
    }
}
```

Replace internal reads that need a synchronous snapshot:

```kotlin
activeHomeProfileSession.profileId
```

with:

```kotlin
activeHomeProfileSessionSnapshot.profileId
```

Keep Flow-driven collectors on:

```kotlin
activeHomeProfileSession
```

Use `activeHomeProfileSessionSnapshot` only for synchronous compatibility code. Every long-lived profile-owned collector must consume the `activeHomeProfileSession` flow.

- [ ] **Step 6: Update direct `HomeViewModel` test construction**

In `HomeViewModelFocusHydrationTest.buildTestHomeViewModel`, add this argument to the `HomeViewModel(...)` constructor call:

```kotlin
homeProfileSessionCoordinator = HomeProfileSessionCoordinator(
    profileManager = profileManager,
    profileModeRouter = profileModeRouter,
    profileBoundary = profileBoundary,
    localeTags = flowOf("en"),
    playerSettings = flowOf(PlayerSettings()),
    nowMs = { 1L }
),
```

Add imports if missing:

```kotlin
import com.nexio.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.flowOf
```

- [ ] **Step 7: Run test to verify partial pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSessionCoordinator.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt
git commit -m "feat: expose active home profile session flow"
```

## Task 3: Restart Continue Watching Collector on Profile Session Switch

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceObserveProfileSnapshotTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingProfileScopedTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt`

- [ ] **Step 1: Write failing empty-snapshot service test**

Add to `ContinueWatchingSnapshotServiceObserveProfileSnapshotTest`:

```kotlin
@Test
fun `observeProfileSnapshot emits empty snapshot for requested profile before records exist`() = runTest {
    val service = service()

    val result = service.observeProfileSnapshot(profileId = 2).first()

    assertEquals(emptyList<WatchProgress>(), result.resumeItems)
    assertEquals(emptyList<TrackingNextUpEntry>(), result.nextUpItems)
    assertEquals(emptyList<TrackingNextUpEntry>(), result.traktUpNextItems)
}
```

- [ ] **Step 2: Add behavior-level collector restart tests**

Add to `HomeViewModelContinueWatchingProfileScopedTest`:

```kotlin
import com.nexio.tv.core.profile.SecondaryProfileRuntimeContext
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `profile switch restarts continue watching collection`() = runTest {
    val sessions = MutableStateFlow(session(profileId = 1, sessionId = "s1", generation = 1L))
    val snapshots = FakeSnapshotFlows()
    val emissions = mutableListOf<ProfileScopedEmission<ContinueWatchingSnapshot>>()
    val job = launch {
        continueWatchingProfileScopedEmissions(
            activeHomeProfileSession = sessions,
            observeProfileSnapshot = snapshots::observe
        ).collect { emissions += it }
    }
    advanceUntilIdle()

    snapshots.emit(1, ContinueWatchingSnapshot(resumeItems = listOf(progress("profile1"))))
    sessions.value = session(profileId = 2, sessionId = "s2", generation = 2L)
    advanceUntilIdle()
    snapshots.emit(2, ContinueWatchingSnapshot(resumeItems = listOf(progress("profile2"))))
    advanceUntilIdle()

    val successes = emissions.filterIsInstance<ProfileScopedEmission.Success<ContinueWatchingSnapshot>>()
    assertEquals(listOf(1, 2), snapshots.observedProfiles)
    assertTrue(successes.any { it.session.profileId == 1 && it.value.resumeItems.single().contentId == "profile1" })
    assertTrue(successes.any { it.session.profileId == 2 && it.value.resumeItems.single().contentId == "profile2" })

    job.cancel()
}

@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `old profile emissions after switch are ignored by cancelled flow`() = runTest {
    val sessions = MutableStateFlow(session(profileId = 1, sessionId = "s1", generation = 1L))
    val snapshots = FakeSnapshotFlows()
    val emissions = mutableListOf<ProfileScopedEmission<ContinueWatchingSnapshot>>()
    val job = launch {
        continueWatchingProfileScopedEmissions(
            activeHomeProfileSession = sessions,
            observeProfileSnapshot = snapshots::observe
        ).collect { emissions += it }
    }
    advanceUntilIdle()

    sessions.value = session(profileId = 2, sessionId = "s2", generation = 2L)
    advanceUntilIdle()
    snapshots.emit(1, ContinueWatchingSnapshot(resumeItems = listOf(progress("stale-profile1"))))
    snapshots.emit(2, ContinueWatchingSnapshot())
    advanceUntilIdle()

    val successes = emissions.filterIsInstance<ProfileScopedEmission.Success<ContinueWatchingSnapshot>>()
    assertTrue(successes.none { success ->
        success.value.resumeItems.any { it.contentId == "stale-profile1" }
    })
    assertTrue(successes.any { it.session.profileId == 2 && it.value.resumeItems.isEmpty() })

    job.cancel()
}

private class FakeSnapshotFlows {
    val observedProfiles = mutableListOf<Int>()
    private val flows = mutableMapOf<Int, MutableSharedFlow<ContinueWatchingSnapshot>>()

    fun observe(profileId: Int) = flowFor(profileId).asSharedFlow().also {
        observedProfiles += profileId
    }

    suspend fun emit(profileId: Int, snapshot: ContinueWatchingSnapshot) {
        flowFor(profileId).emit(snapshot)
    }

    private fun flowFor(profileId: Int): MutableSharedFlow<ContinueWatchingSnapshot> =
        flows.getOrPut(profileId) { MutableSharedFlow(extraBufferCapacity = 16) }
}

private fun session(profileId: Int, sessionId: String, generation: Long): HomeProfileSession {
    return if (profileId == 1) {
        HomeProfileSession.DefaultLegacy(
            generation = generation,
            sessionId = sessionId,
            language = "en",
            subtitleLanguage = "en",
            startedAtMs = generation
        )
    } else {
        HomeProfileSession.Secondary(
            profileId = profileId,
            generation = generation,
            sessionId = sessionId,
            language = "en",
            subtitleLanguage = "en",
            startedAtMs = generation,
            boundaryContext = SecondaryProfileRuntimeContext(
                profileId = profileId,
                languageTag = "en",
                generation = generation
            )
        )
    }
}

private fun progress(contentId: String): WatchProgress =
    WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 500L,
        duration = 1_000L,
        lastWatched = 1L
    )
```

- [ ] **Step 3: Update source-level collector contract**

In `HomeViewModelContinueWatchingProfileScopedTest`, replace the current Path B assertion block with:

```kotlin
@Test
fun `continue watching subscription restarts with active home profile session`() {
    check(sourceFile.exists()) { "expected source at ${sourceFile.absolutePath}" }
    val source = sourceFile.readText()

    assertTrue(source.contains("activeHomeProfileSession"))
    assertTrue(source.contains("continueWatchingProfileScopedEmissions("))
    assertTrue(source.contains(".distinctUntilChangedBy { it.sessionId }"))
    assertTrue(source.contains(".flatMapLatest { session ->"))
    assertTrue(source.contains("continueWatchingSnapshotService.observeProfileSnapshot(session.profileId)"))
    assertTrue(source.contains("ProfileScopedEmission.Success(session, snapshot)"))
    assertTrue(source.contains("ProfileScopedEmission.Error(session, error)"))
    assertTrue(source.contains("isCurrentHomeSession(emission.session)"))
    assertTrue(source.contains("markContinueWatchingGateResolved"))
    assertFalse(source.contains("observeProfileSnapshot(activeHomeProfileSession.profileId)"))
    assertFalse(source.contains("observeProfileSnapshot(activeHomeProfileSessionSnapshot.profileId)"))
}
```

Add the same assertion to `HomeProfileSessionLifecycleContractTest`:

```kotlin
@Test
fun `continue watching collector is session driven`() {
    val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()

    assertTrue(source.contains("activeHomeProfileSession"))
    assertTrue(source.contains("continueWatchingProfileScopedEmissions("))
    assertTrue(source.contains(".distinctUntilChangedBy { it.sessionId }"))
    assertTrue(source.contains(".flatMapLatest { session ->"))
    assertTrue(source.contains("continueWatchingSnapshotService.observeProfileSnapshot(session.profileId)"))
    assertTrue(source.contains("ProfileScopedEmission.Success(session, snapshot)"))
    assertTrue(source.contains("ProfileScopedEmission.Error(session, error)"))
    assertTrue(source.contains("isCurrentHomeSession(emission.session)"))
    assertFalse(source.contains("observeProfileSnapshot(activeHomeProfileSession.profileId)"))
    assertFalse(source.contains("observeProfileSnapshot(activeHomeProfileSessionSnapshot.profileId)"))
}
```

- [ ] **Step 4: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceObserveProfileSnapshotTest' --tests 'com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest' --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest'
```

Expected: FAIL because `observeProfileSnapshot` filters out the default profile 1 empty snapshot when requesting profile 2, and the collector is still one-shot.

- [ ] **Step 5: Make `observeProfileSnapshot` emit an initial empty snapshot**

Modify `ContinueWatchingSnapshotService.observeProfileSnapshot`:

```kotlin
fun observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot> {
    require(profileId > 0) { "observeProfileSnapshot.profileId must be positive, got $profileId" }
    return observeSnapshot()
        .filter { it.profileId == profileId }
        .map { it.snapshot }
        .onStart { emit(ContinueWatchingSnapshot()) }
}
```

- [ ] **Step 6: Add profile-scoped emission wrapper and pure flow builder**

At the top of `HomeViewModelContinueWatching.kt`, add imports:

```kotlin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.launchIn
```

Add near the top of the file:

```kotlin
internal sealed interface ProfileScopedEmission<out T> {
    val session: HomeProfileSession

    data class Loading(
        override val session: HomeProfileSession
    ) : ProfileScopedEmission<Nothing>

    data class Success<T>(
        override val session: HomeProfileSession,
        val value: T
    ) : ProfileScopedEmission<T>

    data class Error(
        override val session: HomeProfileSession,
        val error: Throwable
    ) : ProfileScopedEmission<Nothing>
}
```

Add the pure flow builder below the sealed interface:

```kotlin
internal fun continueWatchingProfileScopedEmissions(
    activeHomeProfileSession: Flow<HomeProfileSession>,
    observeProfileSnapshot: (Int) -> Flow<ContinueWatchingSnapshot>
): Flow<ProfileScopedEmission<ContinueWatchingSnapshot>> {
    return activeHomeProfileSession
        .distinctUntilChangedBy { it.sessionId }
        .flatMapLatest { session ->
            observeProfileSnapshot(session.profileId)
                .map<ContinueWatchingSnapshot, ProfileScopedEmission<ContinueWatchingSnapshot>> { snapshot ->
                    ProfileScopedEmission.Success(session, snapshot)
                }
                .onStart {
                    emit(ProfileScopedEmission.Loading(session))
                }
                .catch { error ->
                    emit(ProfileScopedEmission.Error(session, error))
                }
        }
}
```

- [ ] **Step 7: Replace `loadContinueWatchingPipeline`**

Replace `loadContinueWatchingPipeline()` with:

```kotlin
internal fun HomeViewModel.loadContinueWatchingPipeline() {
    continueWatchingProfileScopedEmissions(
        activeHomeProfileSession = activeHomeProfileSession,
        observeProfileSnapshot = continueWatchingSnapshotService::observeProfileSnapshot
    )
        .onEach { emission ->
            if (!isCurrentHomeSession(emission.session)) {
                Log.d(
                    HomeViewModel.TAG,
                    "Ignoring stale continue watching emission source=continue_watching"
                )
                return@onEach
            }

            when (emission) {
                is ProfileScopedEmission.Loading -> {
                    markContinueWatchingGateLoading(emission.session)
                }
                is ProfileScopedEmission.Success -> {
                    applyContinueWatchingSnapshotForSession(
                        session = emission.session,
                        snapshot = emission.value
                    )
                    markContinueWatchingGateResolved(
                        session = emission.session,
                        reason = if (
                            emission.value.resumeItems.isEmpty() &&
                            emission.value.nextUpItems.isEmpty() &&
                            emission.value.traktUpNextItems.isEmpty()
                        ) {
                            "first_snapshot_empty"
                        } else {
                            "first_snapshot"
                        }
                    )
                }
                is ProfileScopedEmission.Error -> {
                    Log.w(HomeViewModel.TAG, "Continue watching snapshot failed: ${emission.error.message}")
                    clearContinueWatchingForSession(emission.session)
                    markContinueWatchingGateFailedNonBlocking(
                        session = emission.session,
                        reason = emission.error::class.java.simpleName.ifBlank { "error" }
                    )
                }
            }
        }
        .launchIn(viewModelScope)
}
```

- [ ] **Step 8: Extract Continue Watching apply helpers**

Add these helpers below `loadContinueWatchingPipeline()`:

```kotlin
private fun HomeViewModel.markContinueWatchingGateLoading(session: HomeProfileSession) {
    if (!isCurrentHomeSession(session)) return
    _uiState.update { state ->
        state.copy(
            homeReadiness = state.homeReadiness
                .takeIf { it.sessionId == session.sessionId }
                ?.markLoading(HomeInitialGate.CONTINUE_WATCHING)
                ?: HomeInitialReadiness.started(session.sessionId, session.profileId)
                    .markLoading(HomeInitialGate.CONTINUE_WATCHING)
        )
    }
}

private fun HomeViewModel.markContinueWatchingGateResolved(
    session: HomeProfileSession,
    reason: String
) {
    if (!isCurrentHomeSession(session)) return
    _uiState.update { state ->
        state.copy(
            homeReadiness = state.homeReadiness.markResolved(
                HomeInitialGate.CONTINUE_WATCHING,
                reason
            )
        )
    }
}

private fun HomeViewModel.markContinueWatchingGateFailedNonBlocking(
    session: HomeProfileSession,
    reason: String
) {
    if (!isCurrentHomeSession(session)) return
    _uiState.update { state ->
        state.copy(
            homeReadiness = state.homeReadiness.markFailedNonBlocking(
                HomeInitialGate.CONTINUE_WATCHING,
                reason
            )
        )
    }
}

private fun HomeViewModel.clearContinueWatchingForSession(session: HomeProfileSession) {
    if (!isCurrentHomeSession(session)) return
    _uiState.update { state ->
        state.copy(
            continueWatchingItems = emptyList(),
            traktUpNextItems = emptyList()
        )
    }
}
```

Move the existing body of the `collectLatest { snapshot -> ... }` block into:

```kotlin
private suspend fun HomeViewModel.applyContinueWatchingSnapshotForSession(
    session: HomeProfileSession,
    snapshot: ContinueWatchingSnapshot
) {
    val capturedGeneration = session.generation
    val timeline = buildMixedContinueWatchingTimeline(
        resumeItems = snapshot.resumeItems,
        nextUpItems = snapshot.nextUpItems,
        resumeRef = ::resumeRefForContinueWatching,
        nextUpRef = ::nextUpRefForContinueWatching
    )
    val nowMs = System.currentTimeMillis()
    val rawItems = timeline.map { row ->
        when (row) {
            is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
            is ContinueWatchingTimelineRow.NextUp -> row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
        }
    }.filter { item ->
        item !is ContinueWatchingItem.NextUp || item.info.hasAired
    }
    val projectedKeys = try {
        resolveProjectedContinueWatchingIdentityKeys(rawItems, animeSeasonProjectionResolver)
    } catch (_: Exception) {
        emptyMap<Int, String>()
    }
    val items = dedupContinueWatchingByProjectedIdentity(rawItems) { item ->
        val idx = rawItems.indexOfFirst { it === item }
        projectedKeys[idx] ?: item.contentId()
    }
    val traktUpNextItems = snapshot.traktUpNextItems.map { entry ->
        entry.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
    }.filter { it.info.hasAired }

    if (!isCurrentHomeSession(session) || !isCurrentHomeProfileGeneration(capturedGeneration)) {
        Log.d(
            HomeViewModel.TAG,
            "Skipping stale continue watching publish generation=$capturedGeneration"
        )
        return
    }

    _uiState.update { state ->
        if (
            state.continueWatchingItems == items &&
            state.traktUpNextItems == traktUpNextItems
        ) {
            state
        } else {
            state.copy(
                continueWatchingItems = items,
                traktUpNextItems = traktUpNextItems
            )
        }
    }

    val settings = currentTmdbSettings
    if (
        shouldEnrichContinueWatchingProviderMetadata(items, traktUpNextItems, settings) &&
        isNonPlaybackHomeWorkAllowed()
    ) {
        continueWatchingEnrichmentJob?.cancel()
        continueWatchingEnrichmentJob = viewModelScope.launch {
            try {
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedItems = enrichContinueWatchingItems(items, settings)
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedTraktItems = enrichContinueWatchingNextUpItems(traktUpNextItems, settings)
                if (!isCurrentHomeSession(session)) {
                    Log.d(
                        HomeViewModel.TAG,
                        "Skipping stale continue watching enrichment source=continue_watching"
                    )
                    return@launch
                }
                _uiState.update { state ->
                    if (
                        state.continueWatchingItems == enrichedItems &&
                        state.traktUpNextItems == enrichedTraktItems
                    ) {
                        state
                    } else {
                        state.copy(
                            continueWatchingItems = enrichedItems,
                            traktUpNextItems = enrichedTraktItems
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(HomeViewModel.TAG, "Continue watching metadata enrichment failed: ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 9: Run tests to verify pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceObserveProfileSnapshotTest' --tests 'com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest' --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest'
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceObserveProfileSnapshotTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingProfileScopedTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt
git commit -m "fix: restart continue watching on home profile switch"
```

## Task 4: Reset Readiness by Session and Make Continue Watching Non-Blocking for Rendered Home

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt`

- [ ] **Step 1: Write source contract update**

In `ProfileSettingsScopeContractTest`, replace assertions for the old boolean:

```kotlin
assertTrue(homeStateSource.contains("val initialContinueWatchingResolved: Boolean = false"))
assertTrue(homeContinueWatchingSource.contains("initialContinueWatchingResolved = true"))
assertTrue(homeViewModelSource.contains("initialContinueWatchingResolved = false"))
assertTrue(homeScreenSource.contains("uiState.initialContinueWatchingResolved"))
```

with:

```kotlin
assertTrue(homeStateSource.contains("val homeReadiness: HomeInitialReadiness"))
assertTrue(homeContinueWatchingSource.contains("markContinueWatchingGateResolved"))
assertTrue(homeViewModelSource.contains("HomeInitialReadiness.started("))
assertTrue(homeScreenSource.contains("shouldShowFullHomeLoadingGate("))
assertFalse(homeScreenSource.contains("uiState.initialContinueWatchingResolved"))
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.sync.ProfileSettingsScopeContractTest' --tests 'com.nexio.tv.ui.screens.home.HomeScreenRenderabilityTest'
```

Expected: FAIL because HomeScreen still reads the old `initialContinueWatchingResolved` field.

- [ ] **Step 3: Reset readiness when profile-scoped Home state resets**

In `HomeViewModelCatalogPipeline.kt`, replace:

```kotlin
initialContinueWatchingResolved = false,
```

with:

```kotlin
homeReadiness = HomeInitialReadiness.started(
    sessionId = activeHomeProfileSessionSnapshot.sessionId,
    profileId = activeHomeProfileSessionSnapshot.profileId
),
```

- [ ] **Step 4: Update HomeScreen full-screen gate**

In `HomeScreen.kt`, replace the top full-screen loading branch:

```kotlin
uiState.isLoading && !hasRenderableContent && !startupContentGateTimedOut -> {
```

with:

```kotlin
shouldShowFullHomeLoadingGate(uiState, startupContentGateTimedOut) -> {
```

Inside the `else` branch, replace:

```kotlin
val shouldWaitForContinueWatching =
    uiState.homeLayout == HomeLayout.MODERN &&
        !uiState.initialContinueWatchingResolved &&
        uiState.error == null &&
        uiState.installedAddonsCount > 0
val shouldShowLoadingGate = (!hasRenderableContent || shouldWaitForContinueWatching) &&
    uiState.error == null &&
    !startupContentGateTimedOut
```

with:

```kotlin
val shouldShowLoadingGate = shouldShowFullHomeLoadingGate(
    uiState = uiState,
    startupContentGateTimedOut = startupContentGateTimedOut
)
```

This preserves full-screen loading only while there is no renderable Home content.

- [ ] **Step 5: Run tests to verify pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.sync.ProfileSettingsScopeContractTest' --tests 'com.nexio.tv.ui.screens.home.HomeScreenRenderabilityTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeScreenRenderabilityTest.kt
git commit -m "fix: make home readiness session scoped"
```

## Task 5: Add Home Profile Lifecycle Trace Events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotTraceTest.kt`

- [ ] **Step 1: Write trace source assertions**

Add to `ContinueWatchingSnapshotTraceTest`:

```kotlin
@Test
fun `home profile lifecycle trace methods are available`() {
    val source = java.io.File("app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt").readText()
    val sinkSource = java.io.File("app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt").readText()

    assertTrue(source.contains("emitHomeProfileSessionStarted"))
    assertTrue(source.contains("emitHomeProfileSessionCancelled"))
    assertTrue(source.contains("emitHomeProfileEmissionIgnoredStale"))
    assertTrue(source.contains("emitHomeInitialGateStateChanged"))
    assertTrue(source.contains("TraceHash.of"))
    assertTrue(source.contains("\"profileHash\""))
    assertTrue(source.contains("\"sessionHash\""))
    assertFalse(source.contains("\"profileId\" to profileId"))
    assertFalse(source.contains("\"sessionId\" to sessionId"))
    assertTrue(sinkSource.contains("\"home.profile_session_started\""))
    assertTrue(sinkSource.contains("\"home.profile_emission_ignored_stale\""))
    assertTrue(sinkSource.contains("\"home.initial_gate_state_changed\""))
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotTraceTest'
```

Expected: FAIL because the trace methods and curated fields do not exist.

- [ ] **Step 3: Add trace methods**

In `TraceMetadataEvents.kt`, add:

```kotlin
import com.nexio.tv.core.trace.TraceHash

fun emitHomeProfileSessionStarted(
    profileId: Int,
    sessionId: String,
    generation: Long
) {
    emitTraceEvent(
        eventType = "home.profile_session_started",
        payload = mapOf(
            "profileHash" to homeProfileHash(profileId),
            "sessionHash" to homeSessionHash(sessionId),
            "generation" to generation
        )
    )
}

fun emitHomeProfileSessionCancelled(
    profileId: Int,
    sessionId: String,
    reason: String
) {
    emitTraceEvent(
        eventType = "home.profile_session_cancelled",
        payload = mapOf(
            "profileHash" to homeProfileHash(profileId),
            "sessionHash" to homeSessionHash(sessionId),
            "reason" to reason
        )
    )
}

fun emitHomeProfileEmissionIgnoredStale(
    source: String,
    profileId: Int,
    sessionId: String
) {
    emitTraceEvent(
        eventType = "home.profile_emission_ignored_stale",
        payload = mapOf(
            "source" to source,
            "profileHash" to homeProfileHash(profileId),
            "sessionHash" to homeSessionHash(sessionId)
        )
    )
}

fun emitHomeInitialGateStateChanged(
    profileId: Int,
    sessionId: String,
    gate: String,
    state: String,
    reason: String
) {
    emitTraceEvent(
        eventType = "home.initial_gate_state_changed",
        payload = mapOf(
            "profileHash" to homeProfileHash(profileId),
            "sessionHash" to homeSessionHash(sessionId),
            "gate" to gate,
            "state" to state,
            "reason" to reason
        )
    )
}

private fun homeProfileHash(profileId: Int): String {
    return TraceHash.of(traceSessionIdForEmission() ?: "logcat", profileId.toString())
}

private fun homeSessionHash(sessionId: String): String {
    return TraceHash.of(traceSessionIdForEmission() ?: "logcat", sessionId)
}
```

- [ ] **Step 4: Add logcat curated fields**

In `LogcatRuntimeTraceSink.kt`, add cases to `curatedFields`:

```kotlin
"home.profile_session_started" -> linkedMapOf(
    "profile" to payload["profileHash"],
    "session" to payload["sessionHash"],
    "generation" to payload["generation"]
)
"home.profile_session_cancelled" -> linkedMapOf(
    "profile" to payload["profileHash"],
    "session" to payload["sessionHash"],
    "reason" to payload["reason"]
)
"home.profile_emission_ignored_stale" -> linkedMapOf(
    "source" to payload["source"],
    "profile" to payload["profileHash"],
    "session" to payload["sessionHash"]
)
"home.initial_gate_state_changed" -> linkedMapOf(
    "profile" to payload["profileHash"],
    "session" to payload["sessionHash"],
    "gate" to payload["gate"],
    "state" to payload["state"],
    "reason" to payload["reason"]
)
```

- [ ] **Step 5: Emit gate and stale-emission traces from Continue Watching**

In `HomeViewModelContinueWatching.kt`, add this call at the end of `markContinueWatchingGateLoading`:

```kotlin
traceEvents.emitHomeInitialGateStateChanged(
    profileId = session.profileId,
    sessionId = session.sessionId,
    gate = HomeInitialGate.CONTINUE_WATCHING.name,
    state = "LOADING",
    reason = "collector_started"
)
```

Add this call at the end of `markContinueWatchingGateResolved`:

```kotlin
traceEvents.emitHomeInitialGateStateChanged(
    profileId = session.profileId,
    sessionId = session.sessionId,
    gate = HomeInitialGate.CONTINUE_WATCHING.name,
    state = "RESOLVED",
    reason = reason
)
```

Add this call at the end of `markContinueWatchingGateFailedNonBlocking`:

```kotlin
traceEvents.emitHomeInitialGateStateChanged(
    profileId = session.profileId,
    sessionId = session.sessionId,
    gate = HomeInitialGate.CONTINUE_WATCHING.name,
    state = "FAILED_NON_BLOCKING",
    reason = reason
)
```

Replace the stale-emission `Log.d` calls added in Task 3 with:

```kotlin
traceEvents.emitHomeProfileEmissionIgnoredStale(
    source = "continue_watching",
    profileId = emission.session.profileId,
    sessionId = emission.session.sessionId
)
```

Use source values `"continue_watching_apply"` and `"continue_watching_enrichment"` in the two stale apply/enrichment branches.

- [ ] **Step 6: Emit cancellation before session snapshot replacement**

In the `activeHomeProfileSession.collectLatest { session -> ... }` block, emit cancellation before replacing `activeHomeProfileSessionSnapshot`:

```kotlin
val previous = activeHomeProfileSessionSnapshot
if (previous.sessionId != session.sessionId) {
    traceEvents.emitHomeProfileSessionCancelled(
        profileId = previous.profileId,
        sessionId = previous.sessionId,
        reason = "home_session:${session.profileId}"
    )
}
```

Then assign `activeHomeProfileSessionSnapshot = session` and emit:

```kotlin
traceEvents.emitHomeProfileSessionStarted(
    profileId = session.profileId,
    sessionId = session.sessionId,
    generation = session.generation
)
```

- [ ] **Step 7: Run tests to verify pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotTraceTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotTraceTest.kt
git commit -m "chore: trace home profile session lifecycle"
```

## Task 6: Verify Shared Cache Reuse Boundaries

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt`

- [ ] **Step 1: Add source-level cache boundary assertions**

Add to `HomeProfileSessionLifecycleContractTest`:

```kotlin
@Test
fun `profile switch reset only clears profile-owned home state`() {
    assertTrue(catalogPipelineSource.contains("continueWatchingItems = emptyList()"))
    assertTrue(catalogPipelineSource.contains("traktUpNextItems = emptyList()"))
    assertTrue(catalogPipelineSource.contains("catalogRows = emptyList()"))
    assertFalse(catalogPipelineSource.contains("metadataDiskCacheStore.clear"))
    assertFalse(catalogPipelineSource.contains("metadataDiskCacheStore.delete"))
    assertFalse(catalogPipelineSource.contains("artworkDecisionStore.clear"))
    assertFalse(catalogPipelineSource.contains("identityMappingStore.clear"))
    assertFalse(catalogPipelineSource.contains("integrationOwnershipService.clearAll"))
}

@Test
fun `installed addon observer remains shared and not session restarted`() {
    val source = java.io.File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt").readText()

    assertTrue(source.contains("observeInstalledAddonsPipeline"))
    assertFalse(source.contains("activeHomeProfileSession.flatMapLatest { session ->\\n        addonRepository.getInstalledAddons()"))
}
```

- [ ] **Step 2: Add cache reuse guard around focus hydration state**

In `HomeViewModelFocusHydrationTest`, add:

```kotlin
@Test
fun `profile switch reset does not clear focused metadata hydration cache`() {
    val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt").readText()

    assertFalse(source.contains("focusedItemHydrationStates.clear()"))
    assertFalse(source.contains("trailerMetadataAvailableState.clear()"))
    assertFalse(source.contains("trailerPreviewNegativeCache.clear()"))
}
```

- [ ] **Step 3: Run tests to verify behavior**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest' --tests 'com.nexio.tv.ui.screens.home.HomeViewModelFocusHydrationTest'
```

Expected: PASS after Task 2 and Task 3 are complete.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeProfileSessionLifecycleContractTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt
git commit -m "test: guard shared cache reuse on profile switch"
```

## Task 7: Full Verification and Manual Device Reproduction

**Files:**
- No new files.
- Verify changed files from Tasks 1-6.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.HomeScreenRenderabilityTest' --tests 'com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest' --tests 'com.nexio.tv.ui.screens.home.HomeProfileSessionLifecycleContractTest' --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceObserveProfileSnapshotTest' --tests 'com.nexio.tv.data.repository.ContinueWatchingSnapshotTraceTest' --tests 'com.nexio.tv.sync.ProfileSettingsScopeContractTest'
```

Expected: PASS.

- [ ] **Step 2: Run broader Home unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.home.*'
```

Expected: PASS.

- [ ] **Step 3: Run profile and CW boundary tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.*ContinueWatching*' --tests 'com.nexio.tv.core.profile.*Profile*'
```

Expected: PASS.

- [ ] **Step 4: Build debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS and APK produced under `app/build/outputs/apk/debug/`.

- [ ] **Step 5: Manual rooted device reproduction on `192.168.50.98`**

Run:

```bash
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.50.98:5555 logcat -v threadtime -b main -b system -b crash -b events > /tmp/nexio_home_profile_switch_after_fix.log
```

Switch from profile 1 to profile 2.

Expected log sequence:

```text
home.profile_session_started profile=<hash> session=<hash>
home.initial_gate_state_changed gate=CONTINUE_WATCHING state=LOADING
Home catalog loaded ... pending=0
Persisted snapshot applied ...
home.initial_gate_state_changed gate=CONTINUE_WATCHING state=RESOLVED
```

Expected UI:

```text
Home rows are visible after profile switch.
The full-screen spinner does not remain once catalog/snapshot rows exist.
Continue Watching may be empty or absent for profile 2 without blocking Home.
```

- [ ] **Step 6: Check no crash loop**

Run:

```bash
adb -s 192.168.50.98:5555 shell pidof com.nexio.tv
rg -n "FATAL EXCEPTION|AndroidRuntime|am_crash|ANR|home.profile_emission_ignored_stale|home.initial_gate_state_changed|runtime.cache_decision|http.request" /tmp/nexio_home_profile_switch_after_fix.log
```

Expected:

```text
pidof returns one PID.
No FATAL EXCEPTION, AndroidRuntime crash, am_crash, or ANR lines for com.nexio.tv.
Gate events show Continue Watching resolves for profile 2.
runtime.cache_decision HIT lines may appear.
http.request lines must not spike solely because profile switched when cached data is fresh.
```

- [ ] **Step 7: Commit verification notes if the repo tracks review dossiers**

If a review dossier already exists for this incident, append a short dated note. If no dossier exists, skip this step and keep the evidence in the PR description.

Commit only if a file changed:

```bash
git add review-dossier
git commit -m "docs: record home profile switch verification"
```

## Acceptance Criteria

This packet is complete when:

```text
1. Continue Watching collection restarts on activeHomeProfileSession changes.
2. Old profile emissions are ignored after a session switch.
3. Profile 2 receives an empty or real Continue Watching snapshot and resolves the CW gate.
4. Home rows render even if CW is pending, empty, or failed.
5. Empty Home with catalog loading still shows the correct loading state.
6. HomeProfileSession language comes from the profile-aware locale boundary.
7. HomeProfileSession subtitleLanguage comes from profile-scoped player settings.
8. Shared metadata/artwork/runtime caches are not cleared on profile switch.
9. Addons and IntegrationRuntime remain shared and are not re-owned per profile.
10. Trace events use profileHash/sessionHash, not raw profileId/sessionId payloads.
```

## Self-Review

**Spec coverage:** The plan covers the immediate stuck spinner by restarting Continue Watching on session switch, resolving empty/error CW, and removing CW as a full-screen blocker once Home rows exist. It covers the broader rule by requiring session-driven profile-owned collectors and adding tests that shared metadata/artwork/runtime cache owners are not cleared on profile switch. The amended plan also makes Home sessions consume profile-scoped language/subtitle settings, adds behavior-level CW lifecycle tests, and requires hashed profile/session trace identifiers. Trakt/Simkl scrobble ownership is acknowledged as profile/account-scoped but not reimplemented here because it belongs to existing profile-boundary work.

**Placeholder scan:** The plan intentionally avoids open-ended implementation steps. Each task includes exact file paths, concrete code snippets, commands, and expected outcomes.

**Type consistency:** `HomeProfileSession`, `HomeInitialReadiness`, `HomeInitialGate`, `GateStatus`, `activeHomeProfileSession`, `activeHomeProfileSessionSnapshot`, and `ProfileScopedEmission` are introduced before use in later tasks.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-07-home-profile-session-lifecycle.md`. Two execution options:

**1. Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints
