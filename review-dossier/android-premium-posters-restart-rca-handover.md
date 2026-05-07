# Android Premium Posters Restart RCA Handover

Date: 2026-05-07

Status: root cause is narrowed to a specific runtime boundary, but the deepest cause is not fully proven yet. The app still needs a fresh on-device run with the latest diagnostics from `4389da5d2 fix(posters): add restart decision diagnostics`.

## Executive Summary

Premium RPDB posters can appear during a runtime session, disappear after app restart, and then slowly reappear as hydration runs again. That behavior violates the intended integration runtime contract: once a premium poster decision has resolved and been persisted, restart should restore a durable artwork decision or fall back to a non-premium candidate. A restart should not erase the poster decision.

The evidence collected from `adb logcat` on `192.168.50.71:5555` proves this failure mode:

1. Runtime hydration creates premium artwork decisions and persists them.
2. The home snapshot sanitizer later sees persisted `nexio-artwork://decision/...` poster refs as missing decisions.
3. The sanitizer clears the poster and `posterProviderTag`.
4. The damaged snapshot is written back.
5. The next snapshot read rejects or degrades the snapshot due poster provider tag mismatch.
6. Hydration later recreates decisions, explaining why posters slowly reappear.

The remaining unproven question is why the durable decision lookup returns false at snapshot sanitization time. Before the latest diagnostics, logcat did not show `artwork.decision_store_load`, so we could not distinguish between file absence, unreadable file, parse/schema failure, cache not loaded yet, wrong cache instance/path, or decision key mismatch.

## User-Visible Symptom

Observed behavior:

- Premium posters are missing from several rails after restart.
- Some posters slowly reappear while the app continues running.
- Restart pushes multiple posters back to the bad state.
- The issue affects the home/catalog display where premium posters should have either a durable premium decision or a non-premium fallback.

The key implication is that this is not just a network loading delay. If a poster was already shown in a previous run, the app should have a durable artwork identity and/or fallback decision that survives process restart.

## Current Proof From Logcat

Device used for investigation:

- ADB target: `192.168.50.71:5555`
- Package observed: versionCode `73`, versionName `0.55`
- PID observed during the investigation: `26926`

Useful command:

```bash
adb -s 192.168.50.71:5555 logcat -d -v time \
  Nexio.IntRuntime:D Nexio.MetaRoute:D Nexio.FirstPaint:D '*:S' |
  rg 'artwork\.decision_store_load|artwork\.decision_store_write|artwork\.decision_put|home\.snapshot_read|home\.snapshot_write|home\.snapshot_sanitize_artwork|home\.snapshot_decision_lookup'
```

Proven runtime events before latest diagnostics:

```text
Nexio.IntRuntime: t=artwork.decision_put ... provider=RPDB imageType=POSTER sourceRole=PREMIUM rejectedCount=1 hasFallbackCandidate=true
Nexio.IntRuntime: t=artwork.decision_store_write success=true decisionCount=748 linkCount=0 errorClass=null
```

These prove that RPDB premium decisions are produced and persisted during runtime.

Proven destructive snapshot path:

```text
Nexio.MetaRoute: t=home.snapshot_sanitize_artwork scope=catalogRows[0].items[13] reason=missing_decision posterKind=decision posterProviderTag=rpdb decisionFound=false
Nexio.MetaRoute: t=home.snapshot_write success=true profileId=1 catalogRowCount=8 fullCatalogRowCount=8 heroItemCount=7 errorClass=null
Nexio.MetaRoute: t=home.snapshot_read success=false profileId=1 snapshotFound=true ... requiredPosterProviderTag=rpdb reason=poster_provider_tag_mismatch errorClass=null
```

These prove that snapshot sanitization is clearing decision-backed premium poster refs because the durable decision lookup returns false.

Important negative evidence:

```text
No artwork.decision_store_load events were found in the gated logcat output.
```

That missing event was the reason the previous instrumentation was not enough to finish the RCA. We could prove where posters were destroyed, but not why the cache lookup returned false.

## Current Root Cause Statement

The confirmed root cause boundary is:

`HomeCatalogSnapshotStore` destructively clears decision-backed poster refs during snapshot sanitization when `ArtworkDecisionCache.get(ArtworkDecisionKey)` returns null. That sanitized snapshot is then persisted, so restart can re-enter the bad state even if the poster appeared in a previous run.

The not-yet-proven underlying cause is one of:

- Durable decision store file is missing on startup.
- Durable decision store file exists but is unreadable.
- Durable decision store load fails due parse/schema/data issue.
- The cache is not loaded or not authoritative when snapshot sanitization runs.
- Snapshot store is checking a different cache instance or path than the runtime writer uses.
- The decision key embedded in the snapshot does not match the decision key persisted by the cache.
- The decision is invalidated between write and snapshot read due settings/credential/provider policy mismatch.

The latest instrumentation is intended to separate these cases.

## Shared Components And Responsibilities

### `HomeCatalogSnapshotStore`

File: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

Responsibilities:

- Reads and writes the cached home snapshot.
- Encodes the active poster provider token and effective language into snapshot policy.
- Sanitizes unsafe poster refs before persisting/restoring.
- Rejects snapshots whose `posterProviderTag` does not match the active provider.

Role in this bug:

- It is the component that clears the poster and provider tag when `nexio-artwork://decision/...` does not resolve in the durable decision cache.
- It persists the sanitized result, making the bad state survive restart.
- It previously logged `home.snapshot_sanitize_artwork`, but not enough cache state to explain why `decisionFound=false`.

### `ArtworkDecisionCache` / `DurableArtworkDecisionCache`

Files:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`

Responsibilities:

- Stores the durable mapping from `ArtworkDecisionKey` to selected poster candidate.
- Persists premium and fallback candidate metadata without raw secrets.
- Restores decision state across process restarts.
- Invalidates decisions when settings or credential hashes change.

Role in this bug:

- Runtime writes prove this component receives premium RPDB decisions.
- Snapshot reads prove lookups return null for some decision refs.
- Before `4389da5d2`, load-state and file-state diagnostics were not visible in logcat, preventing final proof.

### Artwork Materialization / Image Runtime

Relevant shared responsibility:

- Converts an artwork decision into a loadable poster asset.
- Should use the durable decision and non-premium fallback candidate instead of raw premium URLs in persistent UI state.

Role in this bug:

- Runtime can recreate posters later, so materialization itself is not proven to be the restart-loss cause.
- However, fallback candidate durability remains part of the invariant: if premium cannot be restored immediately, a non-premium fallback should be available and should not be erased by snapshot sanitization.

### Metadata Router And Home Hydration

Relevant shared responsibility:

- Resolves canonical metadata and applies display fields to the home surface.
- Eventually recreates artwork decisions after restart.

Role in this bug:

- Slow poster reappearance points to hydration repairing the surface after startup.
- Hydration repair is not enough because restart destroys or rejects the cached poster state again.

### Logcat Trace Runtime

Files:

- `app/src/main/java/com/nexio/tv/core/trace/LogcatTraceChannel.kt`
- `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`

Responsibilities:

- Routes `FirstPaint`, `MetaRoute`, and `IntRuntime` trace events to gated logcat channels.
- Allows on-device proof without broad noisy logging.

Role in this bug:

- Existing logcat proved the destructive snapshot path.
- Missing `artwork.decision_store_load` proved instrumentation was insufficient at the cache load/lookup boundary.
- New `home.snapshot_decision_lookup` and stronger `artwork.decision_store_load` fields are now the key proof tools.

## Fixes And Instrumentation Tried So Far

### Premium poster persistence and fallback work

Prior fixes attempted to move away from raw premium poster URLs and toward durable decision refs:

- Store `nexio-artwork://decision/...` refs instead of raw RPDB URLs in home snapshots.
- Keep non-premium fallback candidates alongside premium decisions.
- Materialize fallback artwork when premium asset materialization cannot be used.
- Avoid storing raw premium provider URLs or secrets in durable JSON.

This was necessary but not sufficient. The current bug shows that a decision ref can still be cleared on restart if the durable decision lookup fails.

### Durable decision cache hardening

Commit: `ccda6d9b3 fix(posters): prove durable artwork restarts`

Changes included:

- `artwork.decision_put`
- `artwork.decision_store_write`
- `artwork.decision_store_load`
- `home.snapshot_read`
- `home.snapshot_write`
- `home.snapshot_sanitize_artwork`
- Malformed persisted decisions are dropped individually rather than clearing the whole store.

Result:

- This proved runtime writes and snapshot sanitization.
- It did not prove why the durable lookup returned false, because no `artwork.decision_store_load` appeared in the collected logcat.

### Latest proof instrumentation

Commit: `4389da5d2 fix(posters): add restart decision diagnostics`

Changes included:

- `ArtworkDecisionCacheDiagnostics`
- `ArtworkDecisionCacheSnapshotDiagnostics`
- Durable cache load diagnostics:
  - `loaded`
  - `decisionCount`
  - `linkCount`
  - `storeFilePresent`
  - `storeFileReadable`
  - `storeFileBytes`
  - `lastLoadSuccess`
  - `lastLoadReason`
  - `lastLoadErrorClass`
  - `droppedDecisionCount`
- Stronger `artwork.decision_store_load` log fields:
  - `fileReadable`
  - `fileBytes`
- New `home.snapshot_decision_lookup` MetaRoute event:
  - `scope`
  - `decisionFound`
  - `decisionKeyHash`
  - `posterKind`
  - `posterProviderTag`
  - cache load/file diagnostics
  - `lookupErrorClass`

Result expected after deployment:

- The next restart log should identify the exact reason `decisionFound=false` occurs.

## How To Interpret The Next Logs

Look for `home.snapshot_decision_lookup` before each `home.snapshot_sanitize_artwork reason=missing_decision`.

Decision table:

| Log evidence | Meaning | Likely fix area |
| --- | --- | --- |
| `cacheLoaded=false` | Snapshot sanitization ran before cache was loaded or before load state was authoritative | Load ordering or sanitizer policy |
| `storeFilePresent=false` and `decisionCount=0` | Decision store is not present at lookup time | Persistence path, storage lifecycle, profile/account scope |
| `storeFilePresent=true`, `storeFileBytes>0`, `lastLoadSuccess=false` | Store exists but load failed | JSON schema, migration, parse quarantine |
| `lastLoadReason=schema_version_mismatch` | Persisted schema cannot be restored | Forward-compatible migration |
| `lastLoadErrorClass` non-null | Load or lookup threw | Inspect exception class and parser/file access |
| `cacheLoaded=true`, `cacheDecisionCount>0`, `decisionFound=false` | Cache has decisions but not this key | Decision key mismatch, invalidation, provider/settings/credential hash mismatch |
| `decisionFound=false` followed by later `artwork.decision_put` for same hashed identity | Hydration recreates a decision that snapshot could not restore | Startup cache lookup/order/key mismatch |

## Important Invariant For The Real Fix

The real fix should preserve this invariant:

If a snapshot contains a `nexio-artwork://decision/...` poster ref, the app should not destructively clear and persist that poster solely because a lookup is missing unless the durable cache load is known to be successful and authoritative for the same store/path/profile/provider policy.

If cache state is unknown, unavailable, or failed to load, the snapshot should avoid converting a recoverable decision ref into permanent poster loss. At minimum, the app should retain a non-premium fallback poster decision or avoid writing the sanitized bad state back to disk.

## Likely Fix Paths After Proof

Do not pick one until `home.snapshot_decision_lookup` proves the case.

Possible fixes:

1. Cache not loaded or non-authoritative at sanitizer time:
   - Force durable artwork cache load before snapshot read/sanitize.
   - Or change sanitizer behavior so unknown cache state does not destructively clear decision refs.

2. Wrong cache instance or file path:
   - Audit Hilt binding and storage path for `ArtworkDecisionCache`.
   - Ensure snapshot store and runtime artwork resolver use the same singleton and same file.

3. Decision key mismatch:
   - Centralize key construction and compare `decisionKeyHash` across snapshot refs and `artwork.decision_put`.
   - Ensure settings hash, credential hash, image language, provider, canonical id, and policy version are stable across restart.

4. Store parse/schema failure:
   - Add forward-compatible schema migration.
   - Quarantine invalid decisions while restoring valid ones.
   - Ensure failures do not clear all decisions or cause snapshot destruction.

5. Provider/settings invalidation:
   - Confirm poster provider token, credential hash, and settings hash used by the decision key are identical before and after restart.
   - If provider state is not ready at first read, delay provider-scoped snapshot validation until it is authoritative.

## Current Engineering Handoff State

What is proven:

- Premium RPDB decisions are created and written during runtime.
- Snapshot sanitization clears decision-backed posters when cache lookup returns false.
- The cleared snapshot is persisted.
- Snapshot read then rejects/degrades due provider tag mismatch.
- Hydration later recreates decisions, explaining slow reappearance.

What is not proven yet:

- The precise reason durable lookup returns false at restart/startup snapshot sanitization time.

Next action:

1. Deploy/build a version including `4389da5d2`.
2. Restart the app on `192.168.50.71`.
3. Capture gated logcat.
4. Compare `home.snapshot_decision_lookup` diagnostics against `artwork.decision_store_load`, `artwork.decision_put`, `home.snapshot_sanitize_artwork`, and `home.snapshot_write`.
5. Choose the fix path from the decision table above.

## Verification Already Run For Latest Instrumentation

Focused tests passed:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest \
  --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest \
  --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest \
  --tests com.nexio.tv.core.trace.LogcatTraceChannelTest
```

Whitespace check passed on touched files:

```bash
git diff --check
```

