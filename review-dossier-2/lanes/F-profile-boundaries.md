# Lane F — Profile Boundaries and Enforcement

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier series:** review-dossier-2

---

## 1. What changed in this lane on this branch

**Cluster E (F-F-03 / F-F-04 / F-F-05):**

- `F-F-03`: Deleted `ProfileMetadataOverlay` and `ProfileResolvedDisplayDocument`. Profile overlay mechanism is now pure UI chrome (`ProfilePinOverlay` — Compose PIN entry only). No data-layer overlay remains.
- `F-F-04`: Added `ProfileSwitchDeferralPolicy` (pure state machine). `ProfileManager` now wires it: incoming `dataStore.activeProfileId` emissions are routed through `onIncomingSwitch(targetProfileId, hasActivePlayback)` — deferred when playback is active, drained via `onPlaybackIdle()` when `PlaybackSessionRegistry.ownerState` becomes `null`. Four unit tests pin the deferral state machine (`ProfileManagerReactiveSwitchDuringPlaybackTest`).
- `F-F-05`: Deleted the unreachable `validateLegacyAccountScope` branch from `ProfileBoundaryEnforcer` (was dead code after `F-J-02` deleted the single-arg `Account(providerAccountId)` constructor).

**Cluster B (F-F-01 / F-F-02):**

- `F-F-01` (P0): All UI callers of `ProfileManager.setActiveProfile` now catch `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` and show a toast rather than crashing. Callers: `MainActivity.switchProfileAndApplyLocale` (line 348), `MainActivity` inline `ProfileSelectionScreen` lambda (line 538), `ProfileSelectionViewModel.selectProfile` (line 53).
- `F-F-02` (P2): `ProfileManager.setActiveProfile` now routes through `ProfileBoundaryEnforcer.assertCanSwitchProfile` before writing — a `profile.boundary_check` trace event with `verdict=FAIL/PASS` and `violation=PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` fires before throwing. Pinned by `ProfileManagerSwitchBoundaryCheckTraceTest`.

**Scope taxonomy changes (`F-J-03`):**

- `IntegrationScope.Global` deprecated; all production callers migrated to `GlobalContent`, `GlobalLocalizedContent`, or `GlobalEnglishImage`. The `IntegrationScopeGlobalDeprecatedNoCallersTest` architecture pin enforces zero production references (allowlist: `ProfileBoundaryEnforcer.kt`, `IntegrationScope.kt`).
- `IntegrationScope.Account(providerAccountId)` secondary constructor deleted (`F-J-02`); only the explicit triple `Account(profileId, provider, credentialHash)` remains.

**What remains unchanged:** `ProfileBoundaryEnforcer` is an `object` with `@Volatile` trace-sink slot; it is not Hilt-injectable. The sink is wired at startup as a side-effect of `RuntimeTraceModule.provideRuntimeTraceSink`. There is no `IntegrationScopeMatrix` type in this codebase at SHA `774a540f8` — the cross-axis policy table is implicit in `ProfileBoundaryEnforcer.validateRequest`'s `when` branch.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `ProfileManager` | `core/profile/ProfileManager.kt` | active — Hilt `@Singleton` |
| `ProfileSwitchDeferralPolicy` | `core/profile/ProfileSwitchDeferralPolicy.kt` | active — F-F-04 |
| `ProfileBoundaryEnforcer` | `core/integration/ProfileBoundaryEnforcer.kt` | active — object singleton |
| `ProfileBoundaryViolation` enum + `ProfileBoundaryException` | `core/integration/ProfileBoundaryViolation.kt` | active — 11 violations |
| `IntegrationScope` sealed hierarchy | `core/integration/IntegrationScope.kt` | active — 7 variants |
| `IntegrationScope.GlobalContent` | `IntegrationScope.kt:8` | active — default scope |
| `IntegrationScope.GlobalLocalizedContent` | `IntegrationScope.kt:14` | active — defined, NOT used by any production provider |
| `IntegrationScope.GlobalEnglishImage` | `IntegrationScope.kt:30` | active — defined, NOT used by any production IntegrationSpec |
| `IntegrationScope.Global` | `IntegrationScope.kt:40` | deprecated — F-J-03 pin; zero production callers |
| `IntegrationScope.Profile` | `IntegrationScope.kt:56` | active — legacy scope; `validateLegacyProfileScope` allows null context |
| `IntegrationScope.ProfileLocal` | `IntegrationScope.kt:110` | active — strict scope |
| `IntegrationScope.Account` | `IntegrationScope.kt:66` | active — triple constructor only |
| `ProfileExecutionContext` | `core/integration/ProfileExecutionContext.kt` | active — requires `profileId > 0` |
| `ActiveProfileSession` | `core/integration/ProfileExecutionContext.kt` | active — carries `profileId`, `sessionId`, `sessionOrdinal`, `startedAtMs` |
| `PlaybackSessionRegistry` | `core/playback/PlaybackSessionRegistry.kt` | active — atomic `Entry`, `ownerState: StateFlow<PlaybackOwnerContext?>` |
| Profile boundary audit Gradle task | `app/build.gradle.kts:392` | active — `generateProfileBoundaryAudit` runs `ProfileBoundaryAuditGoldenTest` |
| `IntegrationScopeGlobalDeprecatedNoCallersTest` | `architecture/IntegrationScopeGlobalDeprecatedNoCallersTest.kt` | active — F-J-03 pin |
| `ProfileBoundaryArchitectureTest` | `architecture/ProfileBoundaryArchitectureTest.kt` | active — 6 architecture pins |
| `ProfileSwitchDeferralPolicyTest` (named `ProfileManagerReactiveSwitchDuringPlaybackTest`) | `core/profile/ProfileManagerReactiveSwitchDuringPlaybackTest.kt` | active — 4 scenarios |
| `NoIntegrationRuntimeInjectionOutsideBoundaryTest` | `architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt` | active — allowlists `core.tmdb`, `core.tvdb` |

---

## 3. Contracts this lane must satisfy

1. `ProfileBoundaryEnforcer.validateRequest` is called at construction time from every `IntegrationSpec`, `IntegrationCallSpec`, and `IntegrationStreamSpec` — no spec reaches `DefaultIntegrationRuntime` without boundary validation.
2. `ProfileManager.setActiveProfile` rejects profile switches during active playback by throwing `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)`; all UI callers catch and handle it.
3. All `profile.boundary_check` trace events fire before throw, not silently after.
4. A reactive (sibling-device) profile push during playback must be deferred, not dropped; when playback ends, the deferred switch drains.
5. Profile-local writes (e.g., Continue Watching) are guarded by `assertCanWriteProfileState` — stale cross-profile writes are rejected.
6. Scrobble/checkin operations carry the playback-owner profile ID, not the current active profile ID.
7. `IntegrationScope.Account` always pairs `accountCacheKey` (containing `profile:N`) for profile-bound data — global-content endpoints must use a global-scope/global-cache-key pairing.
8. `IntegrationScope.GlobalContent` and `GlobalLocalizedContent` cache keys must not contain `profile:` prefix tokens.
9. `IntegrationScope.GlobalEnglishImage` cache keys must contain `imageLang:en` and no display-language token.
10. `IntegrationScope.Global` (deprecated) must not be constructed in production code.
11. `ProfileExecutionContext.profileId` must be positive (`> 0`); no spec may pass `profileId = 0` or negative.

---

## 4. Per-surface analysis

### 4.1 `ProfileBoundaryEnforcer`

`validateRequest` is the canonical gate. It is called unconditionally from all three spec `init` blocks (`IntegrationSpec`, `IntegrationCallSpec`, `IntegrationStreamSpec`). The `when` dispatch covers all seven `IntegrationScope` variants; there is no `else` fall-through. The violation taxonomy is complete: 11 enum values map cleanly to the specific policy branches.

`assertCanWriteProfileState` checks `resultProfileId == activeProfileId && resultSessionId == activeSessionId`. Only one production caller: `ContinueWatchingSnapshotService.canPublishProfileWrite` (line 1066). The catch block there is correct — it logs the stale-write rejection and returns `false` (does not swallow silently; the discard is an intentional semantic, not an error suppression).

`assertCanSwitchProfile` is the F-F-02 addition. It throws for active-playback rejections and emits a `profile.boundary_check` event for both PASS and FAIL paths. The `ProfileManager.setActiveProfile` path correctly calls this before writing `_activeProfileId`.

**Trace sink thread-safety:** `traceSink` and `traceSessionId` are both `@Volatile`, and `traceSeq` is `AtomicLong`. The emit path is safe for concurrent callers. No lock is needed because `RuntimeTraceSink.emit` implementations are assumed thread-safe (buffered queue or no-op).

**Race-condition timing:** Boundary checks occur at spec *construction* time (in `init`). By the time the network call completes and a result is ready, the profile may have switched. The `assertCanWriteProfileState` guard at the CW write-back path catches this. Scrobble operations do not use `assertCanWriteProfileState` — see F-05 below.

### 4.2 `ProfileManager`

The `init` block launches two coroutines:

1. **DataStore collect coroutine:** `dataStore.activeProfileId.collect { id → deferralPolicy.onIncomingSwitch(id, hasPlayback) → if applied: applyProfileChange(previousId, id) }`.
2. **PlaybackSessionRegistry collect coroutine:** `ownerState.collect { owner → if null: deferralPolicy.onPlaybackIdle() → if drained: applyProfileChange(previousId, drainedTo) }`.

`setActiveProfile(id)` performs a *direct write* to `_activeProfileId.value` (line 144) and then calls `dataStore.setActiveProfile(id)` (line 146). This means the StateFlow is updated immediately — callers observing `activeProfileId` see the new value at once. However, the DataStore write then triggers the collect coroutine, which calls `deferralPolicy.onIncomingSwitch(id, hasPlayback)`. At this point `deferralPolicy.activeProfileId` still holds the *pre-`setActiveProfile`* value (the deferral policy is only updated through `onIncomingSwitch` returning `true` or `onPlaybackIdle`). The collect therefore perceives a "new" switch from the old deferral-policy-tracked id to `id` — if no playback is active, `applied = true` and `applyProfileChange(previousId=currentStateFlow.value=id, newId=id)` is called — a benign no-op except that it re-syncs `deferralPolicy.activeProfileId`. There is no data corruption, but the `deferralPolicy` has a brief desynchronized window between `setActiveProfile`'s direct write and the subsequent collect-path re-sync.

**DataStore as source of truth:** `_activeProfileId` has two writers: `applyProfileChange` (reactive path) and `setActiveProfile` (imperative path). The imperative path also writes to DataStore, which feeds the reactive path. This is a dual-write pattern, not a single-source-of-truth pattern. The brief desync window in `deferralPolicy` is a nit (F-08 below), not a data-safety issue.

### 4.3 `ProfileSwitchDeferralPolicy`

Pure state machine. `activeProfileId` is its own internal tracked state, separate from `ProfileManager._activeProfileId`. `onIncomingSwitch` returns `true` if applied immediately, `false` if deferred (playback active) or no-op (same id). `onPlaybackIdle` drains `pendingActiveProfileId` if one exists. State coverage: all four scenarios are pinned by `ProfileManagerReactiveSwitchDuringPlaybackTest`.

One subtle gap: if multiple reactive switches arrive during playback, each overwrites `pendingActiveProfileId` — only the *last* switch is drained. This is a documented design choice (last-writer-wins), but is not explicitly tested.

### 4.4 `ProfileExecutionContext` + `ActiveProfileSession`

`ProfileExecutionContext.init` requires `profileId > 0`, `sessionId.isNotBlank()`, `displayLanguage.isNotBlank()`, `region.isNotBlank()`. These constraints are enforced at construction. The `profileId > 0` constraint is therefore a compile-time-equivalent guard — no caller can legally pass `profileId = 0`.

`ActiveProfileSession.init` has the same `profileId > 0` constraint. Newly created sessions use `UUID.randomUUID()` for `sessionId`, ensuring uniqueness across profile switches.

### 4.5 `IntegrationScope` hierarchy

Seven variants: `GlobalContent`, `GlobalLocalizedContent`, `GlobalEnglishImage`, `Global` (deprecated), `ProviderConfig`, `Profile`, `ProfileLocal`, `Account`. All constructors with `profileId` enforce `profileId > 0`. `GlobalLocalizedContent` requires `language.isNotBlank()` and `localizationPolicyVersion > 0`.

**Production usage:** No production `IntegrationSpec` or `IntegrationCallSpec` sets `scope = IntegrationScope.GlobalLocalizedContent(...)` or `scope = IntegrationScope.GlobalEnglishImage`. The `ProfileBoundaryArchitectureTest.golden audit` uses both (synthetic scenario), but production provider adapters (TMDB, TVDB, RPDB, TopPosters) all use `GlobalContent` (default) — including language-parameterized text specs (TMDB) and image-download specs (RPDB, TopPosters). See F-06.

### 4.6 Profile boundary audit Gradle task

`generateProfileBoundaryAudit` (defined at `app/build.gradle.kts:392`) runs `ProfileBoundaryAuditGoldenTest` which exercises 5 scenarios:

| Scenario | Scope | Result |
|---|---|---|
| Same-language metadata cache reuse | `GlobalLocalizedContent` | PASS |
| Different-language images not fetched | `GlobalEnglishImage` | PASS |
| Trakt+Simkl account scope | `Account` | PASS |
| CW profile-local visibility | `ProfileLocal` | PASS |
| Profile-switch stale-write rejection | `ProfileLocal` | PASS (assertCanWriteProfileState throws) |

The golden test output at `05-profile-boundary-audit/profile-boundary-report.md` reports **PASS, 5 scenarios, 0 violations**, SHA `9f0555a5a`. This test was generated at a different SHA than the review SHA (`774a540f8`), though the relevant source files are unchanged between those SHAs on this branch.

**Critical gap:** The audit contains no scenario exercising an authenticated Trakt user calling a global-content endpoint (trending/popular/recommendations/calendar). This is the scenario that would expose the D-01 cross-finding. See F-01 below.

### 4.7 Architecture pins

| Pin | Test | Verdict at SHA |
|---|---|---|
| `IntegrationScope.Global` zero production callers | `IntegrationScopeGlobalDeprecatedNoCallersTest` | PASS (F-J-03 closed) |
| Metadata providers (TMDB/TVDB/Kitsu) use global scopes not profile scopes | `ProfileBoundaryArchitectureTest.metadata providers use global content scopes` | PASS |
| Image cache keys include `imageLang:en` | `ProfileBoundaryArchitectureTest.image cache keys always include english image language` | PASS |
| Simkl/Trakt account keys encode account boundary | `ProfileBoundaryArchitectureTest.simkl and trakt account operation keys encode account boundary` | PASS |
| No legacy string Account constructor | `ProfileBoundaryArchitectureTest.production runtime scopes do not use legacy string account constructor` | PASS |
| `getUserSettings` account scoped | `ProfileBoundaryArchitectureTest.simkl user settings auth runtime call is account scoped` | PASS |
| Trakt credential lifecycle scoped | `ProfileBoundaryArchitectureTest.trakt credential lifecycle runtime calls are account scoped after account exists` | PASS |
| `ProfileManager` routes through enforcer (source-grep) | `ProfileManagerSwitchBoundaryCheckTraceTest.ProfileManager source routes through enforcer` | PASS |

---

## 5. Cross-finding: F-01 / D-01

> This finding is **primarily owned by Lane D**. The Lane F angle is documented here because `ProfileBoundaryEnforcer.validateAccountScope` correctly catches the violation, but the production impact is severe and the boundary audit does not exercise it.

### F-01 (Cross-reference of D-01): Trakt global-content specs use `accountScope` + `globalContentCacheKey` — thrown at construction time for every authenticated user

- **Severity:** P1 — runtime crash for any authenticated Trakt user visiting trending/popular/recommendations/calendar rails
- **Primary owner:** Lane D (D-01)
- **Lane F angle:** `ProfileBoundaryEnforcer.validateAccountScope` → `validateProfileScope` requires the cache key to match `Regex("(^|:)profile:${profileId}(:|$)")`. The six Trakt global-content functions (`fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`, `fetchCalendarShows`) construct `IntegrationSpec` with `scope = accountScope(session)` (→ `IntegrationScope.Account(profileId, TRAKT, credentialHash)`) and `cacheKey = globalContentCacheKey(...)` (→ `"global:provider:TRAKT:$logicalKey"`). The enforcer correctly identifies the mismatch and throws `ProfileBoundaryException(PROFILE_CACHE_KEY_MISSING_PROFILE_ID, ...)` at spec construction — before `runtime.get(spec)` is reached. The enforcement is **correct** but exposes that `F-C-06` introduced an irreconcilable scope/key pairing.
- **Why the boundary audit missed it:** No audit scenario exercises an authenticated Trakt user calling a global-content endpoint. All five scenarios either use synthetic non-authenticated contexts or use proper `accountCacheKey` format for account-scoped specs.
- **Required fix (Lane D responsibility):** Change `scope` for global-content Trakt endpoints from `accountScope(session)` to `IntegrationScope.GlobalContent`, with `profileContext = null`. This also requires satisfying `rejectGlobalScopeForAuthenticatedProvider` — that guard rejects `GlobalContent` when `profileContext != null && account != null`. Setting `profileContext = null` resolves both. Note: unauthenticated Trakt usage (no account) already falls through this path safely; the fix is purely for the authenticated path.
- **Follow-up for Lane F:** Add a boundary audit scenario: "authenticated Trakt user fetches trending movies → spec uses `GlobalContent` scope with no `profileContext` → enforcer passes, both profiles share cache entry." This scenario should be added to `ProfileBoundaryAuditGoldenTest`.

---

## 6. Red-flag checklist

### Profile-bound APIs without explicit profile/account scope

**Status: FAIL (F-01 above).** Six Trakt global-content endpoints use `IntegrationScope.Account` (profile-bound) with `globalContentCacheKey` (no profile token). This mismatch is caught by the enforcer at construction time. All other account-scoped specs (Trakt library/watchlists, Simkl, MDBList authenticated) correctly pair `IntegrationScope.Account` with `accountCacheKey(session, ...)` which embeds `profile:N:provider:TRAKT:credential:H:`.

### CW write that uses current profile instead of write-time profile context

**Status: PASS.** `ContinueWatchingSnapshotService.canPublishProfileWrite` (line 1064) calls `ProfileBoundaryEnforcer.assertCanWriteProfileState(resultSession, activeSession)` before committing any profile write. The stale-write scenario is exercised in the golden audit (scenario `profile_switch_rejects_stale_profile_write`) and in `TrackingScrobbleServicePlaybackOwnerTest.late scrobble result with stale owner is discarded by enforcer`.

### Scrobble that uses current profile instead of playback owner profile

**Status: PASS (structural).** `DefaultTrackingScrobbleService.scrobbleStart/Stop/Pause` all accept `PlaybackOwnerContext` and extract `owner.ownerProfileId`, passing it to `TraktScrobbleService`/`SimklScrobbleService`. The scrobble services use `authSession(ownerProfileId)` (which constructs a `TrackingAuthSession` from the *owner* profileId, not `activeProfileId`). `TrackingScrobbleServicePlaybackOwnerTest.scrobbleStart routes via owner profile not active profile` pins this structurally. **Caveat:** `checkScrobbleBoundary` in both scrobble services is observational only (emits a `scrobbleRejected` trace event, does not throw). A scrobble whose envelope profileId mismatches the active profile is logged but not blocked — see F-05.

### Profile-switch silent ignore during playback

**Status: PASS.** `ProfileSwitchDeferralPolicy` enqueues the switch to `pendingActiveProfileId` rather than dropping or applying it. `ProfileManagerReactiveSwitchDuringPlaybackTest` pins all four state-machine scenarios. The `ProfileManager` wiring is verified by source-grep in `ProfileManagerSwitchBoundaryCheckTraceTest`.

### Race condition between profile switch and integration call

**Status: PARTIAL.** Boundary validation happens at spec construction (in `init`). A profile switch between construction and result delivery is not re-validated — only `assertCanWriteProfileState` at the CW write-back path provides post-construction protection. For non-CW write-back paths (library snapshots, scrobble outbox), no `assertCanWriteProfileState`-equivalent guard exists. `TraktLibraryService.refresh` does check `profileId == activeProfileId()` before hydrating metadata (line 391), which is a manual staleness guard but not routed through `assertCanWriteProfileState`. This is a nit for library services (see F-07).

### `ProfileBoundaryException` swallowed by a UI catch-all

**Status: PASS (F-F-01 closed).** Three UI call sites catch `ProfileBoundaryException`:
- `MainActivity.switchProfileAndApplyLocale` (line 348): catches `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK`, shows toast, returns. Re-throws for any other violation. Correct.
- `MainActivity` inline ProfileSelectionScreen lambda (line 538): same pattern as above. Correct.
- `ProfileSelectionViewModel.selectProfile` (line 53): catches `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK`, emits `_switchBlockedByPlayback` (UI signal for user feedback), re-throws otherwise. Correct.

No silent swallows found. Each handler is specific to `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` and re-throws for unexpected violations.

### `GlobalLocalizedContent` vs `GlobalContent` vs `GlobalEnglishImage` usage rules

**Status: PARTIAL (F-06 below).** In production:
- `GlobalContent` is used by all provider adapters for all specs — including language-parameterized text specs (TMDB, TVDB) and image-download specs (RPDB, TopPosters).
- `GlobalLocalizedContent` is defined but never used in any production `IntegrationSpec`.
- `GlobalEnglishImage` is defined but never used in any production `IntegrationSpec`.

The architecture intent (as expressed by `IntegrationScope.GlobalEnglishImage`'s design and `TraceValidationRules.kt` checking for `"GlobalEnglishImage"` scope on image events) is that image specs should carry `GlobalEnglishImage` and text specs with language dimension should carry `GlobalLocalizedContent`. This is not enforced by the enforcer (which only validates the cache-key shape given the scope that was passed) nor by any architecture pin test (which only checks `imageLang:en` in `ArtworkImageCacheKeys`, not in `IntegrationSpec.scope`). See F-06.

### `ProfileExecutionContext` requires positive `profileId`

**Status: PASS (well-guarded).** `ProfileExecutionContext.init` asserts `profileId > 0`. `IntegrationScope.Profile`, `ProfileLocal`, and `Account` all assert `profileId > 0`. No caller can legally construct a zero or negative profileId context. The cross-finding D-01 does not involve a `profileId = 0` — it involves a valid `profileId` paired with a mismatched cache key.

### DataStore vs `ProfileManager` source-of-truth divergence

**Status: CONDITIONAL PASS / Nit (F-08 below).** `ProfileDataStore.activeProfileId` is a `Flow<Int>` from `DataStore`. `ProfileManager._activeProfileId` is a `MutableStateFlow<Int>`. The `init` coroutine keeps them synchronized reactively. However, `setActiveProfile` writes `_activeProfileId.value` directly before writing DataStore, creating a brief desynchronization window where `_activeProfileId.value` is ahead of `deferralPolicy.activeProfileId`. Self-corrects on the subsequent DataStore collect. See F-08.

### Cross-profile cache leak in metadata text cache

**Status: PASS.** The golden audit scenario `profile2_same_language_uses_profile1_metadata_cache_without_network` verifies that global-scope metadata cache keys (`GlobalLocalizedContent` scope with `"metadata:TMDB:tmdb.movie.core:..."` key) contain no `profile:` token. `ProfileBoundaryEnforcer.validateGlobalCacheKey` rejects any global key containing a `profile:` token. `CompositionCacheBoundaryTest` pins the specific case of a resolved-display key accidentally tagged `GlobalContent` scope — enforcer correctly throws. The boundary audit scenario confirms this structural isolation.

### Architecture pin scope: `core/tmdb` and `core/tvdb` allowlisted for `IntegrationRuntime` injection

**Status: PASS / Benign.** `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlists `com.nexio.tv.core.tmdb` and `com.nexio.tv.core.tvdb` for `IntegrationRuntime` references. However, inspection of both packages confirms **neither package currently references `IntegrationRuntime` at SHA `774a540f8`**. The allowlist entry is legacy/forward-looking. No bypass of profile-scope checks is occurring through these packages. Lane A also noted this gap (boundary map §1). The allowlist is low risk but should be removed when the packages are confirmed permanently clean (Nit — see F-09).

---

## 7. Findings

### F-01: Cross-reference of Lane D D-01 — Trakt global-content specs cause `ProfileBoundaryException` at spec construction for authenticated users

- **Severity:** P1 (cross-finding; primary owner: Lane D D-01)
- **Evidence:** Six functions in `TraktIntegrationProvider.kt` (`fetchTrendingMovies` line 758, `fetchTrendingShows` line 796, `fetchPopularMovies` line 834, `fetchPopularShows` line 872, `fetchRecommendations` line 910, `fetchCalendarShows` line 719) construct `IntegrationSpec` with `scope = accountScope(session)` (→ `IntegrationScope.Account(profileId, TRAKT, credentialHash)`) and `cacheKey = globalContentCacheKey(...)` (→ `"global:provider:TRAKT:$logicalKey"`). `ProfileBoundaryEnforcer.validateAccountScope` → `validateProfileScope` throws `ProfileBoundaryException(PROFILE_CACHE_KEY_MISSING_PROFILE_ID)` at spec `init`. Every authenticated Trakt user who triggers these rails gets an exception before any network call.
- **Lane F role:** The enforcer correctly catches this — it is working as designed. The gap is in the spec itself: `F-C-06` changed the cache key without changing the scope. The boundary audit did not cover this because no scenario exercises authenticated Trakt global-content fetch.
- **Required fix (Lane D):** Change `scope` to `IntegrationScope.GlobalContent` and `profileContext = null` for these six functions. Add a golden-audit scenario confirming enforcer passes.
- **Test impact:** `TraktGlobalContentCacheKeyTest` is a source-grep proxy that does not construct a real spec; it would not catch a scope mismatch. A real end-to-end test with `DefaultIntegrationRuntime` and real `ProfileBoundaryEnforcer` is required.

---

### F-02: `ProfileSwitchDeferralPolicy` not tested for last-writer-wins when multiple reactive switches arrive during playback

- **Severity:** P2
- **Evidence:** `ProfileSwitchDeferralPolicy.onIncomingSwitch` overwrites `pendingActiveProfileId` on each call during playback. If a device pushes profile switches P1→P2 and then P1→P3 while playback is active, `pendingActiveProfileId` ends up as `P3` — `P2` is silently discarded. `ProfileManagerReactiveSwitchDuringPlaybackTest` covers only a single incoming switch during playback. There is no test for the second-switch overwrite scenario.
- **Why this matters:** In multi-device sync scenarios where a fast profile change sequence happens (e.g., user quickly switches P2 then P3 on a phone while TV is playing), the TV will eventually land on `P3` — which is correct for eventual consistency, but the discarded `P2` emit to `_profileSwitched` means any observer that expected P2 (e.g., a sync service keyed on switch events) will miss it.
- **Required fix:** Add a test: two consecutive incoming switches during playback; assert only the last one drains when playback ends. Consider emitting a `profile.boundary_check` event for each overwritten pending switch to make the discard observable in traces.

---

### F-03: `ProfileBoundaryEnforcer.validateAccountScope` does not emit a `profile.boundary_check` trace event on FAIL before re-throwing

- **Severity:** P2
- **Evidence:** `ProfileBoundaryEnforcer.validateRequest` wraps its `when` dispatch in a `try/catch (e: ProfileBoundaryException)` that calls `emitBoundaryCheck(..., verdict="FAIL", ...)` before re-throwing (lines 68–79). However, `assertCanWriteProfileState` (line 189) and `assertCanSwitchProfile` (line 133) each have separate emission paths. `assertCanWriteProfileState` does NOT emit any trace event — it throws directly without emitting. Only `assertCanSwitchProfile` was wired with explicit trace emission (F-F-02). A stale-write rejection in `ContinueWatchingSnapshotService` will throw `ProfileBoundaryException(STALE_SESSION_WRITE_REJECTED)` silently from the enforcer's perspective — the only observability is the `Log.d("ContinueWatching", ...)` in the catch block of `canPublishProfileWrite`.
- **User-visible impact:** No `profile.boundary_check` event fires in the trace sink when a stale-write is rejected. The `TraceValidationRules` or any trace-based alert watching for `STALE_SESSION_WRITE_REJECTED` violations will be blind to these rejections.
- **Required fix:** Add trace emission to `assertCanWriteProfileState` — either an explicit `emitBoundaryCheck(...)` call mirroring `assertCanSwitchProfile`, or route stale-write rejection through `validateRequest` so the existing `try/catch` covers it. Add a test analogous to `ProfileManagerSwitchBoundaryCheckTraceTest` for the write-rejection path.

---

### F-04: Audit golden test SHA is `9f0555a5a`, not the review SHA `774a540f8`

- **Severity:** P2 — sign-off artifact and review SHA are different
- **Evidence:** `review-dossier-2/05-profile-boundary-audit/profile-boundary-report.md` records `Git SHA: 9f0555a5a` and `Git worktree: DIRTY`. The review SHA is `774a540f8`. The five scenarios pass at the older SHA, but the D-01 defect (Trakt global-content scope mismatch) was introduced between these SHAs. Because no audit scenario exercises authenticated Trakt global-content fetch, the audit does not detect the regression.
- **Required fix:** Regenerate the profile boundary audit at SHA `774a540f8` after the D-01 fix is applied. The audit MUST include a sixth scenario covering authenticated Trakt global-content fetch before the artifact can be treated as a valid sign-off for this review.

---

### F-05: Scrobble boundary check is observational only — a stale scrobble is logged but not blocked

- **Severity:** P2
- **Evidence:** `TraktScrobbleService.checkScrobbleBoundary` (line 294) and `SimklScrobbleService.checkScrobbleBoundary` (line 248) both call `traceMetadataEvents.emitScrobbleRejected(...)` when `envelopeProfileId != activeProfileId`, but do NOT throw and do NOT return early. The `enqueueCheckin` and `enqueueScrobble` calls immediately follow `checkScrobbleBoundary` without checking its return value (because it returns `Unit`). As a result, a scrobble mutation envelope whose `profileId` no longer matches the active profile is still enqueued to the outbox and will be sent to the Trakt/Simkl API.
- **User-visible impact:** If a profile switch occurs between playback start and the scrobble-stop event (which `ProfileSwitchDeferralPolicy` is designed to prevent but cannot completely eliminate for all edge cases, such as a crash+restart mid-playback), the scrobble will be credited to the wrong profile's account in the Trakt/Simkl backend. This is a data-integrity risk, not a crash.
- **Required fix:** Convert `checkScrobbleBoundary` to return `Boolean` (or throw) and gate the `enqueueCheckin`/`enqueueScrobble` calls on its result. Alternatively route the rejection through `ProfileBoundaryEnforcer.assertCanWriteProfileState` so it both throws and emits a `profile.boundary_check` trace event. Add a test asserting the scrobble is not enqueued when profileId mismatches active profile.

---

### F-06: `GlobalLocalizedContent` and `GlobalEnglishImage` scopes are defined but never used in production `IntegrationSpec` construction

- **Severity:** P2 — design gap; scope architecture is not enforced
- **Evidence:** Grep of all production `IntegrationSpec`, `IntegrationCallSpec`, and `IntegrationStreamSpec` constructions in `app/src/main/java/com/nexio/tv/` shows zero occurrences of `scope = IntegrationScope.GlobalLocalizedContent(...)` or `scope = IntegrationScope.GlobalEnglishImage`. The TMDB provider constructs language-parameterized specs (e.g., `cacheKey = "tmdb:movie:$movieId:$normalizedLanguage:core:..."`) under `GlobalContent` (the default). Image-download providers (RPDB, TopPosters) also use `GlobalContent`.
  - `TraceValidationRules.kt:133` checks `if (scope != "GlobalEnglishImage") return@filter false` — this rule can never match a real runtime event, because no production spec emits with `GlobalEnglishImage` scope.
  - The golden audit uses `GlobalLocalizedContent` and `GlobalEnglishImage` in synthetic `IntegrationSpec` objects (constructed in test code) — not exercising production provider paths.
- **Violated contract:** Architecture intent (scopes should distinguish text-localized vs. image-fixed-English specs from generic global content). The `ProfileBoundaryEnforcer.validateImageCacheKey` function validates `imageLang:en` in keys, but that validation path is never reached in production.
- **Required fix:** Either (a) adopt `GlobalLocalizedContent` for TMDB/TVDB language-parameterized text specs and `GlobalEnglishImage` for RPDB/TopPosters poster download specs (migration work), or (b) document that `GlobalContent` is intentionally used for all global specs and deprecate/remove `GlobalLocalizedContent` and `GlobalEnglishImage` — including the dead `TraceValidationRules` path. An architecture pin test should enforce whichever policy is chosen.

---

### F-07: `TraktLibraryService.refresh` uses a manual `profileId == activeProfileId()` staleness guard rather than `assertCanWriteProfileState`

- **Severity:** Nit
- **Evidence:** `TraktLibraryService.refresh` (line 391) checks `activeAtStart || profileId == activeProfileId()` before hydrating metadata. This is a manual, ad-hoc staleness guard that does not integrate with the `ProfileBoundaryEnforcer` trace pipeline. A stale-write rejection here does not emit a `profile.boundary_check` event and is not visible in the audit trail.
- **Violated contract:** Consistency of boundary enforcement observability.
- **Required fix:** Consider routing the staleness check through `assertCanWriteProfileState` (or at minimum through a `ProfileBoundaryEnforcer`-emitting helper) so that all profile-write rejections are uniformly observable in the trace sink. Low urgency since the guard is functionally correct.

---

### F-08: `ProfileManager.setActiveProfile` writes `_activeProfileId` directly AND to DataStore, leaving `deferralPolicy.activeProfileId` briefly desynchronized

- **Severity:** Nit
- **Evidence:** `setActiveProfile` sets `_activeProfileId.value = id` (line 144) before `dataStore.setActiveProfile(id)` (line 146). The `init` coroutine's `dataStore.activeProfileId.collect` will later fire with `id`, call `deferralPolicy.onIncomingSwitch(id, hasPlayback)`, and re-sync `deferralPolicy.activeProfileId`. Between lines 144 and the collect, `deferralPolicy.activeProfileId` still holds the old value. If a concurrent reactive switch arrives during this window, the deferral policy evaluates it against a stale `activeProfileId`.
- **Impact:** Transient and self-correcting. The reactive path is async (DataStore emit latency) and the window is very short. No data-safety issue since `_activeProfileId.value` is already correct; only `deferralPolicy`'s internal tracking is briefly stale.
- **Required fix:** Consider updating `deferralPolicy.activeProfileId` directly in `setActiveProfile` before the DataStore write (requires exposing a setter or adding a dedicated `onImperativeSwitch` method to `ProfileSwitchDeferralPolicy`). Document the dual-write pattern with a comment explaining the desync window. No test required for this nit.

---

### F-09: `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlists `core.tmdb` and `core.tvdb` but neither package references `IntegrationRuntime`

- **Severity:** Nit
- **Evidence:** `NoIntegrationRuntimeInjectionOutsideBoundaryTest` (line 11–16) allowlists `com.nexio.tv.core.tmdb` and `com.nexio.tv.core.tvdb`. Grep of both packages at SHA `774a540f8` shows zero references to `IntegrationRuntime`, `IntegrationSpec`, or `IntegrationCallSpec`. The allowlist entries are dead — they permit something that does not occur.
- **Impact:** If a future developer adds an `IntegrationRuntime` reference to `core.tmdb` or `core.tvdb` (which the architecture boundary map explicitly notes should be phased out), the pin test will silently allow it.
- **Required fix:** Remove the `core.tmdb` and `core.tvdb` entries from the allowlist, or add a companion test asserting that neither package contains `IntegrationRuntime` references (effectively inverting the gate). The latter approach makes the "clean" state the tested invariant.

---

## Summary table

| ID | Title | Severity | Owner | Status |
|---|---|---|---|---|
| F-01 | Trakt global-content specs throw at construction — cross-ref of D-01 | P1 | Lane D (Lane F audit gap) | Open |
| F-02 | Deferral policy not tested for multi-switch overwrite during playback | P2 | Lane F | Open |
| F-03 | `assertCanWriteProfileState` does not emit `profile.boundary_check` trace | P2 | Lane F | Open |
| F-04 | Profile boundary audit artifact SHA mismatch — must be regenerated | P2 | Lane F | Open |
| F-05 | Scrobble boundary check is observational only — stale scrobble not blocked | P2 | Lane F | Open |
| F-06 | `GlobalLocalizedContent` and `GlobalEnglishImage` never used in production | P2 | Lane F | Open |
| F-07 | `TraktLibraryService` staleness guard bypasses enforcer trace pipeline | Nit | Lane F | Open |
| F-08 | `ProfileManager` dual-write leaves `deferralPolicy` briefly desynchronized | Nit | Lane F | Open |
| F-09 | `NoIntegrationRuntimeInjectionOutsideBoundaryTest` allowlist has dead entries | Nit | Lane F | Open |

**Previously closed (Cluster B / Cluster E):**

| ID | Title | Closed in |
|---|---|---|
| F-F-01 | UI callers of `setActiveProfile` now catch `ProfileBoundaryException` | Cluster B |
| F-F-02 | Profile-switch rejection fires `profile.boundary_check` trace event | Cluster B |
| F-F-03 | Deleted `ProfileMetadataOverlay` and `ProfileResolvedDisplayDocument` | Cluster E |
| F-F-04 | `ProfileSwitchDeferralPolicy` wired in `ProfileManager`; `ownerState` drained on idle | Cluster E |
| F-F-05 | Deleted unreachable `validateLegacyAccountScope` branch | Cluster E |
