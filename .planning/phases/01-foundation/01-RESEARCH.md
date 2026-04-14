# Phase 1: Foundation - Research

**Researched:** 2026-04-14
**Domain:** Android / Kotlin — Jetpack DataStore, Hilt DI, multi-profile infrastructure
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01:** Use Gson (not Moshi) for ProfileDataStore JSON serialization of `List<UserProfile>`. Nexio already uses Gson throughout; this keeps consistency. Create a `ProfileJson` DTO class with `@SerializedName` annotations for the Gson adapter.

**D-02:** Add `avatarId: String? = null` — references entry in Supabase avatar catalog.

**D-03:** Add `pinEnabled: Boolean = false` — local reflection of server-side PIN lock state.

**D-04:** Do NOT add `usesPrimaryPlugins` — plugins are not a Nexio concept.

**D-05:** Preserve existing fields: `id`, `name`, `avatarColorHex`, `usesPrimaryAddons`, `isPrimary` (computed).

**D-06:** Silent migration — ProfileDataStore auto-creates Profile 1 with name "Default" on first read when no profiles exist. No UI prompt. Existing single-profile users never notice the change.

**D-07:** Profile 1 always uses bare DataStore filenames (no `_p1` suffix) — ensures zero data migration for existing users. This is enforced in ProfileDataStoreFactory.

**D-08:** Port NuvioTV's ProfileDataStoreFactory pattern directly — ConcurrentHashMap cache with lazy init, `deletedProfileIds` tracking for safe profile re-creation.

**D-09:** ProfileManager max 4 profiles, IDs 1-4 with slot reuse on deletion. Profile 1 cannot be deleted.

**D-10:** All new classes use `@Singleton` + `@Inject constructor` — Hilt auto-discovers them. ProfileModule is a marker `@Module @InstallIn(SingletonComponent)` with no explicit `@Provides`.

### Claude's Discretion

- Profile default avatar color assignment for new profiles (can cycle through ProfileAvatarColors)
- Internal ProfileJson DTO field naming conventions
- Error handling strategy for corrupted profile JSON

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | App isolates DataStore preferences per profile via ProfileDataStoreFactory | ProfileDataStoreFactory (ConcurrentHashMap, `get(profileId, featureName)`) is the complete implementation — verified against NuvioTV source |
| INFRA-02 | User can create up to 4 named profiles | ProfileManager.createProfile enforces `current.size >= 4` guard; slot reuse logic (IDs 1–4) is proven in NuvioTV |
| INFRA-03 | User can edit profile name and avatar color | ProfileManager.updateProfile calls ProfileDataStore.upsertProfile — no extra machinery needed |
| INFRA-04 | User can delete non-primary profiles (profile 1 protected) | ProfileManager.deleteProfile returns false for id==1; NuvioTV pattern deletes DataStore files and removes from list |
| INFRA-05 | Profile 1 uses bare DataStore filenames for zero-migration from single-profile | Factory: `if (profileId == 1) featureName else "${featureName}_p${profileId}"` — verified in NuvioTV source |
| INFRA-06 | 7 DataStores converted from singleton delegate to factory pattern with flatMapLatest | Phase 1 creates the factory; the DataStore migrations are Phase 2. Factory must be in place first. |
| INFRA-07 | UserProfile model extended with avatarId and pinEnabled fields | Two-line change to existing `UserProfile.kt`; backward-compatible via default values |
</phase_requirements>

---

## Summary

Phase 1 creates five new artifacts with zero changes to existing DataStores: `ProfileDataStoreFactory`, `ProfileDataStore`, `ProfileManager`, `ProfileModule`, and the extended `UserProfile` model. All work is a direct port of proven NuvioTV code with three Nexio-specific adaptations: (1) replace Moshi with Gson for `ProfileDataStore` serialization, (2) omit `usesPrimaryPlugins` from `ProfileJson`, (3) add `pinEnabled: Boolean = false` to `UserProfile`.

The factory pattern (ConcurrentHashMap + bare filenames for Profile 1) is the cornerstone. It must exist before Phase 2 can migrate any per-profile DataStores. Profile 1's DataStore files continue to use their current bare names, so no existing user data is disturbed.

No new library dependencies are required. All five artifacts depend only on libraries already in Nexio's dependency graph: `datastore-preferences:1.1.1`, `hilt-android:2.58`, `kotlinx-coroutines-core:1.8.1`, and `gson:2.10.1`.

**Primary recommendation:** Port NuvioTV's ProfileDataStoreFactory, ProfileDataStore, ProfileManager, and ProfileModule verbatim, then apply the three Nexio-specific adaptations listed above. Treat the NuvioTV source as the specification; deviation from it requires explicit justification.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `androidx.datastore:datastore-preferences` | 1.1.1 | Per-profile DataStore instances via `PreferenceDataStoreFactory.create {}` | Already in project; `PreferenceDataStoreFactory` (programmatic API) is the correct entry point for dynamic file names — the `by preferencesDataStore` delegate cannot do this |
| `com.google.dagger:hilt-android` | 2.58 | `@Singleton @Inject` for all new classes; `ProfileModule` marker | Already in project; all three new classes self-register via `@Inject constructor` — no new `@Provides` needed |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.8.1 | `StateFlow`, `SharingStarted.Eagerly`, `stateIn`, `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | Already in project |
| `com.google.code.gson:gson` | 2.10.1 | Serialize `List<ProfileJson>` to/from JSON in ProfileDataStore | Already in project; replaces Moshi used in NuvioTV reference — D-01 locked decision |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.util.concurrent.ConcurrentHashMap` | JDK stdlib | Thread-safe cache in ProfileDataStoreFactory | Used directly in factory; no external dep |
| `kotlinx.coroutines.flow.flatMapLatest` | coroutines 1.8.1 | Profile-switching reactive flows (Phase 2 DataStore migration) | Requires `@OptIn(ExperimentalCoroutinesApi::class)` — stable behavior since 1.6 |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Gson for ProfileDataStore | Moshi (as in NuvioTV) | NuvioTV uses Moshi — but Nexio uses Gson everywhere; mixing two JSON libraries adds cognitive overhead with no benefit |
| ConcurrentHashMap factory | Room/SQLite profile table | Room adds migration complexity and is overkill for max-4 profile metadata |
| Int profile IDs 1–4 | UUID profile IDs | UUIDs complicate filename suffix generation and provide no benefit at 4-profile scale |

**Installation:** No new packages. All dependencies are already declared in `app/build.gradle.kts` and `gradle/libs.versions.toml`.

---

## Architecture Patterns

### Recommended Project Structure

```
app/src/main/java/com/nexio/tv/
├── core/
│   ├── di/
│   │   └── ProfileModule.kt              # NEW — empty Hilt marker module
│   └── profile/
│       └── ProfileManager.kt             # NEW — CRUD + StateFlow<Int> + StateFlow<List<UserProfile>>
├── data/
│   └── local/
│       ├── ProfileDataStore.kt           # NEW — profiles list JSON + active profile id
│       └── ProfileDataStoreFactory.kt    # NEW — ConcurrentHashMap factory
└── domain/
    └── model/
        └── UserProfile.kt                # MODIFY — add avatarId + pinEnabled
```

All other files in `data/local/` are untouched in Phase 1. DataStore migrations happen in Phase 2.

### Pattern 1: ProfileDataStoreFactory — ConcurrentHashMap Cache with Lazy Init

**What:** A `@Singleton` factory maps `"featureName"` (profile 1) or `"featureName_p{id}"` (profiles 2–4) to a `DataStore<Preferences>` instance. Uses `ConcurrentHashMap.getOrPut` for thread-safe lazy creation. Tracks `deletedProfileIds` so a re-created profile with a recycled ID gets a fresh DataStore instance rather than a dirty cached one.

**When to use:** Any DataStore that should be per-profile. Pass `profileId` from ProfileManager and the existing bare file name.

```kotlin
// Source: NuvioTV ProfileDataStoreFactory.kt (direct inspection)
@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, DataStore<Preferences>>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
        if (profileId != 1 && profileId in deletedProfileIds) {
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

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p${profileId}"
        val keysToRemove = cache.keys.filter { it.endsWith(suffix) }
        for (key in keysToRemove) {
            cache[key]?.let { runCatching { it.edit { prefs -> prefs.clear() } } }
            cache.remove(key)
        }
    }

    fun markProfileCreated(profileId: Int) { deletedProfileIds.remove(profileId) }
    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds
}
```

### Pattern 2: ProfileDataStore — Gson Adaptation of NuvioTV Moshi Pattern

**What:** Stores the full profile list as a JSON string (`profiles_json` key) and the active profile id (`active_profile_id` key) in a single global `profile_settings` DataStore. Uses an internal `ProfileJson` DTO with `@SerializedName` annotations and a `TypeToken` for `List<ProfileJson>`.

**Key Nexio adaptations from NuvioTV original:**
- Replace `Moshi` + `@JsonClass(generateAdapter = true)` with `Gson` + `@SerializedName`
- Remove `usesPrimaryPlugins` field from `ProfileJson` (D-04)
- Add `pinEnabled: Boolean = false` to `ProfileJson` (D-03)
- Inject `Gson` rather than `Moshi`

```kotlin
// Source: Adapted from NuvioTV ProfileDataStore.kt + Nexio Gson convention
private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "profile_settings")

@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val dataStore = context.profileDataStore
    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")
    private val profileListType = object : TypeToken<List<ProfileJson>>() {}.type

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        parseProfiles(prefs[profilesJsonKey])
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val parsed: List<ProfileJson> = gson.fromJson(json, profileListType)
                ?: return listOf(defaultPrimaryProfile())
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())  // corrupted JSON → safe fallback
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1, name = "Default", avatarColorHex = "#1E88E5"
    )
}

internal data class ProfileJson(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("avatar_color_hex") val avatarColorHex: String,
    @SerializedName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerializedName("avatar_id") val avatarId: String? = null,
    @SerializedName("pin_enabled") val pinEnabled: Boolean = false
) {
    fun toDomain() = UserProfile(
        id = id, name = name, avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons, avatarId = avatarId, pinEnabled = pinEnabled
    )
    companion object {
        fun fromDomain(p: UserProfile) = ProfileJson(
            id = p.id, name = p.name, avatarColorHex = p.avatarColorHex,
            usesPrimaryAddons = p.usesPrimaryAddons, avatarId = p.avatarId, pinEnabled = p.pinEnabled
        )
    }
}
```

### Pattern 3: ProfileManager — CRUD Coordinator with Eager StateFlows

**What:** Wraps `ProfileDataStore` and `ProfileDataStoreFactory`. Exposes two `StateFlow`s used by the rest of the app. Enforces profile creation limits, ID slot reuse, and deletion safety.

```kotlin
// Source: NuvioTV ProfileManager.kt (direct inspection), adapted for Nexio
@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")
        ))

    suspend fun createProfile(name: String, avatarColorHex: String, ...): Boolean {
        val current = profiles.value
        if (current.size >= 4) return false
        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..4).firstOrNull { it !in usedIds } ?: return false
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(UserProfile(id = nextId, name = name.trim(), ...))
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        factory.clearProfile(id)
        profileDataStore.deleteProfile(id)  // also resets activeId to 1 if needed
        deleteProfileDataFiles(id)
        return true
    }
}
```

### Pattern 4: ProfileModule — Empty Hilt Marker

```kotlin
// Source: NuvioTV ProfileModule.kt (direct inspection)
@Module
@InstallIn(SingletonComponent::class)
object ProfileModule {
    // All three classes use @Singleton + @Inject constructors.
    // Hilt auto-provides them. This module is a marker for future @Provides if needed.
}
```

### Pattern 5: UserProfile Model Extension

```kotlin
// Source: Nexio UserProfile.kt (current) + D-02, D-03, D-04, D-05 decisions
data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val avatarId: String? = null,       // D-02: Supabase avatar catalog ref
    val pinEnabled: Boolean = false     // D-03: local reflection of server PIN state
) {
    val isPrimary: Boolean get() = id == 1
}
```

Backward-compatible: both new fields have defaults, so all existing instantiation sites compile without changes.

### Anti-Patterns to Avoid

- **Keeping `by preferencesDataStore` AND calling `PreferenceDataStoreFactory.create` for the same filename:** DataStore enforces one-instance-per-file at the process level. This causes `IllegalStateException: There are multiple DataStores active for the same file`. Phase 1 only introduces new DataStore files (`profile_settings`) — the existing stores keep their delegates until Phase 2 migrates them.
- **Storing a fixed `val dataStore = factory.get(...)` at construction time in any per-profile DataStore:** The profile ID must be resolved at call-time (`factory.get(profileManager.activeProfileId.value, FEATURE)`) not at injection time. Phase 1 does not migrate any existing DataStores, so this pitfall applies to Phase 2 work, but the planner must call it out in Phase 2 tasks.
- **Injecting `@ApplicationContext` into ProfileDataStore instead of `Gson`:** The NuvioTV original injects `Moshi`. For Nexio, inject `Gson`. The Hilt graph already provides a `Gson` instance — check `NetworkModule.kt` or equivalent for the existing `@Provides fun provideGson()` binding.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Thread-safe DataStore instance cache | Custom lock-based registry | `ConcurrentHashMap.getOrPut` in ProfileDataStoreFactory | The NuvioTV factory is exactly this; hand-rolling adds risk of double-creation bugs |
| JSON serialization for profile list | Manual string concatenation or custom parser | `Gson` with `TypeToken<List<ProfileJson>>` | Handles null fields, schema evolution, and corrupted input via try/catch fallback |
| Profile ID assignment | Random UUID or incrementing counter with no reuse | ID slot reuse: `(2..4).firstOrNull { it !in usedIds }` | Enables file suffix predictability and prevents unbounded suffix growth |
| Reactive profile switching in DataStores | Manual cache invalidation in services | `flatMapLatest(activeProfileId)` on each per-profile DataStore flow | Automatic, correct, and already proven in NuvioTV; hand-rolled invalidation misses edge cases |
| Hilt DI for new singletons | Manual object graph construction | `@Singleton @Inject constructor` — Hilt discovers automatically | Zero boilerplate; consistent with all existing Nexio singletons |

**Key insight:** All five new artifacts are structural plumbing — resist the temptation to add behavior (sync, PIN verification, avatar fetching) to Phase 1 components. They must remain pure infrastructure that Phase 2+ builds on.

---

## Common Pitfalls

### Pitfall 1: Calling `preferencesDataStore` Delegate and Factory for Same File

**What goes wrong:** If `ProfileDataStore` (which uses `by preferencesDataStore(name = "profile_settings")`) were to somehow share a filename with a factory-created instance, DataStore throws `IllegalStateException` at the second access.

**Why it happens:** DataStore enforces one active instance per file path at the process level via an internal singleton registry.

**How to avoid:** `ProfileDataStore` uses the delegate exclusively for its own `profile_settings` file. `ProfileDataStoreFactory` only ever creates instances for per-profile feature files (e.g., `trakt_auth_store_p2`). These namespaces never overlap in Phase 1.

**Warning signs:** `IllegalStateException: There are multiple DataStores active for the same file` in logcat on first profile feature access.

### Pitfall 2: Gson Instance Not Provided by Hilt

**What goes wrong:** `ProfileDataStore @Inject constructor(..., private val gson: Gson)` requires a `Gson` binding in the Hilt graph. If no `@Provides fun provideGson(): Gson` exists in any existing module, Hilt compilation fails.

**Why it happens:** Unlike Moshi (which NuvioTV's `NetworkModule` explicitly provides), Gson may be instantiated inline at call sites in Nexio rather than via Hilt injection.

**How to avoid:** Before writing `ProfileDataStore`, grep for `@Provides` + `Gson` in `core/di/`. If absent, add `@Provides @Singleton fun provideGson(): Gson = GsonBuilder().create()` to `NetworkModule` (or `ProfileModule`). Alternatively, instantiate `Gson()` directly inside `ProfileDataStore` without injection — acceptable since the default Gson configuration is sufficient for simple DTOs.

**Warning signs:** Hilt compilation error: `[Dagger/MissingBinding] com.google.gson.Gson cannot be provided without an @Provides-annotated method`.

### Pitfall 3: Default Profile Name "Profile 1" vs. "Default"

**What goes wrong:** NuvioTV uses "Profile 1" as the default primary profile name. CONTEXT.md D-06 specifies "Default". If `defaultPrimaryProfile()` in ProfileDataStore uses "Profile 1", existing tests and the profile management UI will see an inconsistent name.

**Why it happens:** Direct copy of NuvioTV `ProfileDataStore.kt` without applying the Nexio-specific name.

**How to avoid:** Change `defaultPrimaryProfile()` to return `UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")`.

**Warning signs:** Unit test for "first-launch creates profile named Default" fails.

### Pitfall 4: ProfileManager Hardcoded Initial StateFlow Value

**What goes wrong:** `profiles.stateIn(scope, SharingStarted.Eagerly, listOf(UserProfile(id = 1, name = "Profile 1", ...)))` — the initial (pre-load) value hardcodes "Profile 1". This value is emitted briefly before DataStore loads. If any consumer checks the name at startup, it sees the wrong default.

**Why it happens:** The `stateIn` initial value is the fallback before the DataStore emits. NuvioTV has "Profile 1" — Nexio should have "Default".

**How to avoid:** Use `"Default"` as the name in the `stateIn` initial fallback value, matching `defaultPrimaryProfile()`.

### Pitfall 5: `deletedProfileIds` and Slot Reuse — Dirty Cache on Re-creation

**What goes wrong:** User creates Profile 2, deletes it, then creates a new Profile 2 (same ID). Without `deletedProfileIds` tracking, the factory returns the old cached DataStore instance (which was cleared but still holds its old file path registration). Writes go to the old instance; reads may return stale data.

**Why it happens:** `ConcurrentHashMap.getOrPut` returns the existing value if the key is present — even after `clearProfile` evicts the key, a race between eviction and re-creation can result in the new `getOrPut` seeing the old key before eviction completes.

**How to avoid:** The `clearProfile` method removes the key from the cache AND adds the profile ID to `deletedProfileIds`. The `get` method checks `deletedProfileIds` first and forces a `cache.compute` (replace) rather than `getOrPut` (no-op if present). `markProfileCreated` clears the ID from `deletedProfileIds` after the new profile is saved. This exact sequence is in NuvioTV — port it verbatim, do not simplify.

**Warning signs:** After delete-and-recreate of the same profile ID, the new profile sees settings from the old profile's deleted DataStore.

---

## Code Examples

### Gson TypeToken for List<ProfileJson>

```kotlin
// Source: Nexio convention (verified: Gson 2.10.1 in libs.versions.toml)
private val profileListType = object : TypeToken<List<ProfileJson>>() {}.type

private fun serializeProfiles(profiles: List<UserProfile>): String =
    gson.toJson(profiles.map { ProfileJson.fromDomain(it) }, profileListType)

private fun parseProfiles(json: String?): List<UserProfile> {
    if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
    return try {
        val parsed: List<ProfileJson> = gson.fromJson(json, profileListType)
            ?: return listOf(defaultPrimaryProfile())
        normalizeProfiles(parsed.map { it.toDomain() })
    } catch (_: Exception) {
        listOf(defaultPrimaryProfile())
    }
}
```

### Hilt Injection Graph (no new modules needed if Gson is already provided)

```
ApplicationContext
    └── ProfileDataStoreFactory(@ApplicationContext)
    └── ProfileDataStore(@ApplicationContext, Gson)
            └── ProfileManager(ProfileDataStore, ProfileDataStoreFactory, @ApplicationContext)
```

All three are `@Singleton` discovered by Hilt. `ProfileModule` is an empty marker object.

### Default Avatar Color Assignment (Claude's Discretion)

```kotlin
// Source: ProfileAvatarColors.kt (verified: 8 colors, index 0 = "#E53935")
// Assign by cycling through PROFILE_AVATAR_COLORS based on (nextId - 2) % 8
// Profile 1 is always "#1E88E5" (Ocean, index 1) — hardcoded in defaultPrimaryProfile()
private fun defaultAvatarColor(profileId: Int): String =
    PROFILE_AVATAR_COLORS[(profileId - 2) % PROFILE_AVATAR_COLORS.size]
```

### Error Handling for Corrupted JSON (Claude's Discretion)

The recommended strategy is silent fallback to `listOf(defaultPrimaryProfile())` — identical to NuvioTV. Do not throw, do not show an error dialog, do not attempt partial repair. If JSON is corrupted, the user effectively loses non-primary profile metadata (names, colors) but the app remains functional. Logging the exception at `Log.w` level is appropriate.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `by preferencesDataStore` delegate (process-global) | `PreferenceDataStoreFactory.create {}` inside ConcurrentHashMap factory | DataStore 1.0+ | Enables per-profile dynamic file creation without process restart |
| Singleton DataStore per feature | Factory-vended DataStore per (profile, feature) pair | N/A — this is the migration | Reactive profile switching via `flatMapLatest` on `activeProfileId` |

**Deprecated/outdated in this context:**
- `by preferencesDataStore`: valid for global singletons; deprecated for any per-profile DataStore after Phase 2 migration
- `@ApplicationContext` as the sole constructor parameter in per-profile DataStores: replaced by `ProfileDataStoreFactory + ProfileManager` injection in Phase 2

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Gson` is not currently provided as a Hilt binding in any existing DI module — it is instantiated inline | Pitfall 2, Pattern 2 | If Gson IS already provided via Hilt, no action needed; if not, one `@Provides` line must be added — low risk either way |
| A2 | Profile 1's default name should be "Default" per D-06 ("name 'Default'") | Pattern 2, Pattern 3, Pitfall 3 | If the intent was "Profile 1" (matching NuvioTV), tests and UI copy would show wrong name |

**All other claims in this research are VERIFIED against direct codebase inspection of both Nexio and NuvioTV source files.**

---

## Open Questions

1. **Is `Gson` provided via Hilt in an existing DI module?**
   - What we know: Gson 2.10.1 is declared in `libs.versions.toml` and `app/build.gradle.kts`. It is used in ~10+ files in the main source (verified by grep).
   - What's unclear: Whether any existing `@Module` contains `@Provides @Singleton fun provideGson(): Gson` or whether each usage site creates `Gson()` directly.
   - Recommendation: Before writing `ProfileDataStore`, check `core/di/NetworkModule.kt` (most likely location). If absent, either add `@Provides` to `ProfileModule` or inject `Gson` via a lazy `Gson()` constructor call inside `ProfileDataStore` — both are acceptable.

2. **Should `ProfileDataStore` inject `Gson` or construct it directly?**
   - What we know: NuvioTV's equivalent injects `Moshi`. The Nexio pattern is to inject shared infrastructure.
   - What's unclear: Whether a Gson Hilt binding exists (see Q1).
   - Recommendation: Prefer injection for testability. If no Hilt binding exists, add one to `ProfileModule` with `@Provides @Singleton fun provideGson(): Gson = Gson()`.

---

## Environment Availability

Step 2.6: SKIPPED — Phase 1 is purely code/config changes with no external service dependencies. All libraries are already in the project dependency graph. The Supabase backend is not needed for Phase 1 (no sync RPCs are added here).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4 + Robolectric (verified: `PlayerSettingsDataStoreTest` uses `@RunWith(RobolectricTestRunner::class)`) |
| Config file | Standard Android test runner config via `build.gradle.kts` |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest" -x` |
| Full suite command | `./gradlew testArm64DebugUnitTest` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| INFRA-01 | `ProfileDataStoreFactory.get(1, "feat")` returns bare-named DataStore | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreFactoryTest"` | ❌ Wave 0 |
| INFRA-01 | `ProfileDataStoreFactory.get(2, "feat")` returns `"feat_p2"` DataStore | unit | same | ❌ Wave 0 |
| INFRA-02 | ProfileManager.createProfile fails when 4 profiles exist | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.profile.ProfileManagerTest"` | ❌ Wave 0 |
| INFRA-02 | ProfileManager.createProfile reuses deleted slot IDs | unit | same | ❌ Wave 0 |
| INFRA-03 | ProfileManager.updateProfile persists name and avatarColorHex changes | unit | same | ❌ Wave 0 |
| INFRA-04 | ProfileManager.deleteProfile(1) returns false | unit | same | ❌ Wave 0 |
| INFRA-04 | ProfileManager.deleteProfile(2) removes profile from list | unit | same | ❌ Wave 0 |
| INFRA-05 | Profile 1 DataStore file is named `"feat"` not `"feat_p1"` | unit | `ProfileDataStoreFactoryTest` | ❌ Wave 0 |
| INFRA-06 | Factory exists and is injectable (compilation gate) | build | `./gradlew assembleArm64Debug` | ❌ implicit |
| INFRA-07 | `UserProfile` with no avatarId/pinEnabled deserializes from existing JSON | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest"` | ❌ Wave 0 |
| INFRA-07 | `UserProfile` backward-compat: existing call sites compile unchanged | build | `./gradlew assembleArm64Debug` | ❌ implicit |

### Sampling Rate

- **Per task commit:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStore*" --tests "com.nexio.tv.core.profile.ProfileManager*"`
- **Per wave merge:** `./gradlew testArm64DebugUnitTest`
- **Phase gate:** Full suite green before marking Phase 1 complete

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreFactoryTest.kt` — covers INFRA-01, INFRA-05
- [ ] `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt` — covers INFRA-07, silent migration (D-06), corrupted JSON fallback
- [ ] `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` — covers INFRA-02, INFRA-03, INFRA-04, slot reuse, deletion guard

---

## Security Domain

Phase 1 introduces no authentication, no PIN verification, no network calls, and no user input surfaces. All new classes are local DataStore infrastructure.

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | N/A — PIN verification is Phase 3/4 Supabase RPC |
| V3 Session Management | No | N/A |
| V4 Access Control | No | N/A |
| V5 Input Validation | Minimal | Profile name `.trim()` on create — no dangerous characters possible in a DataStore string key |
| V6 Cryptography | No | PINs are never stored locally (source of truth is Supabase server-side hash) |

No security-specific tasks required for Phase 1.

---

## Project Constraints (from CLAUDE.md)

- **Package:** `com.nexio.tv` — all new files must use this package prefix
- **Build variant:** Use `arm64` for local development (`assembleArm64Debug`, `testArm64DebugUnitTest`)
- **No new libraries:** Do not introduce new dependencies unless clearly justified — verified: no new deps required
- **Domain code free of Android framework dependencies:** `UserProfile.kt` must remain a pure Kotlin data class with no Android imports
- **Preserve existing architecture and naming patterns:** New `core/profile/` directory follows existing `core/di/`, `core/sync/` naming; new `data/local/` files follow existing `DataStore.kt` naming suffix
- **Small targeted changes:** Phase 1 touches exactly 5 files (4 new, 1 modified `UserProfile.kt`) — no collateral changes
- **Prefer fixing root cause:** The factory pattern fixes the root cause of profile isolation; no workarounds

---

## Sources

### Primary (HIGH confidence)

- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/NuvioTV/.../ProfileDataStoreFactory.kt` — full source read; factory pattern, ConcurrentHashMap, deletedProfileIds, bare filename logic
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/NuvioTV/.../ProfileDataStore.kt` — full source read; Moshi adapter pattern adapted to Gson for Nexio
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/NuvioTV/.../ProfileManager.kt` — full source read; CRUD, StateFlow, deletion cleanup, max-4 guard
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/NuvioTV/.../ProfileModule.kt` — empty marker module pattern confirmed
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/NuvioTV/.../UserProfile.kt` — NuvioTV model with avatarId confirmed
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/.../UserProfile.kt` — current Nexio model confirmed (4 fields, no avatarId/pinEnabled)
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/.../ProfileAvatarColors.kt` — 8 hex colors confirmed
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/.../TraktAuthDataStore.kt` — existing delegate pattern confirmed; `by preferencesDataStore(name = "trakt_auth_store")`
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/gradle/libs.versions.toml` — `gson = "2.10.1"`, `datastore-preferences = "1.1.1"`, `hilt-android = "2.58"` confirmed
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/app/build.gradle.kts` — `implementation(libs.gson)` confirmed
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/.planning/research/ARCHITECTURE.md` — component responsibilities, build order, data flow diagrams
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/.planning/research/STACK.md` — no new deps required, version compatibility table
- `[VERIFIED: direct file inspection]` `/Users/jneerdael/Scripts/nexio/app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreTest.kt` — Robolectric test pattern confirmed

### Secondary (MEDIUM confidence)

- `[CITED: PITFALLS.md lines 1–92]` Pitfall 1 (delegate singleton), Pitfall 4 (singleton services and dynamic factory), Pitfall 5 (deletedProfileIds slot reuse) — all grounded in direct code inspection per document attribution

### Tertiary (LOW confidence / ASSUMED)

- None — all claims verified against direct source inspection

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all library versions verified against `libs.versions.toml` and `build.gradle.kts`
- Architecture patterns: HIGH — all patterns verified against NuvioTV reference source and existing Nexio codebase
- Pitfalls: HIGH — all pitfalls grounded in direct code path inspection of both codebases
- Test infrastructure: HIGH — Robolectric pattern confirmed from existing DataStore test files

**Research date:** 2026-04-14
**Valid until:** Stable for 30+ days — no fast-moving dependencies; all libraries at fixed versions in version catalog
