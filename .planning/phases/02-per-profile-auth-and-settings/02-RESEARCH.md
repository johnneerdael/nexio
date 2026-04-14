# Phase 2: Per-Profile Auth and Settings - Research

**Researched:** 2026-04-14
**Domain:** Android / Kotlin — Jetpack DataStore migration, flatMapLatest reactive switching, Hilt DI consumer updates
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01:** 8 DataStores are per-profile (migrated to factory pattern): `TraktAuthDataStore`, `SimklAuthDataStore`, `TraktSettingsDataStore`, `SimklSettingsDataStore`, `PlayerSettingsDataStore`, `LayoutPreferenceDataStore`, `ThemeDataStore`, `SearchHistoryDataStore`.

**D-02:** 18 DataStores remain shared singletons: all debrid stores (RealDebrid, Premiumize, EasyDebrid, TorBox), all API integration stores (Tmdb, Omdb, MDBList, Imdb, PosterRatings/RPDB, SubtitleTranslation), AnimeSkipSettings, TrailerSettings, YouTubeTrailerAuth, StreamLinkCache, AndroidTvRecommendations, AppOnboarding, DebugSettings.

**D-03:** SearchHistoryDataStore is per-profile despite not being in the original "7 DataStores" requirement — each profile should have their own search history for privacy.

**D-04:** Stop playback immediately when the user switches profiles. Clean cut — stop player, return to home screen. Prevents scrobbling to wrong account.

**D-05:** In-flight Trakt/Simkl sync operations complete using the original profile's tokens. No data leaks between profiles. New profile's sync starts fresh after switch.

**D-06:** Profile switching is inline via sidebar menu (not a full-screen selector). Quick, no screen transition. Matches NuvioTV pattern. (Note: sidebar UI itself is Phase 3 scope; Phase 2 provides the `ProfileManager.switchProfile()` method.)

**D-07:** Non-default profiles do NOT see shared setting sections in the Settings UI at all. Hidden entirely — clean UI, no confusion. Settings screen only shows what the profile can actually change.

**D-08:** Shared settings are readable from any profile in code. Only the Settings UI restricts editing to the default profile. Player, sync, and service logic reads shared settings (debrid tokens, API keys) without profile checks.

**D-09:** Auth stores first, then settings stores. Migrate the hardest case first to validate the pattern, then apply to simpler stores.

**D-10:** Group auth + settings per service: TraktAuth + TraktSettings together as one unit, SimklAuth + SimklSettings together as one unit. These are tightly coupled.

**D-11:** Update consuming services (TraktAuthService, SimklAuthService, ViewModels, etc.) inline with their DataStore migration. The store API change won't compile without updating consumers anyway.

### Claude's Discretion

- Internal implementation of `flatMapLatest` wiring per store (exact Flow chain structure)
- Order of the 4 remaining settings stores after auth migration (PlayerSettings, LayoutPreference, Theme, SearchHistory)
- Error handling when a profile's DataStore file is corrupted or missing
- Whether to batch the 4 settings store migrations into one plan or split into separate plans

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | User can link a unique Trakt account per profile | `TraktAuthDataStore` migration to factory pattern; `flatMapLatest(activeProfileId)` scopes all token reads/writes to active profile |
| AUTH-02 | Trakt scrobbles, library sync, and watch progress are scoped to the active profile | Once `TraktAuthDataStore.state` is profile-reactive, all downstream consumers (`TraktAuthService`, `TraktScrobbleService`, `TraktProgressService`) automatically use the active profile's tokens without code changes |
| AUTH-03 | User can link a unique Simkl account per profile | `SimklAuthDataStore` migration — identical pattern to `TraktAuthDataStore` |
| AUTH-04 | Simkl sync is scoped to the active profile | `SimklAuthDataStore.state` becomes profile-reactive; `SimklAuthService` reads flow automatically |
| AUTH-05 | Shared settings (addons, debrid, TMDB, MDBList, IMDB, OMDB, auto-translate, top-posters, RPDB) are configurable only from default profile | `ProfileManager.isPrimaryProfileActive` gates Settings UI sections; shared DataStores not migrated; Settings screen conditionally hides shared sections for non-primary profiles |
| AUTH-06 | Per-profile settings (language, theme, player, catalog order) persist across profile switches | 6 settings DataStores migrated to factory pattern; `flatMapLatest` switching ensures profile switch emits new profile's data to all observers |
</phase_requirements>

---

## Summary

Phase 2 is a pure migration phase: no new features, no new DataStore schemas, no new UI screens. The work is migrating 8 existing singleton DataStores to the `ProfileDataStoreFactory` pattern built in Phase 1, wiring each store's reactive flows with `flatMapLatest(activeProfileId)`, and updating all compile-time consumers inline. Phase 3 handles the UI for profile switching; Phase 2 delivers the data layer contract that Phase 3 will rely on.

The NuvioTV reference implementation provides a battle-tested template for every migration in this phase. `TraktAuthDataStore` in NuvioTV is a direct, line-for-line model for `TraktAuthDataStore` in Nexio: replace `@ApplicationContext context: Context` with `factory: ProfileDataStoreFactory, profileManager: ProfileManager`; remove the `private val Context.traktAuthDataStore by preferencesDataStore(...)` top-level delegate; add a `private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)` helper; and wrap each flow property in `profileManager.activeProfileId.flatMapLatest { profileId -> store(profileId).data.map { ... } }`. The pattern is identical for all 8 stores.

The most complex integration point is `AccountSettingsSyncService`. It directly observes `traktAuthDataStore.state`, `simklAuthDataStore.state`, `traktSettingsDataStore.catalogPreferences`, `simklSettingsDataStore.catalogPreferences`, `layoutPreferenceDataStore.*`, and `playerSettingsDataStore.playerSettings` — 6 of the 8 stores being migrated. Once those stores are profile-reactive, the sync service automatically observes the active profile's data without any changes to `AccountSettingsSyncService` itself. This is the correct behavior: the v7 sync always pushes/pulls the active profile's state.

The playback-stop-on-switch requirement (D-04) is the only piece that does not follow the DataStore migration pattern. It requires `ProfileManager.setActiveProfile()` to broadcast a navigation event (or call a player controller method) that the player screen observes. The player stack exposes `PlayerRuntimeControllerBackend` and `PlayerRuntimeControllerPlaybackEvents` — Phase 2 needs to hook into these to fulfill D-04.

**Primary recommendation:** Migrate stores in two waves: (1) TraktAuth + TraktSettings + SimklAuth + SimklSettings as one compilation unit — these four are linked by the AccountSettingsSyncService v7 payload; (2) PlayerSettings + LayoutPreference + Theme + SearchHistory as the second wave. Settings UI gating (`isPrimaryProfileActive`) is a lightweight conditional on top of the already-injected `ProfileManager`. All changes compile in isolation per store because Hilt wiring propagates automatically.

---

## Standard Stack

No new library dependencies are required for Phase 2. All work uses libraries already in Nexio's dependency graph.

### Core (unchanged from Phase 1)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `androidx.datastore:datastore-preferences` | 1.1.1 | Per-profile DataStore instances | Already in project; factory wraps it |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.8.1 | `flatMapLatest`, `StateFlow`, `Flow` | Already in project; `flatMapLatest` is the reactive switching mechanism |
| `com.google.dagger:hilt-android` | 2.58 | `@Singleton` + `@Inject constructor` wiring | Already in project; no new modules needed |

### Migration-Specific Requirement: `@OptIn(ExperimentalCoroutinesApi::class)`

`flatMapLatest` is in `kotlinx.coroutines.ExperimentalCoroutinesApi`. Every migrated DataStore class needs `@OptIn(ExperimentalCoroutinesApi::class)` at the class level. This is stable in practice — NuvioTV ships it in production — but the opt-in annotation is mandatory or the build will emit warnings/errors depending on project `kotlinOptions` settings.

[VERIFIED: direct inspection of NuvioTV TraktAuthDataStore.kt — `@OptIn(ExperimentalCoroutinesApi::class)` is present on the class]

---

## Architecture Patterns

### Pattern 1: DataStore Singleton-to-Factory Migration (the core pattern for all 8 stores)

**What:** Remove the top-level `private val Context.xyzDataStore by preferencesDataStore(name = "feature_name")` delegate. Replace `@ApplicationContext context: Context` in the constructor with `factory: ProfileDataStoreFactory, profileManager: ProfileManager`. Add a private `store()` helper that resolves the correct DataStore instance for the current active profile. Wrap all `Flow` properties in `flatMapLatest`.

**When to use:** Every one of the 8 per-profile DataStores in D-01.

**The exact migration transform** (verified against NuvioTV source, applicable to all 8 stores):

```kotlin
// BEFORE (Nexio current pattern):
private val Context.traktAuthDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "trakt_auth_store")

@Singleton
class TraktAuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val state: Flow<TraktAuthState> = context.traktAuthDataStore.data.map { prefs ->
        TraktAuthState(...)
    }

    suspend fun saveToken(token: TraktTokenResponseDto) {
        context.traktAuthDataStore.edit { ... }
    }
}

// AFTER (NuvioTV pattern, port directly):
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trakt_auth_store"  // same string as before — preserves disk files
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs -> TraktAuthState(...) }
    }

    suspend fun saveToken(token: TraktTokenResponseDto) {
        store().edit { prefs -> ... }  // store() with no arg uses activeProfileId.value
    }
}
```

[VERIFIED: direct inspection of NuvioTV `TraktAuthDataStore.kt`]

### Pattern 2: FEATURE constant = existing file name (zero disk migration)

The `FEATURE` constant in each migrated store must equal the existing `preferencesDataStore(name = "...")` string exactly. The factory maps profile 1 to the bare feature name, so Profile 1 continues reading the same `.preferences_pb` file it had before migration. No data migration scripts are needed.

| DataStore | Existing delegate name | FEATURE constant |
|-----------|----------------------|-----------------|
| `TraktAuthDataStore` | `"trakt_auth_store"` | `"trakt_auth_store"` |
| `SimklAuthDataStore` | `"simkl_auth_store"` | `"simkl_auth_store"` |
| `TraktSettingsDataStore` | `"trakt_settings"` | `"trakt_settings"` |
| `SimklSettingsDataStore` | `"simkl_settings"` | `"simkl_settings"` |
| `PlayerSettingsDataStore` | `"player_settings"` | `"player_settings"` |
| `LayoutPreferenceDataStore` | `"layout_settings"` | `"layout_settings"` |
| `ThemeDataStore` | `"theme_settings"` | `"theme_settings"` |
| `SearchHistoryDataStore` | `"search_history"` | `"search_history"` |

[VERIFIED: direct inspection of all 8 Nexio DataStore files]

### Pattern 3: Write path uses `store()` with no argument (synchronous activeProfileId read)

All `suspend fun` write methods call `store()` (no `profileId` arg), which internally calls `profileManager.activeProfileId.value` — a synchronous `StateFlow` read. This is correct: the write goes to whichever profile is active at the moment the write executes. No `flatMapLatest` is needed for writes.

```kotlin
// Source: NuvioTV TraktAuthDataStore.kt
suspend fun clearAuth() {
    store().edit { preferences ->    // store() resolves profileManager.activeProfileId.value at call time
        preferences.remove(accessTokenKey)
        // ...
    }
}
```

[VERIFIED: NuvioTV TraktAuthDataStore.kt — all suspend write functions use `store()` with no explicit profileId]

### Pattern 4: TraktSettingsDataStore — two flatMapLatest styles are both valid

NuvioTV's `TraktSettingsDataStore` exposes each Flow property individually with its own `flatMapLatest`:

```kotlin
// Source: NuvioTV TraktSettingsDataStore.kt
val continueWatchingDaysCap: Flow<Int> = profileManager.activeProfileId.flatMapLatest { pid ->
    factory.get(pid, FEATURE).data.map { prefs -> ... }
}
```

An alternative is a single `flatMapLatest` that produces a full state object (as `TraktAuthDataStore.state` does). For stores with many individual Flow properties (TraktSettings, LayoutPreference, PlayerSettings), the per-property approach matches the NuvioTV reference and avoids introducing a large intermediate state class.

[VERIFIED: NuvioTV TraktSettingsDataStore.kt]

### Pattern 5: LayoutPreferenceDataStore `init` block migration

`LayoutPreferenceDataStore` has an `init` block that applies migrations (sidebar collapsed default, hide unreleased default) by calling `store().edit { ... }`. After migration, `store()` resolves to `factory.get(profileManager.activeProfileId.value, FEATURE)`. This is safe: `init` runs once at injection time when the Singleton is first created, and `profileManager.activeProfileId` is already populated (it uses `SharingStarted.Eagerly` with an initial value of 1). The migration writes to the correct profile 1 DataStore.

**Risk:** If a profile is switched before `LayoutPreferenceDataStore` is first injected — which cannot happen because the Singleton is created at app start — this would be a concern. In practice the Singleton `init` runs before any profile switch is possible. No change needed to the migration logic.

[VERIFIED: direct inspection of Nexio `LayoutPreferenceDataStore.kt` — init block uses `store().edit { ... }` pattern]

### Pattern 6: SearchHistoryDataStore — secondary constructor must be updated

`SearchHistoryDataStore` uses a dual-constructor pattern: the primary constructor takes `DataStore<Preferences>` directly (for testing), and the `@Inject` constructor calls the primary with `context.searchHistoryDataStore`. After migration, the `@Inject` constructor is replaced with factory injection. The primary `(DataStore<Preferences>)` constructor is preserved for testing.

```kotlin
// AFTER migration — primary constructor unchanged for tests:
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryDataStore internal constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val gson: Gson = Gson()
) {
    companion object { private const val FEATURE = "search_history" }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val recentSearches: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs -> decodeSearchHistory(prefs[recentSearchesKey]) }
    }
    // ...
}
```

The existing `SearchHistoryDataStoreTest` injects a `DataStore<Preferences>` directly. After migration, the test constructor must pass a fake factory or the test must be updated to use the factory pattern. See Wave 0 Gaps.

[VERIFIED: direct inspection of Nexio `SearchHistoryDataStore.kt` and `SearchHistoryDataStoreTest.kt`]

### Pattern 7: AccountSettingsSyncService — no changes needed

`AccountSettingsSyncService` directly injects `TraktAuthDataStore`, `SimklAuthDataStore`, `TraktSettingsDataStore`, `SimklSettingsDataStore`, `LayoutPreferenceDataStore`, `PlayerSettingsDataStore`, and `ThemeDataStore`. After those stores become profile-reactive, the sync service automatically observes the active profile's data through the unchanged flow references. No code changes to `AccountSettingsSyncService` are required.

The `applyAccountConfigSyncSettings` function writes back to `traktSettingsDataStore`, `simklSettingsDataStore`, `playerSettingsDataStore`, `layoutPreferenceDataStore`, and `themeDataStore`. These writes use the `suspend fun` write methods which call `store()` → `activeProfileId.value`. After migration, writes land in the active profile's DataStore file. This is correct behavior.

[VERIFIED: direct inspection of AccountSettingsSyncService.kt import list and observeAccountConfigSyncChangedPaths call site]

### Pattern 8: Settings UI gating with `isPrimaryProfileActive`

`ProfileManager.isPrimaryProfileActive: Boolean` (already in NuvioTV, will be present after Phase 1) returns `activeProfileId.value == 1`. Settings screens inject `ProfileManager` via their ViewModel and conditionally hide shared settings sections.

```kotlin
// In SettingsViewModel or TraktViewModel:
val isPrimaryProfile: StateFlow<Boolean> = profileManager.activeProfileId
    .map { it == 1 }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)
```

The UI then gates sections:

```kotlin
// In Settings composable:
if (isPrimaryProfile) {
    DebridSettingsSection()
    ApiIntegrationsSection()
}
```

[ASSUMED] The exact ViewModel and composable structure for settings gating is Claude's discretion per CONTEXT.md. The pattern above is the recommended approach — matches NuvioTV where `isPrimaryProfileActive` is a computed property on `ProfileManager`.

### Pattern 9: Playback stop on profile switch

D-04 requires stopping playback when `ProfileManager.setActiveProfile()` is called while the player is running. The Nexio player uses `PlayerRuntimeControllerBackend` and `PlayerRuntimeControllerPlaybackEvents` (verified to exist). The recommended approach: emit a one-shot event from `ProfileManager` or from the profile switch call site that the player ViewModel observes.

**Option A (recommended):** `ProfileManager` exposes a `SharedFlow<Unit> profileSwitched` that emits on every `setActiveProfile` call. The player ViewModel collects this and calls `stopPlayback()` + navigates back to home.

**Option B:** The call site that triggers profile switching (will be the sidebar ViewModel in Phase 3) also calls `playerController.stop()` before calling `ProfileManager.setActiveProfile()`. Simpler but creates a coupling between the sidebar and the player.

[ASSUMED] The exact mechanism is Claude's discretion. Option A is recommended because it keeps `ProfileManager` as the single source of truth and does not require the sidebar to know about the player.

### Anti-Patterns to Avoid

- **Leave the `preferencesDataStore` delegate in place while adding factory access.** Two DataStore instances for the same file = `IllegalStateException` at runtime. Remove the delegate entirely.
- **Store a `val dataStore = context.xyzDataStore` field referencing a fixed file.** Any field that captures the DataStore at construction time breaks profile switching. `store()` must be called at call time, not stored.
- **Use `profileManager.activeProfileId.value` inside a `.map {}` on an existing flow.** This captures the profile id at subscription time. Use `flatMapLatest` to react to profile changes.
- **Apply the factory to shared stores** (debrid, API integrations). See D-02. These stay as-is.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Per-profile DataStore file routing | Custom file naming logic | `ProfileDataStoreFactory.get(profileId, featureName)` from Phase 1 | Factory handles the bare-name vs. `_p{id}` convention, thread-safety, and deleted-profile re-creation |
| Reactive profile switching in flows | Manual subscription management | `flatMapLatest(activeProfileId)` | Kotlin coroutines handles subscription cancellation and re-subscription on each profile change automatically |
| Profile identity check in UI | Custom profile ID comparisons | `ProfileManager.isPrimaryProfileActive` | Centralized, always in sync with `activeProfileId` StateFlow |

---

## Runtime State Inventory

Phase 2 is a code migration (not a rename or rebrand), so runtime state inventory is not triggered in full. However, one on-disk concern is relevant:

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | Profile 1 DataStore files: `trakt_auth_store.preferences_pb`, `simkl_auth_store.preferences_pb`, `trakt_settings.preferences_pb`, `simkl_settings.preferences_pb`, `player_settings.preferences_pb`, `layout_settings.preferences_pb`, `theme_settings.preferences_pb`, `search_history.preferences_pb` | None — factory preserves bare filenames for profile 1. Zero migration. |
| Live service config | None — no external services store DataStore file names | None |
| OS-registered state | None | None |
| Secrets/env vars | None | None |
| Build artifacts | None | None |

---

## Common Pitfalls

### Pitfall 1: Dual DataStore instances for the same file name

**What goes wrong:** Leaving the `private val Context.xyzDataStore by preferencesDataStore(name = "...")` top-level declaration while also calling `PreferenceDataStoreFactory.create` (via the factory) for the same file name causes `IllegalStateException: There are multiple DataStores active for the same file` at runtime. This crash is silent until the second profile's DataStore is first accessed.

**Why it happens:** DataStore enforces a one-instance-per-file invariant at the process level via an internal singleton registry.

**How to avoid:** Remove the top-level delegate entirely when migrating a store. The factory becomes the sole access point.

**Warning signs:** `IllegalStateException: There are multiple DataStores active for the same file` in logcat.

[VERIFIED: PITFALLS.md Pitfall 1 — direct inspection of Nexio codebase]

### Pitfall 2: `@Singleton` `init` blocks and `CoroutineScope` fields capturing a fixed DataStore reference

**What goes wrong:** `LayoutPreferenceDataStore` has both an `init` block and a `private val ioScope` that are used for one-shot migration writes. If the migration is done by storing `private val dataStore = context.layoutPreferenceDataStore` (as the current code does), and a developer migrates to factory by replacing that field with `private val dataStore = factory.get(1, FEATURE)` — they've hard-coded profile 1 instead of using `store()`.

**Why it happens:** Refactoring `private val dataStore = ...` to `private fun store() = factory.get(...)` is a non-obvious change when the existing code reads `dataStore.edit { ... }` throughout.

**How to avoid:** Replace every `dataStore.edit { ... }` and `dataStore.data.map { ... }` with `store().edit { ... }` and `store(profileId).data.map { ... }`. Search for `dataStore.` (with a dot) in each migrated file to catch all usages.

**Warning signs:** Profile 2 getting Profile 1's layout settings after migration; migrations running on the wrong profile's DataStore.

[VERIFIED: direct inspection of Nexio `LayoutPreferenceDataStore.kt` — uses `private val dataStore = context.layoutPreferenceDataStore` and `private fun store() = dataStore` pattern that must both be replaced]

### Pitfall 3: `AccountSettingsSyncService` push echoing per-profile data cross-profile

**What goes wrong:** After migration, `AccountSettingsSyncService` observes `traktAuthDataStore.state.drop(1)`. When a profile switch occurs, the `flatMapLatest` in `TraktAuthDataStore` emits the new profile's auth state. The sync service sees this as a settings change and triggers a push — potentially overwriting the previous profile's remote data with the new profile's data before the new profile's remote pull has completed.

**Why it happens:** The sync service's `isApplyingRemote` guard only prevents echo-pushes during remote pulls. A profile switch is not a remote pull — it's a local state change that looks like a settings mutation.

**How to avoid:** `AccountSettingsSyncService` should also skip pushes triggered within a brief window (e.g., 500ms) of a profile switch event. The simplest implementation: observe `profileManager.activeProfileId.drop(1)` and set a `recentlySwitchedProfile` flag that suppresses the next push cycle, similar to `isApplyingRemote`. Phase 2's plan should include this guard.

**Warning signs:** After switching from Profile 2 to Profile 1, Profile 1's remote Trakt auth gets overwritten with Profile 2's token.

[VERIFIED: direct inspection of AccountSettingsSyncService.kt observeAccountConfigSyncChangedPaths call — all 6 per-profile stores are in the observer; profile switch emits from all 6 simultaneously]

### Pitfall 4: `TraktViewModel` and settings ViewModels injecting migrated DataStores — constructor changes cascade

**What goes wrong:** `TraktViewModel` injects `TraktAuthDataStore` and `TraktSettingsDataStore`. After migration, these classes' constructors change from `(@ApplicationContext context: Context)` to `(factory: ProfileDataStoreFactory, profileManager: ProfileManager)`. Hilt resolves this automatically — but any Hilt test helper or `@TestInstallIn` module that provides a fake `TraktAuthDataStore` will break if it still provides a `context`-based fake.

**Why it happens:** Hilt test fakes are often written to match the production constructor. After migration, the fake's constructor parameters change too.

**How to avoid:** Per D-11, update consumer code inline with each DataStore migration. For test fakes, update to accept `ProfileDataStoreFactory` and `ProfileManager` fakes (or use the `DataStore<Preferences>` direct constructor approach as `SearchHistoryDataStore` already supports).

**Warning signs:** Hilt test graph fails to compile with "Cannot provide a binding for TraktAuthDataStore" or similar.

[VERIFIED: direct inspection of TraktViewModel.kt — injects both TraktAuthDataStore and TraktSettingsDataStore]

### Pitfall 5: `SearchHistoryDataStore` test constructor incompatibility after migration

**What goes wrong:** `SearchHistoryDataStoreTest` constructs `SearchHistoryDataStore(dataStore)` directly using the internal primary constructor `(DataStore<Preferences>, Gson)`. After migration, the primary constructor changes to `(ProfileDataStoreFactory, ProfileManager, Gson)`. The existing test breaks at compile time.

**Why it happens:** The dual-constructor pattern that makes the store testable becomes invalid when the constructor signature changes.

**How to avoid:** After migrating `SearchHistoryDataStore`, update `SearchHistoryDataStoreTest` to either: (a) use a fake `ProfileDataStoreFactory` that returns a `PreferenceDataStoreFactory.create(...)` backed by a temp file, or (b) test only the pure logic functions (`nextSearchHistory`, `normalizeSearchHistory`) which have no DataStore dependency and already pass without a real store.

[VERIFIED: direct inspection of SearchHistoryDataStoreTest.kt and SearchHistoryDataStore.kt]

---

## Code Examples

### Full migration: SimklAuthDataStore (no NuvioTV reference — must be written from scratch using TraktAuth as the template)

The NuvioTV reference does not include `SimklAuthDataStore.kt` (file not found). Nexio's `SimklAuthDataStore` has a different auth state shape from `TraktAuthDataStore` (no refresh token, `accountId: Long?`, `accountType: String?`). The migration transform is identical in structure; only the keys and state fields differ.

```kotlin
// Source: derived from NuvioTV TraktAuthDataStore.kt pattern + Nexio SimklAuthDataStore.kt fields
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SimklAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "simkl_auth_store"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val usernameKey = stringPreferencesKey("username")
    private val accountIdKey = longPreferencesKey("account_id")
    private val accountTypeKey = stringPreferencesKey("account_type")
    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val state: Flow<SimklAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs ->
            SimklAuthState(
                accessToken = prefs[accessTokenKey],
                username = prefs[usernameKey],
                accountId = prefs[accountIdKey],
                accountType = prefs[accountTypeKey],
                deviceCode = prefs[deviceCodeKey],
                userCode = prefs[userCodeKey],
                verificationUrl = prefs[verificationUrlKey],
                expiresAt = prefs[expiresAtKey],
                pollInterval = prefs[pollIntervalKey]
            )
        }
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }
    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated
    // suspend write functions: same as before but use store().edit { ... }
}
```

### TraktSettingsDataStore — per-property flatMapLatest (NuvioTV verified pattern)

```kotlin
// Source: NuvioTV TraktSettingsDataStore.kt
val continueWatchingDaysCap: Flow<Int> = profileManager.activeProfileId.flatMapLatest { pid ->
    factory.get(pid, FEATURE).data.map { prefs ->
        normalizeContinueWatchingDaysCap(
            prefs[continueWatchingDaysCapKey] ?: DEFAULT_CONTINUE_WATCHING_DAYS_CAP
        )
    }
}

val dismissedNextUpKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
    factory.get(pid, FEATURE).data.map { prefs ->
        prefs[dismissedNextUpKeysKey] ?: emptySet()
    }
}
```

Nexio's `TraktSettingsDataStore` has two additional flow properties that NuvioTV does not: `dismissedRecommendationKeys` and `catalogPreferences`. Apply the same `flatMapLatest` transform to both.

[VERIFIED: NuvioTV TraktSettingsDataStore.kt; Nexio TraktSettingsDataStore.kt]

### ThemeDataStore — simplest migration (2 flows, no migration logic)

```kotlin
// AFTER migration:
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object { private const val FEATURE = "theme_settings" }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val selectedTheme: Flow<AppTheme> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            val name = prefs[themeKey] ?: AppTheme.WHITE.name
            runCatching { AppTheme.valueOf(name) }.getOrDefault(AppTheme.WHITE)
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            val name = prefs[fontKey] ?: AppFont.INTER.name
            runCatching { AppFont.valueOf(name) }.getOrDefault(AppFont.INTER)
        }
    }

    suspend fun setTheme(theme: AppTheme) { store().edit { prefs -> prefs[themeKey] = theme.name } }
    suspend fun setFont(font: AppFont) { store().edit { prefs -> prefs[fontKey] = font.name } }
}
```

[VERIFIED: Nexio ThemeDataStore.kt — current shape; migration is mechanical]

---

## Key Consumers Requiring Inline Updates (per D-11)

The following classes inject one or more of the 8 migrated DataStores. Their constructors do not change (they inject the same class names), but the Hilt graph must recompile. No consumer logic changes are required unless the store's Flow structure changes (it does not — same properties, now profile-reactive).

| Consumer | Migrated Stores Injected | Change Required |
|----------|--------------------------|-----------------|
| `AccountSettingsSyncService` | `TraktAuthDataStore`, `SimklAuthDataStore`, `TraktSettingsDataStore`, `SimklSettingsDataStore`, `LayoutPreferenceDataStore`, `PlayerSettingsDataStore`, `ThemeDataStore` | Add profile-switch push-suppression guard (see Pitfall 3). No other changes. |
| `TraktViewModel` | `TraktAuthDataStore`, `TraktSettingsDataStore` | No logic changes. Recompile confirms Hilt graph. |
| `TraktProgressService` | `TraktSettingsDataStore` | No logic changes. Recompile confirms. |
| `TrackingScrobbleService` | `TraktAuthDataStore` (inferred from architecture) | No logic changes. Profile reactivity flows through `TraktAuthDataStore.state`. |

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Singleton DataStore delegate per feature | Per-profile DataStore factory with `flatMapLatest` switching | This phase | Enables profile isolation without new libraries |
| `@ApplicationContext context: Context` in DataStore constructors | `ProfileDataStoreFactory + ProfileManager` injection | This phase | Profile-reactive data flows |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `ProfileManager.isPrimaryProfileActive` is available from Phase 1 output | Pattern 8 (Settings UI gating) | If Phase 1 did not add this computed property, Phase 2 must add it — trivial fix, `activeProfileId.value == 1` |
| A2 | `AccountSettingsSyncService` does not need a profile-switch push-suppression guard if the v7 sync is being sunset in Phase 4 anyway | Pitfall 3 | If Phase 4 replaces the v7 sync, the guard can be deferred. If not deferred, cross-profile data contamination is a real risk during the Phase 2-4 window |
| A3 | `ProfileManager.setActiveProfile` in Phase 2 can emit a `profileSwitched` SharedFlow without a formal player hook — the player stop can be wired in Phase 3 when the sidebar UI exists | Pattern 9 (Playback stop) | If playback stop must work before Phase 3, Phase 2 must wire the player event now; this requires reading `PlayerRuntimeControllerBackend` stop API |
| A4 | NuvioTV does not include `SimklAuthDataStore.kt` — the migration template is derived by applying the TraktAuth pattern to the Nexio SimklAuth fields | Code Examples | If NuvioTV has a SimklAuthDataStore with different patterns, the derived template may miss Simkl-specific nuances |

---

## Open Questions

1. **Playback stop wiring scope in Phase 2**
   - What we know: D-04 requires stop on profile switch; Phase 3 is UI scope; Phase 2 provides `switchProfile()` method
   - What's unclear: Should the `profileSwitched` SharedFlow emission and player observation be Phase 2 or Phase 3 scope? Phase 2 has no sidebar UI to trigger the switch, so the stop hook cannot be exercised until Phase 3 anyway.
   - Recommendation: Phase 2 adds `val profileSwitched: SharedFlow<Unit>` to `ProfileManager` and emits it in `setActiveProfile()`. Phase 3 wires the player ViewModel to observe it. Document this split explicitly in Phase 2 plans.

2. **`AccountSettingsSyncService` profile-switch push-suppression**
   - What we know: 6 of the 8 migrated stores are observed by AccountSettingsSyncService; profile switch emits from all 6 simultaneously
   - What's unclear: Does the existing `isApplyingRemote` guard already cover this case? Profile switch is not a remote apply, so it does not.
   - Recommendation: Add a short-circuit in `AccountSettingsSyncService` that suppresses a push for 2 seconds after `profileManager.activeProfileId` emits. This is a 5-line change and prevents cross-profile contamination in the Phase 2-4 window.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 2 is purely code changes (DataStore migration). No external CLI tools, databases, or runtimes beyond the standard Android build toolchain are required.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4 + Robolectric (verified in SearchHistoryDataStoreTest.kt, PlayerSettingsDataStoreTest.kt) |
| Config file | `app/build.gradle` — `testOptions.unitTests.includeAndroidResources = true` (inferred from Robolectric usage) |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.*DataStore*"` |
| Full suite command | `./gradlew testArm64DebugUnitTest` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AUTH-01 | TraktAuthDataStore profile isolation — profile 1 token ≠ profile 2 token after migration | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktAuthDataStoreProfileTest"` | ❌ Wave 0 |
| AUTH-02 | `TraktAuthDataStore.state` switches to new profile's data when `activeProfileId` changes | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.TraktAuthDataStoreProfileTest"` | ❌ Wave 0 |
| AUTH-03 | SimklAuthDataStore profile isolation | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.SimklAuthDataStoreProfileTest"` | ❌ Wave 0 |
| AUTH-04 | `SimklAuthDataStore.state` switches on profile change | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.SimklAuthDataStoreProfileTest"` | ❌ Wave 0 |
| AUTH-05 | `isPrimaryProfileActive` is false for profile 2; shared settings section hidden | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ Wave 0 |
| AUTH-06 | Settings DataStores emit new profile's data on switch (spot-check: ThemeDataStore) | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ThemeDataStoreProfileTest"` | ❌ Wave 0 |

### Test Helper Pattern (derived from existing SearchHistoryDataStoreTest)

All profile-isolation tests follow this shape — create two temp-file DataStore instances, wrap them in a fake `ProfileDataStoreFactory`, and swap `activeProfileId` via a `MutableStateFlow`.

```kotlin
// Pattern for TraktAuthDataStoreProfileTest:
@RunWith(RobolectricTestRunner::class)
class TraktAuthDataStoreProfileTest {
    @Test
    fun `state switches to profile 2 token after activeProfileId changes`() = runTest {
        val profile1Store = createTempDataStore(backgroundScope)
        val profile2Store = createTempDataStore(backgroundScope)
        val activeProfileId = MutableStateFlow(1)
        val factory = FakeProfileDataStoreFactory(
            mapOf(1 to profile1Store, 2 to profile2Store)
        )
        val profileManager = FakeProfileManager(activeProfileId)
        val authStore = TraktAuthDataStore(factory, profileManager)

        // Write token to profile 1
        // Switch activeProfileId to 2
        // Assert state emits profile 2's empty auth
    }
}
```

[VERIFIED: SearchHistoryDataStoreTest.kt uses `PreferenceDataStoreFactory.create` with temp files — same pattern extends to profile tests]

### Sampling Rate

- **Per task commit:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.*"`
- **Per wave merge:** `./gradlew testArm64DebugUnitTest`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/data/local/TraktAuthDataStoreProfileTest.kt` — covers AUTH-01, AUTH-02
- [ ] `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt` — covers AUTH-03, AUTH-04
- [ ] `app/src/test/java/com/nexio/tv/data/local/ThemeDataStoreProfileTest.kt` — covers AUTH-06 (spot-check for settings stores)
- [ ] `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` — covers AUTH-05 (`isPrimaryProfileActive`)
- [ ] `FakeProfileDataStoreFactory` and `FakeProfileManager` test helpers — shared fixture for all profile DataStore tests
- [ ] Update `SearchHistoryDataStoreTest.kt` — existing test breaks when primary constructor changes (see Pitfall 5)

---

## Security Domain

Per-profile auth isolation is a security property: tokens written to profile 1's DataStore must not be readable by profile 2, and vice versa. The factory pattern enforces this at the filesystem level — each profile's DataStore is a separate `.preferences_pb` file with no cross-file reads.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Yes | Per-profile token isolation via separate DataStore files; `TraktAuthState.isAuthenticated` scope-checked per profile |
| V3 Session Management | Yes | Profile switch invalidates in-flight requests via `flatMapLatest` unsubscription; playback stopped (D-04) |
| V4 Access Control | Yes | Non-default profiles cannot access shared settings UI (D-07, D-08); `isPrimaryProfileActive` gate |
| V5 Input Validation | No | Phase 2 does not add new input surfaces |
| V6 Cryptography | No | Tokens stored in DataStore (Android Keystore not used here); consistent with existing approach |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Profile 2 reading Profile 1's Trakt token via a stale singleton reference | Information Disclosure | `flatMapLatest` on `activeProfileId` — no stale singleton flow; each profile read uses `factory.get(profileId, FEATURE)` |
| Scrobble to wrong account during profile switch | Tampering | D-04 stops playback; D-05 in-flight operations complete with original token then new profile starts fresh |
| Settings sync push overwriting wrong profile's remote data | Tampering | Profile-switch push-suppression guard in `AccountSettingsSyncService` (see Pitfall 3) |

---

## Sources

### Primary (HIGH confidence)
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktAuthDataStore.kt` — canonical migration pattern
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktSettingsDataStore.kt` — per-property flatMapLatest pattern
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt` — `isPrimaryProfileActive`, `setActiveProfile`, `activeProfileId` StateFlow
- Direct inspection: all 8 Nexio DataStore files (TraktAuth, SimklAuth, TraktSettings, SimklSettings, PlayerSettings, LayoutPreference, Theme, SearchHistory)
- Direct inspection: `AccountSettingsSyncService.kt` (import list + observer call site + apply function) — confirms 6 per-profile stores are observed
- Direct inspection: `TraktViewModel.kt` — confirms TraktAuthDataStore and TraktSettingsDataStore injection
- Direct inspection: `TraktProgressService.kt` — confirms TraktSettingsDataStore injection
- Direct inspection: `.planning/research/ARCHITECTURE.md` — component responsibilities, migration pattern documentation
- Direct inspection: `.planning/research/PITFALLS.md` (first 100 lines) — Pitfalls 1–4 confirmed

### Secondary (MEDIUM confidence)
- `.planning/phases/01-foundation/01-CONTEXT.md` — Phase 1 locked decisions (D-07 bare filenames, D-08 ConcurrentHashMap pattern, D-10 Hilt marker module)
- `.planning/phases/01-foundation/01-RESEARCH.md` — factory architecture, test infrastructure

### Tertiary (LOW confidence)
- None

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new libraries; verified existing versions
- Architecture patterns: HIGH — all derived from direct code inspection of source and reference implementation
- Consumer impact (AccountSettingsSyncService): HIGH — verified by reading full import list and observer wiring
- Pitfalls: HIGH — Pitfalls 1–4 verified from PITFALLS.md + direct code inspection; Pitfall 5 verified from SearchHistoryDataStoreTest.kt
- Playback stop mechanism: LOW — player controller API not fully read; exact stop call site is ASSUMED

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (stable libraries, no fast-moving dependencies)
