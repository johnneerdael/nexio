# Lane D — Cache, TTL, Single-Flight, 429/Backoff

- **Review SHA:** `774a540f8`
- **Dossier series:** review-dossier-2
- **Files inspected:** `LocalIntegrationCacheStore.kt`, `IntegrationCacheDao.kt`, `IntegrationCacheEntity.kt`, `IntegrationBlobStore.kt`, `IntegrationProviderBackoffDao.kt`, `IntegrationProviderBackoffEntity.kt`, `IntegrationOrphanCleanupService.kt`, `IntegrationCachePolicy.kt`, `IntegrationBackoffManager.kt`, `IntegrationSingleFlight.kt`, `TraceCacheDecision.kt`, `MetadataCacheKeys.kt`, `DefaultIntegrationRuntime.kt` (cache + backoff paths), `IntegrationSpec.kt`, `IntegrationScope.kt`, `ProfileBoundaryEnforcer.kt`, `TraktIntegrationProvider.kt` (global-content endpoints), `TraktGlobalContentCacheKeyTest.kt`, `IntegrationSingleFlightTest.kt`, `DefaultIntegrationRuntimeStaleOn429Test.kt`, `TraktIntegrationProviderRecommendationsTest.kt`

---

## 1. What changed since the prior dossier (Cluster B sign-off)

Cluster B closed all six Lane D findings from the original audit (F-D-01 through F-D-06) plus F-TM-02 and F-A-02. Cluster F (provider contracts) subsequently landed F-C-06, which changed Trakt global-content cache keys to drop the `profile:` prefix so multiple profiles share one cache entry. The items this lane must verify in the current SHA are:

| Finding | Cluster B claim | Expected state |
|---|---|---|
| F-D-01 (stale-on-429 fallback) | Closed | Runtime now calls `readStale` on HttpError 429/5xx + NetworkError |
| F-D-02 (non-atomic cache write) | Closed | `atomicRenameAndUpsert` + `@Transaction` in DAO |
| F-D-03 (`INVALIDATED`/`EVICTED` dead values) | Closed | Values removed from enum |
| F-D-04 (`MetadataCacheKeys.localized` unused) | Closed | Method removed from class |
| F-D-05 (single-flight typed key collision) | Closed | `TypedSingleFlightKey` in use |
| F-D-06 (backoff: no exponential schedule, no clear-on-success) | Closed | Exponential schedule + `clear()` on success |
| F-TM-02 (no single-flight regression test) | Closed | `IntegrationSingleFlightTest` added |
| F-A-02 (single-flight only on CacheFirst) | Closed | `coalesceConcurrent` opt-in for `call` path |
| F-C-06 caveat | New | Cache layer keys solely on `cacheKey` — verify scope/profileContext does not influence lookup |

---

## 2. Components examined

### 2.1 `IntegrationCachePolicy`

Sealed interface with four variants: `Disabled`, `ObserveOnly(reason)`, `CacheFirst(ttlMs, staleAfterExpiryMs = 0L)`, `Mutation`. The `ObserveOnlyOrMutation` label seen in the runtime audit report is a display grouping used by the audit tool — it does not correspond to a real variant.

### 2.2 `LocalIntegrationCacheStore`

Backed by `IntegrationCacheDao` (Room) + `IntegrationBlobStore` (filesystem). `readFresh`/`readStale` gate on `CacheFirst` policy and return `null` for all other policies. `write` gates on `CacheFirst` and delegates blob encoding + DAO upsert through `atomicRenameAndUpsert`. `deleteOwnedMedia` removes blob files then Room rows in two separate calls (not transactional — see D-09).

### 2.3 `IntegrationCacheDao`

Abstract Room DAO. `atomicRenameAndUpsert` carries `@Transaction` and writes `.tmp` → rename → `upsertCacheEntry`. Cache row lookup is a single `SELECT * WHERE cacheKey = :cacheKey` — scope, provider, and profileContext fields are stored in the entity but are NOT part of the lookup predicate.

### 2.4 `IntegrationBackoffManager`

Exponential backoff: `min(baseMs × 2^consecutiveFailures−1, capMs) ± jitter`, with `Retry-After` override. `DEFAULT_BASE_MS = 2_000`, `DEFAULT_CAP_MS = 60_000`, `DEFAULT_JITTER_MS = 500`. `clear(provider, scope)` calls `dao.clear(...)`. Hilt-injected singleton.

### 2.5 `IntegrationSingleFlight`

Two parallel structures: `inFlight` (fetch results, keyed by `String`) and `inFlightCalls` (call results, keyed by `String`). Both are driven through `TypedSingleFlightKey` in production, which composes a `"${cacheKey}|${mimeType}"` string. A raw `String` overload of `run(...)` also exists and is called only from tests.

### 2.6 `TraceCacheDecision`

Enum values: `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE`. All seven are emitted from `DefaultIntegrationRuntime`.

### 2.7 `MetadataCacheKeys`

Class with five methods: `providerMetadataKey`, `routerDecisionKey`, `resolvedDocumentKey`, `artworkDecisionKey`, `imageBlobKey`. The `localized(...)` method has been removed. The class itself has **zero production callers** — all five remaining methods are also unreachable from production code.

### 2.8 `DefaultIntegrationRuntime` (cache and backoff paths)

`executeCacheFirst` invokes `singleFlight.run(TypedSingleFlightKey(...))` at line 501. Inside the single-flight lambda, the 429/backoff path calls `cacheStore.readStale` and emits `STALE_HIT`. `executeProviderLoad` calls `backoffManager.clear(provider, scope)` on `Success`, calls `backoffManager.noteHttpFailure` on HttpError 429/5xx, and calls `cacheStore.readStale` before returning `Missing` for both HttpError 429/5xx and NetworkError branches. The `callInternal` path invokes `singleFlight.runCall(TypedSingleFlightKey(...))` when `spec.coalesceConcurrent = true`.

---

## 3. Contract verdicts

| # | Contract | Verdict | Evidence |
|---|---|---|---|
| 1 | TTL and stale window computed and enforced correctly | PASS | `LocalIntegrationCacheStore.readFresh` compares `entry.expiresAtEpochMs < now`; `readStale` compares `entry.staleUntilEpochMs < now`. Write computes `freshUntil = now + ttlMs`, `staleUntil = freshUntil + staleAfterExpiryMs`. Default `staleAfterExpiryMs = 0L` means `staleUntil == freshUntil` — stale window is empty, which is semantically correct for providers with no stale grace period. |
| 2 | 429/5xx/NetworkError falls back to stale cache before returning Missing | PASS | `DefaultIntegrationRuntime.executeProviderLoad:637-660` (HttpError) and `:663-685` (NetworkError) both call `cacheStore.readStale(spec)` and return `IntegrationFetchResult.Stale(stale)` when non-null. Emission of `STALE_HIT` trace event confirmed at lines 650-659 and 677-684. F-D-01 is closed. |
| 3 | Cache write is atomic (blob + Room row) | PASS | `atomicRenameAndUpsert` in `IntegrationCacheDao` carries `@Transaction`; writes blob to `.tmp` then calls `tmpFile.renameTo(finalFile)` before `upsertCacheEntry`. Fall-back copy+delete for cross-filesystem is present. `readFresh`/`readStale` use `runCatching { ... }.getOrNull()` to tolerate any partial state. F-D-02 is closed. |
| 4 | `TraceCacheDecision` has no dead enum values | PASS | Enum now contains only `HIT`, `MISS_THEN_NETWORK`, `STALE_HIT`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE` — all seven are emitted by `DefaultIntegrationRuntime`. `INVALIDATED` and `EVICTED` were removed (F-D-03 closed). |
| 5 | `MetadataCacheKeys.localized` is gone | PASS (partial) | The `localized(...)` method was removed (F-D-04 partially closed). However, the entire `MetadataCacheKeys` class has zero production callers — see D-04. |
| 6 | Single-flight typed key prevents ClassCastException on key collision | PASS | Production uses `TypedSingleFlightKey(cacheKey, mimeType)` at both call sites. The regression test `same key with different result types does not produce ClassCastException` explicitly validates isolation. F-D-05 is closed. |
| 7 | `IntegrationBackoffManager` has exponential schedule and clear-on-success | PASS | Exponential: `(baseMs shl shift).coerceAtMost(capMs)` where `shift = (consecutiveFailures - 1).coerceAtMost(20)`. Jitter: `random.nextLong(jitterMs * 2L) - jitterMs`. Clear-on-success: `backoffManager.clear(provider, scope)` at `DefaultIntegrationRuntime.executeProviderLoad:633`. Retry-After override: `retryAfterMs ?: (expBlockMs + jitter).coerceAtLeast(0L)`. F-D-06 is closed. |
| 8 | Single-flight regression test exists | PASS | `IntegrationSingleFlightTest` covers: loader-once coalescing, key isolation, exception propagation + slot clear, typed key isolation (same cacheKey + different mimeType). F-TM-02 is closed. |
| 9 | Single-flight available on `call` path (F-A-02) | PASS | `callInternal` conditionally invokes `singleFlight.runCall(TypedSingleFlightKey(...))` when `spec.coalesceConcurrent = true`. F-A-02 is closed. |
| 10 | Cache layer keys on `cacheKey` only, not `scope`/`profileContext` (F-C-06 caveat) | PASS | `IntegrationCacheDao.getCacheEntry` queries `WHERE cacheKey = :cacheKey` only. `scope` and `profileContext` are stored in the cache entity for audit/ownership purposes but are not part of the lookup predicate. F-C-06 is effective as long as two specs share the same `cacheKey` value, regardless of scope. |
| 11 | `TraceCacheDecision` values are consumed by validators / tests | WARN | All 7 values are emitted. The regression test `RuntimeCacheDecisionTraceTest` checks only `HIT`; `DefaultIntegrationRuntimeStaleOn429Test` checks only `STALE_HIT`. `MISS_THEN_NETWORK`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE` are emitted but have no dedicated test assertion. See D-05. |

---

## 4. TTL spot-check

| Provider / endpoint | TTL | Stale window | Assessment |
|---|---|---|---|
| Trakt trending/popular/recommended/calendar | 10 min | 1 h | Appropriate — trending content can become stale quickly. |
| Trakt library / watchlists (account-scoped) | 10 min | 1 h | Reasonable for user-specific lists. |
| TMDB movie/series core metadata | 7 d | 30 d | Acceptable — canonical metadata rarely changes. |
| TMDB trending/popular | 10 min | 1 h | Correct — trending is time-sensitive. |
| TMDB person detail / combined credits | 24 h | 7 d | Reasonable. |
| Kitsu series/episode | 24 h | 7 d | Acceptable. |
| AniSkip skip intervals | 7 d | 30 d | Reasonable for per-episode skip data (community-contributed, rarely updated). |
| ARM identity bridge | 7 d | 30 d | Reasonable — ID mappings are near-immutable. |
| TorBox user check | 60 s | 5 min | Reasonable — subscription validation. |
| RPDB / TopPosters poster URL | (not checked inline — uses CacheFirst with ttlMs) | | Not a concern for this lane. |

No obviously wrong TTL values found. Trending endpoints use 10-min TTL, which is appropriate. Static ID-bridge data uses 7-day TTL. No 7-day TTL on high-churn content.

---

## 5. Forbidden overwrites analysis

The metadata execution audit reports **5 forbidden overwrites** across 30 items (summary line in `metadata-execution-report.json`). Breakdown from the JSON:

| Scenario | Field | Primary provider | Rejected provider | Reason |
|---|---|---|---|---|
| `tmdb-kitsu-secondary-content` | `title` | TMDB | KITSU | Field already owned by PRIMARY; rejected secondary candidate |
| `tvdb-trakt-series-core` | `title` | TVDB | TRAKT | Field already owned by PRIMARY; rejected PRIMARY |
| `tvdb-tmdb-series-core` | `title` | TVDB | TMDB | Field already owned by PRIMARY; rejected PRIMARY |
| `tvdb-tmdb-series-core` | `poster` | TVDB | TMDB | Field already owned by PRIMARY; rejected PRIMARY |
| `tmdb-simkl-movie-core` | `title` | TMDB | SIMKL | Field already owned by PRIMARY; rejected PRIMARY |

**These are expected, not defects.** A "forbidden overwrite" means the `FieldResolver` correctly rejected a secondary provider's claim on a field already owned by the primary provider. All five rejections are title/poster fields where the primary provider already populated a value. The `FieldResolver` primary-wins rule is working as designed. The count of 5 across 30 items (~17%) is consistent with multi-provider scenarios where more than one provider returns metadata for the same field.

---

## 6. F-C-06 cache-sharing verification

The F-C-06 fix (`commit 36ece958b`) changed six Trakt global-content functions (`fetchCalendarShows`, `fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`) to use `globalContentCacheKey(...)` → `"global:provider:TRAKT:$logicalKey"` instead of `accountCacheKey(...)` → `"profile:N:provider:TRAKT:credential:H:$logicalKey"`.

Cache lookup in `IntegrationCacheDao.getCacheEntry` is keyed solely on `cacheKey`. Two profiles calling `fetchTrendingMovies(limit=20)` with the same limit will produce the same `cacheKey = "global:provider:TRAKT:trakt:trending:movies:limit:20"` and will hit the same cache row. F-C-06 is effective at the cache layer.

**However, a separate boundary-enforcement bug is introduced by this change — see D-01 below.**

---

## 7. Findings

### D-01: F-C-06 global-content Trakt specs violate `ProfileBoundaryEnforcer.validateAccountScope` at construction time

- **Severity:** P1 — runtime crash for any Trakt user
- **Evidence:** The six global-content Trakt functions (`fetchTrendingMovies`, `fetchTrendingShows`, `fetchPopularMovies`, `fetchPopularShows`, `fetchRecommendations`, `fetchCalendarShows`) construct `IntegrationSpec` with:
  - `scope = accountScope(session)` → `IntegrationScope.Account(profileId = session.profileId, provider = TRAKT, credentialHash = ...)`
  - `cacheKey = globalContentCacheKey(...)` → `"global:provider:TRAKT:..."`

  `IntegrationSpec.init` calls `ProfileBoundaryEnforcer.validateRequest(provider, scope, cacheKey, profileContext)`. Since `scope` is `IntegrationScope.Account`, `validateAccountScope` is invoked. That method calls `validateProfileScope(profileId, cacheKey, profileContext)`, which requires the cache key to match `Regex("""(^|:)profile:${profileId}(:|$)""")`. The key `"global:provider:TRAKT:..."` does not contain `"profile:N"`, so `validateProfileScope` throws `ProfileBoundaryException(PROFILE_CACHE_KEY_MISSING_PROFILE_ID, ...)` at spec construction time — before `runtime.get(spec)` is ever called.

- **Why tests did not catch it:** The pin test `TraktGlobalContentCacheKeyTest` is a source-grep proxy that checks function bodies for absence of the string `accountCacheKey(` — it does not construct a real `IntegrationSpec` or call `ProfileBoundaryEnforcer`. The `TraktIntegrationProviderRecommendationsTest.trending and popular reads` test uses `RecordingIntegrationRuntime`, which receives the `IntegrationSpec` object after construction, so the `init` block does run. But that test asserts the old `"profile:1:provider:TRAKT:credential:trakt-test-1:..."` key format — which means the spec construction must NOT be throwing. This implies either (a) the test environment has the enforcer disabled or `ProfileBoundaryEnforcer.validateRequest` is not being called during testing (needs further investigation), or (b) the test is also broken and passing for the wrong reason. Regardless, there is no test that constructs a real `DefaultIntegrationRuntime`, calls `fetchTrendingMovies`, and asserts the stale/hit behavior end-to-end with real `ProfileBoundaryEnforcer` logic active.

  **Note on test line 119-127:** The expected keys in `TraktIntegrationProviderRecommendationsTest.trending and popular reads` are `"profile:1:provider:TRAKT:credential:trakt-test-1:..."` — the `accountCacheKey` format. If the production code now uses `globalContentCacheKey`, this test's assertions are also stale (expected vs actual key format mismatch).

- **User-visible impact:** Every call to the six global-content Trakt functions will throw `ProfileBoundaryException` at spec construction, propagating as an uncaught exception through `runtime.get(spec)` to the caller. Trending/popular/recommendations rails on Home will fail to load for any authenticated Trakt user.

- **Required fix (option A — recommended):** Change `scope` for global-content endpoints from `accountScope(session)` to `IntegrationScope.GlobalContent`, and set `profileContext = null`. This aligns with `ProfileBoundaryEnforcer.rejectGlobalScopeForAuthenticatedProvider` — but note that function rejects TRAKT + GlobalContent when `profileContext != null && account != null`. So `profileContext` must be `null` for the global scope to pass. Alternatively use a non-account scope that still records credentials in the key for cache invalidation purposes.

- **Required fix (option B):** Keep `scope = accountScope(session)` but modify `validateAccountScope` to allow a `"global:provider:TRAKT:..."` key pattern alongside the standard profile-bearing pattern. This weakens the boundary contract.

- **Test that must pass:** An end-to-end test using a real `DefaultIntegrationRuntime` with real `ProfileBoundaryEnforcer`, two profiles, calling `fetchTrendingMovies` on each, and asserting both receive the same cached result from a single network call.

---

### D-02: `TraktIntegrationProviderRecommendationsTest` expected cache keys are stale post F-C-06

- **Severity:** P2 — test asserts old cache key format, will fail or is silently wrong
- **Evidence:** `TraktIntegrationProviderRecommendationsTest.trending and popular reads` (lines 119-127) asserts keys:
  ```
  "profile:1:provider:TRAKT:credential:trakt-test-1:trakt:trending:movies:limit:20"
  ```
  The production code now calls `globalContentCacheKey(...)` which produces:
  ```
  "global:provider:TRAKT:trakt:trending:movies:limit:20"
  ```
  These cannot both be correct. Either the test is failing (sign-off audit may not have run this test) or the test is passing because spec construction throws before `keys.add(spec.cacheKey)` is reached in `RecordingIntegrationRuntime.get(spec)`. If the spec throws, `RecordingIntegrationRuntime` never records the key, `runtime.keys` is empty, and the test's `assertEquals(listOf(...), runtime.keys)` fails with `expected=[...] but was=[]`.
- **Violated contract:** Test coverage of cache key format (F-C-06 pin).
- **Required fix:** Update the expected keys to the `globalContentCacheKey` format, or add a proper end-to-end test (see D-01 fix above).
- **Test to add:** See D-01.

---

### D-03: F-D-01 regression test uses `ObserveOnly` policy, masking the stale-guard bypass

- **Severity:** P2 — test does not exercise the code path it claims to cover
- **Evidence:** `DefaultIntegrationRuntimeStaleOn429Test.executeProviderLoad HTTP 429 with stale cache returns Stale not Missing` constructs a spec with `cachePolicy = IntegrationCachePolicy.ObserveOnly(reason = "f-d-01-pin")`. The mock `cacheStore.readStale` returns `"cached-value"` unconditionally. However, in production, `LocalIntegrationCacheStore.readStale` returns `null` immediately for any non-`CacheFirst` spec (line 31: `if (spec.cachePolicy !is IntegrationCachePolicy.CacheFirst) return null`). The test passes because it mocks `readStale` to bypass this guard, but the scenario it supposedly covers — a real `CacheFirst` spec receiving a 429 while stale data exists — is never tested against a real cache store with the correct policy gate.
- **Violated contract:** F-D-01 regression coverage.
- **Required fix:** Change the test spec to `cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L, staleAfterExpiryMs = 60_000L)` and use a real (or real-ish) `LocalIntegrationCacheStore` with a pre-seeded stale entry. Alternatively, keep the mock approach but document explicitly that the test only covers the runtime path (not the cache store policy gate) and add a second test with a real cache store.

---

### D-04: `MetadataCacheKeys` entire class is dead code — not just `localized`

- **Severity:** Nit — dead code, follow-up from F-D-04
- **Evidence:** F-D-04 removed `MetadataCacheKeys.localized(...)`. However, the remaining five methods (`providerMetadataKey`, `routerDecisionKey`, `resolvedDocumentKey`, `artworkDecisionKey`, `imageBlobKey`) have zero production callers. Only `MetadataCacheKeysTest` references the class. Grep of `MetadataCacheKeys` in `app/src/main` returns only the class declaration.
- **Violated contract:** F-D-04 was claimed closed; partial closure left dead code behind.
- **Required fix:** Delete `MetadataCacheKeys` and `MetadataCacheKeysTest`, or document a concrete plan for which caller will adopt these helpers and by when.

---

### D-05: `TraceCacheDecision` values `MISS_THEN_NETWORK`, `EXPIRED_MISS`, `BYPASS_DISABLED`, `OBSERVE_ONLY`, `WRITE` have no consumer-side test assertions

- **Severity:** Nit
- **Evidence:** `RuntimeCacheDecisionTraceTest` asserts only `HIT`. `DefaultIntegrationRuntimeStaleOn429Test` asserts only `STALE_HIT`. The remaining five values are emitted from `DefaultIntegrationRuntime` (verified by grep) but no test verifies they appear in the trace sink under the correct conditions.
- **User-visible impact:** A refactor that accidentally drops a `WRITE` or `BYPASS_DISABLED` emission would not be caught. CI validators that gate on `cache_decision` event presence would be silent.
- **Required fix:** Extend `RuntimeCacheDecisionTraceTest` (or add a companion test) asserting: `MISS_THEN_NETWORK` on a cold cache fetch; `EXPIRED_MISS` on a cacheOnly request with an expired entry; `BYPASS_DISABLED` on a `Disabled`-policy spec; `OBSERVE_ONLY` on an `ObserveOnly`-policy spec; `WRITE` after a successful network fetch into an empty cache.

---

### D-06: `IntegrationSingleFlight.run(cacheKey: String)` raw overload is test-only but not annotated

- **Severity:** Nit
- **Evidence:** `IntegrationSingleFlight.run(cacheKey: String, block: ...)` (line 83) is called only from `IntegrationSingleFlightTest` (the tests at lines 20, 27, 47, 48, 60, 69). Production calls use `run(TypedSingleFlightKey, ...)` or `runCall(TypedSingleFlightKey, ...)`. The raw string overload is public API with no `@VisibleForTesting` annotation.
- **Violated contract:** None — hygiene issue.
- **Required fix:** Annotate with `@VisibleForTesting` or restrict to `internal`. If `IntegrationSingleFlightTest` is the only caller and it's not meant for production use, this makes the intent explicit and prevents accidental use from future providers that could reintroduce the untyped collision risk that F-D-05 fixed.

---

### D-07: `IntegrationOrphanCleanupService.cleanupAll` iterates sequentially with no parallelism or batch SQL

- **Severity:** Nit — performance concern
- **Evidence:** `IntegrationOrphanCleanupService.cleanupAll` iterates `mediaKeys.distinct().forEach { mediaKey -> cleanupIfOrphaned(mediaKey) }`. Each `cleanupIfOrphaned` call runs a `railStoreDao.railsForMedia(mediaKey)` SELECT followed (conditionally) by `blobStore.delete` + `cacheDao.deleteByMediaKey`. On a Home screen with 50+ visible media items, a cleanup sweep on profile switch would issue 50+ sequential DB queries and file deletions.
- **User-visible impact:** Profile switch cleanup latency in proportion to the number of owned media keys. Not a crash, but may cause visible jank if called on the main thread (depends on dispatcher of the caller, not inspected in this lane).
- **Required fix:** Consider batching `findByMediaKey` into a single `WHERE ownerToken IN (...)` query, or at minimum parallelizing with `coroutineScope { mediaKeys.map { async { ... } }.awaitAll() }`.

---

### D-08: `deleteOwnedMedia` in `LocalIntegrationCacheStore` is not transactional — blob and Room row deleted in separate steps

- **Severity:** P2
- **Evidence:** `LocalIntegrationCacheStore.deleteOwnedMedia` (lines 79-85): first iterates `ownedEntries.forEach { entry -> blobStore.delete(entry.blobPath) }` then calls `cacheDao.deleteByMediaKey(mediaKey)`. If the process is killed or the DAO throws between the blob deletes and the Room delete, the Room row survives pointing at a now-deleted blob file. On the next `readFresh`/`readStale`, `file.exists()` returns `false` and the read returns `null` — so this is not a data corruption issue, but the cache row becomes a permanently orphaned row that is never cleaned up (no `DELETE WHERE blobPath NOT EXISTS` sweep). Over time, many such orphaned rows accumulate.
- **Violated contract:** The write-path atomicity fix (F-D-02) addressed `write`; `deleteOwnedMedia` was not included.
- **Required fix:** Wrap the blob delete + DAO delete in a coroutine/try block that rolls back (re-creates the file from the DB row if available, or deletes the DB row even if the blob was already deleted). At minimum, run the DAO delete first to remove the authoritative reference before deleting the blob file — so a crash after the DAO delete leaves a dangling blob (harmless disk waste) rather than a dangling DB row.

---

### D-09: `backoffManager.noteHttpFailure` called with synthetic status 503 for network errors, but clear-on-success is tied to the same provider+scope key

- **Severity:** Nit — potential scope-key drift
- **Evidence:** `DefaultIntegrationRuntime.noteSyntheticNetworkFailure` calls `backoffManager.noteHttpFailure(..., statusCode = SYNTHETIC_NETWORK_FAILURE_STATUS = 503, ...)`. The `backoffManager.clear(provider, scope)` at line 633 uses the spec's actual scope. However, `noteHttpFailure` in `callInternal` also uses the spec's scope. The scope for global-content Trakt endpoints is currently `accountScope(session)` — i.e., `Account(profileId, TRAKT, credentialHash)`. If two profiles call a global-content endpoint and one gets a 429 (blocking `account:profile:1:provider:TRAKT:...`), the other profile's scope (`account:profile:2:...`) is unaffected. This is correct isolation, but means the global-content deduplication via shared cache key does not propagate backoff state across profiles — profile 2 will still attempt the 429-blocked endpoint.
- **Note:** This finding is relevant only when D-01 is fixed. If scope is changed to `GlobalContent`, both profiles would share the same backoff key and a 429 from one profile would block both — which is the correct behavior for a global endpoint.

---

## 8. Findings summary

| ID | Title | Severity |
|---|---|---|
| D-01 | F-C-06 Trakt global-content specs crash with `ProfileBoundaryException` at spec construction | P1 |
| D-02 | `TraktIntegrationProviderRecommendationsTest` expected cache keys stale post F-C-06 | P2 |
| D-03 | F-D-01 regression test uses wrong policy (`ObserveOnly` instead of `CacheFirst`) | P2 |
| D-04 | `MetadataCacheKeys` entire class is dead code (partial F-D-04 closure) | Nit |
| D-05 | Five `TraceCacheDecision` values lack consumer-side test assertions | Nit |
| D-06 | `IntegrationSingleFlight.run(String)` raw overload has no `@VisibleForTesting` | Nit |
| D-07 | `IntegrationOrphanCleanupService.cleanupAll` is sequential, not batched | Nit |
| D-08 | `LocalIntegrationCacheStore.deleteOwnedMedia` blob+DAO delete is non-transactional | P2 |
| D-09 | Global-content Trakt backoff not shared across profiles (only relevant post D-01 fix) | Nit |

**Counts:** 1 P1, 3 P2, 5 Nit.

---

## 9. Closed findings confirmation

All six original Lane D findings (F-D-01 through F-D-06) from the prior dossier SHA are closed in the current SHA. F-TM-02 and F-A-02 are also closed. The only regression introduced is D-01, which was created by the F-C-06 cache-key change landing without a corresponding `ProfileBoundaryEnforcer`-aware scope change.

---

## 10. Outcome

**CHANGES_REQUESTED.** D-01 is a P1 that causes a `ProfileBoundaryException` crash for all authenticated Trakt users who trigger any of the six global-content endpoints (trending, popular, recommendations, calendar). The underlying cache deduplication intent of F-C-06 is sound; the fix is a scope alignment (switch from `accountScope` to a non-profile-bound scope for these global endpoints). D-02 and D-03 are P2 test-quality issues that would conceal the D-01 regression from future CI runs. D-08 is a P2 correctness gap in the deletion path (orphaned Room rows after crash mid-delete). D-04 through D-07 and D-09 are nits for follow-up.
