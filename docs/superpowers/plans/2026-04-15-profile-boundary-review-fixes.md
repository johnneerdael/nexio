# Profile Boundary Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the architecture review findings for profile boundary isolation so default profile legacy behavior and secondary profile state cannot cross-contaminate at startup, auth, discovery, or catalog planning boundaries.

**Architecture:** Keep profile 1 on the legacy account/default route. Treat profiles 2-4 as secondary route contexts with explicit profile ids captured at operation start. Use `HomeProfileSession` as the Home UI-session generation owner, while `ProfileBoundary` remains the secondary routing/source owner. Profile-derived discovery and home snapshots must read/write with captured profile ids, and catalog planning must emit one typed rail plan consumed by both loading descriptors and populated synthetic rows.

**Tech Stack:** Android Kotlin, Hilt, coroutines/Flow, DataStore/SharedPreferences, Robolectric/JUnit, Supabase SQL migrations, Nuxt/Vue/TypeScript for web contract checks.

---

## File Structure

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt`
  - Owns Home UI session identity and generation for default legacy and secondary profiles.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  - Initializes and updates the active `HomeProfileSession` from `ProfileManager.activeProfileId`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  - Applies generation/profile guards to discovery and home snapshot hydration; uses `CatalogPlan` for rail planning.
- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
  - Adds explicit profile-id read/write/clear APIs.
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`
  - Adds explicit profile-id read/write/clear APIs.
- `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt`
  - Converts global rendered discovery snapshot storage to profile-derived storage with explicit profile-id APIs.
- `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
  - Stores per-profile in-memory discovery snapshots and writes with captured profile ids.
- `app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt`
  - Stores per-profile in-memory discovery snapshots and writes with captured profile ids.
- `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
  - Stores per-profile rendered discovery snapshots while still using global MDBList account settings as the account availability source.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt`
  - New small value object for captured Trakt/SIMKL auth route profile ids.
- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
  - Captures one `TrackingAuthSession` per async operation.
- `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`
  - Captures one `TrackingAuthSession` per async operation.
- `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt`
  - Upgrades from wrapper to typed planned rails with row builders.
- `docs/architecture/profile-settings-scope.md`
  - Documents Home session generation ownership and discovery cache scoping.
- Tests:
  - `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/TraktAuthServiceTest.kt`
  - `app/src/test/java/com/nexio/tv/data/repository/SimklAuthServiceTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt`

---

## Task 1: Initialize HomeProfileSession From Active Profile State

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write the failing contract test**

Add these assertions to `home refreshes are generation gated across profile switches` in `ProfileSettingsScopeContractTest.kt`:

```kotlin
assertTrue(homeViewModelSource.contains("activeHomeProfileSession = startHomeProfileSession(profileManager.activeProfileId.value)"))
assertTrue(homeViewModelSource.contains("profileManager.activeProfileId"))
assertTrue(homeViewModelSource.contains(".distinctUntilChanged()"))
assertTrue(homeViewModelSource.contains("collectLatest { profileId ->"))
assertTrue(!homeViewModelSource.contains("profileManager.profileSwitched.collectLatest { profileId ->"))
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because `HomeViewModel` still initializes the session as `DefaultLegacy` and collects `profileSwitched`.

- [ ] **Step 3: Implement session initialization and activeProfileId collection**

In `HomeViewModel.kt`, replace the current field initialization:

```kotlin
internal var activeHomeProfileSession: HomeProfileSession = HomeProfileSession.DefaultLegacy(generation = 0L)
```

with:

```kotlin
internal var activeHomeProfileSession: HomeProfileSession =
    startHomeProfileSession(profileManager.activeProfileId.value)
```

Update imports:

```kotlin
import kotlinx.coroutines.flow.distinctUntilChanged
```

Replace `observeProfileSwitches()` with active profile collection:

```kotlin
private fun observeProfileSwitches() {
    viewModelScope.launch {
        profileManager.activeProfileId
            .distinctUntilChanged()
            .collectLatest { profileId ->
                val session = startHomeProfileSession(profileId)
                profileSwitchDiskHydrationActive = true
                suppressProfileSwitchRefreshUntilMs = SystemClock.elapsedRealtime() + 5_000L
                resetProfileScopedHomeState("profile_switch:$profileId")
                try {
                    continueWatchingSnapshotService.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
                    loadActiveProfileDiskBackedHomeState(
                        reason = "profile_switch:$profileId",
                        expectedGeneration = session.generation
                    )
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

- [ ] **Step 4: Run the focused test**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "fix(profile): initialize home session from active profile"
```

---

## Task 2: Add Explicit Profile IDs to Discovery Snapshot Stores

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt`

- [ ] **Step 1: Write failing Trakt store test**

Create `app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktDiscoverySnapshotStoreTest {
    @Test
    fun `explicit profile id keeps trakt discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = TraktDiscoverySnapshotStore(context)
        val profileOne = TraktDiscoverySnapshot(trendingMovieItems = listOf(sampleItem("tt1")), updatedAtMs = 1L)
        val profileTwo = TraktDiscoverySnapshot(trendingMovieItems = listOf(sampleItem("tt2")), updatedAtMs = 2L)

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals(profileOne.trendingMovieItems.single().id, store.read(profileId = 1)?.trendingMovieItems?.single()?.id)
        assertEquals(profileTwo.trendingMovieItems.single().id, store.read(profileId = 2)?.trendingMovieItems?.single()?.id)
    }

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }

    private fun sampleItem(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
```

- [ ] **Step 2: Write failing SIMKL store test**

Create `app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklDiscoverySnapshotStoreTest {
    @Test
    fun `explicit profile id keeps simkl discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = SimklDiscoverySnapshotStore(context)
        val profileOne = SimklDiscoverySnapshot(itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(sampleItem("tt1"))), updatedAtMs = 1L)
        val profileTwo = SimklDiscoverySnapshot(itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_WEEK to listOf(sampleItem("tt2"))), updatedAtMs = 2L)

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals("tt1", store.read(profileId = 1)?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
        assertEquals("tt2", store.read(profileId = 2)?.itemsByCatalog?.get(SimklCatalogIds.MOVIE_TRENDING_WEEK)?.single()?.id)
    }

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }

    private fun sampleItem(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
```

- [ ] **Step 3: Write failing MDBList store test**

Create `app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class MDBListDiscoverySnapshotStoreTest {
    @Test
    fun `explicit profile id keeps mdblist discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = MDBListDiscoverySnapshotStore(context)
        val profileOne = MDBListDiscoverySnapshot(personalLists = listOf(option("one")), updatedAtMs = 1L)
        val profileTwo = MDBListDiscoverySnapshot(personalLists = listOf(option("two")), updatedAtMs = 2L)

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals("one", store.read(profileId = 1)?.personalLists?.single()?.key)
        assertEquals("two", store.read(profileId = 2)?.personalLists?.single()?.key)
    }

    private fun option(key: String) = MDBListListOption(
        key = key,
        owner = "owner",
        listId = key,
        title = key,
        itemCount = 1,
        isPersonal = true
    )

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew testArm64DebugUnitTest \
  --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest \
  --no-daemon
```

Expected: FAIL because `read(profileId)`, `write(profileId)`, and `clear(profileId)` do not exist.

- [ ] **Step 5: Implement explicit profile id APIs in Trakt store**

In `TraktDiscoverySnapshotStore.kt`, replace the profile manager getter and prefs helpers with:

```kotlin
private fun injectedPrefsName(profileId: Int): String =
    profilePrefsName(BASE_PREFS_NAME, profileId)

private fun prefsName(profileId: Int = activeProfileId()): String =
    if (injectedProfileManager != null) {
        injectedPrefsName(profileId)
    } else {
        profilePrefsName(BASE_PREFS_NAME, profileId)
    }

fun read(profileId: Int = activeProfileId()): TraktDiscoverySnapshot? {
    return runCatching {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        decode(raw)
    }.onFailure { error ->
        Log.w(TAG, "Failed to restore Trakt discovery snapshot", error)
        clear(profileId)
    }.getOrNull()
}

fun write(
    snapshot: TraktDiscoverySnapshot,
    profileId: Int = activeProfileId()
) {
    runCatching {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val payload = JsonObject().apply {
            add("calendarItems", gson.toJsonTree(snapshot.calendarItems))
            add("recommendationMovieItems", gson.toJsonTree(snapshot.recommendationMovieItems))
            add("recommendationShowItems", gson.toJsonTree(snapshot.recommendationShowItems))
            add("trendingMovieItems", gson.toJsonTree(snapshot.trendingMovieItems))
            add("trendingShowItems", gson.toJsonTree(snapshot.trendingShowItems))
            add("popularMovieItems", gson.toJsonTree(snapshot.popularMovieItems))
            add("popularShowItems", gson.toJsonTree(snapshot.popularShowItems))
            add("customListCatalogs", gson.toJsonTree(snapshot.customListCatalogs))
            add("popularLists", gson.toJsonTree(snapshot.popularLists))
            add("recommendationRefsByStatusKey", gson.toJsonTree(snapshot.recommendationRefsByStatusKey))
            addProperty("updatedAtMs", snapshot.updatedAtMs)
        }
        prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
    }.onFailure { error ->
        Log.w(TAG, "Failed to persist Trakt discovery snapshot", error)
    }
}

fun clear(profileId: Int = activeProfileId()) {
    runCatching {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        prefs.edit().remove(SNAPSHOT_KEY).commit()
    }.onFailure { error ->
        Log.w(TAG, "Failed to clear Trakt discovery snapshot", error)
    }
}
```

- [ ] **Step 6: Implement explicit profile id APIs in SIMKL store**

In `SimklDiscoverySnapshotStore.kt`, use this shape:

```kotlin
private fun injectedPrefsName(profileId: Int): String =
    profilePrefsName(BASE_PREFS_NAME, profileId)

private fun prefsName(profileId: Int = activeProfileId()): String =
    if (injectedProfileManager != null) {
        injectedPrefsName(profileId)
    } else {
        profilePrefsName(BASE_PREFS_NAME, profileId)
    }

fun read(profileId: Int = activeProfileId()): SimklDiscoverySnapshot? {
    return runCatching {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        decode(raw)
    }.onFailure {
        Log.w(TAG, "Failed to restore SIMKL discovery snapshot", it)
        clear(profileId)
    }.getOrNull()
}

fun write(
    snapshot: SimklDiscoverySnapshot,
    profileId: Int = activeProfileId()
) {
    runCatching {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val payload = JsonObject().apply {
            add("itemsByCatalog", gson.toJsonTree(snapshot.itemsByCatalog))
            addProperty("updatedAtMs", snapshot.updatedAtMs)
        }
        prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(SNAPSHOT_KEY)
            .commit()
    }.onFailure { Log.w(TAG, "Failed to persist SIMKL discovery snapshot", it) }
}

fun clear(profileId: Int = activeProfileId()) {
    runCatching {
        context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE).edit().remove(SNAPSHOT_KEY).commit()
    }
}
```

- [ ] **Step 7: Implement explicit profile id APIs in MDBList store**

In `MDBListDiscoverySnapshotStore.kt`, inject `ProfileManager` and add test constructor:

```kotlin
@Singleton
class MDBListDiscoverySnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        context = context,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    constructor(context: Context) : this(
        context = context,
        activeProfileId = { 1 }
    )
```

Add:

```kotlin
private fun prefsName(profileId: Int = activeProfileId()): String =
    profilePrefsName(PREFS_NAME, profileId)
```

Update `read`, `write`, and `clear` to accept `profileId: Int = activeProfileId()` and call `prefsName(profileId)`.

- [ ] **Step 8: Run tests**

Run:

```bash
./gradlew testArm64DebugUnitTest \
  --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStore.kt \
  app/src/test/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/local/MDBListDiscoverySnapshotStoreTest.kt
git commit -m "refactor(profile): isolate discovery snapshot stores"
```

---

## Task 3: Make Discovery Services Profile-Scoped In Memory

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Add failing source contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
private val traktDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt")
private val simklDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt")
private val mdbListDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt")
```

Add test:

```kotlin
@Test
fun `discovery services keep profile scoped in memory state`() {
    val traktSource = traktDiscoveryService.readText()
    val simklSource = simklDiscoveryService.readText()
    val mdbSource = mdbListDiscoveryService.readText()
    val homePipelineSource = homeCatalogPipeline.readText()

    assertTrue(traktSource.contains("profileSnapshots"))
    assertTrue(traktSource.contains("snapshotForProfile(profileId)"))
    assertTrue(traktSource.contains("snapshotStore.write(snapshotToPersist, profileId = profileId)"))
    assertTrue(simklSource.contains("profileSnapshots"))
    assertTrue(simklSource.contains("snapshotForProfile(profileId)"))
    assertTrue(simklSource.contains("snapshotStore.write(snapshotToPersist, profileId = profileId)"))
    assertTrue(mdbSource.contains("profileSnapshots"))
    assertTrue(mdbSource.contains("snapshotForProfile(profileId)"))
    assertTrue(mdbSource.contains("snapshotStore.write(snapshotState.value, profileId = profileId)"))
    assertTrue(homePipelineSource.contains("val capturedGeneration = homeProfileGeneration"))
    assertTrue(homePipelineSource.contains("Skipping stale discovery snapshot"))
}
```

- [ ] **Step 2: Run failing test**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because discovery services still use singleton snapshot state.

- [ ] **Step 3: Implement per-profile map helpers in TraktDiscoveryService**

In `TraktDiscoveryService.kt`, add constructor dependency:

```kotlin
private val profileManager: ProfileManager,
```

Add imports:

```kotlin
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
```

Replace singleton state fields with:

```kotlin
private val profileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())
private val rawProfileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())

private fun snapshotForProfile(profileId: Int): TraktDiscoverySnapshot =
    profileSnapshots.value[profileId] ?: TraktDiscoverySnapshot()

private fun rawSnapshotForProfile(profileId: Int): TraktDiscoverySnapshot =
    rawProfileSnapshots.value[profileId] ?: TraktDiscoverySnapshot()

private fun setProfileSnapshot(profileId: Int, snapshot: TraktDiscoverySnapshot) {
    profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
}

private fun setRawProfileSnapshot(profileId: Int, snapshot: TraktDiscoverySnapshot) {
    rawProfileSnapshots.value = rawProfileSnapshots.value + (profileId to snapshot)
}
```

In `init`, load persisted snapshot for active profile:

```kotlin
scope.launch {
    val profileId = profileManager.activeProfileId.value
    snapshotStore.read(profileId = profileId)?.let { persisted ->
        setRawProfileSnapshot(profileId, persisted)
        setProfileSnapshot(profileId, persisted)
        lastRefreshMs = persisted.updatedAtMs
    }
}
```

Replace `observeSnapshot` with:

```kotlin
fun observeSnapshot(autoRefreshOnStart: Boolean = true): Flow<TraktDiscoverySnapshot> {
    return profileManager.activeProfileId.flatMapLatest { profileId ->
        profileSnapshots.map { snapshots -> snapshots[profileId] ?: TraktDiscoverySnapshot() }
            .onStart {
                snapshotStore.read(profileId = profileId)?.let { persisted ->
                    setRawProfileSnapshot(profileId, persisted)
                    setProfileSnapshot(profileId, persisted)
                }
                if (autoRefreshOnStart) {
                    scope.launch {
                        ensureStartupGateInitialized()
                        if (isStartupRefreshGated()) {
                            Log.d("TraktDiscovery", "Auto-refresh deferred by startup gate")
                            return@launch
                        }
                        runCatching { ensureFresh(force = false, profileId = profileId) }
                            .onFailure { error -> Log.w("TraktDiscovery", "Failed to refresh Trakt discovery snapshot", error) }
                    }
                }
            }
    }
}
```

Change `ensureFresh` signature:

```kotlin
suspend fun ensureFresh(
    force: Boolean,
    profileId: Int = profileManager.activeProfileId.value
) = withContext(Dispatchers.IO) {
```

Within `ensureFresh`, replace `rawSnapshotState.value` with `rawSnapshotForProfile(profileId)`, `snapshotState.value` with `snapshotForProfile(profileId)`, and write/clear with explicit profile id:

```kotlin
if (!traktAuthService.getCurrentAuthState().isAuthenticated) {
    setRawProfileSnapshot(profileId, TraktDiscoverySnapshot())
    setProfileSnapshot(profileId, TraktDiscoverySnapshot())
    snapshotStore.clear(profileId = profileId)
    return@withContext
}
```

When persisting:

```kotlin
setRawProfileSnapshot(profileId, snapshotToPersist)
setProfileSnapshot(profileId, snapshotToPersist)
snapshotStore.write(snapshotToPersist, profileId = profileId)
```

- [ ] **Step 4: Implement same profile map pattern in SimklDiscoveryService**

Use equivalent helpers:

```kotlin
private val profileSnapshots = MutableStateFlow<Map<Int, SimklDiscoverySnapshot>>(emptyMap())

private fun snapshotForProfile(profileId: Int): SimklDiscoverySnapshot =
    profileSnapshots.value[profileId] ?: SimklDiscoverySnapshot()

private fun setProfileSnapshot(profileId: Int, snapshot: SimklDiscoverySnapshot) {
    profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
}
```

Add `ProfileManager` to constructor and use `profileManager.activeProfileId.flatMapLatest` in `observeSnapshot`. Change `ensureFresh(force: Boolean, profileId: Int = profileManager.activeProfileId.value)`. Replace `snapshotState.value` reads/writes with `snapshotForProfile(profileId)` and `setProfileSnapshot(profileId, ...)`. Persist with:

```kotlin
snapshotStore.write(snapshotToPersist, profileId = profileId)
```

- [ ] **Step 5: Implement profile map pattern in MDBListDiscoveryService**

Add constructor dependency:

```kotlin
private val profileManager: ProfileManager,
```

Use:

```kotlin
private val profileSnapshots = MutableStateFlow<Map<Int, MDBListDiscoverySnapshot>>(emptyMap())

private fun snapshotForProfile(profileId: Int): MDBListDiscoverySnapshot =
    profileSnapshots.value[profileId] ?: MDBListDiscoverySnapshot()

private fun setProfileSnapshot(profileId: Int, snapshot: MDBListDiscoverySnapshot) {
    profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
}
```

In `observeSnapshot`, emit active profile snapshots:

```kotlin
fun observeSnapshot(autoRefreshOnStart: Boolean = true): Flow<MDBListDiscoverySnapshot> {
    return profileManager.activeProfileId.flatMapLatest { profileId ->
        profileSnapshots.map { snapshots -> snapshots[profileId] ?: MDBListDiscoverySnapshot() }
            .onStart {
                snapshotStore.read(profileId = profileId)?.let { persisted ->
                    setProfileSnapshot(profileId, persisted)
                    lastRefreshMs = persisted.updatedAtMs
                }
                if (autoRefreshOnStart) {
                    scope.launch {
                        ensureStartupGateInitialized()
                        if (isStartupRefreshGated()) {
                            Log.d("MDBListDiscovery", "Auto-refresh deferred by startup gate")
                            return@launch
                        }
                        runCatching { ensureFresh(force = false, profileId = profileId) }
                            .onFailure { error -> Log.w("MDBListDiscovery", "Failed to refresh MDBList discovery snapshot", error) }
                    }
                }
            }
    }
}
```

Change `ensureFresh` signature and use explicit profile id:

```kotlin
suspend fun ensureFresh(
    force: Boolean,
    profileId: Int = profileManager.activeProfileId.value
) = withContext(Dispatchers.IO) {
```

When disabled:

```kotlin
setProfileSnapshot(profileId, MDBListDiscoverySnapshot())
snapshotStore.clear(profileId = profileId)
return@withContext
```

When refreshed:

```kotlin
val snapshot = MDBListDiscoverySnapshot(
    personalLists = personalLists,
    topLists = topLists,
    customListCatalogs = customCatalogs,
    updatedAtMs = System.currentTimeMillis()
)
setProfileSnapshot(profileId, snapshot)
snapshotStore.write(snapshot, profileId = profileId)
lastRefreshMs = System.currentTimeMillis()
```

- [ ] **Step 6: Add Home discovery generation guards**

In `HomeViewModelCatalogPipeline.kt`, in each discovery collector (`observeTraktDiscoveryPipeline`, `observeSimklDiscoveryPipeline`, `observeMDBListDiscoveryPipeline`), capture generation before applying main-state updates:

```kotlin
val capturedGeneration = homeProfileGeneration
```

Before writing to `traktDiscoverySnapshot`, `simklDiscoverySnapshot`, or `mdbListDiscoverySnapshot`, add:

```kotlin
if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
    Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
    return@collectLatest
}
```

- [ ] **Step 7: Run tests**

```bash
./gradlew testArm64DebugUnitTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest \
  --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
  app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "refactor(profile): scope discovery state by profile"
```

---

## Task 4: Capture Tracking Auth Session Once Per Operation

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktAuthServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/SimklAuthServiceTest.kt`
- Test: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write failing source contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
private val trackingAuthSession = File("app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt")
```

Add test:

```kotlin
@Test
fun `tracking auth services capture routed session once per operation`() {
    val sessionSource = trackingAuthSession.readText()
    val traktSource = traktAuthService.readText()
    val simklSource = simklAuthService.readText()

    assertTrue(sessionSource.contains("data class TrackingAuthSession"))
    assertTrue(sessionSource.contains("val profileId: Int"))
    assertTrue(traktSource.contains("private fun currentAuthSession(): TrackingAuthSession"))
    assertTrue(traktSource.contains("getCurrentAuthState(session: TrackingAuthSession)"))
    assertTrue(traktSource.contains("fetchUserSettings(session: TrackingAuthSession)"))
    assertTrue(traktSource.contains("executeAuthorizedRequest(session: TrackingAuthSession"))
    assertTrue(simklSource.contains("private fun currentAuthSession(): TrackingAuthSession"))
    assertTrue(simklSource.contains("getCurrentAuthState(session: TrackingAuthSession)"))
    assertTrue(simklSource.contains("fetchUserSettings(session: TrackingAuthSession)"))
    assertTrue(simklSource.contains("executeAuthorizedRequest(session: TrackingAuthSession"))
}
```

- [ ] **Step 2: Run failing test**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because `TrackingAuthSession` does not exist.

- [ ] **Step 3: Create TrackingAuthSession**

Create `app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingAuthSession(
    val provider: TrackingProvider,
    val profileId: Int
)
```

- [ ] **Step 4: Refactor TraktAuthService**

Add:

```kotlin
private fun currentAuthSession(): TrackingAuthSession {
    return when (val route = profileModeRouter.routeFor(profileManager.activeProfileId.value)) {
        ProfileModeRoute.DefaultLegacyRoute -> TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = profileModeRouter.defaultLegacyProfileId()
        )
        is ProfileModeRoute.SecondaryProfileRoute -> TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = profileBoundary.authRoute(route, TrackingProvider.TRAKT).profileId
        )
        is ProfileModeRoute.InvalidProfileRoute -> error("Invalid active profile id ${route.profileId}")
    }
}
```

Replace `getCurrentAuthState()` with overloads:

```kotlin
suspend fun getCurrentAuthState(): TraktAuthState =
    getCurrentAuthState(currentAuthSession())

private suspend fun getCurrentAuthState(session: TrackingAuthSession): TraktAuthState =
    traktAuthDataStore.stateForProfile(session.profileId).first()
```

Update operations:

```kotlin
suspend fun pollDeviceToken(): TraktTokenPollResult {
    if (!hasRequiredCredentials()) return TraktTokenPollResult.Failed("Missing TRAKT credentials")

    val session = currentAuthSession()
    val state = getCurrentAuthState(session)
    val deviceCode = state.deviceCode
    if (deviceCode.isNullOrBlank()) return TraktTokenPollResult.Failed("No active Trakt device code")
    ...
    traktAuthDataStore.saveToken(tokenBody, profileId = session.profileId)
    traktAuthDataStore.clearDeviceFlow(session.profileId)
    val user = fetchUserSettings(session)
    return TraktTokenPollResult.Approved(user)
}
```

Add session overload:

```kotlin
private suspend fun fetchUserSettings(session: TrackingAuthSession): String? {
    val response = executeAuthorizedRequest(session) { authHeader ->
        traktApi.getUserSettings(authorization = authHeader)
    } ?: return null
    if (!response.isSuccessful) return null
    val username = response.body()?.user?.username
    val slug = response.body()?.user?.ids?.slug
    traktAuthDataStore.saveUser(username = username, userSlug = slug, profileId = session.profileId)
    return username
}
```

Update authorized request:

```kotlin
suspend fun <T> executeAuthorizedRequest(
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? = executeAuthorizedRequest(currentAuthSession(), call)

private suspend fun <T> executeAuthorizedRequest(
    session: TrackingAuthSession,
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? {
    var token = getValidAccessToken(session) ?: return null
    ...
}
```

Add:

```kotlin
private suspend fun getValidAccessToken(session: TrackingAuthSession): String? {
    val state = getCurrentAuthState(session)
    if (!isTokenExpiredOrExpiring(state)) return state.accessToken
    return if (refreshTokenIfNeeded(session, force = true)) {
        getCurrentAuthState(session).accessToken
    } else {
        null
    }
}

private suspend fun refreshTokenIfNeeded(
    session: TrackingAuthSession,
    force: Boolean = false
): Boolean { ... }
```

Keep public `refreshTokenIfNeeded(force)` as:

```kotlin
suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean =
    refreshTokenIfNeeded(currentAuthSession(), force)
```

- [ ] **Step 5: Refactor SimklAuthService**

Apply the same pattern:

```kotlin
private fun currentAuthSession(): TrackingAuthSession { ... TrackingProvider.SIMKL ... }

suspend fun getCurrentAuthState(): SimklAuthState =
    getCurrentAuthState(currentAuthSession())

private suspend fun getCurrentAuthState(session: TrackingAuthSession): SimklAuthState =
    simklAuthDataStore.stateForProfile(session.profileId).first()

private suspend fun fetchUserSettings(session: TrackingAuthSession): String? { ... saveUser(profileId = session.profileId) ... }

suspend fun <T> executeAuthorizedRequest(
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? = executeAuthorizedRequest(currentAuthSession(), call)

private suspend fun <T> executeAuthorizedRequest(
    session: TrackingAuthSession,
    call: suspend (authorizationHeader: String) -> Response<T>
): Response<T>? {
    val token = getCurrentAuthState(session).accessToken ?: return null
    return try {
        requestGate.acquire { call("Bearer $token") }
    } catch (e: IOException) {
        Log.w("SimklAuthService", "Network error during authorized request", e)
        null
    }
}
```

- [ ] **Step 6: Add behavioral Trakt test**

Add to `TraktAuthServiceTest.kt`:

```kotlin
@Test
fun `poll token saves token and user to same captured profile after profile switch`() = runTest {
    val activeProfileId = MutableStateFlow(2)
    val profileManager = testProfileManager(activeProfileId)
    val traktApi = mockk<TraktApi>()
    val store = TraktAuthDataStore(profileDataStoreFactoryForTest(), profileManager)
    store.saveDeviceFlow(
        TraktDeviceCodeResponseDto(
            deviceCode = "device",
            userCode = "user",
            verificationUrl = "https://trakt.tv/activate",
            expiresIn = 600,
            interval = 5
        ),
        profileId = 2
    )
    coEvery { traktApi.requestDeviceToken(any()) } answers {
        activeProfileId.value = 3
        Response.success(
            TraktTokenResponseDto(
                accessToken = "access",
                tokenType = "Bearer",
                expiresIn = 3600,
                refreshToken = "refresh",
                createdAt = 1L
            )
        )
    }
    coEvery { traktApi.getUserSettings(any()) } returns Response.success(
        TraktSettingsResponseDto(user = TraktUserDto(username = "profile-two-user", ids = TraktUserIdsDto(slug = "profile-two")))
    )
    val service = spyk(TraktAuthService(traktApi, store, TraktRequestGate(), profileManager, ProfileModeRouter(), ProfileBoundary(profileManager) { "en" }))
    every { service.hasRequiredCredentials() } returns true

    service.pollDeviceToken()

    assertEquals("profile-two-user", store.stateForProfile(2).first().username)
    assertEquals(null, store.stateForProfile(3).first().username)
}
```

Use exact DTO constructors from current source. If names differ, inspect `app/src/main/java/com/nexio/tv/data/remote/dto/trakt`.

- [ ] **Step 7: Add behavioral SIMKL test**

Create or update `app/src/test/java/com/nexio/tv/data/repository/SimklAuthServiceTest.kt` with equivalent profile-switch-during-poll test. Use current SIMKL DTO constructors from `app/src/main/java/com/nexio/tv/data/remote/dto/simkl`.

- [ ] **Step 8: Run tests**

```bash
./gradlew testArm64DebugUnitTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest \
  --tests com.nexio.tv.data.repository.TraktAuthServiceTest \
  --tests com.nexio.tv.data.repository.SimklAuthServiceTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt \
  app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt \
  app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt \
  app/src/test/java/com/nexio/tv/data/repository/TraktAuthServiceTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/SimklAuthServiceTest.kt \
  app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "refactor(profile): capture tracking auth sessions"
```

---

## Task 5: Upgrade CatalogPlan to Emit Typed Planned Rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt`

- [ ] **Step 1: Write failing test for descriptor/synthetic parity**

Add to `CatalogPlanTest.kt`:

```kotlin
@Test
fun `planned simkl rail builds matching loading descriptor and populated row`() {
    val plan = buildConfiguredCatalogPlan(
        addons = emptyList(),
        disabledHomeCatalogKeys = emptySet(),
        availableAddonOrderKeys = emptySet(),
        traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
        traktSnapshot = TraktDiscoverySnapshot(),
        hasTraktUpNextItems = false,
        simklPrefs = SimklCatalogPreferences(
            enabledCatalogs = setOf(SimklCatalogIds.MOVIE_TRENDING_MONTH),
            catalogOrder = listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH)
        ),
        simklSnapshot = SimklDiscoverySnapshot(
            itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_MONTH to listOf(sampleItem()))
        ),
        mdbPrefs = MDBListCatalogPreferences(),
        mdbSnapshot = MDBListDiscoverySnapshot()
    )

    val rail = plan.rails.single()
    val loading = rail.toLoadingCatalogRow()
    val populated = rail.toPopulatedRows().single()

    assertEquals(loading.addonId, populated.addonId)
    assertEquals(loading.catalogId, populated.catalogId)
    assertEquals(loading.catalogName, populated.catalogName)
    assertEquals(false, populated.isLoading)
    assertEquals(1, populated.items.size)
}
```

- [ ] **Step 2: Run failing test**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --no-daemon
```

Expected: FAIL because `rails`, `toLoadingCatalogRow`, and `toPopulatedRows` are not defined.

- [ ] **Step 3: Add planned rail model**

In `CatalogPlan.kt`, add:

```kotlin
internal sealed interface PlannedCatalogRail {
    val orderKey: String
    val descriptor: ConfiguredHomeCatalogDescriptor
    val items: List<MetaPreview>

    fun toLoadingCatalogRow(): CatalogRow = descriptor.toLoadingCatalogRow()

    fun toPopulatedRows(): List<CatalogRow> {
        if (items.isEmpty()) return emptyList()
        return listOf(
            CatalogRow(
                addonId = descriptor.addonId,
                addonName = descriptor.addonName,
                addonBaseUrl = descriptor.addonBaseUrl,
                catalogId = descriptor.catalogId,
                catalogName = descriptor.catalogName,
                type = descriptor.type,
                rawType = descriptor.rawType,
                items = items,
                isLoading = false,
                hasMore = false,
                supportsSkip = false
            )
        )
    }
}

internal data class BuiltInPlannedCatalogRail(
    override val orderKey: String,
    override val descriptor: ConfiguredHomeCatalogDescriptor,
    override val items: List<MetaPreview>
) : PlannedCatalogRail
```

Update `CatalogPlan`:

```kotlin
internal data class CatalogPlan(
    val expectedOrderKeys: List<String>,
    val publishableOrderKeys: List<String>,
    val descriptors: List<ConfiguredHomeCatalogDescriptor>,
    val rails: List<PlannedCatalogRail>
)
```

- [ ] **Step 4: Build rails in CatalogPlan**

Add helpers:

```kotlin
private fun itemsForTraktKey(key: String, snapshot: TraktDiscoverySnapshot, upNextItems: List<MetaPreview>): List<MetaPreview> =
    when (key) {
        TraktCatalogIds.UP_NEXT -> upNextItems
        TraktCatalogIds.TRENDING_MOVIES -> snapshot.trendingMovieItems
        TraktCatalogIds.TRENDING_SHOWS -> snapshot.trendingShowItems
        TraktCatalogIds.POPULAR_MOVIES -> snapshot.popularMovieItems
        TraktCatalogIds.POPULAR_SHOWS -> snapshot.popularShowItems
        TraktCatalogIds.RECOMMENDED_MOVIES -> snapshot.recommendationMovieItems
        TraktCatalogIds.RECOMMENDED_SHOWS -> snapshot.recommendationShowItems
        TraktCatalogIds.CALENDAR -> snapshot.calendarItems
        else -> snapshot.customListCatalogs.firstOrNull { it.key == key }?.items.orEmpty()
    }

private fun itemsForSimklKey(key: String, snapshot: SimklDiscoverySnapshot): List<MetaPreview> =
    snapshot.itemsByCatalog[key].orEmpty()
```

Add `traktUpNextItems: List<MetaPreview> = emptyList()` parameter to `buildConfiguredCatalogPlan`.

Build rails:

```kotlin
val descriptorByKey = descriptors.associateBy { it.orderKey }
val rails = publishableOrderKeys.mapNotNull { key ->
    val descriptor = descriptorByKey[key] ?: return@mapNotNull null
    val items = when (descriptor.addonId) {
        TRAKT_HOME_ADDON_ID -> itemsForTraktKey(key, traktSnapshot, traktUpNextItems)
        SIMKL_HOME_ADDON_ID -> itemsForSimklKey(key, simklSnapshot)
        else -> emptyList()
    }
    BuiltInPlannedCatalogRail(orderKey = key, descriptor = descriptor, items = items)
}
```

- [ ] **Step 5: Use planned rails for synthetic Trakt and SIMKL rows**

In `HomeViewModelCatalogPipeline.kt`, replace `buildSyntheticTraktRows` and `buildSyntheticSimklRows` call sites with plan rail conversion.

For Trakt renewal:

```kotlin
val plan = buildConfiguredCatalogPlan(
    addons = emptyList(),
    disabledHomeCatalogKeys = emptySet(),
    availableAddonOrderKeys = emptySet(),
    traktPrefs = traktPrefsSnapshot,
    traktSnapshot = snapshot,
    hasTraktUpNextItems = traktUpNextItems.isNotEmpty(),
    traktUpNextItems = traktUpNextItems,
    simklPrefs = SimklCatalogPreferences(),
    simklSnapshot = SimklDiscoverySnapshot(),
    mdbPrefs = MDBListCatalogPreferences(),
    mdbSnapshot = MDBListDiscoverySnapshot()
)
val liveGroups = plan.rails
    .filter { it.descriptor.addonId == TRAKT_HOME_ADDON_ID }
    .mapNotNull { rail ->
        val rows = rail.toPopulatedRows()
        if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
    }
```

For SIMKL renewal:

```kotlin
val plan = buildConfiguredCatalogPlan(
    addons = emptyList(),
    disabledHomeCatalogKeys = emptySet(),
    availableAddonOrderKeys = emptySet(),
    traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    traktSnapshot = TraktDiscoverySnapshot(),
    hasTraktUpNextItems = false,
    simklPrefs = simklPrefsSnapshot,
    simklSnapshot = snapshot,
    mdbPrefs = MDBListCatalogPreferences(),
    mdbSnapshot = MDBListDiscoverySnapshot()
)
val liveGroups = plan.rails
    .filter { it.descriptor.addonId == SIMKL_HOME_ADDON_ID }
    .mapNotNull { rail ->
        val rows = rail.toPopulatedRows()
        if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
    }
```

Keep MDBList builder unchanged in this task because MDBList custom lists can emit multiple rows per order key and need a separate follow-up if the data model changes.

- [ ] **Step 6: Run tests**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Run compile**

```bash
./gradlew compileArm64DebugKotlin -x lint --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt
git commit -m "refactor(profile): build synthetic rows from catalog plan"
```

---

## Task 6: Document Home Session Generation Ownership

**Files:**
- Modify: `docs/architecture/profile-settings-scope.md`
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

- [ ] **Step 1: Write failing doc contract**

Add to `ProfileSettingsScopeContractTest.kt`:

```kotlin
@Test
fun `home session generation ownership is documented`() {
    val text = doc()
    assertTrue(text.contains("HomeProfileSession owns Home UI-session generation"))
    assertTrue(text.contains("ProfileBoundary owns secondary profile route decisions"))
}
```

- [ ] **Step 2: Run failing test**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: FAIL because the doc does not state the split.

- [ ] **Step 3: Update architecture doc**

Add to `docs/architecture/profile-settings-scope.md` under `## Required Guards`:

```markdown
- `HomeProfileSession` owns Home UI-session generation and stale UI update rejection.
- `ProfileBoundary` owns secondary profile route decisions for auth/settings/cache ownership, not profile 1/default routing.
```

- [ ] **Step 4: Run contract test**

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -u docs/architecture/profile-settings-scope.md app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt
git commit -m "docs(profile): clarify home session generation owner"
```

---

## Task 7: Final Verification

**Files:**
- No implementation files.

- [ ] **Step 1: Run focused Android tests**

```bash
./gradlew testArm64DebugUnitTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest \
  --tests com.nexio.tv.ui.screens.home.CatalogPlanTest \
  --tests com.nexio.tv.data.local.TraktDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.SimklDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.local.MDBListDiscoverySnapshotStoreTest \
  --tests com.nexio.tv.data.repository.TraktAuthServiceTest \
  --tests com.nexio.tv.data.repository.SimklAuthServiceTest \
  --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run Android compile**

```bash
./gradlew compileArm64DebugKotlin -x lint --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run web profile contract tests**

```bash
cd nexio-web
npx tsx --test tests/profile-settings-blob.test.ts
```

Expected: all tests pass.

- [ ] **Step 4: Document known web typecheck status**

Run:

```bash
cd nexio-web
npx vue-tsc --noEmit
```

Expected: this may fail on pre-existing repo-wide issues unrelated to this plan, including `formatter.tsx` external imports and `.ts` import extension settings. If it fails, copy the first 10 error lines into the final handoff and do not claim full web typecheck passes.

- [ ] **Step 5: Confirm migration status**

Run:

```bash
supabase migration list
```

Expected: migration `20260415000100_enforce_secondary_profile_settings` appears in remote history. If the CLI output uses a table, record the exact row in the handoff.

- [ ] **Step 6: Final git status**

Run:

```bash
git status --short --branch
git -C nexio-web status --short --branch
```

Expected: only unrelated TVDB or `.omc` files remain dirty. Do not include unrelated files in profile-boundary commits.

---

## Self-Review

- **Spec coverage:** This plan maps each architecture review finding to a task:
  - Finding 1: Task 1.
  - Finding 2: Tasks 2 and 3.
  - Finding 3: Task 4.
  - Finding 4: Task 5.
  - Finding 5: Task 6.
- **Placeholder scan:** No `TBD`, `TODO`, “similar to”, or unscoped “add tests” steps remain. Each task includes concrete files, code shape, commands, expected outcomes, and commit commands.
- **Type consistency:** `HomeProfileSession`, `TrackingAuthSession`, `CatalogPlan`, `PlannedCatalogRail`, `profileId`, and `generation` names are consistent across task definitions and code snippets.
