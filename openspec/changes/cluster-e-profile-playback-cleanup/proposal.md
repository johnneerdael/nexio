## Why

The architecture audit (`review-dossier/09-known-gaps.md`) identified 8 cluster-E findings around profile-boundary edges and legacy scope cleanup:

- **F-F-03 (P2):** `ProfileMetadataOverlay` + `ProfileResolvedDisplayDocument` defined but no production callers (only `CompositionTypeShapeTest` references them). Dead-code surface.
- **F-F-04 (P2):** Reactive `dataStore.activeProfileId.collect` in `ProfileManager` silently `Log.w` + `return@collect` when playback is active. Sibling-device push of profile change is silently dropped — user expects the push to apply, instead context spontaneously reverts.
- **F-F-05 (Nit):** `validateLegacyAccountScope` is a partial guard — only blocks `profile:` substring, doesn't block credential leakage. Becomes unreachable after F-J-02 deletion.
- **F-H-01 (P2):** No architecture pin asserts `checkin()` retains `ownerProfileId: Int? = null` shape. A future "harmonize the scrobble surface" refactor would compile and pass tests but force checkin call sites to fabricate a context that never existed.
- **F-H-02 (P2):** `PlaybackSessionRegistry` is single-slot — concurrent `PlayerViewModel`s would silently overwrite each other's registration. Today no callers consume `activeOwner()` so it's latent; document the constraint via test.
- **F-J-02 (Nit):** `IntegrationScope.Account(providerAccountId)` legacy constructor is `@Deprecated(level = ERROR)`, already unreachable. Safe to delete.
- **F-J-03 (P2):** `IntegrationScope.Global` is `@Deprecated` but still constructed in `OpenSubtitlesHashIntegrationProvider.kt:44`.
- **F-J-04 (Nit):** `@Deprecated` markers lack `ReplaceWith` and removal date.

This change closes all 8.

## What Changes

### MODIFIED

- `ProfileManager.init { ... dataStore.activeProfileId.collect { ... } }` (lines 84-101): silent ignore replaced with `pendingActiveProfileId` enqueue. On `playbackSessionRegistry.isIdle()` transition, drain the pending switch (F-F-04).
- `OpenSubtitlesHashIntegrationProvider.kt:44`: `IntegrationScope.Global` → `IntegrationScope.GlobalContent` (F-J-03).
- `IntegrationScope.kt:36` `@Deprecated("Use GlobalContent...")` for `Global` gains `ReplaceWith("IntegrationScope.GlobalContent")` (F-J-04).

### REMOVED

- `app/src/main/java/com/nexio/tv/core/metadata/composition/ProfileMetadataOverlay.kt` (F-F-03 — zero callers).
- `app/src/main/java/com/nexio/tv/core/metadata/composition/ProfileResolvedDisplayDocument.kt` (F-F-03 — depends on overlay).
- `IntegrationScope.Account(providerAccountId)` legacy constructor (F-J-02 — already `@Deprecated(level = ERROR)`).
- `ProfileBoundaryEnforcer.validateLegacyAccountScope(...)` and its single caller at line 277 (F-F-05 + F-J-02 — unreachable after Account ctor deletion).

### ADDED

- `ProfileManagerReactiveSwitchDuringPlaybackTest` (F-F-04 regression).
- `TrackingScrobbleServiceCheckinShapeTest` reflection-based pin (F-H-01).
- `PlaybackSessionRegistrySingleSlotTest` (F-H-02 — documents single-slot constraint).
- `IntegrationScopeGlobalDeprecatedNoCallersTest` (F-J-03 — production never constructs `Global`).
- `DeprecatedAnnotationsHaveReplaceWithTest` (F-J-04 — every `@Deprecated` in production has `ReplaceWith`).

## Impact

- Affected specs: `integration-runtime`.
- Affected code: 4 production files modified + 2 production files deleted + 5 new test files.
- Behavior changes:
  - Sibling-device profile-switch pushes during playback are now **deferred and applied on playback end** (F-F-04) instead of silently dropped.
  - `OpenSubtitlesHashIntegrationProvider` now uses `GlobalContent` scope (no behavior change — `GlobalContent.storageKey = "global:content"`, `Global.storageKey = "global"`; cache keys for OpenSubtitles will rotate once on next deploy).
  - All `@Deprecated` markers gain machine-actionable `ReplaceWith` for IDE quick-fix support.
- No new dependencies. No new trace events. No new migrations.
