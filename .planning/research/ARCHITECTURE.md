# Architecture Research

**Domain:** Android TV streaming app — multi-profile state isolation
**Researched:** 2026-04-14
**Confidence:** HIGH (based on direct code inspection of both Nexio and NuvioTV source)

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          UI Layer                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ProfileSelect │  │SettingsScreen│  │  Existing Feature Screens│  │
│  │  Screen      │  │ (per-profile)│  │  (unchanged navigation)  │  │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬─────────────┘  │
│         └─────────────────┴──────────────────────┘                 │
├─────────────────────────────────────────────────────────────────────┤
│                      ViewModel Layer                                 │
│  ┌──────────────┐  ┌──────────────────────────────────────────────┐ │
│  │ProfileViewModel│ │  Existing ViewModels (observe active profile)│ │
│  │(CRUD, switch) │  │  CatalogOrderViewModel, TraktSettingsVM, etc.│ │
│  └──────┬───────┘  └──────────────────────────────────────────────┘ │
├─────────┼───────────────────────────────────────────────────────────┤
│         │           Domain / Service Layer                           │
│  ┌──────┴───────┐  ┌────────────────┐  ┌──────────────────────────┐ │
│  │ProfileManager│  │TraktAuthService│  │AccountSettingsSyncService│ │
│  │(CRUD, active │  │(uses active    │  │(shared: addons, debrid,  │ │
│  │ profile id)  │  │ profile's DS)  │  │ global integrations)     │ │
│  └──────┬───────┘  └────────────────┘  └──────────────────────────┘ │
│         │                                         ┌──────────────────┐│
│         │                                         │ProfileSettingsSync││
│         │                                         │Service (per-profile││
│         │                                         │ blob push/pull)  ││
│         │                                         └──────────────────┘│
├─────────┼───────────────────────────────────────────────────────────┤
│         │              Data Layer                                    │
│  ┌──────┴────────────┐  ┌───────────────────────────────────────┐   │
│  │ProfileDataStore   │  │     ProfileDataStoreFactory            │   │
│  │(profiles list +   │  │  ConcurrentHashMap<String,DataStore>  │   │
│  │ active profile id)│  │  get(profileId, featureName) →        │   │
│  └───────────────────┘  │  "featureName" (id=1) or             │   │
│                          │  "featureName_p{id}" (id=2..4)       │   │
│                          └───────────────────────────────────────┘   │
│                                                                       │
│  Per-Profile DataStores (resolved via factory at runtime):           │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────────┐  │
│  │trakt_auth  │ │simkl_auth  │ │layout_set  │ │player_settings   │  │
│  │_store      │ │_store      │ │tings       │ │theme_settings    │  │
│  │[_p{id}]    │ │[_p{id}]    │ │[_p{id}]    │ │[_p{id}]          │  │
│  └────────────┘ └────────────┘ └────────────┘ └──────────────────┘  │
│                                                                       │
│  Shared DataStores (always global, no profile suffix):               │
│  ┌────────────┐ ┌────────────┐ ┌─────────────────────────────────┐  │
│  │addon_prefs │ │real_debrid │ │premiumize, torbox, easydebrid,  │  │
│  │(shared)    │ │_auth       │ │tmdb, mdblist, omdb, etc.        │  │
│  └────────────┘ └────────────┘ └─────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Status |
|-----------|---------------|--------|
| `ProfileDataStore` | Stores profiles list (JSON) + active profile id in a single global DataStore | **CREATE** (port from NuvioTV) |
| `ProfileDataStoreFactory` | `ConcurrentHashMap` cache; `get(profileId, featureName)` returns correct per-profile or shared DataStore instance | **CREATE** (port from NuvioTV, drop-in) |
| `ProfileManager` | CRUD (max 4), `activeProfileId: StateFlow<Int>`, `profiles: StateFlow<List<UserProfile>>`, deletion cleanup | **CREATE** (port from NuvioTV) |
| `ProfileModule` | Hilt marker `@Module @InstallIn(SingletonComponent)` — all three above use `@Inject` constructors so no explicit `@Provides` needed | **CREATE** (trivial port) |
| `TraktAuthDataStore` | Remove `Context.preferencesDataStore` delegate; inject `ProfileDataStoreFactory` + `ProfileManager`; `state` flow uses `flatMapLatest(activeProfileId)` | **MODIFY** |
| `SimklAuthDataStore` | Same migration as `TraktAuthDataStore` | **MODIFY** |
| `ThemeDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `LayoutPreferenceDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `PlayerSettingsDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `TraktSettingsDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `SimklSettingsDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `TrailerSettingsDataStore` | Remove delegate; use factory with `profileId` | **MODIFY** |
| `AddonPreferences` | **No change** — shared across profiles; keeps existing `Context.addonPreferencesDataStore` delegate | **KEEP** |
| `RealDebridAuthDataStore` | **No change** — shared (default profile only) | **KEEP** |
| `PremiumizeSettingsDataStore` | **No change** — shared | **KEEP** |
| `TorBoxSettingsDataStore` | **No change** — shared | **KEEP** |
| `EasyDebridSettingsDataStore` | **No change** — shared | **KEEP** |
| `TmdbSettingsDataStore` | **No change** — shared integration | **KEEP** |
| `MDBListSettingsDataStore` | **No change** — shared integration | **KEEP** |
| `OmdbSettingsDataStore` | **No change** — shared integration | **KEEP** |
| `AccountSettingsSyncService` | **No change** — continues syncing shared/global settings (addons, debrid, integrations) | **KEEP** |
| `ProfileSettingsSyncService` | Per-profile blob push/pull to Supabase using `profileDataStoreFactory`; watches `activeProfileId` via `flatMapLatest` | **CREATE** (port from NuvioTV) |
| `UserProfile` domain model | Add `pin: String?` field; NuvioTV has `avatarId: String?` and `usesPrimaryPlugins` fields to evaluate | **MODIFY** |
| `ProfileDataStore` serialization | NuvioTV uses Moshi with `ProfileJson` adapter; Nexio uses Gson elsewhere — use Gson for consistency | **ADAPT** |

---

## Recommended Project Structure

```
app/src/main/java/com/nexio/tv/
├── core/
│   ├── di/
│   │   └── ProfileModule.kt              # NEW — Hilt marker module
│   ├── profile/
│   │   └── ProfileManager.kt             # NEW — CRUD + active profile StateFlow
│   └── sync/
│       ├── AccountSettingsSyncService.kt # KEEP — shared settings sync unchanged
│       └── ProfileSettingsSyncService.kt # NEW — per-profile blob sync
├── data/
│   └── local/
│       ├── ProfileDataStore.kt           # NEW — profiles list + active id
│       ├── ProfileDataStoreFactory.kt    # NEW — ConcurrentHashMap factory
│       ├── TraktAuthDataStore.kt         # MODIFY — flatMapLatest(activeProfileId)
│       ├── SimklAuthDataStore.kt         # MODIFY — flatMapLatest(activeProfileId)
│       ├── ThemeDataStore.kt             # MODIFY — factory-based
│       ├── LayoutPreferenceDataStore.kt  # MODIFY — factory-based
│       ├── PlayerSettingsDataStore.kt    # MODIFY — factory-based
│       ├── TraktSettingsDataStore.kt     # MODIFY — factory-based
│       ├── SimklSettingsDataStore.kt     # MODIFY — factory-based
│       └── TrailerSettingsDataStore.kt   # MODIFY — factory-based
└── domain/
    └── model/
        └── UserProfile.kt                # MODIFY — add pin field
```

### Structure Rationale

- **`core/profile/`**: ProfileManager belongs in `core` not `data/local` — it is a coordination layer that sits above the raw DataStore. NuvioTV places it in `core/profile/` correctly.
- **`core/sync/`**: ProfileSettingsSyncService belongs alongside AccountSettingsSyncService — both are sync infrastructure, not feature code.
- **`data/local/`**: ProfileDataStore and ProfileDataStoreFactory are data layer concerns alongside the other DataStores they serve.

---

## Architectural Patterns

### Pattern 1: ProfileDataStoreFactory — ConcurrentHashMap Cache with Lazy Init

**What:** A `@Singleton` factory that maps `"featureName"` or `"featureName_p{id}"` to a `DataStore<Preferences>` instance. Uses `ConcurrentHashMap.getOrPut` for thread-safe lazy creation. Profile 1 (primary) always maps to the bare feature name, preserving all existing on-disk files without migration.

**When to use:** Any DataStore that should be per-profile. Pass `profileId` and the existing file name (e.g., `"trakt_auth_store"`) and the factory returns the correct instance.

**Trade-offs:** Pros — zero on-disk migration for profile 1 users, thread-safe, minimal footprint. Cons — all per-profile DataStore instances live in memory for the session; with 4 profiles × ~8 per-profile stores = 32 max instances, this is negligible.

**Key implementation note from NuvioTV source:** The factory also tracks `deletedProfileIds` — when a profile is recreated with a previously deleted ID, it forces a fresh DataStore instance instead of reusing a potentially dirty cached one. This is critical for the delete-and-recreate case.

```kotlin
@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, DataStore<Preferences>>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
        if (profileId != 1 && profileId in deletedProfileIds) {
            // Force a new instance after deletion — bypass cache
            return cache.compute(fileName) { _, _ ->
                PreferenceDataStoreFactory.create {
                    context.preferencesDataStoreFile(fileName)
                }
            }!!
        }
        return cache.getOrPut(fileName) {
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(fileName)
            }
        }
    }
}
```

### Pattern 2: flatMapLatest(activeProfileId) for Reactive Per-Profile Flows

**What:** DataStores that are per-profile expose their state flows by chaining on `profileManager.activeProfileId.flatMapLatest { profileId -> factory.get(profileId, FEATURE).data.map { ... } }`. When the active profile changes, the flow automatically switches to the new profile's DataStore.

**When to use:** Every per-profile DataStore's primary `state` or `Flow` property.

**Trade-offs:** Pros — fully reactive, no manual profile switching logic in consumers. Cons — the `@ExperimentalCoroutinesApi` opt-in is required. This is stable in practice (NuvioTV uses it in production).

**Critical implication for TraktAuthService:** `TraktAuthService` calls `traktAuthDataStore.state.first()` and mutates the DataStore directly. Because writes use `store()` which defaults to `profileManager.activeProfileId.value` (synchronous StateFlow read), writes go to the currently active profile. This is correct — the mutation methods do not need changes beyond what `TraktAuthDataStore` already exposes.

```kotlin
// In TraktAuthDataStore after migration:
private fun store(profileId: Int = profileManager.activeProfileId.value) =
    factory.get(profileId, FEATURE)

val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
    store(profileId).data.map { preferences -> TraktAuthState(...) }
}
```

### Pattern 3: ProfileSettingsSyncService — Feature Blob, Not Schema Extension

**What:** Rather than extending `AccountConfigSyncContract` (v7), per-profile settings use an independent Supabase RPC pair: `sync_push_profile_settings_blob(p_profile_id, p_settings_json, p_platform)` and `sync_pull_profile_settings_blob(p_profile_id, p_platform)`. The blob is a JSON snapshot of all per-profile DataStore keys, typed by `{"type": "string", "value": "..."}` envelope. Signature diffing prevents unnecessary pushes.

**When to use:** For all per-profile settings that should survive device reinstall or profile copy to another device.

**Trade-offs:** Pros — completely independent of the existing v7 contract; no risk of breaking existing `AccountSettingsSyncService`; NuvioTV pattern is proven. Cons — requires corresponding Supabase RPC functions to be in place before end-to-end testing is possible.

**Important:** `applyingRemoteBlob` guard prevents the local-change observer from echo-pushing changes that were just pulled from remote. `skipNextPushSignature` prevents a push when the signature matches the just-applied remote blob.

---

## Data Flow

### Active Profile Switch

```
User selects profile in ProfileSelectScreen
    ↓
ProfileViewModel.setActiveProfile(id)
    ↓
ProfileManager.setActiveProfile(id)
    ↓
ProfileDataStore.setActiveProfile(id)  [writes activeProfileId to "profile_settings" DataStore]
    ↓
profileManager.activeProfileId StateFlow emits new id
    ↓
All per-profile DataStores' flatMapLatest chains re-subscribe to new profile's DataStore files
    ↓
All ViewModels observing those flows receive new profile's data automatically
```

### Profile Deletion

```
ProfileViewModel.deleteProfile(id)
    ↓
ProfileManager.deleteProfile(id)
    ├── factory.clearProfile(id)     [clears DataStore in memory, marks id as deleted]
    ├── profileDataStore.deleteProfile(id)  [removes from profiles list, resets activeId to 1 if needed]
    └── deleteProfileDataFiles(id)   [deletes "featureName_p{id}.preferences_pb" files from disk]
```

### Trakt OAuth Flow (per-profile)

```
User initiates Trakt login from Settings screen (while profile P is active)
    ↓
TraktAuthService.startDeviceAuth()
    ↓
traktAuthDataStore.saveDeviceFlow(data)
    → writes to factory.get(activeProfileId, "trakt_auth_store")
    → file: "trakt_auth_store_p{id}.preferences_pb" for id > 1
    ↓
TraktAuthService.pollDeviceToken()
    → on success: traktAuthDataStore.saveToken() and saveUser()
    → both write to same profile-scoped file
    ↓
traktAuthDataStore.state flow emits isAuthenticated = true
    → only for the active profile's DataStore
```

### Per-Profile Sync (new flow)

```
ProfileSettingsSyncService observes:
    profileManager.activeProfileId.flatMapLatest { profileId →
        combine(syncedFeatures.map { factory.get(profileId, it).data })
    }
    .drop(1).distinctUntilChanged().debounce(1500ms)
    ↓
On change: pushCurrentProfileToRemote()
    → exportSettingsBlob(profileId): reads all synced feature DataStores
    → calls Supabase RPC sync_push_profile_settings_blob
    ↓
On foreground: requestForegroundPull()
    → pullCurrentProfileFromRemote()
    → importSettingsBlob(profileId): writes back to profile's DataStores
    → skipNextPushSignature to suppress echo push
```

### AccountSettingsSyncService (unchanged flow)

```
Continues watching shared DataStores:
    traktAuthState, simklAuthState, addonPrefs, debrid settings,
    tmdb, mdblist, omdb, layout (hero/home catalog keys), player settings
    ↓
Pushes to existing v7 AccountConfigSyncContract as before
```

Note: After per-profile migration, `traktAuthState` and `simklAuthState` watched by `AccountSettingsSyncService` will be the active profile's auth state (since `TraktAuthDataStore.state` becomes profile-reactive). This is correct behavior — the v7 sync always reflects the active profile's auth status.

---

## Integration Points

### Hilt DI Integration

The NuvioTV `ProfileModule` contains no explicit `@Provides` methods — it is a marker module that serves as documentation and a hook for future explicit bindings. All three new classes (`ProfileDataStore`, `ProfileDataStoreFactory`, `ProfileManager`) use `@Singleton` + `@Inject constructor`, so Hilt discovers them automatically when installed in `SingletonComponent`.

Nexio's existing `@Singleton` DataStores that need migration (7 stores listed above) will receive `ProfileDataStoreFactory` and `ProfileManager` injected via `@Inject constructor`, replacing the `@ApplicationContext context: Context` parameter in those classes. The Hilt DI graph requires no changes to existing modules — the new classes slot in as additional singletons.

| Boundary | Before | After |
|----------|--------|-------|
| `TraktAuthDataStore` constructor | `(@ApplicationContext context: Context)` | `(factory: ProfileDataStoreFactory, profileManager: ProfileManager)` |
| `SimklAuthDataStore` constructor | `(@ApplicationContext context: Context)` | `(factory: ProfileDataStoreFactory, profileManager: ProfileManager)` |
| Per-profile settings DataStores | `(@ApplicationContext context: Context)` | `(factory: ProfileDataStoreFactory, profileManager: ProfileManager)` |
| Shared DataStores | unchanged | unchanged |

### DataStore Extension Delegate Removal

All 27+ `data/local/*.kt` files use `private val Context.xyzDataStore: DataStore<Preferences> by preferencesDataStore(name = "...")`. Only the 7 per-profile DataStores need this delegate removed and replaced with `factory.get(profileId, "feature_name")`. The remaining ~20 shared DataStores keep their existing delegate — no change required.

The `preferencesDataStore` extension delegate is a top-level `val` backed by a `DataStoreDelegate`. It can only be declared once per process per name (enforced by Android). Replacing it with `PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }` inside the factory is the correct migration — NuvioTV's factory uses exactly this approach.

### AccountConfigSyncContract Extension

The existing v7 contract (`AccountConfigSyncContract.kt`) does **not** need modification. Per-profile settings travel through a separate Supabase RPC path (`ProfileSettingsSyncService`). The only sync-adjacent change is that `traktAuthState` and `simklAuthState` Flows observed by `AccountSettingsSyncService` will automatically reflect the active profile because the DataStores themselves become profile-reactive.

The sync split is:

| What | Service | Supabase path |
|------|---------|--------------|
| Addons, debrid, integrations, global layout | `AccountSettingsSyncService` | `account_config` table, v7 RPC |
| Trakt auth, Simkl auth, theme, layout, player, catalogs | `ProfileSettingsSyncService` | `profile_settings_blob` table, new RPC |

### Navigation

No navigation graph changes are required for the DataStore migration. The profile selection screen and profile management settings screen are additive UI — they read from `ProfileManager` (injected into their ViewModels). The opt-in profile selection gate (`if (profiles.size >= 2) showProfileSelect()`) lives in the root activity or `MainNavHost`.

---

## Anti-Patterns

### Anti-Pattern 1: Migrating Shared DataStores to Per-Profile

**What people do:** Apply `ProfileDataStoreFactory` to all DataStores for consistency.

**Why it's wrong:** `AddonPreferences`, debrid stores, and integration stores (tmdb, mdblist, etc.) are explicitly out of scope for per-profile isolation. Running these through the factory creates per-profile files that are never written to for profiles 2–4, and breaks the existing `AccountSettingsSyncService` which depends on a single global view of these settings.

**Do this instead:** Only the 7 stores identified above get migrated. Shared stores keep their `Context.preferencesDataStore` delegate.

### Anti-Pattern 2: Calling `preferencesDataStore` Delegate and Factory for the Same File Name

**What people do:** Leave the top-level `private val Context.traktAuthDataStore` extension in place while also adding factory-based access, expecting both to work.

**Why it's wrong:** The `preferencesDataStore` delegate creates a `DataStoreDelegate` registered by file name. If `PreferenceDataStoreFactory.create` is also called with the same file name, two separate `DataStore` instances back the same file, causing write corruption and flow inconsistencies.

**Do this instead:** Remove the `private val Context.xyzDataStore by preferencesDataStore(...)` top-level declaration entirely when migrating a store to the factory. The factory becomes the sole access point.

### Anti-Pattern 3: Reading activeProfileId Synchronously in Flows

**What people do:** Use `profileManager.activeProfileId.value` inside a `.map {}` on an existing flow (cold, non-reactive to profile switches).

**Why it's wrong:** The profile id is captured at subscription time and never updates, so a profile switch doesn't change which data the observer sees.

**Do this instead:** Use `flatMapLatest { profileId -> ... }` as NuvioTV's `TraktAuthDataStore.state` demonstrates. The outer flow on `activeProfileId` drives all switching.

### Anti-Pattern 4: Extending AccountConfigSyncContract for Per-Profile Data

**What people do:** Add a `profileId` parameter to `buildAccountConfigSyncPayload` and route per-profile data through the existing v7 RPC.

**Why it's wrong:** The v7 contract is tied to the account (user_id) not the profile. The Supabase schema, the `AccountConfigSyncContract.kt` functions, and the `AccountSettingsSyncService` all assume a single global settings document per account. Adding profile multiplexing here would require schema changes and a contract version bump that affect all existing clients.

**Do this instead:** Use the independent `ProfileSettingsSyncService` with its own RPC pair, as the NuvioTV reference demonstrates.

---

## Build Order (Dependency Chain)

The implementation dependencies form a strict DAG. Phases must proceed in this order:

```
Step 1: Foundation
    UserProfile model update (add pin field)
    ProfileDataStore (profiles list + active id)
    ProfileDataStoreFactory (ConcurrentHashMap factory)
    ProfileModule (Hilt marker)
    ProfileManager (CRUD + activeProfileId StateFlow)
        ↓ (all downstream depends on ProfileManager being injectable)

Step 2: DataStore Migration
    TraktAuthDataStore  ──┐
    SimklAuthDataStore  ──┤
    ThemeDataStore      ──┤  All replace delegate with factory.get(profileId, feature)
    LayoutPreference    ──┤  All add flatMapLatest(activeProfileId) to state flows
    PlayerSettings      ──┤
    TraktSettings       ──┤
    SimklSettings       ──┘
        ↓ (auth services now automatically per-profile)

Step 3: Auth Service Compatibility Check
    TraktAuthService — no code changes needed; it calls traktAuthDataStore methods
    which now operate on the active profile's DataStore. Verify circuit breaker
    state (@Volatile fields) is still appropriate at Singleton scope across profiles.
        ↓

Step 4: Per-Profile Sync Infrastructure
    Supabase RPC functions (backend prerequisite)
    ProfileSettingsSyncService (port from NuvioTV)
    SupabaseProfileSettingsBlob model
        ↓

Step 5: UI
    ProfileDataStore read/write UI (profile list, create, edit, delete)
    Profile selection screen (shown only when profiles.size >= 2)
    PIN lock gate (optional, can be deferred)
    Profile avatar via nexio-web (separate surface, can be deferred)
```

**Why this order:**
- Steps 1–2 are purely local and can be verified without a backend. The app continues functioning for single-profile users because profile 1's DataStore files use the bare feature names — identical to the current on-disk layout.
- Step 3 is a verification step, not an implementation step. `TraktAuthService` needs no changes but must be smoke-tested after Step 2 to confirm the `flatMapLatest` wiring works end-to-end.
- Step 4 requires Supabase schema changes. It can be developed in parallel with Step 5 but must land before profile sync is live.
- Step 5 is the only step users see. The UI depends on `ProfileManager` (Step 1) being correct; profile-specific settings automatically work because of Step 2.

---

## Scaling Considerations

This is an on-device architecture bounded by max 4 profiles. Scaling is not a concern in the network sense. The relevant scaling dimension is DataStore instance count:

| Profile count | DataStore instances | Memory impact |
|---------------|--------------------|----|
| 1 (current) | ~30 instances | Baseline |
| 2 profiles | ~37 instances (+7 per-profile stores for profile 2) | Negligible |
| 4 profiles (max) | ~51 instances (+21 for profiles 2–4) | Negligible |

All DataStore instances are lazily created by the factory and only materialise when their feature is first accessed for a given profile. Profile 2–4 stores for features the user never touches (e.g., Trakt auth for a non-Trakt profile) are never created.

---

## Sources

- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt`
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` (import list + structure)
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStoreFactory.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktAuthDataStore.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStore.kt`
- Confidence: HIGH — all findings based on direct source code, no inference from documentation

---
*Architecture research for: Nexio Android TV — multi-profile support*
*Researched: 2026-04-14*
