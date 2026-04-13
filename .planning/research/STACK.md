# Stack Research

**Domain:** Android TV app — multi-profile support layer
**Researched:** 2026-04-14
**Confidence:** HIGH — all findings are from direct codebase inspection of Nexio and its NuvioTV reference fork. No speculative library choices. No new dependencies required.

---

## Summary Verdict

No new dependencies are needed. Every library required for multi-profile support is already in the Nexio dependency graph. The work is purely structural: new Kotlin files that wire existing libraries in a new pattern (`ProfileDataStoreFactory`, updated DataStore consumers, new sync services, new Compose screens using existing `androidx.tv.material3`).

---

## Recommended Stack

### Core Technologies

| Technology | Version (current in Nexio) | Purpose in multi-profile | Why |
|---|---|---|---|
| `androidx.datastore:datastore-preferences` | 1.1.1 | Per-profile DataStore isolation via `ProfileDataStoreFactory` | Already present. `PreferenceDataStoreFactory.create {}` (the programmatic API, not the `by preferencesDataStore` delegate) is the correct entry point for factory-created instances with dynamic file names. |
| `com.google.dagger:hilt-android` | 2.58 | `@Singleton` injection for `ProfileManager`, `ProfileDataStoreFactory`, `ProfileDataStore` | Already present. The `ProfileModule` in NuvioTV is an empty marker — all three classes use `@Inject` constructors + `@Singleton`, so Hilt wires them automatically with no new `@Provides` needed. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.8.1 | `flatMapLatest` for profile-switching reactive flows; `ConcurrentHashMap` cache in factory | Already present. The `flatMapLatest` pattern on `profileManager.activeProfileId` is the critical operator — it tears down and re-subscribes the downstream DataStore flow on every profile switch. |
| `androidx.tv:tv-material` | 1.0.1 | Profile selection screen and profile card UI | Already present. `androidx.tv.material3.Button`, `androidx.tv.material3.Text` are what NuvioTV's `ProfileSelectionScreen` uses — standard TV focus handling with D-pad navigation is built in. |
| `io.github.jan-tennert.supabase:postgrest-kt` | 3.1.4 (via BOM) | Per-profile Supabase sync RPCs | Already present. Five new RPCs needed: `sync_push_profiles`, `sync_pull_profiles`, `sync_delete_profile_data`, `sync_push_profile_settings_blob`, `sync_pull_profile_settings_blob`. The client call pattern (`postgrest.rpc(name, params)`) is identical to existing sync services. |
| `com.squareup.moshi:moshi-kotlin` | 1.15.1 | Serializing `List<UserProfile>` to/from JSON in `ProfileDataStore` | Already present. `ProfileDataStore` stores profiles as a JSON blob using a Moshi `List<ProfileJson>` adapter. The `@JsonClass(generateAdapter = true)` internal `ProfileJson` DTO pattern is proven. |
| `io.coil-kt:coil-compose` | 2.7.0 | Loading avatar images from Supabase Storage public URL in profile cards | Already present. `AvatarRepository` resolves a `storagePath` to a full public URL using `BuildConfig.AVATAR_PUBLIC_BASE_URL`. Coil loads it like any other remote image — no new configuration. |

### New Files Required (no new dependencies)

| File | Pattern | Notes |
|---|---|---|
| `data/local/ProfileDataStoreFactory.kt` | `@Singleton`, `ConcurrentHashMap<String, DataStore<Preferences>>`, `PreferenceDataStoreFactory.create {}` | Copied verbatim from NuvioTV. File naming: `featureName` for profile 1, `${featureName}_p${profileId}` for profiles 2–4. |
| `data/local/ProfileDataStore.kt` | `@Singleton`, `by preferencesDataStore("profile_settings")`, Moshi `List<ProfileJson>` | Stores profile list + active profile ID. Copied from NuvioTV with `usesPrimaryPlugins` field removed (out of scope for Nexio). |
| `core/profile/ProfileManager.kt` | `@Singleton`, `StateFlow<List<UserProfile>>`, `StateFlow<Int>` (active ID) | Wraps `ProfileDataStore` + `ProfileDataStoreFactory`. Max-4 enforcement, ID reuse logic (IDs 1–4, slot reuse on delete), file cleanup on `deleteProfile`. |
| `core/di/ProfileModule.kt` | Empty `@Module @InstallIn(SingletonComponent::class)` marker | All three classes auto-provide via `@Inject` constructors. |
| `core/sync/ProfileSyncService.kt` | `@Singleton`, `postgrest.rpc(...)` | Push/pull profile list + PIN state to Supabase. Five RPCs. Requires backend schema additions (see Supabase section). |
| `core/sync/ProfileSettingsSyncService.kt` | `@Singleton`, `flatMapLatest` on `profileManager.activeProfileId`, debounced push | Per-profile settings sync using `ProfileDataStoreFactory`. Copied from NuvioTV. Feature list for Nexio must exclude `plugin_settings` (out of scope). |
| `ui/screens/profile/ProfileSelectionScreen.kt` | Stateful Composable, `hiltViewModel()`, `androidx.tv.material3` | TV-optimized layout: 56dp horizontal padding, 4-card grid, `FocusRequester` + `onFocusChanged`. PIN entry overlay (4-digit). Avatar picker grid fed by `AvatarRepository`. |
| `ui/screens/profile/ProfileSelectionViewModel.kt` | `@HiltViewModel`, exposes `StateFlow`s for profiles, active ID, avatar catalog | Calls `ProfileSyncService`, `ProfileManager`, `AvatarRepository`. |

---

## Integration Points with Existing Architecture

### DataStore Refactoring

The two existing auth DataStores use the `by preferencesDataStore` top-level delegate, which produces a single global instance per file. Converting them to per-profile instances requires replacing the delegate with factory injection.

**`TraktAuthDataStore` — required change:**

Current (Nexio):
```
// Uses top-level delegate → single global store
private val Context.traktAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "trakt_auth_store")

@Singleton
class TraktAuthDataStore @Inject constructor(@ApplicationContext private val context: Context)
```

Required (NuvioTV pattern):
```
@Singleton
class TraktAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, "trakt_auth_store")

    val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs -> TraktAuthState(...) }
    }
}
```

The `flatMapLatest` is the load-bearing operator — it switches the subscribed DataStore file when the active profile changes without requiring a process restart.

**`SimklAuthDataStore` — same change required.** Same pattern, `featureName = "simkl_auth_store"`.

**All other `@Singleton` DataStores with `@ApplicationContext`** (theme, player, layout, etc.) need to be injected via `ProfileDataStoreFactory` if they are classified as per-profile settings. Shared settings (addons, debrid) keep their existing `@Singleton` + `@ApplicationContext` approach unchanged.

### Hilt Injection Graph

`ProfileManager` depends on `ProfileDataStore` and `ProfileDataStoreFactory`. Both of those depend only on `@ApplicationContext`. The graph forms cleanly in `SingletonComponent` with no circular dependencies and no new modules. `ProfileManager` becomes an injection point for `TraktAuthDataStore`, `SimklAuthDataStore`, and `ProfileSettingsSyncService`.

### Existing `UserProfile` Model — Required Extension

Current Nexio model:
```kotlin
data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false
)
```

Required additions for this milestone:
- `avatarId: String? = null` — references an entry in the Supabase avatar catalog
- `pinEnabled: Boolean = false` — local reflection of remote PIN lock state (source of truth is Supabase; local value used for opt-in profile selection gating)

Do not add `usesPrimaryPlugins` — plugins are out of scope for Nexio.

### `AccountSettingsSyncService` / `AccountConfigSyncContract`

The existing v7 contract syncs Trakt and Simkl auth state as part of the global account payload. After per-profile auth DataStores are in place, the `traktAuthState` and `simklAuthState` flows in `observeAccountConfigSyncChanges` must be scoped to the active profile's DataStore. The contract version does not need to change — the payload shape for auth tokens in the global sync is unchanged; per-profile settings travel through the new `ProfileSettingsSyncService` blob RPC path instead.

---

## Supabase Schema Changes

The backend needs additions. These are new tables/functions — nothing in the existing v7 contract is modified.

| Addition | Type | Purpose |
|---|---|---|
| `user_profiles` table | Table | Stores per-user profile list: `user_id`, `profile_index` (1–4), `name`, `avatar_color_hex`, `uses_primary_addons`, `avatar_id`, `created_at`, `updated_at` |
| `user_profile_pins` table | Table | Stores hashed PINs: `user_id`, `profile_index`, `pin_hash`, `pin_locked_until` |
| `user_profile_settings` table | Table | Stores per-profile settings blob: `user_id`, `profile_id` (1–4), `settings_json` (JSONB), `platform`, `updated_at` |
| `sync_push_profiles(p_profiles jsonb[])` | RPC | Upserts profile list for authenticated user |
| `sync_pull_profiles()` | RPC | Returns profile list for authenticated user |
| `sync_delete_profile_data(p_profile_id int)` | RPC | Deletes all per-profile data for a given profile index |
| `sync_pull_profile_locks()` | RPC | Returns PIN-enabled status per profile index |
| `set_profile_pin(p_profile_id int, p_pin text, p_current_pin text)` | RPC | Sets/changes PIN (server-side hash) |
| `clear_profile_pin(p_profile_id int, p_current_pin text)` | RPC | Removes PIN |
| `verify_profile_pin(p_profile_id int, p_pin text)` | RPC | Returns `{unlocked: bool, retry_after_seconds: int}` |
| `sync_push_profile_settings_blob(p_profile_id int, p_settings_json jsonb, p_platform text)` | RPC | Upserts settings blob for one profile |
| `sync_pull_profile_settings_blob(p_profile_id int, p_platform text)` | RPC | Returns settings blob for one profile |
| `get_avatar_catalog()` | RPC | Returns avatar catalog rows from `avatar_catalog` table; `storage_path` resolved to public URL on client using `AVATAR_PUBLIC_BASE_URL` BuildConfig constant |

**Avatar photo storage:** Photos uploaded via nexio-web are stored in a Supabase Storage bucket (e.g., `avatars`). The app never writes to storage — it only reads public URLs via `AvatarRepository.avatarImageUrl(storagePath)`. The `BuildConfig.AVATAR_PUBLIC_BASE_URL` constant (already present in NuvioTV, needs adding to Nexio) points to the bucket's public base URL. No `supabase-storage-kt` client dependency is needed on the Android side.

---

## What NOT to Add

| Avoid | Why | Use Instead |
|---|---|---|
| `supabase-storage-kt` client library | The app is read-only for avatars; photos are uploaded from nexio-web, not the TV app. Adding the storage client adds complexity with no benefit. | `coil-compose` loading from a plain HTTPS URL built by `AvatarRepository` |
| Room / SQLite for profile data | 4 profiles of lightweight key-value preferences don't warrant a relational database. Adds migration complexity. | `ProfileDataStore` (JSON blob for profile list) + per-profile `DataStore<Preferences>` files |
| `kotlinx-serialization` for `ProfileDataStore` | Moshi is already in the project and used consistently for DTO serialization. Mixing two JSON libraries creates inconsistency. | Moshi `@JsonClass(generateAdapter = true)` on `ProfileJson` |
| `EncryptedSharedPreferences` / `EncryptedDataStore` for PIN storage | PINs must never be stored locally — the source of truth is Supabase server-side hashing. Storing a local PIN hash creates a bypass vector. | `ProfileSyncService.verifyProfilePin` RPC — all PIN verification is server-round-trip |
| Custom `FocusManager` / D-pad navigation library | `androidx.tv.material3` already handles TV focus semantics. Custom navigation adds maintenance burden. | `FocusRequester`, `onFocusChanged`, `onPreviewKeyEvent` from existing Compose TV stack |
| Separate `ProfileId` value class | Integer IDs (1–4) are already type-safe in context. A wrapper class adds boilerplate across all DataStore call sites with no meaningful benefit at this scale. | `Int` profile IDs, enforced at `ProfileManager` boundaries |

---

## Version Compatibility

| Package | Compatible With | Notes |
|---|---|---|
| `datastore-preferences:1.1.1` | `PreferenceDataStoreFactory` API | The factory API (`PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }`) is stable since 1.0. `ConcurrentHashMap` cache is safe because `PreferenceDataStoreFactory.create` is idempotent per file path — returning the same instance for the same key is the correct contract. |
| `hilt-android:2.58` | `@Singleton` + `@Inject` constructors | No `@Provides` changes needed. New classes follow existing patterns. |
| `kotlinx-coroutines-core:1.8.1` | `flatMapLatest` (stable), `combine` | `@OptIn(ExperimentalCoroutinesApi::class)` is required on `TraktAuthDataStore` and `SimklAuthDataStore` for `flatMapLatest`. This annotation is already present in NuvioTV's version and is acceptable — `flatMapLatest` has been stable behavior since coroutines 1.6. |
| `supabase-bom:3.1.4` | `postgrest.rpc()` call pattern | Existing RPC call pattern (`postgrest.rpc(name, jsonObject)` + `decodeList<T>()`) is unchanged. New RPCs follow the same pattern. |
| `androidx.tv:tv-material:1.0.1` | Compose BOM 2026.01.01 | Stable release. `Button`, `Text`, focus APIs used in `ProfileSelectionScreen` are all stable APIs at this version. |
| `coil-compose:2.7.0` | Compose BOM 2026.01.01 | Avatar image loading is a plain `AsyncImage(model = avatarUrl)` call — no new configuration. |

---

## Sources

- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStoreFactory.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStore.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/TraktAuthDataStore.kt` (per-profile `flatMapLatest` pattern)
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSyncService.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/sync/ProfileSettingsSyncService.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/remote/supabase/AvatarRepository.kt`
- Direct inspection: `/Users/jneerdael/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/remote/supabase/SupabaseModels.kt` (lines 129–171)
- Direct inspection: `/Users/jneerdael/Scripts/nexio/gradle/libs.versions.toml` — confirmed all referenced libraries are already in the version catalog
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/build.gradle.kts` — confirmed all referenced libraries are already declared as dependencies
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` — confirmed fields to extend
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` — confirmed current singleton/delegate pattern requiring refactor
- Direct inspection: `/Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` — confirmed same pattern as Trakt

---

*Stack research for: Nexio Android TV — multi-profile support milestone*
*Researched: 2026-04-14*
