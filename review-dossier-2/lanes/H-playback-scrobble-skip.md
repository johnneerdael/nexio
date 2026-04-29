# Lane H — Playback, Scrobble, Skip Segments

Review SHA: `774a540f8` — Generated 2026-04-29

---

## 1. What changed in this lane on this branch

**PlaybackSessionRegistry and PlaybackOwnerContext added.**
`PlaybackSessionRegistry` is a singleton single-slot registry backed by `AtomicReference<Entry?>`.
`register(context)` emits a UUID token and overwrites any prior entry; `unregister(token)` is a no-op
for stale tokens. `ownerState: StateFlow<PlaybackOwnerContext?>` was added per F-F-04; `ProfileManager`
subscribes to drain `pendingActiveProfileId` when the slot goes idle. `PlaybackOwnerContext` carries a
5-field shape: `ownerProfileId`, `ownerSessionId`, `traktAccount`, `simklAccount`, `startedAtEpochMs`.
All three fields carry `init` precondition guards.

**TrackingScrobbleService signature frozen (F-H-01).**
`TrackingScrobbleService.checkin(item, message, ownerProfileId: Int?)` retains `Int?` as the third
parameter, intentionally asymmetric with `scrobble*(…, owner: PlaybackOwnerContext)`. Architecture pin
`TrackingScrobbleServiceCheckinShapeTest` enforces this via reflection.

**Scrobble boundary check added (partial F-H-03).**
`TraktScrobbleService.checkScrobbleBoundary()` and `SimklScrobbleService.checkScrobbleBoundary()` now
emit a `playback.scrobble_rejected` trace event when `envelopeProfileId != activeProfileId`. The
`TraceMetadataEventsScrobbleRejectedTest` pin verifies the event shape.

**Skip-segment carve-out documented (F-12-01 / F-12-02).**
`ResolverOrchestrator` carries an explicit comment that `ResolverType.SKIP_SEGMENTS` is intentionally
omitted from every `MetadataDepth` schedule because player-skip latency requirements (sub-50 ms)
are incompatible with the resolver pipeline's identity-resolution overhead.
`SkipIntroRepository` is the canonical owner.
`SkipIntroRepositoryCanonicalSurfaceTest` enforces that skip-provider APIs are only called from
`SkipIntroRepository` or its registered sub-providers (`data/integration/skip/`).

**PlayerViewModel wires PlaybackOwnerContext on construction.**
`PlaybackOwnerContext` is built from `profileManager.activeProfileSession.value` inside the `run {}`
block that creates `PlayerRuntimeController`, and `playbackSessionRegistry.register(ownerContext)` is
called before any scrobble can fire. `unregisterPlaybackSession()` is called from both `stopAndRelease()`
and `onCleared()` (with null-guard on the token, so double-call is safe).

**MetadataDepth.PLAYER has one production caller.**
`PlayerRuntimeControllerMetadata.applyProviderLocalizedPlaybackMetadata()` issues
`metadataRouterFacade.fetchTvEnrichment(depth = MetadataDepth.PLAYER)` for localized title/art
enrichment. `ResolverOrchestrator.schedule(PLAYER)` schedules `ResolverType.TRACKING` (network);
SKIP_SEGMENTS is explicitly excluded.

---

## 2. Architecture surfaces in scope

| Surface | File | Status |
|---|---|---|
| `PlaybackSessionRegistry` | `core/playback/PlaybackSessionRegistry.kt` | active, single-slot |
| `PlaybackOwnerContext` | `core/playback/PlaybackOwnerContext.kt` | active, 5-field shape with init guards |
| `TrackingScrobbleService` (interface + impl) | `data/repository/TrackingScrobbleService.kt` | active |
| `TrackingProgressService` (interface + impl) | `data/repository/TrackingProgressService.kt` | active |
| `TraktScrobbleService` | `data/repository/TraktScrobbleService.kt` | active |
| `SimklScrobbleService` | `data/repository/SimklScrobbleService.kt` | active |
| `SkipIntroRepository` | `data/repository/SkipIntroRepository.kt` | active, canonical skip surface |
| `SkipApiShapes` | `core/integration/IntegrationApiShapes.kt:107` | active, 7 constants |
| `PlayerViewModel` | `ui/screens/player/PlayerViewModel.kt` | active, `@HiltViewModel` |
| `PlayerRuntimeController` | `ui/screens/player/PlayerRuntimeController.kt` | active |
| `PlayerRuntimeControllerPlaybackEvents` | `ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt` | active — scrobble heartbeat |
| `PlayerRuntimeControllerMetadata` | `ui/screens/player/PlayerRuntimeControllerMetadata.kt` | active — `MetadataDepth.PLAYER` caller |
| `PlayerRuntimeControllerLifecycle` | `ui/screens/player/PlayerRuntimeControllerLifecycle.kt` | active — releasePlayer path |
| `PlayerPlaybackSessionGuard` | `ui/screens/player/PlayerPlaybackSessionGuard.kt` | active |
| `ResolverOrchestrator` (MetadataDepth.PLAYER branch) | `core/metadata/router/ResolverOrchestrator.kt:51` | active, SKIP_SEGMENTS excluded |
| `PlaybackSessionRegistrySingleSlotTest` | `test/…/architecture/PlaybackSessionRegistrySingleSlotTest.kt` | pin — F-H-02 |
| `TrackingScrobbleServiceCheckinShapeTest` | `test/…/data/repository/TrackingScrobbleServiceCheckinShapeTest.kt` | pin — F-H-01 |
| `SkipIntroRepositoryCanonicalSurfaceTest` | `test/…/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt` | pin — F-12-02 |

---

## 3. Contracts this lane must satisfy

1. `TrackingScrobbleService.checkin` third parameter is `Int?` (not `PlaybackOwnerContext`). (F-H-01)
2. `PlaybackSessionRegistry` is single-slot; a second `register()` overwrites; stale `unregister()` is a no-op. (F-H-02)
3. Scrobble calls use `owner.ownerProfileId`, not the ambient active profile ID. Profile-boundary violation emits trace event AND prevents the write. (F-H-03 — partially satisfied; see H-01)
4. `TrackingProgressService` result-time re-check gates writes against stale sessions. (F-H-03 coverage)
5. Skip segments are fetched only through `SkipIntroRepository` or its registered sub-providers. (F-12-01 / F-12-02)
6. `MetadataDepth.PLAYER` schedules `ResolverType.TRACKING` only; `SKIP_SEGMENTS` is not routed through the metadata facade. (F-12-01)
7. `PlaybackOwnerContext` is registered before the first scrobble can fire; `PlayerViewModel.onCleared()` and `stopAndRelease()` both unregister idempotently.
8. Skip-segment cache keys are content-scoped, not language-scoped (anime segments are language-independent by design).
9. `checkScrobbleBoundary` emits telemetry on profile mismatch in both Trakt and Simkl scrobble services.
10. `HomeViewModelContinueWatching.checkin()` and `MetaDetailsViewModel.checkin()` call `checkin` without supplying `ownerProfileId`; these calls fall back to the ambient active-profile session.

---

## 4. Generated reports proving (or not) each contract

| Contract | Proof | Verdict |
|---|---|---|
| C-1: `checkin` shape `Int?` | `TrackingScrobbleServiceCheckinShapeTest` reflection pin — 4 params, third is `java.lang.Integer` | PASS |
| C-2: Single-slot registry | `PlaybackSessionRegistrySingleSlotTest` — overwrite + stale-noop + clear all pass | PASS |
| C-3: Scrobble boundary enforcement | `checkScrobbleBoundary` emits trace event only; no throw, no early return — write still proceeds on mismatch | PARTIAL / H-01 (P1) |
| C-4: `TrackingProgressService` result-time re-check | No call to `assertCanWriteProfileState` inside `TrackingProgressService`; no dedicated pin test | MISSING / H-02 (P1) |
| C-5: Skip via canonical surface only | `SkipIntroRepositoryCanonicalSurfaceTest` regex scan — 0 offenders | PASS |
| C-6: `SKIP_SEGMENTS` excluded from router | `ResolverOrchestrator.kt:47` comment + code — SKIP_SEGMENTS absent from all depth branches | PASS |
| C-7: Registration before first scrobble | `PlayerViewModel` init block registers before `PlayerRuntimeController` is constructed; no deferred first-scrobble window observed | PASS |
| C-8: Skip cache key is content-scoped | Cache keys are `"$contentId:$season:$episode"`, `"anime:$imdbId:$season:$episode"`, `"mal:$malId:$episode"`, `"kitsu:$kitsuId:$episode"` — no language component | PASS (by design) |
| C-9: `checkScrobbleBoundary` wired on both providers | TraktScrobbleService:294, SimklScrobbleService:248 — both present | PASS |
| C-10: `checkin` call sites without ownerProfileId | `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` both omit `ownerProfileId`; fallback to `currentState()` (ambient active profile) is documented behavior but not pinned | OPEN / H-03 (P2) |

---

## 5. Findings

### H-01 — `checkScrobbleBoundary` emits telemetry but does not halt the write (P1)

**Severity:** P1

**Location:**
- `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:294`
- `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt:248`

**Description:**
Both `checkScrobbleBoundary` implementations compare `envelopeProfileId != activeProfileId` and, on
mismatch, call `traceMetadataEvents.emitScrobbleRejected(…, reason = "STALE_SESSION_WRITE_REJECTED")`.
The function is `Unit`-returning and the callers (`enqueueCheckin`, `enqueueScrobble`) do not inspect
any return value or thrown exception — execution continues to `traktMutationOutboxCoordinator.enqueueAndDrain(…)`
regardless. The existing context ("Cluster B closed F-H-03") claimed this was a closed finding, but the
actual write is not rejected; only the trace event fires.

Contrast with `ContinueWatchingSnapshotService.canPublishProfileWrite()` which calls
`ProfileBoundaryEnforcer.assertCanWriteProfileState(…)` (throws `ProfileBoundaryException`) inside a
try/catch and returns `false` to gate the write. The scrobble path has no equivalent gate.

**Impact:** A scrobble in-flight during a profile switch will be credited to the old profile's Trakt/Simkl
account even after the active profile has changed, because the mutation proceeds despite the boundary signal.

**Recommendation:** Change `checkScrobbleBoundary` to throw (or rethrow) a `ProfileBoundaryException`,
or restructure `enqueueCheckin`/`enqueueScrobble` to return early when the check fails — matching the
pattern in `ContinueWatchingSnapshotService`. Add a unit test that verifies the write is suppressed,
not just traced.

---

### H-02 — `TrackingProgressService` result-time `assertCanWriteProfileState` re-check absent (P1)

**Severity:** P1

**Location:** `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt` (entire file)

**Description:**
The existing context states "Cluster B closed F-H-03 (P0): result-time `assertCanWriteProfileState`
re-check on scrobble completion — STALE_SESSION_WRITE_REJECTED reachable from scrobble path."
Inspection of `DefaultTrackingProgressService` finds no call to `assertCanWriteProfileState` or any
analogous profile-boundary gate. `TraktScrobbleService` and `SimklScrobbleService` contain only the
telemetry-only `checkScrobbleBoundary`. The `ProfileBoundaryEnforcer.assertCanWriteProfileState`
function that actually throws is called only from `ContinueWatchingSnapshotService` and `ProfileManager`
— not from any scrobble or progress-write path.

`DefaultTrackingProgressServiceTest` covers provider-routing correctness but does not assert any
boundary-rejection behavior. No dedicated pin test enforces the F-H-03 invariant at the service level.

**Impact:** The STALE_SESSION_WRITE_REJECTED signal is observable in traces but has no enforcement
effect in production. A late-arriving scrobble result (e.g., from a slow network) after a profile switch
will write to the wrong profile's history.

**Recommendation:** Implement a result-time boundary check in the scrobble completion path
(e.g., after `enqueueAndDrain` returns, compare the session that initiated the request against
`profileManager.activeProfileSession.value` and discard if they differ). Add a dedicated test that
asserts the write is rejected, not just traced.

---

### H-03 — `checkin` call sites in `HomeViewModelContinueWatching` and `MetaDetailsViewModel` omit `ownerProfileId` (P2)

**Severity:** P2

**Location:**
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt:590`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:3076`

**Description:**
Both call sites invoke `trackingScrobbleService.checkin(scrobbleItem)` without supplying `ownerProfileId`,
relying on the default `null` fallback. `DefaultTrackingScrobbleService.providerState(null)` resolves to
`trackingProviderStateService.currentState()` (ambient active profile). This is intentional — `checkin`
has no playback session context — but it is not enforced or documented by any architecture pin. If the
active profile changes between the time the UI action fires and the time `currentState()` is read, the
checkin is credited to the post-switch profile.

The F-H-01 pin correctly locks the `checkin` signature to `Int?` to prevent spurious context fabrication,
but no "Stage 2" caller-side pin enforces that high-value checkin paths supply an explicit `ownerProfileId`
when one is available.

**Impact:** Medium — checkin from Continue Watching or Details will occasionally credit the wrong
profile in a race with a profile switch. Likely rare in practice but undetectable without observability.

**Recommendation:** Either (a) document the ambient-profile fallback as a known architectural limitation
with a `// ARCHITECTURE: checkin is ambient-profile, no playback session` comment at both call sites, or
(b) add a caller-side pin that asserts `checkin` with `ownerProfileId = null` is only permitted from
non-playback call sites. A follow-up Stage 2 task should surface the active-profile ID from callers
that have it (e.g., a profile-aware ViewModel parameter).

---

### H-04 — `PlaybackOwnerContext.traktAccount` and `.simklAccount` are always `null` in production (P3)

**Severity:** P3

**Location:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:73-74`

**Description:**
`PlayerViewModel` constructs `PlaybackOwnerContext` with `traktAccount = null, simklAccount = null`.
These are the only production construction sites (confirmed by grep). Neither field is read anywhere in
`TrackingScrobbleService`, `TraktScrobbleService`, or `SimklScrobbleService` — the scrobble path uses
`owner.ownerProfileId` exclusively and resolves provider auth independently via `authSession(ownerProfileId)`.
The fields were presumably reserved for future per-session account pinning but carry no current semantic weight.

**Impact:** Low — no behavioral defect, but the fields add noise to `PlaybackOwnerContext` and may mislead
future maintainers into thinking per-session account context is being used when it is not.

**Recommendation:** Either populate the fields from the active session's provider accounts (if account-pinning
is planned) or remove them from `PlaybackOwnerContext` and adjust the architecture pin tests accordingly.

---

### H-05 — `checkScrobbleBoundary` race: `activeProfileId` is sampled without synchronizing with `submitMutation` (P2)

**Severity:** P2

**Location:** `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt:294-303`

**Description:**
`checkScrobbleBoundary` reads `profileManager.activeProfileId.value` (a `StateFlow` emission, snapshotted
at the time of the call). `submitMutation` is guarded by `pendingMutationMutex` but `checkScrobbleBoundary`
is called from `enqueueScrobble`/`enqueueCheckin`, which execute inside the drain coroutine launched from
`mutationScope`. Between the moment a mutation is enqueued and the moment it drains, the active profile
may have changed — meaning the boundary check fires against a `profileId` that has already moved. Combined
with the telemetry-only nature of the check (H-01), this creates a window where the scrobble proceeds
against the new profile's active state while the trace event logs the old profile comparison.

**Impact:** The trace event `STALE_SESSION_WRITE_REJECTED` may fire on profiles that are not actually
stale (false positive) if the profile switch happens between enqueue and drain, or may not fire at all
if the switch happens after the boundary check but before the actual network write.

**Recommendation:** Capture the profile ID at mutation-enqueue time (already present as `request.profileId`)
and verify it against `activeProfileId` at drain time as an atomic pair, rather than performing the check
in `checkScrobbleBoundary` with a fresh `profileManager.activeProfileId.value` snapshot.

---

### H-06 — Skip-segment cache does not vary by language; confirmed intentional but undocumented (P3)

**Severity:** P3

**Location:** `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt:117`

**Description:**
The primary `getSkipIntervals` cache key is `"$contentId:$season:$episode"` — no language component.
All anime-primary paths similarly use content identifiers only. This is correct because skip-segment
timestamps (intro/OP/ED start and end positions) are language-independent — they are the same regardless
of the audio track or subtitle language selected. However, this design decision is nowhere documented as
deliberate; a future maintainer might introduce a language parameter expecting differentiated results.

**Impact:** None currently. Documentation risk only.

**Recommendation:** Add a one-line comment at the cache key construction sites:
`// Language is intentionally excluded: skip timestamps are audio/subtitle-track-independent.`

---

### H-07 — `PlayerViewModel.stopAndRelease()` and `onCleared()` both call `unregisterPlaybackSession()`; double-unregister is idempotent but the dual call path is a latent maintenance hazard (P3)

**Severity:** P3

**Location:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:125-175`

**Description:**
`stopAndRelease()` calls `unregisterPlaybackSession()` (sets token to `null`). `onCleared()` also calls
`unregisterPlaybackSession()`. The null-guard (`playbackRegistrationToken?.let {…}; playbackRegistrationToken = null`)
makes the second call a no-op, so there is no correctness defect. However, `PlayerScreen` calls
`viewModel.stopAndRelease()` from at least four code paths (back-press, error, playback-ended, episode
stream switch) and Hilt will also invoke `onCleared()` when the ViewModel is collected. In some paths,
`stopAndRelease()` is called first and the `ownerState` slot clears immediately; in others, `onCleared()`
arrives first. The order is not contractually guaranteed and could cause subtle timing differences in
when `ProfileManager` drains its `pendingActiveProfileId`.

**Impact:** Low — the null-guard is present. But the asymmetry between explicit `stopAndRelease()` and
implicit `onCleared()` means profile-switch drain can happen at different points in the activity lifecycle
depending on exit path.

**Recommendation:** Consider moving `unregisterPlaybackSession()` exclusively into `onCleared()` (the
guaranteed teardown hook) and instead of explicit unregistration in `stopAndRelease()`, rely on
`onCleared()` being called. This would also simplify the dual-path tracking.

---

## 6. Dependency map

```
PlayerScreen
  └─ PlayerViewModel (@HiltViewModel, viewModelScope)
        ├─ PlaybackSessionRegistry.register(ownerContext)  ← on init
        ├─ PlaybackSessionRegistry.unregister(token)       ← stopAndRelease + onCleared
        ├─ PlayerRuntimeController
        │     ├─ emitScrobbleStart / emitScrobbleStop / emitPauseScrobble
        │     │     └─ TrackingScrobbleService.scrobbleStart/Stop (owner: PlaybackOwnerContext)
        │     │           └─ TraktScrobbleService / SimklScrobbleService
        │     │                 ├─ checkScrobbleBoundary()  [telemetry only, H-01]
        │     │                 └─ submitMutation → enqueueAndDrain → network
        │     ├─ skipIntroRepository.getSkipIntervals(…)
        │     │     └─ IntroDbIntegrationProvider / AniSkipIntegrationProvider / …
        │     └─ metadataRouterFacade.fetchTvEnrichment(depth = MetadataDepth.PLAYER)
        │           └─ ResolverOrchestrator(PLAYER) → TRACKING resolver only
        └─ TrackingScrobbleService.checkin(item)  ← NOT called from PlayerViewModel
              (called from HomeViewModelContinueWatching, MetaDetailsViewModel — H-03)

ProfileManager
  └─ subscribes ownerState (PlaybackSessionRegistry)
        └─ drains pendingActiveProfileId on idle (ProfileSwitchDeferralPolicy)

PlaybackSessionRegistry (singleton)
  └─ ownerState: StateFlow<PlaybackOwnerContext?>
  └─ register(context): String  [single-slot, overwrites]
  └─ unregister(token): Unit    [no-op for stale tokens]
```

---

## 7. Red-flag checklist

| Red flag | Verdict | Evidence |
|---|---|---|
| "Scrobble uses current profile instead of playback owner profile" | CLEAR | `DefaultTrackingScrobbleService.providerState(owner)` calls `currentState(owner.ownerProfileId)` — owner profile is used, not ambient active profile. Verified at lines 165-166 of `TrackingScrobbleService.kt`. |
| "checkin uses PlaybackOwnerContext (it shouldn't)" | CLEAR | F-H-01 pin enforces `ownerProfileId: Int?` shape via reflection. `DefaultTrackingScrobbleService.checkin` calls `providerState(ownerProfileId)`, not `providerState(owner)`. Pin still passes. |
| "PlaybackSessionRegistry concurrent register silently overwrites" | DOCUMENTED / MONITORED | F-H-02 pin documents single-slot behavior. No multi-VM concurrent scenario exists in the current codebase. If PiP is introduced, migration to `ConcurrentHashMap<String, PlaybackOwnerContext>` is required per pin comment. |
| "PLAYER MetadataDepth has no production caller" (F-04-01 / F-12-01) | CLEAR | `PlayerRuntimeControllerMetadata.applyProviderLocalizedPlaybackMetadata()` at line 70 is the single production caller. Depth is used for TV enrichment (localized title/art), not skip segments. |
| "Skip-segment fetch bypasses canonical metadata facade" (F-12-02) | BY DESIGN / CLOSED | `SkipIntroRepositoryCanonicalSurfaceTest` enforces the canonical surface. `ResolverOrchestrator` explicitly omits `SKIP_SEGMENTS`. `PlayerRuntimeController` calls `skipIntroRepository.getSkipIntervals()` directly. This is intentional per F-12-01 latency rationale. Closing F-12-02 as "by design". |
| "Player start path doesn't register PlaybackOwnerContext before scrobbling" | CLEAR | `PlaybackOwnerContext` is constructed and `register()` called inside the same `run {}` block that creates `PlayerRuntimeController`. The controller receives `playbackOwnerContext` as a constructor parameter. No scrobble can fire before registration. |
| "Trakt scrobble race with profile switch" | OPEN (H-01, H-05) | `checkScrobbleBoundary` fires on mismatch but does not halt the write. A scrobble in-flight during a profile switch will proceed to network. See H-01 and H-05. |
| "OpenSubtitles hash fetch — error path silently degrades subtitle UX" | ACCEPTABLE | `OpenSubtitlesHashIntegrationProvider.compute()` returns `null` on `NetworkError` or `HttpError`; `fetchAddonSubtitlesNow()` continues without hash — subtitle providers that support hash-matching will fall back to metadata-only matching. No crash, no hang. Backoff applies at the `IntegrationRuntime` level. |
| "Skip-segment cache key — does it vary by language? Should be language-independent for anime." | CORRECT / UNDOCUMENTED | Cache keys are content-scoped only (`contentId:season:episode`). Language is correctly excluded. No documentation at call site — see H-06. |
| "Trailer playback bypasses RuntimeTraceInterceptor" — F-I-05 closed (cluster D deferral) | CLEAR | YouTube trailer OkHttp clients (`youtubeTrailer.main` and `youtubeTrailer.probe`) in `NetworkModule.kt:746-778` both wire `taggingInterceptor` (application) and `traceInterceptor` (network) with explicit F-I-05 comments. Skip-API clients (AniSkip, AnimeSkip, ARM, IntroDb) use the base `okHttpClient` which includes `traceInterceptor` at line 127. |
| "PlayerViewModel disposal: Hilt scope leaks past activity destruction?" | LOW RISK | `PlayerViewModel` is `@HiltViewModel` — scoped to the Compose `ViewModelStoreOwner` (typically the `NavBackStackEntry`). `onCleared()` is called by Hilt/Jetpack when the entry leaves the back stack. `viewModelScope` cancels on `onCleared()`. `stopAndRelease()` in `DisposableEffect` on the lifecycle owner provides an earlier release on `ON_DESTROY`. Dual-call is idempotent (H-07). No leak path identified, but the dual-path unregistration order is non-deterministic. |
| "checkin call sites that don't supply ownerProfileId" | OPEN (H-03) | `HomeViewModelContinueWatching.kt:590` and `MetaDetailsViewModel.kt:3076` both omit `ownerProfileId`. Fallback to ambient active profile is documented behavior for non-playback checkins, but no architecture pin enforces caller-side contract. |
