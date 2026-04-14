---
phase: 02-per-profile-auth-and-settings
verified: 2026-04-14T16:00:00Z
status: passed
score: 5/5
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 3/5
  gaps_closed:
    - "SimklAuthDataStore migrated to ProfileDataStoreFactory + ProfileManager; flatMapLatest on activeProfileId; no singleton preferencesDataStore delegate"
    - "SimklSettingsDataStore migrated to ProfileDataStoreFactory + ProfileManager; catalogPreferences wrapped with flatMapLatest; no singleton preferencesDataStore delegate"
  gaps_remaining: []
  regressions: []
---

# Phase 2: Per-Profile Auth and Settings Verification Report

**Phase Goal:** Users have isolated Trakt and Simkl accounts per profile, and per-profile settings persist independently across profile switches
**Verified:** 2026-04-14T16:00:00Z
**Status:** passed
**Re-verification:** Yes — final verification after all gap closures

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | User can authenticate a distinct Trakt account on each profile; scrobbles and library sync are scoped to the active profile | VERIFIED | `TraktAuthDataStore.kt`: `factory: ProfileDataStoreFactory`, `flatMapLatest` on `activeProfileId` (line 63). All write methods use `store().edit`. No singleton delegate. `TraktAuthDataStoreProfileTest.kt` present. |
| 2 | Switching profiles instantly reflects that profile's Trakt and Simkl tokens without re-authentication | VERIFIED | `ProfileManager.kt`: `_profileSwitched.emit(Unit)` in `setActiveProfile` (line 68). `AccountSettingsSyncService.kt`: `recentlySwitchedProfile` flag (line 196), `profileManager.activeProfileId.drop(1).collect { recentlySwitchedProfile = true; delay(2000); recentlySwitchedProfile = false }` (lines 210-217). Guard applied at lines 261 and 272. Both Trakt and Simkl stores reactive via `flatMapLatest`. |
| 3 | Per-profile settings (language, theme, player preferences, catalog order) persist independently when switching between profiles | VERIFIED | `ThemeDataStore.kt`: `ProfileDataStoreFactory` + 2x `flatMapLatest`. `PlayerSettingsDataStore.kt`: `ProfileDataStoreFactory` + `flatMapLatest`. `LayoutPreferenceDataStore.kt`: `ProfileDataStoreFactory` + `profileFlow` helper (wraps `flatMapLatest`). `SearchHistoryDataStore.kt`: `ProfileDataStoreFactory` + `flatMapLatest` (line 56). `ThemeDataStoreProfileTest.kt` present. |
| 4 | User can authenticate a distinct Simkl account on each profile; Simkl sync is scoped to the active profile | VERIFIED | `SimklAuthDataStore.kt`: `factory: ProfileDataStoreFactory` (line 34), `profileManager: ProfileManager` (line 35), `flatMapLatest` on `activeProfileId` (line 54), `store().edit` in all 6 write methods, no `preferencesDataStore` delegate (grep: no match). `SimklSettingsDataStore.kt`: `factory: ProfileDataStoreFactory` (line 50), `flatMapLatest` on `activeProfileId` (line 63), no singleton delegate. |
| 5 | Shared settings (addons, debrid, TMDB, MDBList, IMDB, OMDB, auto-translate, top-posters, RPDB) are only configurable from the default profile | VERIFIED | `SettingsScreen.kt`: `SettingsProfileViewModel` (line 68-74) exposes `isPrimaryProfile`; collected at line 188; passed to `IntegrationSettingsContent` at line 401. `if (isPrimaryProfile)` guards Debrid, TheIntroDb, Tmdb, Omdb, Imdb, MdbList, AnimeSkip, SubtitleTranslation, YouTubeTrailerLogin, PosterRatings entries (lines 545-634). `LaunchedEffect` at lines 499-502 redirects non-primary profiles away from shared sections. Trakt and Simkl entries visible for all profiles. `TraktViewModel.isPrimaryProfile` (lines 78-80). `SimklViewModel.isPrimaryProfile` (line 60+). |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `app/src/main/java/com/nexio/tv/data/local/TraktAuthDataStore.kt` | Per-profile auth with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, `profileManager: ProfileManager`, `flatMapLatest` on activeProfileId (line 63), `FEATURE = "trakt_auth_store"`, no singleton delegate |
| `app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt` | Per-profile settings with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, 4x `flatMapLatest` on flow properties, no singleton delegate |
| `app/src/main/java/com/nexio/tv/data/local/SimklAuthDataStore.kt` | Per-profile Simkl auth with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory` (line 34), `profileManager: ProfileManager` (line 35), `FEATURE = "simkl_auth_store"` (line 38), `private fun store(profileId: Int = profileManager.activeProfileId.value)` (line 51), `flatMapLatest { profileId ->` on state flow (line 54), all 6 write methods use `store().edit`. Grep confirms: no `preferencesDataStore` delegate, no `@ApplicationContext context`. |
| `app/src/main/java/com/nexio/tv/data/local/SimklSettingsDataStore.kt` | Per-profile Simkl settings with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory` (line 50), `profileManager: ProfileManager` (line 51), `FEATURE = "simkl_settings"` (line 54), `store()` helper (line 60), `catalogPreferences` wrapped with `flatMapLatest { pid ->` (line 63), write methods use `store().edit`. Grep confirms: no singleton delegate. |
| `app/src/main/java/com/nexio/tv/data/local/ThemeDataStore.kt` | Per-profile theme with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, `FEATURE = "theme_settings"`, 2x `flatMapLatest`, write methods use `store().edit` |
| `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt` | Per-profile player settings with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, `flatMapLatest` on flow properties |
| `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt` | Per-profile layout prefs with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, `profileFlow` helper wrapping `flatMapLatest` |
| `app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt` | Per-profile search history with flatMapLatest | VERIFIED | `factory: ProfileDataStoreFactory`, `FEATURE = "search_history"`, `flatMapLatest` (line 56), `store().edit` in write methods |
| `app/src/test/java/com/nexio/tv/data/local/FakeProfileDataStoreFactory.kt` | Shared test helper | VERIFIED | Present in test source tree |
| `app/src/test/java/com/nexio/tv/core/profile/FakeProfileManager.kt` | Shared test helper | VERIFIED | Present in test source tree |
| `app/src/test/java/com/nexio/tv/data/local/TraktAuthDataStoreProfileTest.kt` | Profile isolation tests for Trakt auth | VERIFIED | Present in test tree |
| `app/src/test/java/com/nexio/tv/data/local/TraktSettingsDataStoreProfileTest.kt` | Profile isolation tests for Trakt settings | VERIFIED | Present in test tree |
| `app/src/test/java/com/nexio/tv/data/local/SimklAuthDataStoreProfileTest.kt` | Profile isolation tests for Simkl auth | VERIFIED | Present; production implementation now matches factory pattern |
| `app/src/test/java/com/nexio/tv/data/local/SimklSettingsDataStoreProfileTest.kt` | Profile isolation tests for Simkl settings | VERIFIED | Present; production implementation now matches factory pattern |
| `app/src/test/java/com/nexio/tv/data/local/ThemeDataStoreProfileTest.kt` | Profile isolation test for ThemeDataStore | VERIFIED | Present in test tree |
| `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt` | profileSwitched SharedFlow | VERIFIED | `_profileSwitched: MutableSharedFlow<Unit>(extraBufferCapacity = 1)` (line 45), `val profileSwitched: SharedFlow<Unit>` (line 46), `_profileSwitched.emit(Unit)` in `setActiveProfile` (line 68) |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | recentlySwitchedProfile push-suppression guard | VERIFIED | `private val profileManager: ProfileManager` in constructor (line 186), `@Volatile private var recentlySwitchedProfile = false` (line 196), `observeProfileSwitches()` with `drop(1).collect { recentlySwitchedProfile = true; delay(2000); recentlySwitchedProfile = false }` (lines 210-217), `if (isApplyingRemote || recentlySwitchedProfile) return@collect` (line 261) and `if (isApplyingRemote || recentlySwitchedProfile) return` in `schedulePush` (line 272) |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt` | isPrimaryProfile StateFlow | VERIFIED | `val isPrimaryProfile: StateFlow<Boolean> = profileManager.activeProfileId.map { it == 1 }.stateIn(viewModelScope, SharingStarted.Eagerly, true)` (lines 78-80) |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SimklViewModel.kt` | isPrimaryProfile StateFlow | VERIFIED | `val isPrimaryProfile: StateFlow<Boolean>` at line 60, derived from `profileManager.activeProfileId` |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` | Integration Hub isPrimaryProfile gating | VERIFIED | `SettingsProfileViewModel` (line 68-74) exposes `isPrimaryProfile`; collected (line 188); `LaunchedEffect` redirect guard (lines 499-502); `if (isPrimaryProfile)` at lines 545 and 570 gates 10 shared integration entries |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| TraktAuthDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` in store() | WIRED | Confirmed line 61 |
| TraktAuthDataStore.state | profileManager.activeProfileId | `flatMapLatest` | WIRED | Confirmed line 63 |
| TraktSettingsDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` in store() | WIRED | Confirmed |
| SimklAuthDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` in store() | WIRED | Confirmed line 51-52; grep confirms no preferencesDataStore delegate |
| SimklAuthDataStore.state | profileManager.activeProfileId | `flatMapLatest` | WIRED | Confirmed line 54 |
| SimklSettingsDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` in store() | WIRED | Confirmed line 60-61 |
| SimklSettingsDataStore.catalogPreferences | profileManager.activeProfileId | `flatMapLatest` | WIRED | Confirmed line 63 |
| ThemeDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` | WIRED | Confirmed |
| PlayerSettingsDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` | WIRED | Confirmed |
| LayoutPreferenceDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` | WIRED | Confirmed via profileFlow helper |
| SearchHistoryDataStore | ProfileDataStoreFactory | `factory.get(profileId, FEATURE)` | WIRED | Confirmed lines 53-54 |
| ProfileManager.setActiveProfile | profileSwitched SharedFlow | `_profileSwitched.emit(Unit)` | WIRED | Confirmed line 68 |
| AccountSettingsSyncService | ProfileManager.activeProfileId | `drop(1)` observer sets recentlySwitchedProfile | WIRED | Confirmed lines 210-217; guard at lines 261 and 272 |
| TraktViewModel.isPrimaryProfile | ProfileManager.activeProfileId | `activeProfileId.map { it == 1 }.stateIn` | WIRED | Confirmed lines 78-80 |
| SimklViewModel.isPrimaryProfile | ProfileManager.activeProfileId | `activeProfileId.map { it == 1 }.stateIn` | WIRED | Confirmed line 60+ |
| SettingsScreen IntegrationSettingsContent | SettingsProfileViewModel.isPrimaryProfile | `if (isPrimaryProfile)` gates shared hub entries | WIRED | Confirmed lines 545 and 570; LaunchedEffect redirect at 499-502 |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| AUTH-01 | 02-01 | User can link a unique Trakt account per profile | SATISFIED | TraktAuthDataStore fully migrated; flatMapLatest on activeProfileId; profile isolation tests present |
| AUTH-02 | 02-01 | Trakt scrobbles, library sync, and watch progress are scoped to the active profile | SATISFIED | TraktAuthDataStore state flow is profile-reactive; sync guard (recentlySwitchedProfile) prevents spurious push on switch |
| AUTH-03 | 02-02 | User can link a unique Simkl account per profile | SATISFIED | SimklAuthDataStore.kt now uses ProfileDataStoreFactory + ProfileManager; flatMapLatest on activeProfileId; no singleton delegate confirmed by grep |
| AUTH-04 | 02-02 | Simkl sync is scoped to the active profile | SATISFIED | SimklSettingsDataStore.kt migrated; catalogPreferences wrapped with flatMapLatest; all writes use store().edit at call time |
| AUTH-05 | 02-04 | Shared settings configurable only from default profile | SATISFIED | SettingsProfileViewModel + isPrimaryProfile gating in SettingsScreen; TraktViewModel and SimklViewModel both expose isPrimaryProfile |
| AUTH-06 | 02-03 | Per-profile settings persist across profile switches | SATISFIED | All 8 DataStores use ProfileDataStoreFactory + flatMapLatest; ThemeDataStore, PlayerSettingsDataStore, LayoutPreferenceDataStore, SearchHistoryDataStore all migrated |

### Anti-Patterns Found

No blockers or warnings found in the 8 phase-2 production files. The `preferencesDataStore` singleton delegate pattern was confirmed absent from all 8 target files (TraktAuthDataStore, TraktSettingsDataStore, SimklAuthDataStore, SimklSettingsDataStore, ThemeDataStore, PlayerSettingsDataStore, LayoutPreferenceDataStore, SearchHistoryDataStore) via grep. Remaining `preferencesDataStore` occurrences in the codebase (Debrid, TMDB, YouTube, RealDebrid, etc.) are intentionally outside the scope of this phase.

### Behavioral Spot-Checks

Step 7b: SKIPPED — No runnable entry points accessible without Android device or emulator. Android TV app cannot be invoked via CLI commands.

### Human Verification Required

None. All must-haves are structurally verifiable from source. The previous gap (Simkl stores) was a concrete code absence now confirmed resolved.

## Gaps Summary

No gaps. All 5 observable truths are VERIFIED. The previously failing gap (AUTH-03 / AUTH-04 — SimklAuthDataStore and SimklSettingsDataStore still using singleton `preferencesDataStore` delegate) is now closed:

- `SimklAuthDataStore.kt`: Grep for `preferencesDataStore` returns no matches; `factory: ProfileDataStoreFactory` present at line 34; `flatMapLatest` on `activeProfileId` at line 54; all 6 write methods confirmed using `store().edit`.
- `SimklSettingsDataStore.kt`: Grep for `preferencesDataStore` returns no matches; `factory: ProfileDataStoreFactory` present at line 50; `catalogPreferences` wrapped with `flatMapLatest { pid ->` at line 63.

All 8 DataStores from the phase plan are profile-reactive. All cross-cutting behaviors (ProfileManager.profileSwitched, AccountSettingsSyncService push-suppression guard, isPrimaryProfile gating in Settings UI) are wired and verified.

---

_Verified: 2026-04-14T16:00:00Z_
_Verifier: Claude (gsd-verifier)_
