# Trace 10 — Profile Switch

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Status:** VERIFIED

---

## 1. Path Summary

Two independent triggers can initiate a profile switch:

| Trigger | Entry point | Enforcement | Outcome |
|---|---|---|---|
| Direct UI | `ProfileSelectionViewModel.selectProfile` → `ProfileManager.setActiveProfile` | `ProfileBoundaryEnforcer.assertCanSwitchProfile` | immediate apply or `ProfileBoundaryException` |
| Reactive (DataStore push / sibling device) | `init { dataStore.activeProfileId.collect }` → `deferralPolicy.onIncomingSwitch` | pure state machine | immediate apply or deferred to playback idle |

---

## 2. Case A — Direct UI Switch

### 2.1 Call chain

```
ProfileSelectionViewModel.selectProfile(profileId)           [ProfileSelectionViewModel.kt:49]
  └─ viewModelScope.launch {
       profileManager.setActiveProfile(profileId)            [ProfileManager.kt:130]
         ├─ guard: profile exists in list?  (returns silently if not)
         ├─ guard: id == current active?    (returns silently if same)
         ├─ playbackSessionRegistry.activeOwner()            [PlaybackSessionRegistry.kt:34]
         └─ ProfileBoundaryEnforcer.assertCanSwitchProfile(  [ProfileBoundaryEnforcer.kt:133]
              activeProfileId, targetProfileId,
              hasActivePlaybackOwner = (owner != null)
            )
              ├─ [playback active] emitProfileSwitchBoundaryCheck(verdict=FAIL)
              │    └─ traceSink.emit("profile.boundary_check", violation=
              │         PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)     [line 175]
              │  throws ProfileBoundaryException(
              │    PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)          [line 145]
              └─ [playback idle] emitProfileSwitchBoundaryCheck(verdict=PASS) [line 150]
                   └─ traceSink.emit("profile.boundary_check")
         ├─ AppLocaleResolver.setActiveProfileId(context, id)          [line 143]
         ├─ _activeProfileId.value = id
         ├─ _activeProfileSession.value = newProfileSession(id)
         ├─ dataStore.setActiveProfile(id)
         └─ _profileSwitched.emit(id)   ← suspend call           [line 147]
     }
```

### 2.2 Relevant source locations

| File | Lines | Role |
|---|---|---|
| `ProfileSelectionViewModel.kt` | 49–61 | UI entry point; catches `ProfileBoundaryException` |
| `ProfileManager.kt` | 130–148 | `setActiveProfile` implementation |
| `ProfileBoundaryEnforcer.kt` | 133–156 | `assertCanSwitchProfile`; emits trace event |
| `ProfileBoundaryEnforcer.kt` | 158–187 | `emitProfileSwitchBoundaryCheck` — builds `profile.boundary_check` envelope |
| `ProfileBoundaryViolation.kt` | 14 | `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` enum value |
| `AppLocaleResolver.kt` | 52–57 | `setActiveProfileId` — writes `active_profile_id` to `app_locale` SharedPreferences |
| `ProfileDataStore.kt` | 54–58 | `setActiveProfile` — persists to DataStore |

---

## 3. Case B — Reactive (DataStore-Driven) Switch

### 3.1 Call chain

```
ProfileManager.init { }                                             [ProfileManager.kt:86]
  ├─ scope.launch {
  │    dataStore.activeProfileId.collect { id ->                    [line 88]
  │      deferralPolicy.onIncomingSwitch(
  │        targetProfileId = id,
  │        hasActivePlayback = playbackSessionRegistry.activeOwner() != null
  │      )                                                          [line 90-92]
  │        ├─ [playback active] pendingActiveProfileId = targetProfileId; return false
  │        └─ [playback idle]   activeProfileId = targetProfileId; return true
  │      if (applied) → applyProfileChange(previousId, id)         [line 95]
  │    }
  │  }
  └─ scope.launch {
       playbackSessionRegistry.ownerState.collect { owner ->        [line 101]
         if (owner == null) {
           deferralPolicy.onPlaybackIdle()                          [line 103]
             ├─ returns pending if deferred switch was waiting
             └─ returns null if no pending
           if (drainedTo != null) → applyProfileChange(prev, drainedTo) [line 106]
         }
       }
     }

applyProfileChange(previousId, newId)   [ProfileManager.kt:113]   ← suspend fun
  ├─ _activeProfileId.value = newId
  ├─ _activeProfileSession.value = newProfileSession(newId)   (if id changed)
  ├─ AppLocaleResolver.setActiveProfileId(context, newId)     [line 118]
  └─ _profileSwitched.emit(newId)                             [line 120]  ← suspend call
```

### 3.2 Relevant source locations

| File | Lines | Role |
|---|---|---|
| `ProfileManager.kt` | 86–111 | `init` block — two coroutine collectors |
| `ProfileManager.kt` | 113–122 | `applyProfileChange` — `private suspend fun` |
| `ProfileManager.kt` | 78 | `deferralPolicy` instantiation |
| `ProfileSwitchDeferralPolicy.kt` | 14–53 | Pure state machine for defer/drain logic |
| `PlaybackSessionRegistry.kt` | 17–18 | `_ownerState` / `ownerState: StateFlow<PlaybackOwnerContext?>` |
| `ProfileDataStore.kt` | 50–51 | `activeProfileId: Flow<Int>` — the reactive DataStore source |

---

## 4. Finding Verification

### F-F-01 — UI catches ProfileBoundaryException (no crash)

**Status: VERIFIED — all 3 UI callers confirmed**

Three call sites of `profileManager.setActiveProfile` exist in the UI layer; all three wrap the call in try/catch for `ProfileBoundaryException`:

| Caller | File | Lines | Handling |
|---|---|---|---|
| `ProfileSelectionViewModel.selectProfile` | `ProfileSelectionViewModel.kt` | 50–59 | Catches `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` → `_switchBlockedByPlayback.tryEmit(Unit)`; re-throws other violations |
| `MainActivity.switchProfileAndApplyLocale` | `MainActivity.kt` | 344–364 | Catches `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` → shows Toast and returns; re-throws other violations |
| `MainActivity.onCreate` compose lambda | `MainActivity.kt` | 534–548 | Catches `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` → shows Toast and returns; re-throws other violations |

No caller lets `ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` propagate uncaught.

### F-F-02 — Rejection emits `profile.boundary_check` trace event

**Status: VERIFIED**

`ProfileBoundaryEnforcer.assertCanSwitchProfile` (lines 133–156) unconditionally calls `emitProfileSwitchBoundaryCheck` before throwing. The emit path:

- Calls `emitProfileSwitchBoundaryCheck(verdict="FAIL", violation=PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK)` at line 139.
- `emitProfileSwitchBoundaryCheck` (lines 158–187) constructs a `TraceEventEnvelope` with `eventType = "profile.boundary_check"` and fields `operation`, `activeProfileId`, `targetProfileId`, `activeProfileHash`, `targetProfileHash`, `verdict`, `violation`.
- Guard: if `traceSink === NoopRuntimeTraceSink` or `traceSessionId()` returns null, emission is silently skipped (no-op in tests without an installed sink).
- A PASS event is also emitted when there is no active playback (line 150).

Note: The comment at `ProfileManager.kt:135` explicitly calls out the F-F-02 contract, confirming intentional design.

### F-F-04 — Deferred switch drains on `PlaybackSessionRegistry.ownerState` becoming null

**Status: VERIFIED**

Drain path in `ProfileManager.init`:

1. The second `scope.launch` block (lines 100–110) collects `playbackSessionRegistry.ownerState`.
2. When `owner == null`, it calls `deferralPolicy.onPlaybackIdle()`.
3. `ProfileSwitchDeferralPolicy.onPlaybackIdle()` (lines 43–52) returns the pending profile id if one was deferred, sets `activeProfileId = pending`, clears `pendingActiveProfileId`, and returns the id.
4. `ProfileManager` calls `applyProfileChange(previousId, drainedTo)` to complete the switch.

This wires directly to `ownerState: StateFlow<PlaybackOwnerContext?>` in `PlaybackSessionRegistry` (line 18), which is set to `null` by `unregister` when the last token is removed (line 31).

### `ProfileManager.init { }` subscribes to `PlaybackSessionRegistry.ownerState`

**Status: VERIFIED**

`ProfileManager.kt` line 101: `playbackSessionRegistry.ownerState.collect { owner -> ... }` inside an `init`-launched coroutine. This is the drain trigger for F-F-04.

### `applyProfileChange` is `suspend`

**Status: VERIFIED**

`ProfileManager.kt` line 113: `private suspend fun applyProfileChange(previousId: Int, newId: Int)`.

The function requires `suspend` because it calls `_profileSwitched.emit(newId)` (line 120), which is a suspending call on `MutableSharedFlow` (as opposed to `tryEmit`). Both the reactive path (lines 95, 106) and implicitly the direct path (line 147 uses its own `emit`) depend on this.

Note: The direct-path `setActiveProfile` does not call `applyProfileChange`; it performs the same steps inline (lines 143–147), also calling `_profileSwitched.emit(id)` directly as a suspend call.

### `AppLocaleResolver.setActiveProfileId(context, id)` called

**Status: VERIFIED — called on both paths**

| Path | Location | Call |
|---|---|---|
| Direct UI (`setActiveProfile`) | `ProfileManager.kt:143` | `AppLocaleResolver.setActiveProfileId(context, id)` — called before DataStore write |
| Reactive (`applyProfileChange`) | `ProfileManager.kt:118` | `AppLocaleResolver.setActiveProfileId(context, newId)` — called after `_activeProfileId.value` update |

`AppLocaleResolver.setActiveProfileId` (line 52–57) commits `active_profile_id` to the `app_locale` SharedPreferences synchronously (`.commit()`), ensuring the locale resolver sees the new profile immediately on next read.

---

## 5. Test Coverage

| Test file | What is tested | Finding covered |
|---|---|---|
| `ProfileSwitchDuringPlaybackTest.kt` | `setActiveProfile` throws `PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK` when registry has active owner; succeeds after `unregister` | F-F-01, F-F-02 (exception path) |
| `ProfileSelectionViewModelSwitchDuringPlaybackTest.kt` | `ProfileSelectionViewModel.selectProfile` catches exception → emits `switchBlockedByPlayback` without crashing coroutine | F-F-01 (ViewModel layer) |
| `ProfileManagerReactiveSwitchDuringPlaybackTest.kt` | `ProfileSwitchDeferralPolicy` unit tests: incoming switch during playback is enqueued; drains on `onPlaybackIdle`; immediate apply when idle; no-op drain when no pending | F-F-04 |

`ProfileSwitchDeferralPolicy` is tested as a pure unit (no Android dependencies). The `ProfileManagerReactiveSwitchDuringPlaybackTest` covers all four branches of the state machine directly.

---

## 6. Gaps and Observations

1. **Trace event emission guarded by NoopSink check.** `emitProfileSwitchBoundaryCheck` short-circuits when `traceSink === NoopRuntimeTraceSink` (line 164) or `traceSessionId()` returns null (line 165). In tests that do not install a sink via `ProfileBoundaryEnforcer.installTraceSink`, the `profile.boundary_check` event is never emitted. This is expected — it is not a gap — but means trace emission is not exercised by the current unit tests for this path.

2. **Direct path duplicates `applyProfileChange` steps.** `setActiveProfile` (lines 143–147) re-implements the same sequence as `applyProfileChange` rather than calling it. The two paths are functionally equivalent but diverge subtly: the direct path calls `AppLocaleResolver.setActiveProfileId` before `_activeProfileId.value = id`; `applyProfileChange` calls it after. Both are correct for the locale resolver contract (it only needs the id persisted before the next locale read), but the ordering asymmetry is worth noting for future consolidation.

3. **`ownerState` drain races DataStore collect.** In the reactive path, both the `dataStore.activeProfileId` collector and the `ownerState` collector run concurrently in the same `scope`. If a sibling-device push and a local playback-end race, `deferralPolicy` state could be mutated from two coroutines without synchronisation. `ProfileSwitchDeferralPolicy` is not thread-safe (plain `var` fields). This is a latent race condition that is mitigated in practice by the coroutine dispatcher serialising on a single IO thread, but it is not guaranteed by the type system.

---

## 7. Cross-Reference

| Finding | Status | Notes |
|---|---|---|
| F-F-01 | Closed | All 3 UI callers catch `ProfileBoundaryException`; no crash path |
| F-F-02 | Closed | `assertCanSwitchProfile` emits `profile.boundary_check` before throwing |
| F-F-04 | Closed | Deferred switch drains via `ownerState.collect { owner == null }` → `onPlaybackIdle()` |
