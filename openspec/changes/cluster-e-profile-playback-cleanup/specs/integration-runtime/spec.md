## ADDED Requirements

### Requirement: Reactive profile-switch during playback is deferred, not dropped

`ProfileManager`'s reactive `dataStore.activeProfileId.collect` MUST NOT silently drop a switch when playback is active. The new active profile id MUST be stored in a `pendingActiveProfileId` slot, and drained the next time `playbackSessionRegistry.isIdle()` becomes true.

#### Scenario: Sibling-device profile push during active playback applies on playback end

- **GIVEN** profile 1 is active AND `playbackSessionRegistry.activeOwner()` is non-null
- **WHEN** `dataStore.activeProfileId` emits 2
- **THEN** the switch is NOT applied immediately
- **AND** `pendingActiveProfileId` holds `2`
- **WHEN** playback ends (`unregister` token, registry becomes idle)
- **THEN** `_activeProfileId.value` becomes `2` and the pending slot is cleared

### Requirement: TrackingScrobbleService.checkin retains the legacy ownerProfileId surface

`TrackingScrobbleService.checkin(item, message, ownerProfileId)` MUST keep `ownerProfileId: Int? = null` as the third parameter shape (NOT `PlaybackOwnerContext`). A reflection-based architecture pin asserts the contract.

#### Scenario: Reflection scan rejects a refactor that changes the checkin shape

- **WHEN** `TrackingScrobbleService.checkin`'s third parameter is something other than `Int?`
- **THEN** `TrackingScrobbleServiceCheckinShapeTest` fails

### Requirement: PlaybackSessionRegistry single-slot constraint is documented and pinned

`PlaybackSessionRegistry` is intentionally single-slot. An architecture pin documents the constraint and asserts that calls to `register(...)` overwrite previous registration (rather than accumulate). Future features that need multi-VM registry semantics MUST migrate to a `ConcurrentHashMap<String, PlaybackOwnerContext>` AND document the migration.

#### Scenario: Concurrent register calls retain only the most recent

- **WHEN** `register(contextA)` returns tokenA, then `register(contextB)` is called BEFORE `unregister(tokenA)`
- **THEN** `activeOwner()` returns `contextB` (the most recent)
- **AND** `unregister(tokenA)` is a no-op (the slot no longer holds tokenA's entry)

### Requirement: IntegrationScope.Global MUST NOT be constructed by production code

Production code MUST NOT construct `IntegrationScope.Global`. A production-source architecture pin scans `app/src/main/java` for `IntegrationScope.Global` constructions. The only allowed reference site is `ProfileBoundaryEnforcer.kt` (where the deprecated value is referenced for backwards-compat boundary checks).

#### Scenario: Adding IntegrationScope.Global construction in a provider trips the test

- **WHEN** a developer writes `scope = IntegrationScope.Global` in any production file outside the allowlist
- **THEN** `IntegrationScopeGlobalDeprecatedNoCallersTest` fails

### Requirement: Production @Deprecated markers carry ReplaceWith

Every `@Deprecated` annotation in `app/src/main/java` MUST include a `replaceWith = ReplaceWith(...)` argument so IDE quick-fix can offer a migration. The architecture pin scans source.

#### Scenario: Adding a bare @Deprecated trips the test

- **WHEN** a developer adds `@Deprecated("Use NewThing")` without `replaceWith`
- **THEN** `DeprecatedAnnotationsHaveReplaceWithTest` fails

## REMOVED Requirements

### Requirement: ProfileMetadataOverlay + ProfileResolvedDisplayDocument exist as composition surface

**Reason:** Zero production callers. Only test reference is `CompositionTypeShapeTest`. Deferred-refactor surface that should not exist if no caller is wiring it.

**Migration:** Delete both data classes; prune the test references. If a future profile-overlay composition concern emerges, design it from the actual caller backwards.

### Requirement: IntegrationScope.Account(providerAccountId) legacy constructor exists

**Reason:** Already `@Deprecated(level = ERROR)` — uncallable from Kotlin. Safe to remove.

**Migration:** None required (no compilable callers). Delete the constructor body and the `providerAccountId` field if it's only set in this constructor.

### Requirement: ProfileBoundaryEnforcer.validateLegacyAccountScope blocks profile-bound cache keys

**Reason:** Was a partial guard against the now-removed `IntegrationScope.Account(providerAccountId)` legacy constructor. Once that constructor is deleted (above requirement), the validator becomes unreachable.

**Migration:** Delete the validator method and its single caller at `ProfileBoundaryEnforcer.kt:277`.
