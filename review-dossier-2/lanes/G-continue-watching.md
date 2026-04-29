# Lane G — Continue Watching

Review SHA: `774a540f8` — Generated 2026-04-29

---

## 1. What changed in this lane on this branch

**Cluster D deferrals now closed.** The primary change landing on this SHA is the delivery of the three Cluster D deferrals that the prior review (SHA `39b0df54a`) left open as findings F-G-01, F-G-02, and F-G-03.

**F-G-01 path B closed:** `ContinueWatchingSnapshotService.observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot>` was added at `ContinueWatchingSnapshotService.kt:365-370`. It chains on `observeSnapshot()`, applies the `filter { it.profileId == profileId }` predicate internally, and unwraps to the naked `ContinueWatchingSnapshot`. Both production consumers were migrated: `HomeViewModelContinueWatching.kt:76` now calls `continueWatchingSnapshotService.observeProfileSnapshot(activeHomeProfileSession.profileId)` (replacing the prior manual `return@collectLatest` guard), and `AndroidTvFeedCatalogService.kt:155, 233` calls `continueWatchingSnapshotService.observeProfileSnapshot(activeProfileId).first()` in both `resolveSelectedRows` and `resolveFeed`. The corresponding test class `ContinueWatchingSnapshotServiceObserveProfileSnapshotTest` was added with four cases: filters by profile, drops foreign profiles, rejects `profileId=0`, rejects negative `profileId`.

**F-G-02 closed:** `ContinueWatchingSnapshotReadTraceTest` was added at `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotReadTraceTest.kt`. It installs a `RecordingTraceSink`, subscribes to `observeSnapshot()`, and asserts that at least one `continue_watching.snapshot_read` event fires with `profileId` and `recordCount` in the payload, that `traceSessionId` matches the installed session, and that `sequence > 0` and `wallClockMs > 0`.

**F-G-03 (Cluster D) closed:** All three `recordCount` computation sites now include `traktUpNextItems.size`. Site 1 at `loadPersistedSnapshotForActiveProfile` (line 328): `normalized.resumeItems.size + normalized.nextUpItems.size + normalized.traktUpNextItems.size`. Site 2 at `observeSnapshot` `onStart` `emitRead` (line 348): same formula. Site 3 at `persistRawSnapshot` (line 955): `hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size`. The static test `ContinueWatchingRecordCountIncludesTraktUpNextTest` reads the source file at test time and asserts that every line containing `recordCount` with `resumeItems` and `nextUpItems` also contains `traktUpNextItems`.

**F-B-02 Cluster F cleanup (Task 12):** `enrichContinueWatchingItemWithProvider` was rewritten to use `providerLocalizedMetadataResolver` and `metadataRouterFacade` directly. The prior `?: return item` silent-swallow guards were removed; failures are now caught in the outer `catch (e: Exception)` block with a `Log.w` and item passthrough (still a catch-all, but no silent swallow in the happy path). The `recordContinueWatchingRouteContextForPlayback` function uses `?: return item` only in `localizedContinueWatchingEpisodeDescription` for genuine null-season/null-episode early-returns, which are semantically correct.

**Other additions on this branch (CW-adjacent):**
- `ContinueWatchingSnapshotStore` raised schema version to 5, with a language-epoch and language-tag invalidation guard on decode.
- `ContinueWatchingSnapshotService` added the full optimistic mutation API: `removeResumeEntry`, `removeAllForShow`, `reinsertResumeEntry`, `applyEpisodesMarked`, `rollbackEpisodes`, `snapshotForRollback`, `snapshotForEpisodes`, plus the `EpisodeRef` and `EpisodeRollbackState` data classes.
- Air-date gating via `AirDateGate` is applied uniformly to all three rails; unaired candidates collect into `scheduledReemit` and drive `ContinueWatchingAirScheduler`.
- Integration-ownership rail sync (`syncContinueWatchingRail`) added inside `persistRawSnapshot`; ordering test confirms `rail_sync` precedes `snapshot_write`.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `ContinueWatchingSnapshotService` | `data/repository/ContinueWatchingSnapshotService.kt` | active |
| `ContinueWatchingSnapshot` | `data/repository/ContinueWatchingSnapshotService.kt:67-76` | active |
| `ProfileOwnedContinueWatchingSnapshot` | `data/repository/ContinueWatchingSnapshotService.kt:78-85` | active |
| `ContinueWatchingRecord` + `EpisodeContext` | `data/repository/ContinueWatchingRecord.kt` | active |
| `ContinueWatchingMetadataSnapshot` | `data/repository/ContinueWatchingMetadataSnapshot.kt` | active |
| `ContinueWatchingSnapshotStore` (SharedPreferences, not Room) | `data/local/ContinueWatchingSnapshotStore.kt` | active |
| `observeSnapshot()` | `ContinueWatchingSnapshotService.kt:341-357` | active; used by `AndroidTvChannelPublisher` (trigger-only) |
| `observeProfileSnapshot(profileId)` | `ContinueWatchingSnapshotService.kt:365-370` | active (F-G-01 path B) |
| `observeContinueWatching(profileId)` | `ContinueWatchingSnapshotService.kt:372-377` | declared; still zero production callers |
| `emitWrite` / `emitRead` trace companions | `ContinueWatchingSnapshotService.kt:1358-1401` | active |
| `HomeViewModelContinueWatching` | `ui/screens/home/HomeViewModelContinueWatching.kt` | active; migrated to `observeProfileSnapshot` |
| `AndroidTvFeedCatalogService` | `core/recommendations/AndroidTvFeedCatalogService.kt` | active; migrated to `observeProfileSnapshot` |
| `AndroidTvChannelPublisher` | `core/recommendations/AndroidTvChannelPublisher.kt` | active; still uses `observeSnapshot()` (trigger only) |
| `RailStoreDao` (CW ownership rail) | `data/local/integration/RailStoreDao.kt` | active; `@Transaction` on `replaceRailItems`, `deleteRailWithMembership` |
| `IntegrationOwnershipService.upsertRailMembership` | `core/integration/IntegrationOwnershipService.kt:25-38` | active; no outer `@Transaction` wrapper |

**Note on persistence layer:** ContinueWatching does **not** use Room for its primary snapshot storage. It uses Android `SharedPreferences` via `ContinueWatchingSnapshotStore`, with per-profile files derived from `profilePrefsName("continue_watching_snapshot", profileId)`. Room is used only for the integration-ownership rail metadata (`RailStoreDao`, `MediaIdentityDao`).

---

## 3. Contracts this lane must satisfy

1. Every CW read that surfaces data to the UI threads an explicit `profileId` — no reads from the unscoped `observeSnapshot()` flow by data consumers.
2. Every CW write passes the write-time `profileId` explicitly; the profile-boundary enforcer (`canPublishProfileWrite`) rejects stale sessions before any persist occurs.
3. `ContinueWatchingRecord.profileId` is positive (enforced in `init`) and matches the owning `ProfileOwnedContinueWatchingSnapshot.profileId`.
4. `continue_watching.snapshot_write` is emitted at every successful persist with `profileHash`, `profileId`, `recordCount` (all three rails), and `source = "LOCAL_PERSIST"`.
5. `continue_watching.snapshot_read` is emitted on every `observeSnapshot()` subscription with `profileId`, `recordCount` (all three rails), `profileHash`, and `source = "OBSERVE_SUBSCRIBE"`.
6. `recordCount` on both events includes `resumeItems.size + nextUpItems.size + traktUpNextItems.size`.
7. Profile switch clears in-memory CW state, resets `persistedSnapshotReady`, and suppresses live emissions until the new profile has observed a remote-snapshot reset (the `canPublishLiveSnapshot` gate).
8. `displayMetadataByItemKey` is scoped to the snapshot it belongs to; no shared in-memory cross-profile cache exists.
9. Trakt up-next sync does not introduce a write-after-read race with optimistic CW mutations.

---

## 4. Contract verdicts

| # | Contract | Verdict | Evidence |
|---|---|---|---|
| C-1 | CW reads thread explicit profileId | ⚠️ | `HomeViewModelContinueWatching.kt:76` and `AndroidTvFeedCatalogService.kt:155, 233` both use `observeProfileSnapshot(profileId)`. However, `AndroidTvChannelPublisher.kt:70` still calls `observeSnapshot()` — read below. |
| C-2 | CW writes pass explicit profileId through boundary enforcer | ✅ | `persistRawSnapshot` passes `profileId` positionally to `snapshotStore.write(hydrated, profileId = profileId)` (`:952`) and calls `canPublishProfileWrite(resultSession)` (`:948`) before any write. `ProfileBoundaryEnforcer.assertCanWriteProfileState` is the gate. |
| C-3 | `ContinueWatchingRecord.profileId` is positive and stamped from snapshot | ✅ | `ContinueWatchingRecord.kt:19` enforces `require(profileId > 0)`. `toContinueWatchingRecords()` stamps every record at `:104, 124` from the owning `profileId`. `ContinueWatchingProfileScopedQueryTest` covers the zero rejection. |
| C-4 | `snapshot_write` event has all required payload fields | ✅ | `emitWrite(profileId, recordCount)` at `:1358-1378` builds `payload = mapOf("profileHash" to ..., "profileId" to profileId, "recordCount" to recordCount, "source" to "LOCAL_PERSIST")`. All four keys present. `recordCount` includes all three rails (F-G-03 closed). |
| C-5 | `snapshot_read` event has required payload fields | ⚠️ | `emitRead` at `:1380-1401` emits `profileHash`, `profileId`, `recordCount`, `source = "OBSERVE_SUBSCRIBE"`. The new `ContinueWatchingSnapshotReadTraceTest` asserts `profileId` and `recordCount` but does **not** assert `profileHash` or `source`. See **G-01**. |
| C-6 | `recordCount` on both events covers all three rails | ✅ | All three sites (`:328`, `:348`, `:955`) now compute `resumeItems.size + nextUpItems.size + traktUpNextItems.size`. `ContinueWatchingRecordCountIncludesTraktUpNextTest` pins this statically. F-G-03 closed. |
| C-7 | Profile switch clears state and gates live emissions | ✅ | `profileSwitched.collectLatest { markProfileAwaitingLiveReset; persistedSnapshotReady=false; loadPersistedSnapshotForActiveProfile(clearWhenMissing=true) }` (`:192-196`). `canPublishLiveSnapshot` gate at `:1099-1113` prevents live data from the old profile's remote fetch from leaking into the new profile's first emission. `ContinueWatchingSnapshotServiceProfileBoundaryTest.stale_default_profile_emission` covers the cross-profile write scenario. `MainActivity.kt:362, 554` calls `recreate()` on profile switch, so `HomeViewModel` (and its CW pipeline) is rebuilt per-profile — the baked-in `activeHomeProfileSession.profileId` is always the correct session. |
| C-8 | `displayMetadataByItemKey` is not shared across profiles | ✅ | The map lives inside `ContinueWatchingSnapshot`, which lives inside `ProfileOwnedContinueWatchingSnapshot`. No static or companion-object cache stores it. `sanitizeSnapshot` and `hydrateSnapshotMetadata` build fresh maps; `invalidateLocalizedMetadata` zeroes the map on the instance (`:636`). No cross-profile sharing path exists. |
| C-9 | Trakt up-next sync does not race with optimistic mutations | ✅ (with note) | `TraktProgressService.myShowsNextUpAll` is a `MutableStateFlow<List<NextUpEntry>>` (`:209`). The live-snapshot pipeline in `ContinueWatchingSnapshotService` collects via `observeSyntheticContinueWatchingNextUp()` which is a `combine(myShowsNextUpAll, metadataState)` — a cold derivation, not a shared mutable map. Optimistic mutations (`removeShowOptimistically`, `applyEpisodesMarked`) operate on `rawSnapshotState` under `refreshMutex`. The upstream flow produces a new snapshot on the next emission cycle, which re-applies `buildRawSnapshot`. The write-after-read is inherent to the reactive pattern but is bounded by the `refreshMutex` and `canPublishLiveSnapshot` gate. No unbounded race path identified. |

---

## 5. Findings

### G-01 (formerly F-G-02, partially closed): `snapshot_read` test does not assert `profileHash` or `source`

- **Severity:** P3
- **Status:** Partial closure of F-G-02. The test exists; the primary gap from the previous review (zero test coverage for `snapshot_read`) is closed. However, the test's payload assertions are incomplete.
- **Evidence:**
  - `ContinueWatchingSnapshotReadTraceTest.kt:51-59` asserts `payload.containsKey("profileId")`, `payload.containsKey("recordCount")`, and that both are non-negative integers. It does **not** assert `payload.containsKey("profileHash")`, nor does it assert `payload["source"] == "OBSERVE_SUBSCRIBE"`.
  - The `emitRead` implementation at `ContinueWatchingSnapshotService.kt:1392-1397` always emits all four keys. There is no test guarding against a future refactor that drops `profileHash` (e.g., for a privacy policy change) or renames `source`.
  - The analogous `snapshot_write` event has validator-rule coverage in `TraceValidationRulesTest.kt:198-205` that checks `profileHash` presence. `snapshot_read` has no corresponding validator rule.
  - The timing-drift concern identified in F-G-02 (read event emitted before `persistedSnapshotReady=true`, so `recordCount` may not match what the subscriber eventually receives) is still present and not addressed in the test. The test uses a service with `snapshotStore.read(any()) returns null`, so `persistedSnapshotReady` becomes `true` with an empty snapshot and there is no timing window — but a test with a persisted snapshot would expose the drift.
- **Impact:** Operator-side trace analysis. A refactor silently dropping `profileHash` from the read event would not fail CI.
- **Recommended fix:** Extend `ContinueWatchingSnapshotReadTraceTest` to assert `payload.containsKey("profileHash")`, `payload["source"] == "OBSERVE_SUBSCRIBE"`, and that `profileHash` is non-blank. Add a `TraceValidationRule` entry for `continue_watching.snapshot_read` mirroring the write-side rule. Additionally, either (a) move `emitRead(...)` inside the `combine { }.filterNotNull()` downstream so the `recordCount` matches the delivered snapshot, or (b) document the pre-ready-state drift in the emitter's KDoc.

---

### G-02: `AndroidTvChannelPublisher` still consumes the unscoped `observeSnapshot()` for CW trigger

- **Severity:** P3 (observability / defense-in-depth)
- **Status:** New finding (not present in previous review — F-G-01 identified `AndroidTvFeedCatalogService` as the unscoped consumer; that was fixed. `AndroidTvChannelPublisher` is a distinct consumer also using `observeSnapshot()`).
- **Evidence:**
  - `AndroidTvChannelPublisher.kt:70`: `combine(dataStore.preferences, continueWatchingSnapshotService.observeSnapshot()) { prefs, snapshot -> ... snapshot.snapshot.updatedAtMs > 0L }`. This uses the unscoped flow to decide whether to call `requestSync("continue_watching_changed")`.
  - The usage is **trigger-only** — the publisher does not read `resumeItems`, `nextUpItems`, or `displayMetadataByItemKey` from the snapshot. It only checks `updatedAtMs > 0L` as a liveness signal. The actual CW data is fetched inside `syncNow()` → `feedCatalogService.resolveSelectedRows(...)` → `observeProfileSnapshot(activeProfileId)` (the already-migrated path).
  - As a result, the snapshot profile-ownership is not directly exposed through this consumer. However, if a future refactor reads snapshot content from this `combine` rather than delegating to `feedCatalogService`, the caller would have no profile-ID guard.
  - `observeSnapshot()` is also used by `AndroidTvChannelPublisher` from a different `CoroutineScope` (not `viewModelScope`) with no profle-switch cancel signal. If the user switches profiles while an Android TV sync is in progress, the publisher will continue to react to the old snapshot's `updatedAtMs` until the scope terminates.
- **Impact:** Currently inert — no profile data leaks because the trigger path does not read content. Latent risk: any content-reading added to the `combine` lambda will bypass the profile boundary.
- **Recommended fix:** Replace `continueWatchingSnapshotService.observeSnapshot()` with `continueWatchingSnapshotService.observeProfileSnapshot(profileManager.activeProfileId.value)` (or observe `profileManager.activeProfileId` and flatMap to `observeProfileSnapshot`). This closes the latent path and documents the intent. Since this publisher does not have viewModelScope, it should flatMap over `profileManager.activeProfileId.distinctUntilChanged()` to restart on profile switch.

---

### G-03: `upsertRailMembership` in `IntegrationOwnershipService` has no outer `@Transaction` wrapper — partial-write window for CW rail

- **Severity:** P2 (data integrity under process kill)
- **Status:** New finding.
- **Evidence:**
  - `IntegrationOwnershipService.upsertRailMembership` (`:25-38`) performs the following Room operations in sequence without a surrounding `@Transaction`:
    1. `railStoreDao.itemsForRail(membership.rail.railKey)` (SELECT)
    2. `railStoreDao.upsertRail(membership.rail)` (INSERT OR REPLACE)
    3. `railStoreDao.replaceRailItems(membership.rail.railKey, membership.items)` — this method itself carries `@Transaction` (delete + insert), so items are atomically replaced
    4. One `mediaIdentityDao.upsertMediaIdentity(it)` per identity
    5. One `mediaIdentityDao.replaceExternalIds(mediaKey, ids)` per media key (also carries internal `@Transaction`)
  - Steps 2-5 are individually atomic but not jointly atomic. A process kill between step 2 (rail header written) and step 3 (items written) leaves the rail cache row pointing to a now-empty item list. A kill between steps 3 and 4 leaves the rail items without their media-identity rows.
  - This is invoked for the CW rail from `ContinueWatchingSnapshotService.persistRawSnapshot` (`:964-1022`) on every snapshot write. CW snapshots can be frequent (triggered on every `allProgress` change during active playback).
  - The `ContinueWatchingSnapshotServiceProfileBoundaryTest.continue_watching_syncs_rail_ownership_before_persisting_snapshot` verifies ordering (`rail_sync` before `snapshot_write`) but does not cover partial-write recovery.
- **Impact:** On a process kill between rail-header write and item write, the Android TV channel will show an empty CW rail on next boot until the next successful full sync. For CW specifically (frequent writes), the window is narrow but real.
- **Recommended fix:** Wrap the body of `upsertRailMembership` in a `@Transaction`-annotated DAO method or introduce a helper that runs the combined insert under a single Room transaction. Alternatively, promote `replaceRailItems` to also upsert the rail header atomically so the combined operation is a single `@Transaction` unit.

---

### G-04: `enrichContinueWatchingItemWithProvider` broad `catch (e: Exception)` swallows Hilt DI failures silently

- **Severity:** P3
- **Status:** Partial improvement from F-B-02. The prior `?: return item` silent-swallow guards are removed. However, the replacement introduces a broad `catch (e: Exception)` at `HomeViewModelContinueWatching.kt:249-252` that returns `item` unchanged after only logging a warning.
- **Evidence:**
  - `enrichContinueWatchingItemWithProvider` (`:195-253`): the entire body is wrapped in `try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(...); item }`.
  - If `providerLocalizedMetadataResolver` or `metadataRouterFacade` is `null` at call time (e.g., Hilt fails to inject them), accessing them inside the `try` block throws `NullPointerException`, which is caught by `catch (e: Exception)` and silently returns `item`. The log message `"Provider enrichment failed for continue watching item $contentId: ${e.message}"` would show the NPE, but there is no crash or explicit error state surfaced to CI.
  - The Hilt injection for `metadataRouterFacade` in `HomeViewModel` is typed as non-null (`MetadataRouterFacade`), so in correctly-wired production this is a non-issue. The risk is in test harnesses that do not fully wire Hilt.
  - In `recordContinueWatchingRouteContextForPlayback` (`:255-284`), the `catch (_: Exception)` suppresses all failures silently with no log — this is explicitly documented (`"Playback navigation should not be blocked by route-context persistence."`) and is acceptable per design intent. The concern is limited to `enrichContinueWatchingItemWithProvider`.
- **Impact:** In correctly-wired production: none. In partially-wired integration tests: silent enrichment skip that masks misconfiguration.
- **Recommended fix:** Add an explicit null-check or non-null assertion for `providerLocalizedMetadataResolver` and `metadataRouterFacade` before the try block (or rely on Kotlin's non-null type to fail at Hilt binding time). Distinguish NPE from expected enrichment failures in the catch block — e.g., rethrow `NullPointerException` or `IllegalStateException` that indicate wiring failures, only swallow transient network/IO exceptions.

---

## 6. Red-flag checklist (Lane G scope)

| Red flag | Finding | Verdict |
|---|---|---|
| CW query does not require profile | C-1, G-02 | ⚠️ — Primary data consumers (`HomeVM`, `AndroidTvFeedCatalogService`) are migrated. `AndroidTvChannelPublisher` still uses unscoped `observeSnapshot()` as a trigger; content is not directly read, but the latent path exists (G-02). |
| CW write uses current-profile-at-write-resolve instead of write-issue time | C-2 | ✅ — `updateSnapshot` captures `resultSession = activeProfileSession()` at the point the live-emission pipeline delivers the snapshot (`:293-297`), and `persistRawSnapshot` passes it to `canPublishProfileWrite`. Profile-boundary enforcer rejects if the session changed between capture and resolve. |
| CW snapshot includes Trakt-driven items but `recordCount` excludes them (F-G-03) | C-6 | ✅ CLOSED — all three emission sites include `traktUpNextItems.size`. Pinned by static test. |
| `snapshot_read` has no test coverage (F-G-02) | G-01 | ✅ PARTIALLY CLOSED — test exists; payload assertion incomplete (`profileHash`, `source` not checked). |
| `snapshot_write` event payload completeness | C-4 | ✅ — `profileHash`, `profileId`, `recordCount`, `source = "LOCAL_PERSIST"` all present. |
| Manual filter on `observeSnapshot()` instead of `observeProfileSnapshot()` (F-G-01 path B regression) | C-1 | ✅ — `HomeViewModelContinueWatching.kt:76` and `AndroidTvFeedCatalogService.kt:155, 233` use `observeProfileSnapshot`. No manual `.filter { it.profileId == ... }` pattern outside the service implementation itself. |
| Cross-profile CW leak via shared in-memory cache (`displayMetadataByItemKey`) | C-8 | ✅ — Map is per-snapshot, per-`ProfileOwnedContinueWatchingSnapshot`. No static or companion-object cache. `invalidateLocalizedMetadata` zeroes it on the instance. |
| Trakt up-next sync race with local writes | C-9 | ✅ — `myShowsNextUpAll` is a `StateFlow` consumed reactively; optimistic mutations under `refreshMutex` are disjoint from the reactive pipeline. No unbounded race path. |
| Profile-switch transition: snapshot for old profile flushed correctly | C-7 | ✅ — `markProfileAwaitingLiveReset` + `persistedSnapshotReady=false` + `loadPersistedSnapshotForActiveProfile(clearWhenMissing=true)` on `profileSwitched`. Activity `recreate()` on profile switch ensures ViewModel (and the baked-in `observeProfileSnapshot(profileId)`) is rebuilt. |
| CW item enrichment fails silently due to missing Hilt dependency | G-04 | ⚠️ — Broad `catch (e: Exception)` returns `item` unchanged; NPE from null injectable is caught and logged but not rethrown. Acceptable in production (Hilt wiring guaranteed), but masks misconfiguration in partial test harnesses. |
| Room `@Transaction` usage on CW writes | G-03 | ⚠️ — `ContinueWatchingSnapshotStore` uses SharedPreferences (not Room); no `@Transaction` concern there. The ownership rail write (`upsertRailMembership`) performs multiple DAO calls without an outer `@Transaction`, leaving a partial-write window on process kill. |

---

## 7. Summary

**Lane verdict: ⚠️ — substantially improved; two open items require follow-up before this lane can be considered fully closed.**

The three Cluster D deferrals are closed at the code level (F-G-01 path B, F-G-02, F-G-03). The primary data consumers for CW snapshots now use the typed, profile-scoped `observeProfileSnapshot(profileId)` API; the `recordCount` trace fields include all three rails at all emission sites; and the `snapshot_read` event now has test coverage. Profile-boundary enforcement, in-memory cache isolation, and profile-switch flushing all pass their checks.

Three residual findings remain:

- **G-01 (P3):** The `snapshot_read` test does not assert `profileHash` or `source = "OBSERVE_SUBSCRIBE"`, leaving those fields unguarded against regression. Also, the timing-drift between `emitRead` and the actual delivered snapshot count is undocumented.
- **G-02 (P3):** `AndroidTvChannelPublisher` uses the unscoped `observeSnapshot()` for a trigger-only signal. Content is not read directly, but the latent path is open for future accidental content access.
- **G-03 (P2):** `IntegrationOwnershipService.upsertRailMembership` — invoked on every CW write — has no outer `@Transaction` wrapper, leaving a partial-write window (rail header written but items not yet written) on process kill.

**Findings count:** 4 findings — 0 P1, 1 P2 (G-03), 3 P3 (G-01, G-02, G-04).
