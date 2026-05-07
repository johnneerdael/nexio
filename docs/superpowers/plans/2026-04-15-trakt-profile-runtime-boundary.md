# Trakt Profile Runtime Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Trakt Continue Watching, Up Next, progress reads, scrobbles, history mutations, playback deletes, and outbox drains 100% bound to the originating profile while preserving profile 1's legacy Trakt behavior.

**Architecture:** Keep profile 1 on the existing legacy Trakt storage route. For profiles 2-4, every Trakt runtime operation must capture a `TrackingRuntimeSession`/`TrackingAuthSession` once and use that profile id for state, cache, auth, and outbox execution. Trakt runtime memory becomes profile-keyed, Continue Watching emissions become owner-tagged, and provider dispatch refuses Trakt paths unless the active profile has Trakt auth.

**Tech Stack:** Android Kotlin, Hilt, coroutines/Flow, DataStore/SharedPreferences, MockK/JUnit, existing Trakt outbox and profile boundary services.

---

## File Structure

- `app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt`
  - Extend the existing captured auth route value with reusable runtime helpers. Profile 1 remains `profileId = 1`; profiles 2-4 use their own profile id.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingRuntimeSession.kt`
  - New value object for runtime reads/writes: provider, profile id, and generation.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt`
  - Expose provider auth as an explicit route decision. Prevent fallback to Trakt when neither Trakt nor SIMKL is authenticated.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt`
  - Add session-aware APIs and keep compatibility wrappers for current active profile callers during migration.
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
  - Move singleton Trakt runtime fields into `TraktProgressRuntimeState` keyed by profile id.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  - Tag in-memory snapshots with profile id and publish only the active owner. Always clear inactive/no-auth owner state.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
  - Reject Continue Watching snapshots that do not match the active `HomeProfileSession`.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt`
  - Refuse Trakt scrobble/check-in if the active profile is not Trakt-authenticated.
- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
  - Add public session-aware request methods used by outbox adapters.
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`
  - Add `profileId` to persisted mutation envelopes.
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`
  - Read/write `profileId`, defaulting old persisted envelopes to profile 1 for seamless upgrade.
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt`
  - Include profile id in collapse semantics so profile 1 and profile 2 mutations cannot supersede each other.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt`
  - Execute writes with the envelope's profile id.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt`
  - Execute writes with the envelope's profile id.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt`
  - Add session-aware write methods.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt`
  - Add session-aware write methods.
- `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
  - Add source-level invariants so future changes cannot reintroduce global Trakt runtime ownership.
- `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingProgressServiceTest.kt`
  - Add provider auth gating tests.
- `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingScrobbleServiceTest.kt`
  - Add no-auth Trakt refusal tests.
- `app/src/test/java/com/nexio/tv/data/repository/TraktProgressRuntimeStateTest.kt`
  - New unit tests for profile-keyed Trakt runtime state.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`
  - New unit tests proving profile 1 and secondary Continue Watching snapshots cannot leak.
- `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`
  - Add profile id persistence/backfill tests.
- `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicyTest.kt`
  - Add collapse isolation tests.
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt`
  - Add adapter execution profile-id tests.

---

## Task 1: Add TrackingRuntimeSession Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/TrackingRuntimeSession.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write the failing contract test**

Add this test to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `tracking runtime session exists and carries profile owner`() {
    val sessionFile = File("app/src/main/java/com/nexio/tv/data/repository/TrackingRuntimeSession.kt")
    val authSessionSource = trackingAuthSession.readText()
    val runtimeSessionSource = sessionFile.readText()

    assertTrue(authSessionSource.contains("fun toRuntimeSession"))
    assertTrue(runtimeSessionSource.contains("data class TrackingRuntimeSession"))
    assertTrue(runtimeSessionSource.contains("val provider: TrackingProvider"))
    assertTrue(runtimeSessionSource.contains("val profileId: Int"))
    assertTrue(runtimeSessionSource.contains("val generation: Long"))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because `TrackingRuntimeSession.kt` and `toRuntimeSession` do not exist.

- [ ] **Step 3: Create the runtime session type**

Create `TrackingRuntimeSession.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingRuntimeSession(
    val provider: TrackingProvider,
    val profileId: Int,
    val generation: Long = 0L
) {
    init {
        require(profileId in 1..4) {
            "Tracking runtime profile id must be 1-4; was $profileId"
        }
    }

    val authSession: TrackingAuthSession
        get() = TrackingAuthSession(provider = provider, profileId = profileId)
}
```

- [ ] **Step 4: Add conversion helper to TrackingAuthSession**

Update `TrackingAuthSession.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingAuthSession(
    val provider: TrackingProvider,
    val profileId: Int
) {
    fun toRuntimeSession(generation: Long = 0L): TrackingRuntimeSession {
        return TrackingRuntimeSession(
            provider = provider,
            profileId = profileId,
            generation = generation
        )
    }
}
```

- [ ] **Step 5: Run the contract test**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS for the new contract test.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt app/src/main/java/com/nexio/tv/data/repository/TrackingRuntimeSession.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "refactor(profile): add tracking runtime session contract"
```

---

## Task 2: Stop Provider Fallback From Entering Trakt When Profile Has No Trakt Auth

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingProgressServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write failing progress-routing tests**

Add this helper and tests to `DefaultTrackingProgressServiceTest.kt`:

```kotlin
private fun unauthenticatedProviderStateService(
    storedProvider: TrackingProvider = TrackingProvider.TRAKT
) = mockk<TrackingProviderStateService> {
    val state = EffectiveTrackingProviderState(
        storedProvider = storedProvider,
        effectiveProvider = storedProvider,
        traktAuthenticated = false,
        simklAuthenticated = false
    )
    every { this@mockk.state } returns flowOf(state)
    coEvery { currentState() } returns state
}

@Test
fun `unauthenticated profile does not observe Trakt progress`() = runTest {
    val traktService = mockk<TraktProgressService>(relaxed = true)
    val simklService = mockk<SimklProgressService>(relaxed = true)
    val service = DefaultTrackingProgressService(
        traktProgressService = traktService,
        simklProgressService = simklService,
        trackingProviderStateService = unauthenticatedProviderStateService()
    )

    val observed = service.observeAllProgress().firstValue()

    assertEquals(emptyList<WatchProgress>(), observed)
    coVerify(exactly = 0) { traktService.refreshNow() }
}

@Test
fun `unauthenticated profile reports remote snapshot unloaded without calling Trakt`() = runTest {
    val traktService = mockk<TraktProgressService>()
    val simklService = mockk<SimklProgressService>()
    val service = DefaultTrackingProgressService(
        traktProgressService = traktService,
        simklProgressService = simklService,
        trackingProviderStateService = unauthenticatedProviderStateService()
    )

    val observed = service.observeRemoteSnapshotLoaded().firstValue()

    assertEquals(false, observed)
}
```

- [ ] **Step 2: Write source contract for explicit auth gating**

Add this test to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `tracking provider state exposes unauthenticated runtime state explicitly`() {
    val source = trackingProviderStateService.readText()

    assertTrue(source.contains("val hasAuthenticatedProvider: Boolean"))
    assertTrue(source.contains("val canReadEffectiveProvider: Boolean"))
    assertTrue(source.contains("effectiveProvider = if (authState.hasAnyAuthenticatedProvider)"))
    assertTrue(!source.contains("else -> settings.trackingProvider"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.DefaultTrackingProgressServiceTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because `DefaultTrackingProgressService` currently routes by `effectiveProvider` even when no provider is authenticated, and the source contract is not present.

- [ ] **Step 4: Add explicit auth flags to provider state**

Update `EffectiveTrackingProviderState` in `TrackingProviderStateService.kt`:

```kotlin
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
```

Update the `combine` block:

```kotlin
val effectiveProvider = if (authState.hasAnyAuthenticatedProvider) {
    when {
        authState.traktAuthenticated && !authState.simklAuthenticated -> TrackingProvider.TRAKT
        authState.simklAuthenticated && !authState.traktAuthenticated -> TrackingProvider.SIMKL
        else -> settings.trackingProvider
    }
} else {
    settings.trackingProvider
}
```

Update the private `TrackingAuthState`:

```kotlin
private data class TrackingAuthState(
    val traktAuthenticated: Boolean = false,
    val simklAuthenticated: Boolean = false
) {
    val hasAnyAuthenticatedProvider: Boolean
        get() = traktAuthenticated || simklAuthenticated
}
```

- [ ] **Step 5: Gate `DefaultTrackingProgressService` readers and writes**

In `TrackingProgressService.kt`, update each `trackingProviderStateService.state.flatMapLatest` reader to return empty/inert flows when `!state.canReadEffectiveProvider`.

Use this exact pattern for list readers:

```kotlin
override fun observeAllProgress(): Flow<List<WatchProgress>> =
    trackingProviderStateService.state.flatMapLatest { state ->
        if (!state.canReadEffectiveProvider) {
            return@flatMapLatest flowOf(emptyList())
        }
        when (state.effectiveProvider) {
            TrackingProvider.SIMKL -> simklProgressService.observeAllProgress()
            TrackingProvider.TRAKT -> traktProgressService.observeAllProgress()
        }
    }
```

Use this exact pattern for loaded state:

```kotlin
override fun observeRemoteSnapshotLoaded(): Flow<Boolean> =
    trackingProviderStateService.state.flatMapLatest { state ->
        if (!state.canReadEffectiveProvider) {
            return@flatMapLatest flowOf(false)
        }
        when (state.effectiveProvider) {
            TrackingProvider.SIMKL -> simklProgressService.observeRemoteSnapshotLoaded()
            TrackingProvider.TRAKT -> traktProgressService.observeRemoteSnapshotLoaded()
        }
    }
```

Apply the same `if (!state.canReadEffectiveProvider)` guard to `observeContinueWatchingNextUp`, `observeSyntheticContinueWatchingNextUp`, `observeEpisodeProgress`, and `observeMovieWatched`.

Update suspend methods:

```kotlin
override suspend fun refreshNow() {
    val state = trackingProviderStateService.currentState()
    if (!state.canReadEffectiveProvider) return
    when (state.effectiveProvider) {
        TrackingProvider.SIMKL -> simklProgressService.refreshNow()
        TrackingProvider.TRAKT -> traktProgressService.refreshNow()
    }
}
```

Apply the same early return/empty result to `resolvePlaybackDeleteIdsForOutbox`, `rollbackQueuedHistoryRemove`, and `rollbackQueuedPlaybackDelete`.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.DefaultTrackingProgressServiceTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingProgressServiceTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): gate tracking progress by provider auth"
```

---

## Task 3: Move Trakt Progress Runtime State Behind Profile-Keyed State

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/TraktProgressRuntimeStateTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write runtime state unit tests**

Create `TraktProgressRuntimeStateTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressRuntimeStateTest {

    @Test
    fun `profile states are independent`() {
        val registry = TraktProgressRuntimeRegistry()
        val profileOne = registry.stateFor(TrackingRuntimeSession(TrackingProvider.TRAKT, profileId = 1))
        val profileTwo = registry.stateFor(TrackingRuntimeSession(TrackingProvider.TRAKT, profileId = 2))
        val progress = WatchProgress(
            contentId = "tt-profile-one",
            contentType = "movie",
            name = "Profile One Movie",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt-profile-one",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            lastWatched = 10L,
            progressPercent = 10f
        )

        profileOne.remoteProgress.value = listOf(progress)

        assertEquals(listOf(progress), profileOne.remoteProgress.value)
        assertEquals(emptyList<WatchProgress>(), profileTwo.remoteProgress.value)
    }

    @Test
    fun `clearing one profile does not clear another profile`() {
        val registry = TraktProgressRuntimeRegistry()
        val profileOne = registry.stateFor(TrackingRuntimeSession(TrackingProvider.TRAKT, profileId = 1))
        val profileTwo = registry.stateFor(TrackingRuntimeSession(TrackingProvider.TRAKT, profileId = 2))
        profileOne.hasLoadedRemoteProgress.value = true
        profileTwo.hasLoadedRemoteProgress.value = true

        registry.clearProfile(2)

        assertTrue(profileOne.hasLoadedRemoteProgress.value)
        assertEquals(false, profileTwo.hasLoadedRemoteProgress.value)
    }
}
```

- [ ] **Step 2: Write source contract**

Add this test to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `trakt progress runtime state is profile keyed`() {
    val source = File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

    assertTrue(source.contains("class TraktProgressRuntimeRegistry"))
    assertTrue(source.contains("private val states = mutableMapOf<Int, TraktProgressRuntimeState>()"))
    assertTrue(source.contains("fun stateFor(session: TrackingRuntimeSession): TraktProgressRuntimeState"))
    assertTrue(source.contains("fun clearProfile(profileId: Int)"))
    assertTrue(source.contains("private fun runtimeState(): TraktProgressRuntimeState"))
    assertTrue(!source.contains("private val remoteProgress = MutableStateFlow<List<WatchProgress>>(emptyList())"))
    assertTrue(!source.contains("private val myShowsNextUp = MutableStateFlow<List<NextUpEntry>>(emptyList())"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.TraktProgressRuntimeStateTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because runtime state classes do not exist and `TraktProgressService` owns global flows.

- [ ] **Step 4: Add runtime state classes to TraktProgressService.kt**

Near the top of `TraktProgressService.kt`, after `data class TraktCachedStats`, add:

```kotlin
internal data class TraktProgressRuntimeState(
    val remoteProgress: MutableStateFlow<List<WatchProgress>> = MutableStateFlow(emptyList()),
    val myShowsNextUp: MutableStateFlow<List<TraktProgressService.NextUpEntry>> = MutableStateFlow(emptyList()),
    val myShowsNextUpAll: MutableStateFlow<List<TraktProgressService.NextUpEntry>> = MutableStateFlow(emptyList()),
    val optimisticProgress: MutableStateFlow<Map<String, TraktProgressService.OptimisticProgressEntry>> = MutableStateFlow(emptyMap()),
    val metadataState: MutableStateFlow<Map<String, TraktProgressService.ContentMetadata>> = MutableStateFlow(emptyMap()),
    val watchedMoviesState: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    val watchedShowsState: MutableStateFlow<Map<String, TraktProgressService.WatchedShowIndexEntry>> = MutableStateFlow(emptyMap()),
    val hiddenProgressState: MutableStateFlow<TraktProgressService.HiddenProgressSnapshot> = MutableStateFlow(TraktProgressService.HiddenProgressSnapshot()),
    val episodeProgressState: MutableStateFlow<Map<String, TraktProgressService.EpisodeProgressCacheEntry>> = MutableStateFlow(emptyMap()),
    val showNextUpState: MutableStateFlow<Map<String, TraktProgressService.ShowNextUpCacheEntry>> = MutableStateFlow(emptyMap()),
    val nextUpValidationCache: MutableMap<String, TraktProgressService.CachedNextUpValidation> = mutableMapOf(),
    val nextUpValidationBypassKeys: MutableSet<String> = mutableSetOf(),
    val inFlightMetadataKeys: MutableSet<String> = mutableSetOf(),
    val inFlightEpisodeProgressKeys: MutableSet<String> = mutableSetOf(),
    val inFlightShowNextUpKeys: MutableSet<String> = mutableSetOf(),
    val episodeProgressLastAttemptAtMs: MutableMap<String, Long> = mutableMapOf(),
    val showNextUpLastAttemptAtMs: MutableMap<String, Long> = mutableMapOf(),
    var cachedMoviesPlayback: TraktProgressService.TimedCache<List<TraktPlaybackItemDto>>? = null,
    var cachedEpisodesPlayback: TraktProgressService.TimedCache<List<TraktPlaybackItemDto>>? = null,
    var cachedUserStats: TraktProgressService.TimedCache<TraktProgressService.TraktCachedStats>? = null,
    var forceRefreshUntilMs: Long = 0L,
    var watchedMoviesUpdatedAtMs: Long = 0L,
    var watchedMoviesLastAttemptAtMs: Long = 0L,
    var watchedShowsUpdatedAtMs: Long = 0L,
    var watchedShowsLastAttemptAtMs: Long = 0L,
    var hiddenProgressUpdatedAtMs: Long = 0L,
    var hiddenProgressLastAttemptAtMs: Long = 0L,
    var hasLoadedWatchedMovies: Boolean = false,
    var hasLoadedWatchedShows: Boolean = false,
    var hasLoadedHiddenProgress: Boolean = false,
    var watchedMoviesStale: Boolean = true,
    var watchedShowsStale: Boolean = true,
    var hiddenProgressStale: Boolean = true,
    var lastFastSyncRequestMs: Long = 0L,
    var lastKnownActivityFingerprint: String? = null,
    var lastKnownMoviesWatchedAt: String? = null,
    var lastKnownEpisodeActivityFingerprint: String? = null,
    var lastKnownWatchedShowsFingerprint: String? = null,
    var lastKnownHiddenProgressFingerprint: String? = null,
    var lastManualRefreshSignalMs: Long = 0L,
    val episodeProgressActivityVersion: AtomicLong = AtomicLong(0L),
    val showNextUpActivityVersion: AtomicLong = AtomicLong(0L)
) {
    fun clear() {
        remoteProgress.value = emptyList()
        myShowsNextUp.value = emptyList()
        myShowsNextUpAll.value = emptyList()
        optimisticProgress.value = emptyMap()
        metadataState.value = emptyMap()
        watchedMoviesState.value = emptySet()
        watchedShowsState.value = emptyMap()
        hiddenProgressState.value = TraktProgressService.HiddenProgressSnapshot()
        episodeProgressState.value = emptyMap()
        showNextUpState.value = emptyMap()
        nextUpValidationCache.clear()
        nextUpValidationBypassKeys.clear()
        inFlightMetadataKeys.clear()
        inFlightEpisodeProgressKeys.clear()
        inFlightShowNextUpKeys.clear()
        episodeProgressLastAttemptAtMs.clear()
        showNextUpLastAttemptAtMs.clear()
        cachedMoviesPlayback = null
        cachedEpisodesPlayback = null
        cachedUserStats = null
        forceRefreshUntilMs = 0L
        watchedMoviesUpdatedAtMs = 0L
        watchedMoviesLastAttemptAtMs = 0L
        watchedShowsUpdatedAtMs = 0L
        watchedShowsLastAttemptAtMs = 0L
        hiddenProgressUpdatedAtMs = 0L
        hiddenProgressLastAttemptAtMs = 0L
        hasLoadedWatchedMovies = false
        hasLoadedWatchedShows = false
        hasLoadedHiddenProgress = false
        watchedMoviesStale = true
        watchedShowsStale = true
        hiddenProgressStale = true
        lastFastSyncRequestMs = 0L
        lastKnownActivityFingerprint = null
        lastKnownMoviesWatchedAt = null
        lastKnownEpisodeActivityFingerprint = null
        lastKnownWatchedShowsFingerprint = null
        lastKnownHiddenProgressFingerprint = null
        lastManualRefreshSignalMs = 0L
        episodeProgressActivityVersion.set(0L)
        showNextUpActivityVersion.set(0L)
    }
}

internal class TraktProgressRuntimeRegistry {
    private val states = mutableMapOf<Int, TraktProgressRuntimeState>()

    fun stateFor(session: TrackingRuntimeSession): TraktProgressRuntimeState {
        require(session.provider == TrackingProvider.TRAKT) {
            "TraktProgressRuntimeRegistry only accepts TRAKT sessions"
        }
        return states.getOrPut(session.profileId) { TraktProgressRuntimeState() }
    }

    fun clearProfile(profileId: Int) {
        states[profileId]?.clear()
    }
}
```

- [ ] **Step 5: Make nested cache types visible to runtime state**

Change these private declarations in `TraktProgressService` from `private` to `internal`:

```kotlin
internal data class TimedCache<T>(...)
internal data class EpisodeProgressCacheEntry(...)
internal data class OptimisticProgressEntry(...)
internal data class ContentMetadata(...)
internal data class WatchedShowIndexEntry(...)
internal data class HiddenProgressSnapshot(...)
internal data class ShowNextUpCacheEntry(...)
internal data class CachedNextUpValidation(...)
```

Keep their constructors and fields unchanged.

- [ ] **Step 6: Add a session resolver and runtime accessor**

Inject `TrackingProviderStateService` into `TraktProgressService` if not already available through constructor dependencies. Add:

```kotlin
private val runtimeRegistry = TraktProgressRuntimeRegistry()

private suspend fun currentRuntimeSession(): TrackingRuntimeSession? {
    val state = trackingProviderStateService.currentState()
    if (!state.traktAuthenticated) return null
    return TrackingRuntimeSession(
        provider = TrackingProvider.TRAKT,
        profileId = traktAuthService.currentTraktProfileId(),
        generation = 0L
    )
}

private suspend fun runtimeState(): TraktProgressRuntimeState? {
    return currentRuntimeSession()?.let(runtimeRegistry::stateFor)
}
```

Also add `currentTraktProfileId()` to `TraktAuthService` in Task 6 before compiling this task if needed:

```kotlin
fun currentTraktProfileId(): Int = currentRoutedProfileId()
```

- [ ] **Step 7: Replace global state field access with local `state` variables**

In every public method that reads runtime state, resolve `val state = runtimeState() ?: return inert value`.

Use these exact patterns:

```kotlin
fun observeAllProgress(): Flow<List<WatchProgress>> {
    return flow {
        val state = runtimeState()
        if (state == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            combine(
                state.remoteProgress,
                state.optimisticProgress,
                state.metadataState,
                state.hasLoadedRemoteProgress
            ) { remote, optimistic, metadata, loaded ->
                // keep existing merge body unchanged, replacing field reads with state.*
            }.filterNotNull().distinctUntilChanged()
        )
    }
}
```

For suspend methods such as `refreshRemoteSnapshot`, resolve at the top:

```kotlin
private suspend fun refreshRemoteSnapshot() {
    val state = runtimeState() ?: return
    // existing body, with remoteProgress -> state.remoteProgress, etc.
}
```

Replace each old singleton field reference with its `state.` equivalent:

```kotlin
remoteProgress -> state.remoteProgress
myShowsNextUp -> state.myShowsNextUp
myShowsNextUpAll -> state.myShowsNextUpAll
optimisticProgress -> state.optimisticProgress
metadataState -> state.metadataState
watchedMoviesState -> state.watchedMoviesState
watchedShowsState -> state.watchedShowsState
hiddenProgressState -> state.hiddenProgressState
episodeProgressState -> state.episodeProgressState
showNextUpState -> state.showNextUpState
nextUpValidationCache -> state.nextUpValidationCache
```

- [ ] **Step 8: Run runtime state tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.TraktProgressRuntimeStateTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt app/src/test/java/com/nexio/tv/data/repository/TraktProgressRuntimeStateTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "refactor(profile): key trakt runtime state by profile"
```

---

## Task 4: Tag Continue Watching Snapshots With Profile Owner

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write source contract test**

Add this test to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `continue watching snapshots carry and enforce profile owner`() {
    val serviceSource = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt").readText()
    val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()

    assertTrue(serviceSource.contains("val profileId: Int = 1"))
    assertTrue(serviceSource.contains("ProfileOwnedContinueWatchingSnapshot"))
    assertTrue(serviceSource.contains("snapshot.profileId != activeProfileId()"))
    assertTrue(serviceSource.contains("rawSnapshotState.value = ProfileOwnedContinueWatchingSnapshot(profileId = profileId)"))
    assertTrue(homeSource.contains("if (snapshot.profileId != activeHomeProfileSession.profileId)"))
    assertTrue(homeSource.contains("Skipping foreign continue watching snapshot"))
}
```

- [ ] **Step 2: Write profile boundary behavior tests**

Create `ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingSnapshotServiceProfileBoundaryTest {

    @Test
    fun `foreign snapshot is not publishable for active profile`() {
        val foreign = ProfileOwnedContinueWatchingSnapshot(
            profileId = 1,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-one")),
                updatedAtMs = 10L
            )
        )

        val activeProfileId = 2

        assertEquals(false, foreign.isOwnedBy(activeProfileId))
    }

    @Test
    fun `owned snapshot is publishable for active profile`() {
        val owned = ProfileOwnedContinueWatchingSnapshot(
            profileId = 2,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-two")),
                updatedAtMs = 20L
            )
        )

        assertEquals(true, owned.isOwnedBy(2))
    }

    private fun sampleProgress(id: String): WatchProgress {
        return WatchProgress(
            contentId = id,
            contentType = "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            lastWatched = 1L,
            progressPercent = 10f
        )
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because owner wrapper does not exist.

- [ ] **Step 4: Add owner wrapper**

In `ContinueWatchingSnapshotService.kt`, after `ContinueWatchingSnapshot`, add:

```kotlin
data class ProfileOwnedContinueWatchingSnapshot(
    val profileId: Int = 1,
    val snapshot: ContinueWatchingSnapshot = ContinueWatchingSnapshot()
) {
    fun isOwnedBy(activeProfileId: Int): Boolean {
        return profileId == activeProfileId
    }
}
```

- [ ] **Step 5: Change state flows to owner-tagged snapshots**

Replace:

```kotlin
private val rawSnapshotState = MutableStateFlow(ContinueWatchingSnapshot())
private val snapshotState = MutableStateFlow(ContinueWatchingSnapshot())
```

with:

```kotlin
private val rawSnapshotState = MutableStateFlow(ProfileOwnedContinueWatchingSnapshot())
private val snapshotState = MutableStateFlow(ProfileOwnedContinueWatchingSnapshot())
```

When reading the current raw snapshot, use `.snapshot`:

```kotlin
fun currentRawResumeItems(): List<WatchProgress> = rawSnapshotState.value.snapshot.resumeItems
```

When updating, preserve profile id:

```kotlin
rawSnapshotState.update { current ->
    current.copy(
        snapshot = current.snapshot.copy(
            resumeItems = current.snapshot.resumeItems.filterNot { it.videoId == videoId }
        )
    )
}
```

- [ ] **Step 6: Publish only active-owner snapshots**

In `persistRawSnapshot`, after hydration:

```kotlin
snapshotStore.write(hydrated, profileId = profileId)
if (!isActiveProfile(profileId)) {
    Log.d("ContinueWatching", "Skipping stale continue watching publish for profile=$profileId")
    return false
}
val owned = ProfileOwnedContinueWatchingSnapshot(profileId = profileId, snapshot = hydrated)
rawSnapshotState.value = owned
```

Update `observeSnapshot()`:

```kotlin
fun observeSnapshot(): Flow<ProfileOwnedContinueWatchingSnapshot> {
    return snapshotState.onStart {
        scope.launch {
            runCatching { ensureFresh(force = false) }
                .onFailure { error ->
                    Log.w("ContinueWatching", "Failed to refresh continue watching snapshot", error)
                }
        }
    }
}
```

- [ ] **Step 7: Always clear no-auth active profile owner**

Replace the unauthenticated branch in the `trackingProviderStateService.state` collector:

```kotlin
if (!isAuthenticated) {
    val profileId = activeProfileId()
    val empty = ProfileOwnedContinueWatchingSnapshot(profileId = profileId)
    rawSnapshotState.value = empty
    snapshotState.value = empty
    metadataDiskCacheStore.replaceHomeFeedReferences(feedKey = "continue_watching", itemKeys = emptySet())
    lastRefreshRequestMs = 0L
    cancelReemitScheduling()
    hasSeenAuthenticatedSession = false
    flowOf<ContinueWatchingSnapshot?>(null)
}
```

Do not call `snapshotStore.clear(profileId)` here; a profile temporarily losing auth state during startup must not destroy its disk snapshot. Only explicit logout or language invalidation should clear persisted snapshots.

- [ ] **Step 8: Reject foreign snapshots in Home**

In `HomeViewModelContinueWatching.kt`, update the collector:

```kotlin
continueWatchingSnapshotService.observeSnapshot().collectLatest { ownedSnapshot ->
    val capturedGeneration = homeProfileGeneration
    if (ownedSnapshot.profileId != activeHomeProfileSession.profileId) {
        Log.d(HomeViewModel.TAG, "Skipping foreign continue watching snapshot profile=${ownedSnapshot.profileId}")
        return@collectLatest
    }
    val snapshot = ownedSnapshot.snapshot
    // existing timeline code continues unchanged
}
```

Add `profileId` to `HomeProfileSession.DefaultLegacy` and `HomeProfileSession.Secondary` if it does not already expose a common `profileId` property:

```kotlin
internal sealed interface HomeProfileSession {
    val profileId: Int
    val generation: Long

    data class DefaultLegacy(
        override val generation: Long
    ) : HomeProfileSession {
        override val profileId: Int = 1
    }

    data class Secondary(
        override val profileId: Int,
        override val generation: Long,
        val boundaryContext: SecondaryProfileRuntimeContext
    ) : HomeProfileSession
}
```

- [ ] **Step 9: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): tag continue watching snapshots by owner"
```

---

## Task 5: Refuse Trakt Scrobbles For Profiles Without Trakt Auth

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingScrobbleServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write failing scrobble tests**

Add these tests to `DefaultTrackingScrobbleServiceTest.kt`:

```kotlin
@Test
fun `trakt scrobble start is ignored when profile lacks trakt auth`() = runTest {
    val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
    val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
    val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
        traktService,
        simklService,
        trackingProviderStateService(
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            traktAuthenticated = false,
            simklAuthenticated = false
        )
    )

    service.scrobbleStart(
        com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
            contentId = "tt1375666",
            title = "Inception",
            year = 2010
        ),
        progressPercent = 12f
    )

    coVerify(exactly = 0) { traktService.scrobbleStart(any(), any()) }
    coVerify(exactly = 0) { simklService.scrobbleStart(any(), any()) }
}

@Test
fun `trakt checkin returns false when profile lacks trakt auth`() = runTest {
    val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
    val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
    val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
        traktService,
        simklService,
        trackingProviderStateService(
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            traktAuthenticated = false,
            simklAuthenticated = false
        )
    )

    val result = service.checkin(
        com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
            contentId = "tt1375666",
            title = "Inception",
            year = 2010
        )
    )

    assertFalse(result)
    coVerify(exactly = 0) { traktService.checkin(any(), any()) }
}
```

- [ ] **Step 2: Write source contract**

Add this test to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `tracking scrobble service requires provider specific auth`() {
    val source = File("app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt").readText()

    assertTrue(source.contains("if (!providerState.traktAuthenticated) return"))
    assertTrue(source.contains("if (!providerState.simklAuthenticated) return"))
    assertTrue(source.contains("if (!providerState.traktAuthenticated) return false"))
    assertTrue(source.contains("if (!providerState.simklAuthenticated) return false"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.DefaultTrackingScrobbleServiceTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because `DefaultTrackingScrobbleService` currently dispatches by `effectiveProvider` without explicit provider-auth checks.

- [ ] **Step 4: Gate each scrobble method**

Update `TrackingScrobbleService.kt`:

```kotlin
override suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float) {
    val providerState = trackingProviderStateService.currentState()
    when (providerState.effectiveProvider) {
        TrackingProvider.SIMKL -> {
            if (!providerState.simklAuthenticated) return
            simklScrobbleService.scrobbleStart(item, progressPercent)
        }
        TrackingProvider.TRAKT -> {
            if (!providerState.traktAuthenticated) return
            toTraktItem(item)?.let { traktScrobbleService.scrobbleStart(it, progressPercent) }
        }
    }
}
```

Apply the same shape to `scrobbleStop` and `scrobblePause`.

Update `checkin`:

```kotlin
override suspend fun checkin(item: TrackingScrobbleItem, message: String?): Boolean {
    val providerState = trackingProviderStateService.currentState()
    return when (providerState.effectiveProvider) {
        TrackingProvider.SIMKL -> {
            if (!providerState.simklAuthenticated) return false
            simklScrobbleService.checkin(item, message)
        }
        TrackingProvider.TRAKT -> {
            if (!providerState.traktAuthenticated) return false
            val traktItem = toTraktItem(item) ?: return false
            traktScrobbleService.checkin(traktItem, message)
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.DefaultTrackingScrobbleServiceTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingScrobbleServiceTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): require provider auth for scrobble routing"
```

---

## Task 6: Persist Outbox Mutations With Origin Profile

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicyTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write store persistence test**

Add to `TraktMutationOutboxStoreTest.kt`:

```kotlin
@Test
fun `write and read round trips mutation profile id`() = runTest {
    val prefs = InMemorySharedPreferences()
    val store = TraktMutationOutboxStore(context = mockContext(prefs))
    val snapshot = TraktMutationOutboxSnapshot(
        items = listOf(sampleEnvelope(id = "queued-profile-2", profileId = 2)),
        updatedAtMs = 1_000L
    )

    store.write(snapshot)

    assertEquals(2, store.read().items.single().profileId)
}

@Test
fun `legacy persisted mutation defaults to profile one`() = runTest {
    val prefs = InMemorySharedPreferences()
    prefs.edit().putString(
        "snapshot",
        """
        {
          "schemaVersion": 1,
          "snapshot": {
            "items": [
              {
                "id": "legacy-queued",
                "adapterKey": "scrobble",
                "mutationKind": "scrobble.state",
                "priority": "SCROBBLE",
                "payload": {},
                "metadata": {},
                "state": "QUEUED",
                "createdAtMs": 1,
                "updatedAtMs": 1,
                "nextAttemptAtMs": 1,
                "attemptCount": 0
              }
            ],
            "nextWritableAtMs": 0,
            "updatedAtMs": 1
          }
        }
        """.trimIndent()
    ).commit()
    val store = TraktMutationOutboxStore(context = mockContext(prefs))

    assertEquals(1, store.read().items.single().profileId)
}
```

Update the local `sampleEnvelope` helper signature:

```kotlin
private fun sampleEnvelope(
    id: String = "queued",
    profileId: Int = 1
): TraktMutationEnvelope {
    return TraktMutationEnvelope(
        id = id,
        profileId = profileId,
        adapterKey = "progress",
        mutationKind = "progress.history.add",
        priority = TraktMutationPriorityBucket.WATCHED
    )
}
```

- [ ] **Step 2: Write policy collapse test**

Add to `TraktMutationOutboxPolicyTest.kt`:

```kotlin
@Test
fun `mutations with same collapse key but different profiles do not collapse`() {
    val policy = TraktMutationOutboxPolicy()
    val now = 1_000L
    val first = sampleEnvelope(
        id = "p1",
        profileId = 1,
        collapseKey = "scrobble:tt1375666"
    )
    val second = sampleEnvelope(
        id = "p2",
        profileId = 2,
        collapseKey = "scrobble:tt1375666"
    )

    val afterFirst = policy.enqueue(TraktMutationOutboxSnapshot(), first, now)
    val afterSecond = policy.enqueue(afterFirst, second, now + 1)

    assertEquals(2, afterSecond.items.count { it.state == TraktMutationLifecycleState.QUEUED })
}
```

Update `sampleEnvelope` in that file to accept `profileId` and pass it into `TraktMutationEnvelope`.

- [ ] **Step 3: Write source contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `trakt mutation envelopes carry origin profile`() {
    val modelSource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt").readText()
    val storeSource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt").readText()
    val policySource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt").readText()

    assertTrue(modelSource.contains("val profileId: Int = 1"))
    assertTrue(storeSource.contains("obj.intOrNull(\"profileId\") ?: 1"))
    assertTrue(storeSource.contains("addProperty(\"profileId\", envelope.profileId)"))
    assertTrue(policySource.contains("existing.profileId == incoming.profileId"))
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxPolicyTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because envelopes do not persist origin profile.

- [ ] **Step 5: Add profile id to envelope model**

Update `TraktMutationOutboxModels.kt`:

```kotlin
data class TraktMutationEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val profileId: Int = 1,
    val adapterKey: String,
    val mutationKind: String,
    val priority: TraktMutationPriorityBucket,
    ...
)
```

- [ ] **Step 6: Persist and restore profile id**

In `TraktMutationOutboxStore.kt`, when encoding each envelope object, add:

```kotlin
addProperty("profileId", envelope.profileId)
```

When decoding:

```kotlin
profileId = obj.intOrNull("profileId") ?: 1,
```

If `intOrNull` does not exist in that file, add:

```kotlin
private fun JsonObject.intOrNull(key: String): Int? {
    return runCatching {
        get(key)?.takeIf { !it.isJsonNull }?.asInt
    }.getOrNull()
}
```

- [ ] **Step 7: Include profile id in collapse matching**

In `TraktMutationOutboxPolicy.kt`, update the collapse predicate to include:

```kotlin
existing.profileId == incoming.profileId &&
```

The final predicate must include adapter, mutation kind, collapse key, priority, and profile id:

```kotlin
existing.adapterKey == incoming.adapterKey &&
    existing.mutationKind == incoming.mutationKind &&
    existing.collapseKey == incoming.collapseKey &&
    existing.priority == incoming.priority &&
    existing.profileId == incoming.profileId
```

- [ ] **Step 8: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxPolicyTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicyTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): persist trakt mutation origin profile"
```

---

## Task 7: Execute Trakt Outbox Mutations With Captured Profile Session

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write adapter test for captured profile execution**

Add to `TraktScrobbleMutationAdapterTest.kt`:

```kotlin
@Test
fun `scrobble executes with envelope profile id`() = runTest {
    val traktApi = mockk<TraktApi>()
    val authService = mockk<TraktAuthService>()
    val progressService = mockk<TraktProgressService>(relaxed = true)
    val watchingNowController = mockk<TraktWatchingNowStateController>(relaxed = true)
    val sessionSlot = slot<TrackingAuthSession>()
    coEvery {
        authService.executeAuthorizedWriteRequest(capture(sessionSlot), any<suspend (String) -> retrofit2.Response<Any>>())
    } returns retrofit2.Response.success(Any())
    val adapter = TraktScrobbleMutationAdapter(
        traktApi = traktApi,
        traktAuthService = authService,
        traktProgressService = progressService,
        watchingNowStateController = watchingNowController
    )
    val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
        item = TraktScrobbleItem.Movie(
            title = "Inception",
            year = 2010,
            ids = TraktIdsDto(imdb = "tt1375666")
        ),
        action = "stop",
        progressPercent = 90f,
        rollbackState = TraktWatchingNowStateController.Snapshot(),
        optimisticVersion = 1L
    ).copy(profileId = 2)

    adapter.execute(envelope)

    assertEquals(2, sessionSlot.captured.profileId)
}
```

If generic type inference makes the test awkward, capture only the first argument and use `any()` for the lambda:

```kotlin
coEvery { authService.executeAuthorizedWriteRequest(capture(sessionSlot), any()) } returns retrofit2.Response.success(Unit)
```

- [ ] **Step 2: Write source contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `trakt outbox adapters execute with envelope profile session`() {
    val authSource = File("app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt").readText()
    val scrobbleSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt").readText()
    val historySource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt").readText()

    assertTrue(authSource.contains("suspend fun <T> executeAuthorizedWriteRequest("))
    assertTrue(authSource.contains("session: TrackingAuthSession"))
    assertTrue(scrobbleSource.contains("TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)"))
    assertTrue(historySource.contains("TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)"))
    assertTrue(!scrobbleSource.contains("traktAuthService.executeAuthorizedWriteRequest { authHeader ->"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because adapter writes resolve the current active profile.

- [ ] **Step 4: Make TraktAuthService session-aware public APIs available**

In `TraktAuthService.kt`, make the private overload public:

```kotlin
suspend fun <T> executeAuthorizedRequest(
    session: TrackingAuthSession,
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? {
    // existing private body unchanged
}
```

Add a public write overload:

```kotlin
suspend fun <T> executeAuthorizedWriteRequest(
    session: TrackingAuthSession,
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? = executeAuthorizedRequest(session, call)
```

Add current route accessor:

```kotlin
fun currentTraktProfileId(): Int = currentRoutedProfileId()
```

Keep the existing no-session methods as compatibility wrappers:

```kotlin
suspend fun <T> executeAuthorizedRequest(
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? = executeAuthorizedRequest(currentAuthSession(), call)

suspend fun <T> executeAuthorizedWriteRequest(
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? = executeAuthorizedRequest(call)
```

- [ ] **Step 5: Update scrobble adapter**

In `TraktScrobbleMutationAdapter.kt`, add imports:

```kotlin
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
```

Update `executeCheckin`:

```kotlin
val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
val response = traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
    traktApi.checkin(authHeader, envelope.buildCheckinRequestBody())
} ?: return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")
```

Update `executeScrobble`:

```kotlin
val requestBody = envelope.buildScrobbleRequestBody()
val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
val response = traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
    when (envelope.scrobbleAction()) {
        "start" -> traktApi.scrobbleStart(authHeader, requestBody)
        "pause" -> traktApi.scrobblePause(authHeader, requestBody)
        else -> traktApi.scrobbleStop(authHeader, requestBody)
    }
} ?: return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")
```

- [ ] **Step 6: Update progress history adapter**

In `TraktProgressHistoryMutationAdapter.kt`, add the same session imports. For each execute method, capture:

```kotlin
val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
```

Then call:

```kotlin
traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
    ...
}
```

- [ ] **Step 7: Update mutation executors**

In `TraktProgressMutationExecutor.kt`, add overloads:

```kotlin
suspend fun addHistory(
    session: TrackingAuthSession,
    body: TraktHistoryAddRequestDto
): Response<TraktHistoryAddResponseDto>? {
    return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
        traktApi.addHistory(authHeader, body)
    }
}

suspend fun removeHistory(
    session: TrackingAuthSession,
    body: TraktHistoryRemoveRequestDto
): Response<TraktHistoryRemoveResponseDto>? {
    return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
        traktApi.removeHistory(authHeader, body)
    }
}

suspend fun deletePlayback(
    session: TrackingAuthSession,
    playbackId: Long
): Response<Unit>? {
    return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
        traktApi.deletePlayback(authHeader, playbackId)
    }
}
```

Keep the existing no-session overloads for legacy call sites.

In `TraktLibraryMutationExecutor.kt`, add matching overloads for each write method using `TrackingAuthSession`. Keep existing no-session overloads.

- [ ] **Step 8: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt app/src/test/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapterTest.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): execute trakt outbox with origin profile"
```

---

## Task 8: Stamp New Trakt Mutations With Captured Profile

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktSeasonMarkMutationAdapter.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write source contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `new trakt mutations are stamped with captured profile id`() {
    val scrobbleServiceSource = File("app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt").readText()
    val watchProgressSource = File("app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt").readText()
    val scrobbleAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt").readText()
    val historyAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt").readText()
    val seasonAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktSeasonMarkMutationAdapter.kt").readText()

    assertTrue(scrobbleServiceSource.contains("val session = traktAuthService.currentAuthSession()"))
    assertTrue(scrobbleAdapterSource.contains("profileId: Int"))
    assertTrue(scrobbleAdapterSource.contains("profileId = profileId"))
    assertTrue(historyAdapterSource.contains("profileId: Int"))
    assertTrue(historyAdapterSource.contains("profileId = profileId"))
    assertTrue(seasonAdapterSource.contains("profileId: Int"))
    assertTrue(watchProgressSource.contains("val profileId = traktAuthService.currentTraktProfileId()"))
}
```

- [ ] **Step 2: Run source contract to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because builders do not accept profile ids.

- [ ] **Step 3: Make current auth session public**

In `TraktAuthService.kt`, change:

```kotlin
private fun currentAuthSession(): TrackingAuthSession
```

to:

```kotlin
fun currentAuthSession(): TrackingAuthSession
```

- [ ] **Step 4: Add profile id parameters to Trakt envelope builders**

In `TraktScrobbleMutationAdapter.kt`, update both builder signatures:

```kotlin
fun buildScrobbleEnvelope(
    item: TraktScrobbleItem,
    action: String,
    progressPercent: Float,
    rollbackState: TraktWatchingNowStateController.Snapshot,
    optimisticVersion: Long,
    profileId: Int = 1
): TraktMutationEnvelope {
    ...
    return TraktMutationEnvelope(
        profileId = profileId,
        adapterKey = ADAPTER_KEY,
        ...
    )
}

fun buildCheckinEnvelope(
    item: TraktScrobbleItem,
    message: String?,
    rollbackState: TraktWatchingNowStateController.Snapshot,
    optimisticVersion: Long,
    profileId: Int = 1
): TraktMutationEnvelope {
    ...
    return TraktMutationEnvelope(
        profileId = profileId,
        adapterKey = ADAPTER_KEY,
        ...
    )
}
```

In `TraktProgressHistoryMutationAdapter.kt`, add `profileId: Int = 1` to every `build...Envelope` method and pass it to `TraktMutationEnvelope(profileId = profileId, ...)`.

In `TraktSeasonMarkMutationAdapter.kt`, add `profileId: Int = 1` to `buildEnvelope` and pass it to `TraktMutationEnvelope(profileId = profileId, ...)`.

- [ ] **Step 5: Stamp scrobble service mutations**

In `TraktScrobbleService.submitMutation`, capture the session before queueing:

```kotlin
val session = traktAuthService.currentAuthSession()
```

Pass `session.profileId` into builder calls:

```kotlin
TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
    item = request.item,
    action = request.action,
    progressPercent = request.progressPercent,
    rollbackState = rollbackState,
    optimisticVersion = request.optimisticVersion,
    profileId = session.profileId
)
```

Apply the same to `buildCheckinEnvelope`.

- [ ] **Step 6: Stamp watch progress repository mutations**

Inject `TraktAuthService` into `WatchProgressRepositoryImpl`:

```kotlin
private val traktAuthService: TraktAuthService,
```

For Trakt branches only, capture:

```kotlin
val profileId = traktAuthService.currentTraktProfileId()
```

Pass `profileId = profileId` to:

```kotlin
TraktProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(...)
TraktProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(...)
TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(...)
TraktSeasonMarkMutationAdapter.buildEnvelope(...)
```

Do not pass Trakt profile ids into SIMKL envelope builders.

- [ ] **Step 7: Run source contract**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt app/src/main/java/com/nexio/tv/data/repository/trakt/TraktSeasonMarkMutationAdapter.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): stamp trakt mutations with origin profile"
```

---

## Task 9: End-to-End Regression Tests And Release Verification

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingProgressServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DefaultTrackingScrobbleServiceTest.kt`

- [ ] **Step 1: Add final source guard against direct global Trakt runtime fields**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `trakt profile isolation forbids global runtime and current-profile outbox execution`() {
    val progressSource = File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()
    val scrobbleAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt").readText()
    val historyAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt").readText()

    assertTrue(progressSource.contains("TraktProgressRuntimeRegistry"))
    assertTrue(!progressSource.contains("private val remoteProgress = MutableStateFlow"))
    assertTrue(!progressSource.contains("private val myShowsNextUp = MutableStateFlow"))
    assertTrue(!progressSource.contains("private var cachedMoviesPlayback"))
    assertTrue(scrobbleAdapterSource.contains("envelope.profileId"))
    assertTrue(historyAdapterSource.contains("envelope.profileId"))
    assertTrue(!scrobbleAdapterSource.contains("executeAuthorizedWriteRequest { authHeader ->"))
    assertTrue(!historyAdapterSource.contains("executeAuthorizedWriteRequest { authHeader ->"))
}
```

- [ ] **Step 2: Run focused unit and contract tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --tests com.nexio.tv.data.repository.DefaultTrackingProgressServiceTest --tests com.nexio.tv.data.repository.DefaultTrackingScrobbleServiceTest --tests com.nexio.tv.data.repository.TraktProgressRuntimeStateTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest --tests com.nexio.tv.data.trakt.outbox.TraktMutationOutboxPolicyTest --tests com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapterTest --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run broader repository tests for touched domains**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.* --tests com.nexio.tv.data.trakt.outbox.* --tests com.nexio.tv.data.repository.trakt.* --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Build release APK only**

Run:

```bash
./gradlew assembleArm64Release --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual release-build verification checklist**

Use a release build only. Do not install debug builds.

```text
1. Upgrade-install the release APK over the current app.
2. Confirm the logged-in account remains logged in.
3. Open default profile with Trakt authenticated.
4. Confirm default profile Trakt Continue Watching and Trakt Up Next populate from disk first.
5. Switch to profile 2 with no Trakt auth.
6. Confirm profile 2 has no Trakt Continue Watching, no Trakt Up Next, no Trakt catalog rows, and no Trakt scrobble activity.
7. Start playback on profile 2.
8. Confirm no Trakt scrobble request is sent for profile 2.
9. Switch back to default profile.
10. Confirm default profile Trakt Continue Watching returns from the profile 1 disk snapshot and was not cleared by profile 2.
11. Authenticate Trakt on profile 2 with a different Trakt account.
12. Confirm profile 2 receives only its own Continue Watching and profile 1 remains unchanged.
```

- [ ] **Step 6: Commit verification guard**

```bash
git add app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "test(profile): guard trakt runtime profile isolation"
```

---

## Self-Review

**Spec coverage:** The plan covers profile 1 legacy Trakt, secondary-profile Trakt auth routing, Continue Watching, Up Next, progress reads, scrobbles, playback/history mutations, and outbox drain ownership. It also covers no-auth profiles refusing Trakt entirely.

**Placeholder scan:** No step relies on placeholder markers or generic edge-case filler language. Each task includes exact files, code snippets, commands, and expected outcomes.

**Type consistency:** `TrackingAuthSession`, `TrackingRuntimeSession`, `profileId`, `EffectiveTrackingProviderState.canReadEffectiveProvider`, `ProfileOwnedContinueWatchingSnapshot`, and `TraktMutationEnvelope.profileId` are defined before later tasks depend on them.

**Risk note:** Task 3 is the largest change because `TraktProgressService` is currently a large singleton. Keep the change mechanical: introduce `TraktProgressRuntimeState`, then replace field reads with `state.*` without changing Trakt algorithms.
