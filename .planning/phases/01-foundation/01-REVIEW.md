---
phase: 01-foundation
reviewed: 2026-04-14T00:00:00Z
depth: standard
files_reviewed: 9
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/core/di/ProfileModule.kt
  - app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt
  - app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt
  - app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt
  - app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt
  - app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreFactoryTest.kt
  - app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt
  - app/src/test/java/com/nexio/tv/domain/model/UserProfileTest.kt
findings:
  critical: 0
  warning: 4
  info: 4
  total: 8
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-04-14
**Depth:** standard
**Files Reviewed:** 9
**Status:** issues_found

## Summary

This phase introduces the multi-profile infrastructure: `UserProfile` model extension, `ProfileDataStore`/`ProfileDataStoreImpl` for list persistence, `ProfileDataStoreFactory` for per-profile DataStore isolation, `ProfileManager` for CRUD + active-profile StateFlow, and a Hilt `ProfileModule`. The implementation follows the NuvioTV reference closely and the design decisions from the context document are correctly reflected in code.

No critical security or data-loss bugs were found. The four warnings are correctness concerns that could cause subtle misbehavior at runtime: a TOCTOU race in `ProfileDataStoreFactory.get`, an unchecked `@Inject` placement on a secondary constructor, a Gson singleton conflict that will cause a Hilt binding collision at compile time, and a missing `setActiveProfile` guard that allows switching to a non-existent profile ID. The info items are quality and test-coverage notes.

---

## Warnings

### WR-01: TOCTOU Race in `ProfileDataStoreFactory.get` — Deleted Profile Path Creates Duplicate DataStore Instances

**File:** `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt:23-34`

**Issue:** When `profileId` is in `deletedProfileIds`, the code takes the `cache.compute` branch which always creates a new `DataStore` instance and writes it into the cache, bypassing the normal `getOrPut` path. This is correct in isolation, but the two branches are not atomic with respect to concurrent callers. A concurrent call that arrives between `clearProfile` removing the key and `markProfileCreated` running can land on the `getOrPut` branch and get the stale (pre-clear) cached instance while the `compute` branch simultaneously installs a fresh one. Under normal single-threaded use this is benign, but `ProfileDataStoreFactory` is `@Singleton` and can be called from multiple coroutines.

Additionally, `cache.compute` unconditionally replaces whatever is already in the cache for that key. If `get` is called twice for the same deleted profile before `markProfileCreated` runs, two distinct `DataStore` instances are created for the same backing file — a violation of the DataStore contract (only one instance per file).

**Fix:** Use a single atomic path for all callers. The simplest safe fix is to remove the split branch and instead invalidate the stale entry in `clearProfile` before `cache.remove`, then always use `getOrPut`:

```kotlin
fun get(profileId: Int, featureName: String): DataStore<Preferences> {
    val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
    // Always use getOrPut — clearProfile already removed the key from cache,
    // so a post-clear call naturally gets a fresh instance here.
    return cache.getOrPut(fileName) {
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(fileName)
        }
    }
}
```

The `deletedProfileIds` check in `get` is then unnecessary; the set is only needed by `isProfileDeleted` and `markProfileCreated` for external callers. If re-creation must be forced even before `clearProfile` finishes removing keys, remove the key explicitly at the start of `clearProfile` before clearing the DataStore contents.

---

### WR-02: `@Inject` on Secondary Constructor — Hilt Will Not Inject `ProfileManager`

**File:** `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:31`

**Issue:** `ProfileManager` has a primary constructor with four parameters and a secondary constructor annotated with `@Inject`. Hilt (JSR-330) requires `@Inject` to be placed on the constructor that Hilt will call. When placed on a secondary constructor in Kotlin, Hilt sees the secondary constructor as the injection point. This works if Hilt can satisfy all the secondary constructor's parameters — but the secondary constructor delegates to the primary constructor which accepts a `CoroutineScope`. At runtime Hilt will attempt to inject `ProfileDataStore` (the interface, not `ProfileDataStoreImpl`) because the secondary constructor declares `profileDataStore: ProfileDataStore`. If `ProfileDataStore` is not separately bound as a `ProfileDataStoreImpl`, this will fail at compile time with a missing binding error.

More subtly: even if the binding exists, the delegation chain `secondary → primary` creates an internal `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is unscoped and leaks for the lifetime of the singleton. This scope is never cancelled. For a `@Singleton` this is acceptable, but it means the scope outlives any test lifecycle — tests that use the primary constructor avoid this.

**Fix:** Move `@Inject` to the primary constructor, make the test-friendly scope parameter optional via a default, or use a single constructor with default arguments and rely on a `@Provides` method in `ProfileModule` if a test scope needs to be injected:

```kotlin
@Singleton
class ProfileManager @Inject constructor(
    private val dataStore: ProfileDataStoreImpl,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // ... rest of the class
}
```

For tests, construct directly using the internal primary constructor (as tests already do).

---

### WR-03: Gson `@Singleton` Binding Conflict With Existing Codebase Gson Usage

**File:** `app/src/main/java/com/nexio/tv/core/di/ProfileModule.kt:17-19`

**Issue:** `ProfileModule.provideGson()` registers a `Gson` singleton in the Hilt graph. If any other Hilt module in the codebase also provides `Gson` (or if Gson is provided elsewhere — even transitively), Hilt will fail at compile time with a duplicate binding error. The `NetworkModule` already provides `Moshi` but the codebase grep showed no existing `Gson` Hilt binding. However, this is a fragile point: the moment any dependency or future module adds a `Gson` `@Provides`, this will break the build.

More immediately: `ProfileDataStore` is `@Singleton` and `@Inject`-constructed; it receives `Gson` via injection. `ProfileModule.provideGson()` is the sole provider. If `ProfileDataStoreImpl` is ever instantiated in tests without the Hilt graph (as the tests currently do, passing `Gson()` directly), the two Gson instances are independent and unrelated — this is fine for tests. But the production singleton `ProfileDataStore` is bound to the `Gson` from `ProfileModule`, while any other component that injects `Gson` directly would get the same instance (correct). The risk is forward-compatibility: if another module needs a differently configured `Gson` (e.g., with a custom type adapter), a naming qualifier will be required.

**Fix:** Add a `@Named("profile")` qualifier to the `Gson` binding to scope it narrowly and avoid future collisions:

```kotlin
@Provides
@Singleton
@Named("profile")
fun provideProfileGson(): Gson = GsonBuilder().create()
```

And update `ProfileDataStore`'s `@Inject constructor` to match:

```kotlin
class ProfileDataStore @Inject constructor(
    @ApplicationContext context: Context,
    @Named("profile") gson: Gson
)
```

---

### WR-04: `setActiveProfile` Accepts Any Integer ID Without Existence Check at the DataStore Layer

**File:** `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt:54-57`

**Issue:** `ProfileDataStoreImpl.setActiveProfile(id: Int)` writes the given `id` directly to the DataStore without verifying that a profile with that ID actually exists in the profile list. The only guard is in `ProfileManager.setActiveProfile`, which reads from `dataStore.profilesList.first()` before calling `dataStore.setActiveProfile`. However, `ProfileDataStoreImpl` is `open` and `internal` — any caller that obtains a reference to the impl directly (e.g., in a test or a future Phase 2 migration path) can persist an orphaned `activeProfileId` that references a non-existent profile. This would cause `ProfileManager.activeProfile` to silently return `null` for the active session.

**Fix:** Add an existence check inside `setActiveProfile` at the DataStore layer, or document the precondition clearly with a `require` statement:

```kotlin
suspend fun setActiveProfile(id: Int) {
    dataStore.edit { prefs ->
        val current = parseProfiles(prefs[profilesJsonKey])
        if (current.any { it.id == id }) {
            prefs[activeProfileIdKey] = id
        }
        // silently ignore invalid id — consistent with existing no-op patterns
    }
}
```

Note: this adds one DataStore read inside the `edit` lambda (which is acceptable since `edit` is already a suspending transaction), or alternatively the check can be done before `edit` with the same pattern used in `ProfileManager`.

---

## Info

### IN-01: `ProfileModule` Deviates from Phase Design Decision D-10

**File:** `app/src/main/java/com/nexio/tv/core/di/ProfileModule.kt:1-20`

**Issue:** The context document (D-10) states: "ProfileModule is a marker `@Module @InstallIn(SingletonComponent)` with no explicit `@Provides`." The implementation adds a `@Provides fun provideGson()` method, making it a non-marker module. This is not a bug, but it diverges from the stated design intent. If the design intent was specifically to avoid `@Provides` in this module (because the Gson binding belongs in a general-purpose infrastructure module), then the Gson provision should be moved — either to an existing module or a new `InfrastructureModule`.

---

### IN-02: `ProfileDataStoreImpl` Visibility — `open internal` Combination Is Unusual

**File:** `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt:38`

**Issue:** `ProfileDataStoreImpl` is declared `open class ProfileDataStoreImpl internal constructor(...)`. The `internal` modifier on the constructor restricts direct instantiation to the same module (which is the goal — tests use it, but they reside in the same module). However, the class itself is `public` (no visibility modifier) and `open`, which means external consumers can subclass it even though they cannot call the constructor directly. This is an inconsistency: either restrict the class visibility to `internal` as well, or make the constructor `internal` and seal the class if subclassing is not intended beyond `ProfileDataStore`.

---

### IN-03: File Deletion in `deleteProfileDataAsync` Uses Fire-and-Forget Pattern Without Error Reporting

**File:** `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:108-122`

**Issue:** `deleteProfileDataAsync` calls `file.delete()` (line 119) without checking the return value. On Android, `File.delete()` returns `false` if the file does not exist or cannot be deleted (e.g., permission denied). The method name suggests asynchronous fire-and-forget, which is acceptable for best-effort cleanup, but the silent failure means orphaned DataStore files will accumulate on disk if deletion consistently fails. There is no logging, no retry, and no way for callers to know cleanup failed.

**Fix:** At minimum, log a warning on failure for diagnosability:

```kotlin
if (!file.delete()) {
    android.util.Log.w("ProfileManager", "Failed to delete DataStore file: ${file.name}")
}
```

---

### IN-04: Test Coverage Gap — No Test for Concurrent `createProfile` / `deleteProfile` Calls or `replaceAllProfiles` + Active Profile Boundary

**File:** `app/src/test/java/com/nexio/tv/core/profile/ProfileManagerTest.kt` and `app/src/test/java/com/nexio/tv/data/local/ProfileDataStoreTest.kt`

**Issue:** The test suite is thorough for the single-threaded sequential cases. Two gaps worth noting:

1. `replaceAllProfiles` is tested in `ProfileDataStoreTest` for list replacement and normalization, but there is no test that verifies the active profile reset behaviour when `replaceAllProfiles` is called with a list that excludes the current active profile (e.g., active = 3, replace with [profile 1, profile 2] — active should reset to 1). The logic exists in the production code (`ProfileDataStore.kt:90-92`) but is uncovered.

2. `ProfileManagerTest` has no test verifying that `createProfile` correctly reuses the lowest available slot after a non-sequential deletion (e.g., create profiles 2, 3, 4; delete profile 3; next create should get ID 3, not 4). The current slot-reuse test only covers the simplest case (delete 2, recreate gets 2).

---

_Reviewed: 2026-04-14_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
