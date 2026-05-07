# Android Premium Posters Restart RCA Handover

Date: 2026-05-07

Status: root cause is now narrowed to persisted artwork reference integrity. The implementation in this branch adds the first complete protection layer: non-destructive snapshot reads, decision-to-asset recovery, a write barrier for artwork refs, legacy fallback visibility, and tests for the restart-loss cases.

## Executive Summary

Premium posters can appear during one app run, disappear after restart, and slowly reappear as home hydration runs again. That violates the integration runtime contract: once artwork has resolved, restart must restore a durable artwork decision, a durable asset, or a safe fallback. Restart must not convert a previously rendered poster into a permanently damaged snapshot.

The strongest evidence so far shows two related failures:

1. The home snapshot sanitizer treated a missing decision lookup as authoritative even when the durable decision cache was failed, partial, or otherwise not trustworthy.
2. Some persisted `nexio-artwork://decision/...` refs had no matching durable decision record, even though premium asset bytes could exist and render.

The bug is therefore not simply "RPDB selection failed." Premium selection and asset rendering can work. The broken boundary is persisted artwork reference integrity across restart.

## User-Visible Symptom

Observed behavior:

- Premium posters are missing from several home/catalog rails after restart.
- Some posters slowly reappear while the app keeps running.
- Restart pushes multiple posters back into the bad state.
- Missing poster cards may still have provider state, causing snapshot mismatch behavior.
- Non-premium fallback artwork is not consistently used when a premium decision ref cannot be restored.

The key implication: this is not only a network delay. If a poster appeared in a previous run, the app should have enough durable identity to render it or fall back without destructive cleanup.

## Evidence Collected

Device investigations used ADB logcat on the local network, including `192.168.50.71:5555` during RCA and `192.168.50.98:5555` as the release-build verification target.

Earlier gated logcat proved runtime writes:

```text
artwork.decision_put provider=RPDB imageType=POSTER sourceRole=PREMIUM hasFallbackCandidate=true
artwork.decision_store_write success=true decisionCount=748
```

Those events prove the runtime produced and persisted premium artwork decisions.

The same investigation proved destructive snapshot cleanup:

```text
home.snapshot_sanitize_artwork reason=missing_decision posterKind=decision posterProviderTag=rpdb decisionFound=false
home.snapshot_write success=true
home.snapshot_read success=false reason=poster_provider_tag_mismatch
```

Later evidence narrowed the decision-store boundary:

```text
storeFilePresent=true
storeFileReadable=true
storeFileBytes=545965
cacheLoaded=true
cacheDecisionCount=700
lastLoadSuccess=false
lastLoadErrorClass=ClassCastException
decisionFound=false x456
home.snapshot_sanitize_artwork reason=missing_decision x456
home.snapshot_read success=false reason=poster_provider_tag_mismatch
```

That changed the root cause from "maybe the decision file is missing" to:

```text
The durable store can exist and contain decisions, but lookup misses are not authoritative when load failed or was partial.
Snapshot cleanup used those misses destructively anyway.
```

Newer RCA also showed:

```text
Premium asset files can exist and contain valid image bytes.
Some decision refs have no matching durable decision record.
Some materialization succeeds from disk.
```

That means persisted decision refs need recovery from durable asset records, not only decision-store lookup.

## Current Root Cause Statement

The root cause is persisted artwork reference integrity failure:

```text
Home snapshot persisted a premium artwork decision ref.
On restart, durable decision lookup could be missing, failed, partial, or orphaned.
Snapshot read/sanitize treated that state as destructive authority.
The snapshot was cleared or rejected, then written back damaged.
Hydration later recreated artwork, but the next restart could repeat the cycle.
```

The invariant that must hold is:

```text
A failed, partial, unknown, or orphaned artwork decision state must never cause destructive snapshot cleanup during read.
```

The stronger write-side invariant is:

```text
A persisted snapshot should not claim a premium poster is valid unless the artwork ref is backed by a durable decision, a durable asset, or a safe fallback/rehydration path.
```

## Shared Components And Their Roles

### `HomeCatalogSnapshotStore`

File: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

Responsibilities:

- Read and write the cached home snapshot.
- Preserve home rows for fast startup.
- Track provider tag compatibility for artwork fields.
- Sanitize unsafe persisted refs.

Role in the bug:

- It destructively cleared decision-backed poster refs when decision lookup returned null.
- It persisted the sanitized result, making bad state survive restart.
- It rejected whole snapshots for `poster_provider_tag_mismatch`, even though the problem was item-scoped artwork drift.

Current fix:

- Snapshot read no longer fails the whole snapshot for provider-tag mismatch.
- Missing/unknown decision refs are preserved during read and marked for rehydration.
- Snapshot write now validates artwork refs through an integrity validator.
- Provider tags are cleared with missing posters and repaired when asset recovery can replace a decision ref.

### `DurableArtworkDecisionCache`

Files:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`

Responsibilities:

- Persist decisions from selected artwork candidates.
- Restore decisions across process restart.
- Expose typed lookup state instead of a nullable boolean result.

Role in the bug:

- Runtime decision writes were observed.
- Store load could be partial/failed with `ClassCastException`.
- Lookup misses were previously consumed as if authoritative.

Current fix boundary:

- The branch relies on typed decision lookup results already introduced in earlier work.
- Missing decisions are no longer enough to destroy snapshot state during read.
- Missing authoritative decisions can now recover through the asset reverse index.

### `ArtworkAssetRepository`

File: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`

Responsibilities:

- Materialize decisions into durable image assets.
- Serve disk hits without repeating network fetches.
- Track trace state for runtime cache decisions.

Role in the bug:

- Premium bytes could exist even when the matching decision record was unavailable.
- Without a reverse index, a `nexio-artwork://decision/...` URI could not recover from an existing asset.

Current fix:

- Records durable asset metadata when assets are written or reconstructed from disk.
- Adds `decisionKey -> latest asset record` recovery.
- Missing decision plus valid asset now returns a disk-backed result and emits recovery diagnostics.
- Duplicate record writes are skipped where possible to reduce write amplification.

### `ArtworkAssetDiskCache`

File: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetDiskCache.kt`

Responsibilities:

- Own concrete cached artwork files.
- Keep file access inside the cache root.
- Provide lightweight image byte validation.

Current fix:

- Adds canonical path guards to prevent path traversal.
- Adds readable image header checks for JPEG, PNG, and WebP.
- Supports reverse-index recovery only when the file exists and looks like image bytes.

### `DurableArtworkAssetRecordStore`

Files:

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRecordStore.kt`
- `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkAssetRecordStore.kt`

Responsibilities:

- Persist asset records independently from decision records.
- Provide `findLatestAssetForDecision(decisionKey)`.
- Quarantine malformed records without dropping valid records.

Current fix:

- Uses explicit DTO persistence rather than direct domain-object JSON.
- Refuses writes when a future schema is detected.
- Restores valid records while quarantining bad ones.

### `ArtworkReferenceIntegrityValidator`

File: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkReferenceIntegrityValidator.kt`

Responsibilities:

- Validate snapshot artwork refs before write.
- Distinguish valid, recoverable, orphaned, unknown, invalid, and empty refs.
- Avoid collapsing non-authoritative cache state into authoritative missing data.

Current fix:

- `ValidDecision`: keep.
- `ValidAsset`: keep.
- `RecoverableAssetForDecision`: replace decision URI with asset URI.
- `OrphanedDecisionRef`: clear provider tag and request hydration on write.
- `UnknownDecisionRef`: preserve and request hydration.
- `Invalid`: clear internal invalid artwork refs and provider tags.

### `LegacyRemoteArtworkFetcher`

Files:

- `app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkFetcher.kt`
- `app/src/main/java/com/nexio/tv/core/image/LegacyRemoteArtworkModel.kt`

Responsibilities:

- Compatibility fallback for remote artwork not yet routed through the durable artwork system.

Role in the bug:

- Some fallback paths could render through legacy Coil/remote fetching without the same durable artwork instrumentation.

Current fix:

- Adds gated safe logcat/runtime trace events for start, success, and failure.
- Rejects premium provider hosts, including trailing-dot host variants.
- Keeps legacy fallback visible but not a normal premium-provider escape hatch.

### Logcat Trace Runtime

Files:

- `app/src/main/java/com/nexio/tv/core/trace/LogcatTraceChannel.kt`
- `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- `app/src/main/java/com/nexio/tv/core/trace/CompositeRuntimeTraceSink.kt`

Responsibilities:

- Route gated FirstPaint, MetaRoute, and Integration Runtime events to logcat.
- Keep diagnostic payloads safe: no raw URLs, no raw decision keys, no secrets.

Current fix:

- Routes legacy remote artwork events through `Nexio.IntRuntime`.
- Curates safe legacy fields for logcat.
- Supports logcat-only trace events so compatibility fetch diagnostics do not pollute active file traces.

## Fixes Tried Before This Packet

Earlier work moved premium artwork away from raw provider URLs:

- Store `nexio-artwork://decision/...` refs instead of raw RPDB/Top-Posters URLs.
- Persist selected premium candidates and fallback candidates.
- Add decision-store write/load diagnostics.
- Add snapshot sanitize/read/write diagnostics.
- Add typed decision lookup states so failed/partial cache loads are not equivalent to authoritative misses.

Those changes were necessary, but not sufficient. They still left an integrity gap when a persisted decision ref had no matching durable decision record or when a decision cache miss could not recover from durable asset bytes.

## Fixes In This Branch

This branch implements the P0 reference-integrity packet:

1. DTO-backed durable asset record store with per-record quarantine.
2. Asset disk cache helpers with canonical path and image header validation.
3. Asset record persistence during artwork materialization and disk-hit reconstruction.
4. Decision URI recovery from latest valid asset record.
5. `ArtworkReferenceIntegrityValidator` with explicit valid/recoverable/orphaned/unknown/invalid results.
6. Snapshot read made item-scoped and non-fatal for artwork mismatch.
7. Missing decision refs preserved on read and queued for rehydration.
8. Snapshot write barrier that validates refs, promotes recoverable decisions to asset refs, and never persists `poster=null` with a provider tag.
9. Hilt wiring for the validator and asset record store.
10. Legacy remote fallback instrumentation and premium-host rejection.

## Expected Behavior After This Packet

After restart:

- Decision ref with durable decision renders normally.
- Decision ref without decision but with durable valid asset renders from the asset.
- Decision ref without decision or asset does not fail the whole snapshot.
- Unknown/non-authoritative lookup preserves the ref and requests rehydration.
- Orphaned refs are repaired or cleared through the write barrier, not destructively during read.
- Provider tag mismatch is diagnostic and item-scoped, not a full snapshot rejection.
- Legacy fallback fetches are visible in gated logcat.

## Verification Notes

Host verification passed for the focused unit suite covering:

- asset record store persistence/quarantine
- disk cache recovery helpers
- decision-to-asset recovery
- artwork reference integrity validation
- snapshot read/write behavior
- legacy fallback instrumentation
- logcat routing/formatting
- DI contract wiring

Release-device verification should use `192.168.50.98:5555` and package `com.nexio.tv`, because that is where the real release data/profile state lives. A debug install on `192.168.50.71:5555` proved logcat gate routing but did not reproduce the existing release snapshot state because it used a fresh debug package.

Useful release verification flow:

```bash
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
adb -s 192.168.50.98:5555 logcat -d -v time |
  rg 'Nexio\.|artwork\.decision|artwork\.ref_|artwork\.orphan|home\.snapshot|legacy_remote_artwork|poster_provider_tag|ClassCastException|OutOfMemory|Skipped [0-9]+ frames|Background concurrent copying GC'
```

Required proof points:

- no `home.snapshot_read success=false reason=poster_provider_tag_mismatch`
- no destructive read-time sanitize for missing decisions
- orphan or unknown decision refs emit rehydration diagnostics
- missing decision plus valid asset emits `artwork.orphan_decision_ref_asset_recovered`
- legacy remote fallback events appear only for compatibility fallback, not premium provider URLs

## Remaining Risks

- This packet does not remove every legacy fallback path; it instruments the compatibility path and blocks premium hosts.
- This packet does not guarantee every old damaged snapshot is immediately repaired. It prevents further destructive read damage and adds recovery/rehydration paths.
- Device proof still depends on release build verification against the package/data where the bad snapshot exists.
- The earlier write-amplification slowdown must stay monitored: durable asset/decision stores should avoid rewriting full files for bulk thumbnail churn.

