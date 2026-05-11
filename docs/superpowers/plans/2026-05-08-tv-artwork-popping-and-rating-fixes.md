# TV Artwork Popping and Rating Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop premium TV posters from being silently replaced by addon first-paint posters, restore TVDB logo/backdrop visibility on Modern Home, and stop a TMDB rail-preview rating from being clobbered by an empty TVDB primary rating.

**Architecture:** Three independent packets at three different layers — (A) artwork repository's expired-record + network-miss fallback path, (B) Coil fetcher + asset repository on-demand re-materialization for `nexio-artwork://asset/` URIs, (C) field resolver's primary-canonical-replaces-preview rule for the RATING field plus the home-display mapper's source plumbing.

**Tech Stack:** Kotlin, Android, Hilt, Coil, JUnit 4, MockK, Gradle armv7 debug unit tests, ADB-installed armv7 release for device verification.

---

## Evidence Summary

Captured on Ugoos AM6 (192.168.50.98:5555) right after a fresh launch on commit `ae75c350e` (Tasks 1–7 of the prior plan applied):

- `runtime.cache_decision provider=RPDB apiShapeId=rpdb.poster_template decision=EXPIRED_MISS reason=network-missing networkSuppressed=false ttlMs=604800000 staleWindowMs=1987200000` — the cached RPDB asset record has expired and the freshness probe failed.
- `runtime.operation_finish provider=RPDB ... outcome=MISSING durationMs=532`
- `artwork.asset_record_store_write_failed` (twice in the 35s window)
- `artwork.fallback_materialized fallbackProvider=TMDB`
- `artwork.asset_materialized provider=TMDB imageType=POSTER cacheDecision=FALLBACK_MATERIALIZED`
- `metadata.field_selected contentId=tmdb:114922 field=RATING selectedProvider=TVDB sourceRole=PRIMARY ownershipRule=primary canonical field replaces rail preview rejectedCount=1` — TVDB's null/empty rating displaced TMDB's good rail-preview rating.
- Persisted overlay `fields.imdbRating = null`, `fields.ratingSource = null` for TVDB-canonical SERIES rows.
- Zero `artwork.asset_materialized` events with `imageType=LOGO` or `imageType=BACKDROP` in 35s — backdrop and logo asset URIs in `fields.backdrop`/`fields.logo` are dangling pointers when their bytes aren't on disk.
- Persisted overlay xml at T0 and T+30s differ only for 37 KITSU canonical entries; all TVDB-canonical SERIES rows are byte-identical (same poster/backdrop/logo refs, same displayHash, only `expiresAtMs` ticks). The overlay is stable; demotion happens in the runtime fetch + asset materialization layer.

## File Map

- Modify `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - `getOrFetch(decision)` must short-circuit to the existing on-disk asset (even when the cached record is expired) before letting `getOrFetchFallback` run.
  - Add a new `getOrRehydrateAsset(assetKey)` that looks up the originating decision and re-runs `getOrFetch(decision)` so a previously-persisted asset URI can recover after byte eviction.
- Modify `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
  - When the asset URI's `getExistingFile()` returns null, call `getOrRehydrateAsset(assetKey)` instead of failing silently.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
  - Skip the unconditional primary-canonical-replaces-preview overwrite for `ResolvedField.RATING`; let primary candidates only replace when their value is a non-null `Number > 0`.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - In `toHomeDisplayMetadata` set `ratingSource` from `sourceProviders[ResolvedField.RATING]` instead of inheriting whatever `MetaPreview` carried.
- Modify tests:
  - `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
  - `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`
  - `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`
  - `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeStableIdBundleTest.kt` (or nearest neighbor for facade-level rating tests)

## Execution Packets

```text
Packet A — premium poster popping:
  Task 1   stale-while-revalidate disk-hit
  Task 1.5 suppress fallback materialization on soft failure when prior asset is recoverable
  Task 2   asset record write failures must not publish a durable asset URI

Packet B — logo/backdrop never re-materialize (asset URI rehydration):
  Tasks 3-4

Packet C — rating ownership + source plumbing:
  Tasks 5-6 (tactical fix)
  Task 7   required follow-up if device verification still shows wrong rating source

Packet D — release/device verification:
  Task 8
```

Packets are independent. Implementer may take A → B → C → D in order, or run A and C in parallel. Don't start Task 8 until A, B, and C all green.

---

## Task 1: Stale-While-Revalidate For Expired Decision Records

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Read the existing `getOrFetch` body**

Run:

```bash
sed -n '275,340p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
```

Expected: you see `suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult?` starting at line 280 with a runtime fetch (`runtime.execute(...)`), a `bytes == null` branch that returns `diskCache.getExistingFile(materialized.assetKey)?.let { existing -> existingAssetResultOrNull(...) }`, and the fall-through that writes new bytes.

- [ ] **Step 2: Read the call site that drives the fallback**

Run:

```bash
sed -n '115,135p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
```

Expected: `val result = getOrFetch(decision) ?: getOrFetchFallback(decision)` at line 121. This is the line that decides "primary missing → use a rejected candidate".

- [ ] **Step 3: Write a failing test for stale-while-revalidate when decision record is expired but bytes are present**

Add this test to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `getOrFetch returns disk hit with stale-while-revalidate cache decision when network probe fails and bytes are on disk`() = runTest {
    val decision = ArtworkDecisionFixtures.rpdbPosterDecision(
        ownerKey = "tmdb:series-202555",
        provider = ProviderId.RPDB,
        imageType = ArtworkType.POSTER,
        expiresAtMs = NOW_MS - 1L,
        staleUntilMs = NOW_MS + 1L
    )
    val materialized = ArtworkMaterializedDecisionFixtures.materialize(decision)
    val existingBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    diskCache.writeForTest(materialized.assetKey, decision, materialized.provider, existingBytes)
    runtime.failNextWith(IntegrationRuntimeOutcome.MISSING_NETWORK)

    val result = repository.getOrFetch(decision)

    assertNotNull(result)
    assertEquals("ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS", result?.cacheDecision)
    val rejected = traceSink.events.lastOrNull { it.eventType == "artwork.fallback_materialized" }
    assertNull(rejected, "fallback must not be triggered when on-disk bytes still exist")
}
```

If `ArtworkDecisionFixtures` / `ArtworkMaterializedDecisionFixtures` / `diskCache.writeForTest` / `runtime.failNextWith` don't exist, locate the closest equivalent helpers in the existing `ArtworkAssetRepositoryTest.kt`. Match the local helper names; do not invent new fixture builders.

- [ ] **Step 4: Run the red test**

Run:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetch returns disk hit with stale-while-revalidate cache decision when network probe fails and bytes are on disk'
```

Expected: fail. The current `bytes == null` branch should return the disk file already, so the test may pass if your fixture is wrong. Fail mode is acceptable; passing-on-first-run signals the fixture isn't reproducing the bug.

If the test passes on first run, the fixture is wrong — adjust `runtime.failNextWith(...)` so it produces a `runtime.cache_decision decision=EXPIRED_MISS reason=network-missing` followed by a `null` bytes result. The bug only manifests when the runtime path returns `null` AND the disk has the file AND `expiresAtMs <= now <= staleUntilMs`.

- [ ] **Step 5: Inspect why the bug reproduces**

The disk-hit-after-runtime-miss branch in `getOrFetch` (around line 320) already returns the existing file. So the bug reported on device must mean that branch isn't hit because `diskCache.getExistingFile(materialized.assetKey)` returns null — i.e., bytes were evicted.

If the test from Step 3 truly passes, the popping symptom on device is fully explained by Task 3's "asset bytes are evicted; URI is dangling" path, NOT by stale-while-revalidate. In that case skip Step 6 below and add a code comment at line 121 explaining why no further change is needed:

```kotlin
// getOrFetch handles stale-while-revalidate via diskCache.getExistingFile after a runtime miss
// (lines 318-326). getOrFetchFallback is invoked only when the asset bytes are no longer on disk.
val result = getOrFetch(decision) ?: getOrFetchFallback(decision)
```

Mark this task DONE_WITH_CONCERNS in your report and proceed to Task 3.

- [ ] **Step 6: Implement the fix only if the bug reproduces**

If the test from Step 3 fails (i.e., the existing branch is wrong), patch `getOrFetch` so that when `result.bytes == null` AND `diskCache.getExistingFile(materialized.assetKey) != null` AND `decision.staleUntilMs >= System.currentTimeMillis()`, the function returns the on-disk asset with `cacheDecision = "ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS"` instead of letting the call site fall through to `getOrFetchFallback`. Don't change `getOrFetchFallback`'s body — only the trigger.

- [ ] **Step 7: Run the focused test green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetch returns disk hit with stale-while-revalidate cache decision when network probe fails and bytes are on disk'
```

Expected: pass.

- [ ] **Step 8: Run the full ArtworkAssetRepositoryTest suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "$(cat <<'EOF'
fix: keep stale-but-on-disk artwork over fallback materialization

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

If Step 5 routed you to a comment-only change, the commit message becomes `docs: clarify artwork stale-while-revalidate path`.

## Task 1.5: Suppress Fallback Materialization On Soft Failure When A Prior Asset Exists

**Why this exists:** Task 1 only handles "disk-hit-after-runtime-miss" inside `getOrFetch`. The call site at `ArtworkAssetRepository.kt:121` still triggers `getOrFetchFallback(decision)` whenever `getOrFetch` returns null — regardless of *why*. A transient network blip on a freshness probe (HTTP timeout, DNS hiccup, 5xx, 429) is currently indistinguishable from a hard failure (credential invalid, provider disabled, unsupported ID). The user-visible result: a TMDB fallback poster replaces a perfectly fine RPDB poster on a soft transient.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Identify how `getOrFetch` distinguishes soft vs hard failures**

Run:

```bash
grep -n "IntegrationRuntimeOutcome\|MISSING_NETWORK\|MISSING\b\|outcome=" app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt | head -20
grep -n "sealed class IntegrationRuntimeOutcome\|enum class IntegrationRuntimeOutcome\|IntegrationRuntimeOutcome\." app/src/main/java/com/nexio/tv/core/integration/ | head -10
```

Expected: `IntegrationRuntimeOutcome` (or equivalent) defines outcome variants — at minimum a `MISSING` / `MISSING_NETWORK` / `SUCCESS` / `FAILURE` set. Note the actual variant names; the test will use them.

- [ ] **Step 2: Decide where to plumb the soft/hard signal**

Read the existing `getOrFetch` body again:

```bash
sed -n '275,345p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
```

Two viable shapes:

(a) Add an internal sealed `FetchFailure` and have `getOrFetch` return `Result<ArtworkAssetResult, FetchFailure>` instead of `ArtworkAssetResult?`. The call site at line 121 then inspects the failure variant.

(b) Keep `getOrFetch`'s nullable return; have `getOrFetchFallback` consult an external predicate `softFailureWithRecoverableAsset(decision): Boolean` that checks (i) was the runtime outcome a network/timeout/5xx, and (ii) does the prior decision's expected asset key still resolve via `assetRecordStore` or `diskCache`. Skip fallback iteration when both true.

Pick (b) — it's a localized change with no API surface refactor. The predicate keeps the typed signal as an internal helper.

- [ ] **Step 3: Write a failing test for soft-failure suppression**

Add this test to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `getOrFetchFallback is skipped on soft network failure when prior premium asset is on disk`() = runTest {
    val rpdbDecision = ArtworkDecisionFixtures.rpdbPosterDecision(
        ownerKey = "tmdb:series-202555",
        provider = ProviderId.RPDB,
        imageType = ArtworkType.POSTER,
        rejectedCandidates = listOf(
            ArtworkRejectedCandidateFixtures.tmdbPosterRailPreview()
        )
    )
    val materialized = ArtworkMaterializedDecisionFixtures.materialize(rpdbDecision)
    val priorBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    diskCache.writeForTest(materialized.assetKey, rpdbDecision, materialized.provider, priorBytes)
    runtime.failNextWith(IntegrationRuntimeOutcome.MISSING_NETWORK)

    val result = repository.fetchOrFallback(rpdbDecision)

    assertNotNull(result)
    assertEquals(ProviderId.RPDB, result?.record?.provider)
    val fallback = traceSink.events.lastOrNull { it.eventType == "artwork.fallback_materialized" }
    assertNull(fallback, "soft network failure must not publish a TMDB fallback when prior RPDB asset exists")
    val skipped = traceSink.events.lastOrNull { it.eventType == "artwork.fallback_suppressed_soft_failure" }
    assertNotNull(skipped)
}

@Test
fun `getOrFetchFallback runs on hard failure even when prior asset would otherwise exist`() = runTest {
    val rpdbDecision = ArtworkDecisionFixtures.rpdbPosterDecision(
        rejectedCandidates = listOf(ArtworkRejectedCandidateFixtures.tmdbPosterRailPreview())
    )
    val materialized = ArtworkMaterializedDecisionFixtures.materialize(rpdbDecision)
    diskCache.writeForTest(materialized.assetKey, rpdbDecision, materialized.provider, byteArrayOf(0x89.toByte()))
    runtime.failNextWith(IntegrationRuntimeOutcome.CREDENTIAL_INVALID)

    val result = repository.fetchOrFallback(rpdbDecision)

    assertNotNull(result)
    assertEquals(ProviderId.TMDB, result?.record?.provider)
    val fallback = traceSink.events.lastOrNull { it.eventType == "artwork.fallback_materialized" }
    assertNotNull(fallback, "hard credential failure must allow fallback materialization")
}
```

If `repository.fetchOrFallback(decision)` doesn't exist yet, this is the entry point we're introducing — see Step 5. If `IntegrationRuntimeOutcome.CREDENTIAL_INVALID` doesn't exist as a variant, use the closest hard-failure outcome the runtime produces (e.g., `RUNTIME_REJECTED`, `INTEGRATION_HARD_FAILURE`). Hard failures are: HTTP 401/403 from auth, provider disabled, unsupported ID. Soft failures are: HTTP 408/429/5xx, DNS, socket timeouts, no-network. Match the actual taxonomy this codebase exposes.

- [ ] **Step 4: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetchFallback is skipped on soft network failure when prior premium asset is on disk' --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetchFallback runs on hard failure even when prior asset would otherwise exist'
```

Expected: both fail. The first fails because the current code triggers fallback on any null. The second may pass already (if hard-failure handling exists) or fail because `fetchOrFallback` isn't a public method.

- [ ] **Step 5: Introduce a public `fetchOrFallback` entry point**

In `ArtworkAssetRepository.kt`, find the call at line 121:

```kotlin
val result = getOrFetch(decision) ?: getOrFetchFallback(decision)
```

Replace with a call to a new public method `fetchOrFallback(decision)` that wraps the same logic plus the soft-failure suppression. Add the method below `getOrFetch`:

```kotlin
suspend fun fetchOrFallback(decision: ArtworkDecision): ArtworkAssetResult? {
    val primary = getOrFetch(decision)
    if (primary != null) return primary

    if (softFailureWithRecoverableAsset(decision)) {
        traceArtwork(
            eventType = "artwork.fallback_suppressed_soft_failure",
            payload = mapOf(
                "decisionKeyHash" to decision.decisionKey.hashedForTrace(),
                "reason" to "soft_failure_prior_asset_recoverable"
            )
        )
        return null
    }
    return getOrFetchFallback(decision)
}

private suspend fun softFailureWithRecoverableAsset(decision: ArtworkDecision): Boolean {
    val lastOutcome = runtime.lastOutcomeFor(decision.materializedSourceKey()) ?: return false
    if (!lastOutcome.isSoftFailure()) return false
    val materialized = decisionMaterializer.materialize(decision) ?: return false
    val recordExists = runCatching { assetRecordStore.get(materialized.assetKey) }.getOrNull() != null
    val fileExists = diskCache.getExistingFile(materialized.assetKey) != null
    return recordExists || fileExists
}

private fun IntegrationRuntimeOutcome.isSoftFailure(): Boolean = when (this) {
    IntegrationRuntimeOutcome.MISSING_NETWORK,
    IntegrationRuntimeOutcome.RUNTIME_TIMEOUT,
    IntegrationRuntimeOutcome.HTTP_5XX,
    IntegrationRuntimeOutcome.HTTP_429 -> true
    else -> false
}
```

Match the actual `IntegrationRuntimeOutcome` variants from Step 1. If `runtime.lastOutcomeFor(...)` doesn't exist, expose the most recent outcome via a small helper on the runtime — but if that's a wider refactor, fall back to capturing the outcome from `runtime.execute(...)` inside `getOrFetch` and threading it via a private mutable field on the repository for the duration of the same `getOrFetch → fetchOrFallback` chain. Document the choice in the commit message.

Update the existing call site to use `fetchOrFallback(decision)` instead of `getOrFetch(decision) ?: getOrFetchFallback(decision)`.

- [ ] **Step 6: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "$(cat <<'EOF'
fix: suppress artwork fallback materialization on soft transient failures

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 2: Asset Record Write Failure Must Not Publish A Durable Asset Result

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

**Why this matters:** Today `getOrFetch` writes bytes to disk, calls `persistAssetRecordBestEffort` which **swallows the failure**, and then returns a successful `ArtworkAssetResult` regardless. If a snapshot writer or overlay writer downstream captures `nexio-artwork://asset/<assetKey>` as a durable reference based on that result, the asset URI is later un-recoverable: `getOrRehydrateAsset` (Task 3) reaches `assetRecordStore.get(assetKey)` and gets null, falls into the orphan branch, and the logo/backdrop never renders. The diagnostic enrichment is necessary but not sufficient — the result must also be flagged so downstream consumers don't promote it to durable.

- [ ] **Step 1: Read `persistAssetRecordBestEffort` and its caller**

Run:

```bash
sed -n '330,355p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
sed -n '385,420p' app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
```

Expected: at line ~339 inside `getOrFetch`, `persistAssetRecordBestEffort(write.record)` is called and the function then returns `ArtworkAssetResult(...)` unconditionally. The helper at line ~390 swallows write failures via `runCatching { assetRecordStore.put(record) }.onFailure { ... traceArtwork("artwork.asset_record_store_write_failed", ...) }`.

- [ ] **Step 2: Decide the result-shape change**

Pick the smallest viable change. `ArtworkAssetResult` already has `cacheDecision: String`. Two options:

(a) Add a Boolean `durable: Boolean = true` field to `ArtworkAssetResult`. Default true preserves call-site compatibility.

(b) Use the existing `cacheDecision` string and reserve the value `"EPHEMERAL_RECORD_WRITE_FAILED"` to mean "bytes are on disk but the record store write failed; do not promote to durable."

Pick (a) — explicit field is harder to silently misread. If `ArtworkAssetResult` is a data class, adding a field with a default value is a non-breaking change.

- [ ] **Step 3: Write a failing test for the durable flag**

Add to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `getOrFetch returns non-durable result when asset record store write fails`() = runTest {
    val decision = ArtworkDecisionFixtures.tvdbBackdropDecision()
    runtime.queueBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    assetRecordStore.failPutWith(IllegalStateException("simulated record store failure"))

    val result = repository.getOrFetch(decision)

    assertNotNull(result)
    assertFalse(result!!.durable, "asset result must not be durable when record store write failed")
    assertEquals("EPHEMERAL_RECORD_WRITE_FAILED", result.cacheDecision)
    val event = traceSink.events.singleOrNull { it.eventType == "artwork.asset_record_store_write_failed" }
    assertNotNull(event)
    assertEquals("simulated record store failure", event!!.payload["errorMessage"])
}

@Test
fun `getOrFetch returns durable result when asset record store write succeeds`() = runTest {
    val decision = ArtworkDecisionFixtures.tvdbBackdropDecision()
    runtime.queueBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    val result = repository.getOrFetch(decision)

    assertNotNull(result)
    assertTrue(result!!.durable)
}
```

- [ ] **Step 4: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetch returns non-durable result when asset record store write fails' --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrFetch returns durable result when asset record store write succeeds'
```

Expected: both fail because `durable` doesn't exist yet.

- [ ] **Step 5: Implement the result-shape change and the failure path**

In `ArtworkAssetResult`, add a `val durable: Boolean = true` field next to `cacheDecision`. Update the data class constructor sites in `ArtworkAssetRepository.kt` only — leave other callers unchanged so the default applies.

Change `persistAssetRecordBestEffort` to return Boolean:

```kotlin
private fun persistAssetRecordBestEffort(record: ArtworkAssetRecord): Boolean {
    val existing = runCatching { assetRecordStore.get(record.assetKey) }
        .onFailure { error ->
            traceArtwork(
                eventType = "artwork.asset_record_store_read_failed",
                payload = mapOf(
                    "assetKeyHash" to record.assetKey.hashedForTrace(),
                    "decisionKeyHash" to record.decisionKey?.hashedForTrace(),
                    "errorClass" to error::class.java.name,
                    "errorMessage" to (error.message ?: "")
                )
            )
        }.getOrNull()
    if (existing == record) return true

    return runCatching { assetRecordStore.put(record); true }
        .onFailure { error ->
            traceArtwork(
                eventType = "artwork.asset_record_store_write_failed",
                payload = mapOf(
                    "assetKeyHash" to record.assetKey.hashedForTrace(),
                    "decisionKeyHash" to record.decisionKey?.hashedForTrace(),
                    "errorClass" to error::class.java.name,
                    "errorMessage" to (error.message ?: "")
                )
            )
        }.getOrDefault(false)
}
```

Update the caller in `getOrFetch` at line ~339:

```kotlin
val write = diskCache.write(record, bytes)
val persisted = persistAssetRecordBestEffort(write.record)
return ArtworkAssetResult(
    assetKey = materialized.assetKey,
    localFile = write.file,
    record = write.record,
    runtimeResult = result,
    runtimeApiShapeId = apiShapeId,
    cacheDecision = if (persisted) result.cacheDecision() else "EPHEMERAL_RECORD_WRITE_FAILED",
    mimeType = write.record.mimeType,
    networkExecuted = loaderInvoked,
    durable = persisted
)
```

- [ ] **Step 6: Mark the consumer-side guard as a follow-up note**

The reviewer flagged that downstream snapshot/overlay writers must check `durable` before persisting the asset URI as a stored reference. Wiring that check is a separate change touching `HomeHydrationCoordinator` / `HomeResolvedDisplayMapper` / overlay store. **Out of scope for this packet.** Add a code comment above the `durable = persisted` line:

```kotlin
// durable=false signals downstream snapshot/overlay writers that they must NOT persist
// nexio-artwork://asset/<assetKey> as a stored reference until a successful record write.
// Consumers of ArtworkAssetResult must check this flag — wired in a follow-up plan.
durable = persisted
```

Track this as a known gap in the commit message body.

- [ ] **Step 7: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/
git commit -m "$(cat <<'EOF'
fix: flag artwork asset results as non-durable when record store write fails

Adds a 'durable' flag to ArtworkAssetResult and surfaces a distinct
cacheDecision (EPHEMERAL_RECORD_WRITE_FAILED). Also enriches the
artwork.asset_record_store_write_failed trace with the underlying
error message so the device verification can pinpoint what's failing.

Follow-up: snapshot and overlay writers must consult result.durable
before persisting the asset URI as a stored reference. Tracked in
docs/superpowers/plans/2026-05-08-tv-artwork-popping-and-rating-fixes.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 3: Asset URI On-Demand Rehydration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Read the public surface of `ArtworkAssetRepository`**

Run:

```bash
grep -n "^[[:space:]]*\(public\|suspend\)\?[[:space:]]*fun\b" app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt | head -20
```

Expected: `suspend fun getOrFetch(decision: ArtworkDecision)`, `fun getExistingFile(assetKey: ArtworkAssetKey)`, plus several private helpers.

- [ ] **Step 2: Read `assetRecordStore` and `decisionCache` injection**

Run:

```bash
grep -n "private val assetRecordStore\|private val decisionCache\|private val diskCache" app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt
```

Expected: three injected dependencies are visible. Note their names exactly.

- [ ] **Step 3: Write a failing test for `getOrRehydrateAsset`**

Add to `ArtworkAssetRepositoryTest.kt`:

```kotlin
@Test
fun `getOrRehydrateAsset re-runs the originating decision when bytes are absent`() = runTest {
    val decision = ArtworkDecisionFixtures.tvdbLogoDecision(
        ownerKey = "tvdb:series:401003",
        urlHash = "c504af673640b5aa1c4ace5647fb25461506cbdeb9e5d9d1b553946db93d21f1"
    )
    val record = ArtworkAssetFixtures.recordFor(decision)
    assetRecordStore.put(record)
    decisionCache.putForTest(decision)
    diskCache.deleteForTest(record.assetKey)
    runtime.queueBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    val result = repository.getOrRehydrateAsset(record.assetKey)

    assertNotNull(result)
    assertEquals(record.assetKey, result?.assetKey)
    assertNotNull(diskCache.getExistingFile(record.assetKey), "rehydrated asset must be on disk")
}

@Test
fun `getOrRehydrateAsset returns null when no decision is on record for the asset key`() = runTest {
    val orphanKey = ArtworkAssetKeyFixtures.tvdbLogo("urlHash:orphan-key")

    val result = repository.getOrRehydrateAsset(orphanKey)

    assertNull(result)
    val event = traceSink.events.lastOrNull { it.eventType == "artwork.orphan_asset_ref_rehydrate_skipped" }
    assertNotNull(event, "orphaned asset key must emit a diagnostic trace")
}
```

If `assetRecordStore.put`, `decisionCache.putForTest`, `diskCache.deleteForTest`, `runtime.queueBytes`, or `ArtworkAssetKeyFixtures` don't exist, look in the existing test for the correct helper names. The test must (a) seed the asset record + decision, (b) ensure the file is absent, (c) prime the runtime to return bytes on the next fetch, (d) call the new method, (e) assert the file is now on disk. The negative test must (a) call with an asset key that has no record, (b) assert null is returned, (c) assert a diagnostic trace was emitted.

- [ ] **Step 4: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrRehydrateAsset*'
```

Expected: fail because `getOrRehydrateAsset` doesn't exist.

- [ ] **Step 5: Implement `getOrRehydrateAsset`**

Add to `ArtworkAssetRepository.kt`, just below `fun getExistingFile`:

```kotlin
suspend fun getOrRehydrateAsset(assetKey: ArtworkAssetKey): ArtworkAssetResult? {
    val record = runCatching { assetRecordStore.get(assetKey) }.getOrNull()
        ?: run {
            traceArtwork(
                eventType = "artwork.orphan_asset_ref_rehydrate_skipped",
                payload = mapOf(
                    "assetKeyHash" to assetKey.hashedForTrace(),
                    "reason" to "no_asset_record"
                )
            )
            return null
        }
    val decisionKey = record.decisionKey ?: run {
        traceArtwork(
            eventType = "artwork.orphan_asset_ref_rehydrate_skipped",
            payload = mapOf(
                "assetKeyHash" to assetKey.hashedForTrace(),
                "reason" to "asset_record_missing_decision_key"
            )
        )
        return null
    }
    val decision = runCatching { decisionCache.get(decisionKey) }.getOrNull() ?: run {
        traceArtwork(
            eventType = "artwork.orphan_asset_ref_rehydrate_skipped",
            payload = mapOf(
                "assetKeyHash" to assetKey.hashedForTrace(),
                "decisionKeyHash" to decisionKey.hashedForTrace(),
                "reason" to "decision_cache_miss"
            )
        )
        return null
    }
    return getOrFetch(decision)
}
```

If `decisionCache.get(decisionKey)` is named differently in this codebase (likely `decisionCache.find(decisionKey)` or `decisionCache.read(decisionKey)`), match the existing API.

- [ ] **Step 6: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest.getOrRehydrateAsset*'
```

Expected: pass.

- [ ] **Step 7: Run full repository test suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "$(cat <<'EOF'
feat: rehydrate evicted artwork assets from their originating decision

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 4: NexioArtworkFetcher Calls `getOrRehydrateAsset` On Disk Miss

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt`

- [ ] **Step 1: Read the asset-URI branch**

Run:

```bash
sed -n '15,55p' app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt
```

Expected: an `if (assetKey != null)` branch that calls `repository.getExistingFile(assetKey)` and returns null on miss.

- [ ] **Step 2: Write a failing test for the rehydrate path**

Add to `NexioArtworkFetcherTest.kt`:

```kotlin
@Test
fun `fetch rehydrates evicted asset by calling getOrRehydrateAsset`() = runTest {
    val assetKey = ArtworkAssetKeyFixtures.tvdbLogo("urlHash:c504af67")
    val rehydratedFile = tempFolder.newFile("logo.png").apply { writeBytes(VALID_PNG_HEADER) }
    repository.queueRehydrate(
        forKey = assetKey,
        result = ArtworkAssetResult.fromFile(assetKey, rehydratedFile)
    )

    val result = fetcher.fetchForTest(assetUri(assetKey))

    assertTrue(result is SourceResult)
    val source = result as SourceResult
    assertEquals(rehydratedFile.absolutePath, source.source.fileOrNull()?.absolutePath)
    repository.verifyRehydrateCalledOnce(forKey = assetKey)
}

@Test
fun `fetch returns null when getOrRehydrateAsset returns null`() = runTest {
    val assetKey = ArtworkAssetKeyFixtures.tvdbLogo("urlHash:orphan")
    repository.queueRehydrate(forKey = assetKey, result = null)

    val result = fetcher.fetchForTest(assetUri(assetKey))

    assertNull(result)
}
```

`repository.queueRehydrate` and `repository.verifyRehydrateCalledOnce` are test doubles — match the existing test doubles in the file. If the fetcher test uses MockK directly, switch to `coEvery { repository.getOrRehydrateAsset(assetKey) } returns ...` and `coVerify(exactly = 1) { ... }`.

- [ ] **Step 3: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.image.NexioArtworkFetcherTest.fetch rehydrates evicted asset by calling getOrRehydrateAsset' --tests 'com.nexio.tv.core.image.NexioArtworkFetcherTest.fetch returns null when getOrRehydrateAsset returns null'
```

Expected: fail because the fetcher does not call `getOrRehydrateAsset` yet.

- [ ] **Step 4: Implement the fetcher change**

In `NexioArtworkFetcher.kt`, locate the asset-URI branch (currently around line 22-27):

```kotlin
if (assetKey != null) {
    val file = repository.getExistingFile(assetKey) ?: return null
    return SourceResult(
        source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK
    )
}
```

Replace with:

```kotlin
if (assetKey != null) {
    val file = repository.getExistingFile(assetKey)
        ?: repository.getOrRehydrateAsset(assetKey)?.localFile
        ?: return null
    return SourceResult(
        source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK
    )
}
```

Match the existing `getExistingFile(...)` invocation style; do not introduce a new helper. The result of `getOrRehydrateAsset` is `ArtworkAssetResult?`, whose `localFile` field is `File`.

- [ ] **Step 5: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/image/NexioArtworkFetcher.kt app/src/test/java/com/nexio/tv/core/image/NexioArtworkFetcherTest.kt
git commit -m "$(cat <<'EOF'
fix: rehydrate evicted artwork assets when Coil resolves a stored asset URI

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 5: RATING Field Skips Primary-Replaces-Preview Path

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`

- [ ] **Step 1: Read the primary-replaces-preview block**

Run:

```bash
sed -n '76,108p' app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt
```

Expected: a `primary?.fields?.forEach { (field, fieldValue) -> ... }` loop that, for any field, marks the existing preview as rejected with reason `"primary canonical field available"` and then unconditionally calls `selectField(...)` with `selectedOwner = FieldOwner.PRIMARY`.

- [ ] **Step 2: Write a failing test asserting RATING preserves a numerically-better preview**

Add to `FieldResolverTest.kt`:

```kotlin
@Test
fun `RATING field preserves numerically-better preview when primary canonical rating is null`() {
    val tmdbPreview = MetadataCandidate(
        provider = MetadataPrimaryProvider.TMDB,
        sourceRole = SourceRole.RAIL_PREVIEW,
        fields = mapOf(ResolvedField.RATING to FieldValue(8.5, FieldOwner.PRIMARY))
    )
    val tvdbPrimary = MetadataCandidate(
        provider = MetadataPrimaryProvider.TVDB,
        sourceRole = SourceRole.PRIMARY,
        fields = mapOf(ResolvedField.RATING to FieldValue(0.0, FieldOwner.PRIMARY))
    )

    val doc = FieldResolver().resolve(
        preview = tmdbPreview,
        primary = tvdbPrimary,
        secondary = emptyList()
    )

    assertEquals(8.5, doc.rating)
    assertEquals(MetadataPrimaryProvider.TMDB.name, doc.sourceProviders[ResolvedField.RATING])
    assertEquals(SourceRole.RAIL_PREVIEW, doc.sourceRoles[ResolvedField.RATING])
}

@Test
fun `RATING field is replaced when primary canonical rating is non-zero`() {
    val tmdbPreview = MetadataCandidate(
        provider = MetadataPrimaryProvider.TMDB,
        sourceRole = SourceRole.RAIL_PREVIEW,
        fields = mapOf(ResolvedField.RATING to FieldValue(8.5, FieldOwner.PRIMARY))
    )
    val tvdbPrimary = MetadataCandidate(
        provider = MetadataPrimaryProvider.TVDB,
        sourceRole = SourceRole.PRIMARY,
        fields = mapOf(ResolvedField.RATING to FieldValue(9.0, FieldOwner.PRIMARY))
    )

    val doc = FieldResolver().resolve(
        preview = tmdbPreview,
        primary = tvdbPrimary,
        secondary = emptyList()
    )

    assertEquals(9.0, doc.rating)
    assertEquals(MetadataPrimaryProvider.TVDB.name, doc.sourceProviders[ResolvedField.RATING])
}
```

If `MetadataCandidate`'s constructor takes more args (likely it does), match the actual signature in this file. If `FieldResolver().resolve(...)` is named differently (e.g. `resolveFields(...)`), match. If `doc.rating` is exposed via a different property, match. The semantics being asserted: TMDB rail-preview rating of 8.5 must survive a TVDB primary rating of 0.0; TMDB rail-preview rating of 8.5 must lose to a TVDB primary rating of 9.0.

- [ ] **Step 3: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.metadata.router.FieldResolverTest.RATING field preserves numerically-better preview when primary canonical rating is null' --tests 'com.nexio.tv.core.metadata.router.FieldResolverTest.RATING field is replaced when primary canonical rating is non-zero'
```

Expected: the first test fails (TVDB null overrides preview); the second test passes already.

- [ ] **Step 4: Implement the RATING-aware skip**

In the `primary?.fields?.forEach { (field, fieldValue) -> ... }` block (around line 76-107), wrap the body so RATING is skipped when the primary's value is not a positive number:

```kotlin
primary?.fields?.forEach { (field, fieldValue) ->
    if (field == ResolvedField.RATING && !fieldValue.isPositiveRating()) {
        rejectedByField.getOrPut(field) { mutableListOf() }.add(
            mapOf(
                "provider" to primary.provider.name,
                "sourceProvider" to primary.provider.name,
                "sourceRole" to SourceRole.PRIMARY.name,
                "reason" to "primary canonical rating value missing"
            )
        )
        return@forEach
    }
    val previewSourceRole = sourceRoles[field]
    // ... existing body unchanged ...
}
```

Add the helper near the bottom of `FieldResolver.kt`:

```kotlin
private fun FieldValue.isPositiveRating(): Boolean {
    val number = value as? Number ?: return false
    return number.toDouble() > 0.0
}
```

Match the file's existing `private fun FieldValue.X(): Y` style (extension on `FieldValue` if other helpers do that, top-level otherwise).

- [ ] **Step 5: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests 'com.nexio.tv.core.metadata.router.FieldResolverTest.RATING field*'
```

Expected: both tests pass.

- [ ] **Step 6: Run the full FieldResolver test suite**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
```

Expected: pass. If any pre-existing test breaks because it relied on the buggy behavior (TVDB primary clobbering a preview rating), update the fixture so the primary's rating is `> 0.0` and re-run. Don't change assertions to mask the fix.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt
git commit -m "$(cat <<'EOF'
fix: skip primary canonical RATING replacement when primary value is missing

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 6: `toHomeDisplayMetadata` Reads `sourceProviders[RATING]`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeStableIdBundleTest.kt` OR a new `MetadataRouterFacadeRatingSourceTest.kt`

- [ ] **Step 1: Read `toHomeDisplayMetadata`**

Run:

```bash
sed -n '1085,1110p' app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt
```

Expected: a function that copies a `fallback: HomeDisplayMetadata` and overrides fields like `imdbRating = (resolved.fields[ResolvedField.RATING] as? Number)?.toDouble() ?: fallback.imdbRating`. The `ratingSource` field is preserved from the fallback (i.e., the addon preview's source).

- [ ] **Step 2: Write a failing test**

Decide where to put the test. If `MetadataRouterFacadeStableIdBundleTest.kt` already covers `toHomeDisplayMetadata`, add the test there. Otherwise create `MetadataRouterFacadeRatingSourceTest.kt` in the same directory. Add:

```kotlin
@Test
fun `toHomeDisplayMetadata maps sourceProviders RATING to ratingSource`() {
    val resolved = ResolvedMetadataDocumentFixtures.tvShow(
        rating = 8.4,
        sourceProviders = mapOf(ResolvedField.RATING to MetadataPrimaryProvider.TMDB.name),
        sourceRoles = mapOf(ResolvedField.RATING to SourceRole.RAIL_PREVIEW)
    )
    val fallback = HomeDisplayMetadata(
        title = "The Boys",
        ratingSource = TitleRatingSource.IMDB
    )

    val mapped = MetadataRouterFacade.toHomeDisplayMetadata(resolved, fallback)

    assertEquals(8.4, mapped.imdbRating)
    assertEquals(TitleRatingSource.TMDB, mapped.ratingSource)
}

@Test
fun `toHomeDisplayMetadata falls back to addon preview ratingSource when sourceProviders RATING is absent`() {
    val resolved = ResolvedMetadataDocumentFixtures.tvShow(
        rating = null,
        sourceProviders = emptyMap()
    )
    val fallback = HomeDisplayMetadata(
        title = "The Boys",
        imdbRating = 9.1,
        ratingSource = TitleRatingSource.IMDB
    )

    val mapped = MetadataRouterFacade.toHomeDisplayMetadata(resolved, fallback)

    assertEquals(9.1, mapped.imdbRating)
    assertEquals(TitleRatingSource.IMDB, mapped.ratingSource)
}

@Test
fun `toHomeDisplayMetadata does not silently default unknown rating source to IMDB`() {
    val resolved = ResolvedMetadataDocumentFixtures.tvShow(
        rating = 7.6,
        sourceProviders = mapOf(ResolvedField.RATING to MetadataPrimaryProvider.TVDB.name),
        sourceRoles = mapOf(ResolvedField.RATING to SourceRole.PRIMARY)
    )
    val fallback = HomeDisplayMetadata(
        title = "The Boys",
        ratingSource = null
    )

    val mapped = MetadataRouterFacade.toHomeDisplayMetadata(resolved, fallback)

    assertEquals(7.6, mapped.imdbRating, "rating value must survive even when source enum cannot represent the provider")
    assertNull(mapped.ratingSource, "TVDB rating source must not be silently coerced to IMDB; leave null until enum is widened")
}
```

If `ResolvedMetadataDocumentFixtures` doesn't exist, locate the closest `ResolvedMetadataDocument` builder used in this test file (search `ResolvedMetadataDocument(`). The fixture must populate `sourceProviders` and `sourceRoles` with the relevant RATING entries.

- [ ] **Step 3: Run the red tests**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests '*toHomeDisplayMetadata maps sourceProviders RATING to ratingSource*' --tests '*toHomeDisplayMetadata falls back to addon preview ratingSource when sourceProviders RATING is absent*'
```

Expected: the first test fails (returns IMDB instead of TMDB); the second test passes.

- [ ] **Step 4: Implement the source mapping**

In `MetadataRouterFacade.kt` `toHomeDisplayMetadata`, locate the `imdbRating = ...` assignment and add a `ratingSource = ...` line:

```kotlin
ratingSource = resolved.sourceProviders[ResolvedField.RATING]
    ?.let { providerName ->
        when (providerName) {
            MetadataPrimaryProvider.TMDB.name -> TitleRatingSource.TMDB
            MetadataPrimaryProvider.IMDB.name -> TitleRatingSource.IMDB
            else -> null
        }
    }
    ?: fallback.ratingSource
```

Reasoning: until `TitleRatingSource` is widened, only TMDB and IMDB are representable. Map known providers; fall back to the existing addon-preview source for everything else.

- [ ] **Step 5: Run the tests green**

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeStableIdBundleTest --tests '*MetadataRouterFacadeRatingSource*'
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/metadata/router/
git commit -m "$(cat <<'EOF'
fix: derive HomeDisplayMetadata ratingSource from resolved sourceProviders

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Task 7: Wire `RatingResolver` Into Resolved-Doc RATING Selection

**Status:** REQUIRED follow-up — only execute if device verification (Task 8) confirms Tasks 5 and 6 left the rating value or source still wrong. Do not skip this task on the basis of "looks fine in unit tests" — verify on the device first.

**Why it's separate:** Tasks 5 and 6 are tactical patches:
- Task 5 just keeps a positive-value preview from being clobbered by an empty primary.
- Task 6 just plumbs the source provider name through.

Neither makes `RatingResolver` (`app/src/main/java/com/nexio/tv/core/metadata/router/resolver/RatingResolver.kt`) the actual decision authority for RATING. The resolver already implements the right precedence (`CUSTOM_IMDB → MDBLIST → OMDB → PRIMARY_PROVIDER → PREVIEW_FALLBACK`) and filters out 0.0 values, but no production code calls `RatingResolver.resolveTitleRating` for the home metadata path. The proper architecture is: collect rating candidates from all sources (canonical primary, secondary, preview, MDBList, OMDb), pass them to `RatingResolver`, and apply the resolver's chosen value+source as the RATING field's selected source — bypassing the `FieldResolver`'s primary-replaces-preview rule entirely for this one field.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt` (or whichever site assembles `ResolvedMetadataDocument`)
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverTest.kt`

- [ ] **Step 1: Confirm whether `RatingResolver` is invoked**

Run:

```bash
grep -rn "RatingResolver\.resolveTitleRating" app/src/main/java/com/nexio/tv/ | head
```

Expected: zero or one production caller. If zero, `RatingResolver.resolveTitleRating` is dead code today.

- [ ] **Step 2: Decide scope**

If `RatingResolver` is unused in production, this task expands beyond the current bug. Stop and report up to the controller before continuing — wiring `RatingResolver` into `FieldResolver`'s RATING path is a refactor that needs its own scope decision.

If `RatingResolver` already gates RATING selection somewhere upstream, this task is a no-op — close it and move to Task 8.

- [ ] **Step 3 (only if greenlighted): Refactor RATING selection through `RatingResolver`**

Outside the bounds of this plan. Open a follow-up plan with brainstorming.

## Task 8: Device Verification Without Raw Evidence Commit

**Files:**
- Modify: `review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json` (replace counts with the new run's values)

- [ ] **Step 1: Build the release APK**

```bash
./gradlew :app:assembleArmv7Release
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Force-stop, clear logcat, install, launch on the device**

```bash
DEV=192.168.50.98:5555
adb -s $DEV shell am force-stop com.nexio.tv
adb -s $DEV logcat -c
adb -s $DEV install -r app/build/outputs/apk/armv7/release/app-armv7-release.apk
adb -s $DEV shell monkey -p com.nexio.tv 1
```

Expected: install Success and `Events injected: 1`.

- [ ] **Step 3: Wait for hydration and capture artifacts**

Wait at least 30 seconds for hydration to settle. Then:

```bash
DEV=192.168.50.98:5555
mkdir -p tmp/crash-investigation-2026-05-08/repro2
adb -s $DEV logcat -d -v threadtime > tmp/crash-investigation-2026-05-08/repro2/logcat-postfix.txt
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/hydrated_home_overlay_v1.xml /sdcard/overlay-postfix.xml'
adb -s $DEV pull /sdcard/overlay-postfix.xml tmp/crash-investigation-2026-05-08/repro2/overlay-postfix.xml
adb -s $DEV shell su -c 'cp /data/data/com.nexio.tv/shared_prefs/continue_watching_snapshot.xml /sdcard/cw-postfix.xml'
adb -s $DEV pull /sdcard/cw-postfix.xml tmp/crash-investigation-2026-05-08/repro2/cw-postfix.xml
```

Expected: three files under `tmp/crash-investigation-2026-05-08/repro2/`. None will be staged.

- [ ] **Step 4: Sanity-check the overlay and runtime traces against expected behavior**

Run a broader targeted python check that covers all three packets, not just rating:

```bash
python3 << 'EOF'
import xml.etree.ElementTree as ET, html, json, re
from collections import Counter

overlay_path = 'tmp/crash-investigation-2026-05-08/repro2/overlay-postfix.xml'
logcat_path = 'tmp/crash-investigation-2026-05-08/repro2/logcat-postfix.txt'

# --- Overlay-shape checks ---
t = ET.parse(overlay_path)
strings = [(c.attrib['name'], c.text or '') for c in t.getroot() if c.tag == 'string']
tvdb_series = [(n, json.loads(html.unescape(v))['value']) for n, v in strings
               if n.startswith('overlay::canonical:TVDB:') and 'type:SERIES' in n]

ratings_non_null = sum(1 for _, v in tvdb_series if v.get('fields', {}).get('imdbRating') is not None)
rpdb_poster_refs = sum(1 for _, v in tvdb_series
                      if 'provider:RPDB' in (v.get('fields', {}).get('poster') or ''))
tvdb_logo_refs = sum(1 for _, v in tvdb_series
                     if 'TVDB:logo' in (v.get('fields', {}).get('logo') or ''))
tvdb_backdrop_refs = sum(1 for _, v in tvdb_series
                         if 'TVDB:backdrop' in (v.get('fields', {}).get('backdrop') or ''))

print(f'TVDB SERIES overlays: {len(tvdb_series)}')
print(f'  with imdbRating non-null: {ratings_non_null} (target: > 0 — Tasks 5-6)')
print(f'  with RPDB poster ref:    {rpdb_poster_refs}')
print(f'  with TVDB logo ref:      {tvdb_logo_refs}')
print(f'  with TVDB backdrop ref:  {tvdb_backdrop_refs}')

# --- Runtime-trace checks ---
with open(logcat_path) as f:
    log = f.read()

def count(pattern):
    return len(re.findall(pattern, log))

events = {
    'fallback_materialized':        count(r'artwork\.fallback_materialized'),
    'fallback_suppressed_soft':     count(r'artwork\.fallback_suppressed_soft_failure'),
    'asset_record_write_failed':    count(r'artwork\.asset_record_store_write_failed'),
    'orphan_rehydrate_skipped':     count(r'artwork\.orphan_asset_ref_rehydrate_skipped'),
    'asset_materialized_logo':      count(r'asset_materialized.*imageType=LOGO'),
    'asset_materialized_backdrop':  count(r'asset_materialized.*imageType=BACKDROP'),
    'asset_materialized_poster':    count(r'asset_materialized.*imageType=POSTER'),
    'rpdb_expired_miss':            count(r'rpdb\.poster_template.*EXPIRED_MISS'),
}
print('\nRuntime trace counts:')
for k, n in events.items():
    print(f'  {k}: {n}')

print('\nAcceptance signals:')
print(f'  Bug 1 (popping): fallback_materialized must NOT exceed fallback_suppressed_soft on soft network failures.')
print(f'    fallback_materialized={events["fallback_materialized"]} fallback_suppressed_soft={events["fallback_suppressed_soft"]}')
print(f'  Bug 2 (logos/backdrops): asset_materialized for LOGO and BACKDROP must be > 0 in a 30s window.')
print(f'    LOGO={events["asset_materialized_logo"]} BACKDROP={events["asset_materialized_backdrop"]}')
print(f'  Bug 3 (rating): TVDB SERIES overlays with imdbRating non-null must be > 0.')
print(f'    non-null={ratings_non_null}')
EOF
```

Expected after Tasks 1, 1.5, 2, 3, 4, 5, 6:
- `imdbRating non-null` > 0 for TVDB SERIES overlays (was 0)
- `fallback_suppressed_soft` events appear when network is flaky on RPDB
- `fallback_materialized` is rare or zero on a healthy network
- `asset_materialized` counts for `LOGO` and `BACKDROP` are non-zero (were both zero before)
- `orphan_rehydrate_skipped` traces appear if the asset URI's record/decision are missing — log them but do not let the test fail on their existence; they're informational

Capture the key counts in the sanitized summary in Step 5.

- [ ] **Step 5: Regenerate the sanitized summary**

```bash
python3 tools/reporting/summarize_artwork_state.py \
  --overlay tmp/crash-investigation-2026-05-08/repro2/overlay-postfix.xml \
  --snapshot tmp/crash-investigation-2026-05-08/repro2/cw-postfix.xml \
  --logcat tmp/crash-investigation-2026-05-08/repro2/logcat-postfix.txt \
  --adb-connect-status connected \
  --apk-install-status success \
  --launch-status success \
  --su-access available \
  --device-error-category none \
  --logcat-captured-without-clear \
  --output review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json
```

- [ ] **Step 6: Verify no raw URLs / device evidence is staged**

```bash
git diff --cached --name-only
```

Expected: only `review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json` is staged. The `tmp/` files are untracked.

- [ ] **Step 7: Commit the summary refresh**

```bash
git add review-dossier/2026-05-08-tv-artwork-cw-postfix-summary.json
git commit -m "$(cat <<'EOF'
docs: refresh sanitized device summary after popping/rating fixes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

## Required Verification Commands

Run before claiming the plan complete:

```bash
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.image.NexioArtworkFetcherTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverTest
./gradlew :app:testArmv7DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeStableIdBundleTest
./gradlew :app:assembleArmv7Release
```

Then run the device verification in Task 8 without clearing logcat against the freshly-installed APK.

## Acceptance Criteria

- A premium poster decision URI whose primary asset is on disk is served stale without invoking `getOrFetchFallback`, even when the runtime probe fails with `network-missing`.
- `ArtworkAssetRepository.fetchOrFallback(decision)` suppresses fallback materialization on soft transient failures (`MISSING_NETWORK`, timeouts, 5xx, 429) when the prior decision's asset key is recoverable on disk or in the asset record store. Hard failures (credential invalid, provider disabled, unsupported ID) still allow fallback.
- `artwork.fallback_suppressed_soft_failure` trace events appear when the suppression fires.
- `artwork.asset_record_store_write_failed` trace events carry an `errorMessage` payload field populated from the underlying exception.
- `ArtworkAssetResult.durable` is `false` when `persistAssetRecordBestEffort` failed, with `cacheDecision = "EPHEMERAL_RECORD_WRITE_FAILED"`. Downstream snapshot/overlay-writer integration is tracked as a follow-up.
- `ArtworkAssetRepository.getOrRehydrateAsset(assetKey)` re-runs the originating decision when bytes are absent and the asset record + decision are still on file; returns null and emits `artwork.orphan_asset_ref_rehydrate_skipped` for orphans.
- `NexioArtworkFetcher.fetch` for `nexio-artwork://asset/...` URIs falls through to `getOrRehydrateAsset` when the disk file is missing, instead of returning null.
- `FieldResolver` does not let a primary canonical RATING with value `<= 0.0` (or non-Number) replace a numerically-better rail-preview RATING.
- `MetadataRouterFacade.toHomeDisplayMetadata` derives `HomeDisplayMetadata.ratingSource` from `sourceProviders[ResolvedField.RATING]` when present, mapping known providers to `TitleRatingSource`.
- An unknown rating-source provider (TVDB, Kitsu, Trakt, etc.) is preserved as a non-null `imdbRating` value with `ratingSource = null`, never silently coerced to `IMDB`.
- Device overlay xml after re-launch shows `fields.imdbRating != null` for at least one TVDB-canonical SERIES row that previously had `null`.
- Device runtime trace shows `asset_materialized` events for `imageType=LOGO` and `imageType=BACKDROP` in a 30s window (currently zero per the dossier).
- Device runtime trace shows `fallback_materialized` count is zero (or strictly less than `fallback_suppressed_soft_failure` count) on a soft-failure run.
- Sanitized summary JSON refreshed without staging raw evidence.

## Commit Plan

```text
fix: keep stale-but-on-disk artwork over fallback materialization
fix: suppress artwork fallback materialization on soft transient failures
fix: flag artwork asset results as non-durable when record store write fails
feat: rehydrate evicted artwork assets from their originating decision
fix: rehydrate evicted artwork assets when Coil resolves a stored asset URI
fix: skip primary canonical RATING replacement when primary value is missing
fix: derive HomeDisplayMetadata ratingSource from resolved sourceProviders
docs: refresh sanitized device summary after popping/rating fixes
```

If Task 7 is required after Task 8 verification, append:

```text
refactor: route RATING field selection through RatingResolver
```

Do not stage:

```text
tmp/crash-investigation-2026-05-08/repro2/*.xml
tmp/crash-investigation-2026-05-08/repro2/*logcat*.txt
app/src/main/assets/openrouter_reasoning_models.json
media
```
