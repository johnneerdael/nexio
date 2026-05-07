# Premium Poster Restart Loss Authority Design

Date: 2026-05-07
Status: Draft (brainstorming approved; pending written-spec review)
Related:
- `review-dossier/android-premium-posters-restart-rca-handover.md`
- `docs/superpowers/plans/2026-05-06-premium-artwork-restart-recovery.md`
- `docs/superpowers/plans/2026-05-06-premium-poster-fallback-and-artwork-logging.md`
- `docs/superpowers/plans/2026-05-06-universal-nexio-artwork-provider.md`

## Problem

Premium posters can appear during one app run, then disappear again after restart. This violates the integration-runtime and artwork-cache contract: once a premium poster has resolved to a durable `nexio-artwork://decision/...` reference, restart should not permanently remove it unless an authoritative store proves that decision is invalid.

Latest device logs show the durable decision store is not absent:

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

The failure is therefore not simply "file missing." The app has a readable durable store with decisions, but the load is marked failed. Snapshot sanitization then treats `decisionFound=false` as authoritative missing data, clears posters, and can write the damaged snapshot back.

## Scope

This design covers one implementation packet:

1. `P0-A`: stop destructive home snapshot sanitization when the artwork decision cache is not authoritative.
2. `P0-B`: harden durable decision cache parsing so malformed records are quarantined and partial loads are modeled explicitly.
3. `P0-C`: request artwork rehydration for unresolved decision refs preserved because cache authority is unknown.

Removing the remaining raw premium URL bypass from home refresh is included as a trailing check in this packet if the change is small. It must not delay the authority fix.

## Goals

- Make artwork decision cache authority explicit.
- Preserve decision refs when cache load is failed, partial, not loaded, loading, or lookup throws.
- Allow destructive cleanup only when a fully authoritative cache proves a decision is missing.
- Clear poster refs and poster provider tags together when cleanup is authoritative.
- Prevent non-authoritative read-time sanitization from writing damaged snapshots.
- Restore valid durable decisions even when some records fail conversion.
- Record enough gated diagnostics to prove the load state, lookup state, sanitizer action, and rehydration path.
- Keep all work in dirty local `/Users/jneerdael/Scripts/nexio` without reverting unrelated restored files.

## Non-Goals

- No raw premium URL fallback as the fix.
- No asset-ref promotion optimization in this packet.
- No broad artwork router rewrite beyond the decision authority boundary required here.
- No destructive migration that deletes old decision-store contents.
- No logging of raw artwork URLs, raw API keys, or raw decision keys.

## Core Invariant

```text
A failed or partial artwork decision cache must never cause destructive snapshot cleanup.
```

A read-time sanitizer may only write destructive artwork changes when every validation input is authoritative for the same store path, profile scope, provider policy, schema, and credentials/settings context.

## Architecture

`DurableArtworkDecisionCache` becomes the authority boundary for durable artwork decisions. It must no longer expose only a nullable decision lookup to callers that perform destructive cleanup.

The cache exposes:

```kotlin
fun lookup(decisionKey: ArtworkDecisionKey): ArtworkDecisionLookupResult
fun loadState(): ArtworkDecisionStoreLoadState
```

`HomeCatalogSnapshotStore` consumes the typed result:

- `Found`: keep the decision ref and provider tag.
- `MissingAuthoritative`: clear the poster ref and provider tag together, mark artwork for hydration, and allow snapshot writeback.
- `CacheNotAuthoritative`: keep the decision ref and provider tag, mark artwork for hydration, and do not write a destructive snapshot.
- `LookupFailed`: keep the decision ref and provider tag, mark artwork for hydration, and do not write a destructive snapshot.

## Decision Lookup Model

```kotlin
sealed interface ArtworkDecisionLookupResult {
    data class Found(
        val decision: ArtworkDecision,
    ) : ArtworkDecisionLookupResult

    data class MissingAuthoritative(
        val decisionKey: ArtworkDecisionKey,
    ) : ArtworkDecisionLookupResult

    data class CacheNotAuthoritative(
        val decisionKey: ArtworkDecisionKey,
        val loadState: ArtworkDecisionStoreLoadState,
        val reason: String?,
        val errorClass: String?,
    ) : ArtworkDecisionLookupResult

    data class LookupFailed(
        val decisionKey: ArtworkDecisionKey,
        val errorClass: String,
        val messageHash: String?,
    ) : ArtworkDecisionLookupResult
}
```

Only `LoadedAuthoritative` may produce `MissingAuthoritative`.

## Store Load State

```kotlin
sealed interface ArtworkDecisionStoreLoadState {
    data object NotLoaded : ArtworkDecisionStoreLoadState
    data object Loading : ArtworkDecisionStoreLoadState

    data class LoadedAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int,
        val schemaVersion: Int?,
        val storedSchemaVersion: Int?,
    ) : ArtworkDecisionStoreLoadState

    data class LoadedPartialNonAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int,
        val quarantinedDecisionCount: Int,
        val schemaVersion: Int?,
        val storedSchemaVersion: Int?,
        val errorClass: String?,
        val errorMessageHash: String?,
        val errorTopFrame: String?,
    ) : ArtworkDecisionStoreLoadState

    data class FailedNonAuthoritative(
        val errorClass: String?,
        val errorMessageHash: String?,
        val errorTopFrame: String?,
    ) : ArtworkDecisionStoreLoadState
}
```

Lookup rules:

| Load state | Key found | Key missing |
| --- | --- | --- |
| `LoadedAuthoritative` | `Found` | `MissingAuthoritative` |
| `LoadedPartialNonAuthoritative` | `Found` | `CacheNotAuthoritative` |
| `FailedNonAuthoritative` | `CacheNotAuthoritative` | `CacheNotAuthoritative` |
| `NotLoaded` | `CacheNotAuthoritative` | `CacheNotAuthoritative` |
| `Loading` | `CacheNotAuthoritative` | `CacheNotAuthoritative` |
| Lookup exception | `LookupFailed` | `LookupFailed` |

Partial loads are useful for hits but are not authoritative for misses.

## Durable Store Parsing

The decision store should persist explicit DTOs only. It must not persist polymorphic domain objects, sealed-interface implementations, inline-class-heavy domain graphs, or generic maps that later require unsafe casts.

Top-level shape:

```kotlin
data class DurableArtworkDecisionStoreDto(
    val schemaVersion: Int,
    val decisions: List<ArtworkDecisionDto>,
    val writtenAtMs: Long,
)
```

Each record is converted independently:

```text
read top-level DTO
for each decision DTO:
  convert DTO to domain inside runCatching
  on success: restore decision
  on failure: quarantine record and continue
```

Rules:

- A malformed record must not poison the whole store.
- A top-level unreadable or unparsable file becomes `FailedNonAuthoritative`.
- A readable file with one or more quarantined records becomes `LoadedPartialNonAuthoritative`.
- A clean readable file becomes `LoadedAuthoritative`.
- Found decisions from a partial load may be used.
- Missing decisions from a partial load are unknown and must not drive destructive cleanup.

## Snapshot Sanitization

When `HomeCatalogSnapshotStore` sees a `nexio-artwork://decision/...` poster ref:

```kotlin
when (val result = decisionCache.lookup(decisionKey)) {
    is Found -> {
        keepPosterRef()
        keepPosterProviderTag()
    }

    is MissingAuthoritative -> {
        clearPosterRef()
        clearPosterProviderTag()
        markArtworkNeedsHydration()
        markSnapshotDirty(reason = "missing_decision_authoritative")
    }

    is CacheNotAuthoritative,
    is LookupFailed -> {
        keepPosterRef()
        keepPosterProviderTag()
        markArtworkNeedsHydration(reason = "decision_cache_not_authoritative")
        markNonDestructiveSanitization()
    }
}
```

Writeback policy:

```text
If the only snapshot changes are non-authoritative artwork preservation or rehydration markers,
do not write the snapshot back as a destructive sanitized snapshot.
```

Poster ref and `posterProviderTag` must be preserved or cleared together. The sanitizer must not produce:

```text
poster=null
posterProviderTag=rpdb
```

`poster_provider_tag_mismatch` must not reject a snapshot merely because a decision lookup was non-authoritative. It should reject only internally inconsistent snapshots after authoritative validation.

## Rehydration

Unknown decision refs should recover through hydration, not cleanup.

When lookup returns `CacheNotAuthoritative` or `LookupFailed`:

- Preserve the existing decision ref.
- Preserve the provider tag.
- Mark the item as needing artwork hydration.
- Emit `home.snapshot_artwork_rehydrate_requested`.
- Let the existing artwork refresh/runtime path produce a replacement decision or asset ref later.

If a later authoritative cache load still cannot find the decision, the sanitizer may then clear the ref and provider tag together and write the cleaned snapshot.

## Diagnostics

The existing first-paint metadata routing and integration-runtime logcat toggles should expose all proof points. If a gated trace event is not visible in logcat, add a direct gated logcat line in the same channel.

`artwork.decision_store_load` payload:

```text
success
authoritative
loadState
decisionCount
droppedDecisionCount
quarantinedDecisionCount
schemaVersion
storedSchemaVersion
storeFilePresent
storeFileReadable
storeFileBytes
errorClass
errorMessageHash
errorTopFrame
firstQuarantinedDecisionKeyHash
```

`home.snapshot_decision_lookup` payload:

```text
lookupResult
authoritative
loadState
decisionFound
decisionKeyHash
cacheDecisionCount
quarantinedDecisionCount
errorClass
errorTopFrame
```

`home.snapshot_sanitize_artwork` payload:

```text
action=preserve|clear
reason=decision_cache_not_authoritative|lookup_failed|missing_decision_authoritative
destructive=true|false
writeBackAllowed=true|false
posterProviderTagAction=preserve|clear
```

`home.snapshot_artwork_rehydrate_requested` payload:

```text
reason
posterKind
providerTag
decisionKeyHash
```

Do not log raw URLs, keys, credentials, or full decision keys.

## Raw Premium URL Bypass

The home refresh path should not write raw Top Posters or RPDB URLs into home snapshots. The trailing check for this packet is:

```text
HomeCatalogRefreshCoordinator must not call PosterRatingsUrlResolver.apply(...)
for snapshot poster values.
```

Home snapshots should contain app-owned artwork refs. Provider URL construction belongs behind the artwork provider byte-loader/runtime boundary.

If this cleanup is not tiny, split it into the next packet after the authority fix lands.

## Tests

Required unit tests:

- `failed_cache_load_preserves_snapshot_decision_ref`
- `failed_cache_load_does_not_write_destructive_snapshot`
- `partial_cache_found_decision_resolves`
- `partial_cache_missing_decision_is_not_authoritative`
- `authoritative_missing_clears_poster_and_provider_tag_together`
- `lookup_exception_preserves_snapshot_and_requests_hydration`
- `malformed_store_record_is_quarantined_without_losing_valid_decisions`
- `class_cast_record_failure_does_not_clear_whole_store`
- `top_level_store_parse_failure_is_failed_non_authoritative`
- `poster_provider_tag_preserved_when_decision_ref_preserved`
- `poster_provider_tag_cleared_when_poster_ref_cleared`
- `snapshot_read_does_not_fail_due_to_non_authoritative_artwork_lookup`
- `home_refresh_does_not_write_raw_topposters_or_rpdb_urls`

Required diagnostic tests:

- `decision_store_load_logs_authority_state`
- `decision_store_load_logs_error_message_hash_and_top_frame`
- `snapshot_lookup_logs_typed_result_and_load_state`
- `snapshot_sanitize_logs_preserve_for_non_authoritative_lookup`
- `snapshot_rehydrate_request_is_logged_for_unknown_decision_ref`

## Verification

Local verification should include focused tests around:

```text
ArtworkDecisionCacheTest
HomeCatalogSnapshotStoreTest
LogcatRuntimeTraceSinkTest
LogcatTraceChannelTest
```

Device verification on `192.168.50.71:5555` should confirm:

```text
lastLoadSuccess=false no longer produces missing_decision destructive cleanup
CacheNotAuthoritative preserves decision refs
home.snapshot_artwork_rehydrate_requested appears for unknown refs
poster_provider_tag_mismatch no longer follows non-authoritative preservation
restart does not remove posters that previously appeared
```

## Acceptance Criteria

- Restart cannot remove a premium poster solely because durable decision cache load is failed or partial.
- `decisionFound=false` is never treated as authoritative when load state is failed, partial, not loaded, loading, or lookup failed.
- Valid decisions in a partially loaded store still resolve.
- Malformed durable records are quarantined and diagnosed.
- Authoritative missing decisions still clear unsafe refs and provider tags together.
- Snapshot writeback is blocked for non-authoritative artwork-only sanitization.
- Rehydration is requested for preserved unknown decision refs.
- Logs can prove which branch ran without exposing raw secrets or URLs.
