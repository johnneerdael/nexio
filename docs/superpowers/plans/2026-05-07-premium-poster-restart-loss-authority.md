# Premium Poster Restart Loss Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent premium poster loss on restart by making durable artwork decision cache authority explicit and making home snapshot sanitization non-destructive whenever cache authority is failed, partial, loading, not loaded, context-mismatched, or lookup-failed.

**Architecture:** Add typed decision lookup and explicit durable-store load states at the `ArtworkDecisionCache` boundary. `DurableArtworkDecisionCache` becomes the authority source, including per-record quarantine and authority-context diagnostics; `HomeCatalogSnapshotStore` consumes typed lookup results and only clears decision-backed poster refs on `MissingAuthoritative`. Rehydration is requested through trace/event signaling without persistently clearing the original decision ref.

**Tech Stack:** Kotlin, Android shared preferences snapshot store, Gson DTO persistence, existing `RuntimeTraceSink` / `LogcatRuntimeTraceSink`, JUnit/Robolectric, MockK.

---

## Preflight

This repo is intentionally dirty under `/Users/jneerdael/Scripts/nexio`. Do not move this work to a clean worktree. Do not revert unrelated restored files.

Current known unrelated or separately scoped local changes include:

- Artwork decision write-amplification patch in `DurableArtworkDecisionCache.kt`, `IntegrationRuntimeModule.kt`, and `ArtworkDecisionCacheTest.kt`.
- Untracked slowdown RCA: `review-dossier/android-home-screensaver-slowdown-rca-2026-05-07.md`.

When executing this plan, stage only files touched by the current task. If the write-amplification patch remains unstaged, preserve it and edit around it. If it is already committed by the time this plan runs, continue normally.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
  - Owns public cache contracts: typed lookup results, load state, authority context, diagnostics DTO.
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
  - Owns durable JSON parsing, per-record quarantine, authoritative/partial/failed load state, typed lookup, and store-load diagnostics.
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  - Owns snapshot read/write sanitization, non-destructive preservation, provider-tag handling, and rehydration request trace.
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Owns curated logcat fields for decision load, lookup, sanitizer action, and rehydration request.
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`
  - Tests load-state authority, partial loads, malformed DTO quarantine, top-level parse failure, lookup results, and authority context.
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
  - Tests snapshot preservation/cleanup/writeback behavior and rehydration request traces.
- Modify: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`
  - Tests logcat curated fields.
- Optional trailing audit only: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogRefreshCoordinator.kt`
  - Only touch if the raw premium URL bypass is tiny and directly writes snapshot poster URLs.

## Task 1: Add Authority Models And Cache Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`

- [ ] **Step 1: Write failing contract tests for in-memory lookup authority**

Add these tests near the existing in-memory cache tests in `ArtworkDecisionCacheTest.kt`:

```kotlin
@Test
fun `in-memory cache reports authoritative found and missing lookup results`() {
    val key = ArtworkDecisionKey("in-memory-authority")
    val decision = decision(key, ArtworkOwnerKey.CanonicalContent("imdb:tt123"))

    cache.put(decision)

    assertEquals(
        ArtworkDecisionLookupResult.Found(decision),
        cache.lookup(key)
    )
    assertEquals(
        ArtworkDecisionLookupResult.MissingAuthoritative(
            decisionKey = ArtworkDecisionKey("absent"),
            loadState = ArtworkDecisionStoreLoadState.LoadedAuthoritative(
                decisionCount = 1,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 0,
                schemaVersion = null,
                storedSchemaVersion = null,
                authorityContext = null
            )
        ),
        cache.lookup(ArtworkDecisionKey("absent"))
    )
}

@Test
fun `loaded authoritative state requires zero dropped and quarantined records`() {
    assertFalse(
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = 2,
            droppedDecisionCount = 1,
            quarantinedDecisionCount = 0,
            schemaVersion = 1,
            storedSchemaVersion = 1,
            authorityContext = null
        ).isAuthoritativeForMissing()
    )
    assertFalse(
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = 2,
            droppedDecisionCount = 0,
            quarantinedDecisionCount = 1,
            schemaVersion = 1,
            storedSchemaVersion = 1,
            authorityContext = null
        ).isAuthoritativeForMissing()
    )
    assertTrue(
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = 2,
            droppedDecisionCount = 0,
            quarantinedDecisionCount = 0,
            schemaVersion = 1,
            storedSchemaVersion = 1,
            authorityContext = null
        ).isAuthoritativeForMissing()
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: compile failure for missing `ArtworkDecisionLookupResult`, `ArtworkDecisionStoreLoadState`, `lookup`, and `isAuthoritativeForMissing`.

- [ ] **Step 3: Add models and interface methods**

In `ArtworkDecisionCache.kt`, replace the top of the file with these contracts while keeping the existing cache methods:

```kotlin
package com.nexio.tv.core.artwork

interface ArtworkDecisionCache {
    fun get(key: ArtworkDecisionKey): ArtworkDecision?
    fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext? = null
    ): ArtworkDecisionLookupResult
    fun loadState(): ArtworkDecisionStoreLoadState
    fun put(decision: ArtworkDecision)
    fun remove(key: ArtworkDecisionKey)
    fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    )
    fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision?
    fun invalidateBySettingsHash(settingsHash: String)
    fun invalidateByCredentialHash(credentialHash: String)
    fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>)
    fun invalidatePremiumArtworkPolicy()
}

data class ArtworkDecisionAuthorityContext(
    val storeIdHash: String,
    val schemaVersion: Int?,
    val providerPolicyHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String = "en"
) {
    fun matches(required: ArtworkDecisionAuthorityContext?): Boolean {
        if (required == null) return true
        return storeIdHash == required.storeIdHash &&
            schemaVersion == required.schemaVersion &&
            required.providerPolicyHash.matchesNullable(providerPolicyHash) &&
            required.settingsHash.matchesNullable(settingsHash) &&
            required.credentialHash.matchesNullable(credentialHash) &&
            imageLanguage == required.imageLanguage
    }

    private fun String?.matchesNullable(actual: String?): Boolean =
        this == null || this == actual
}

sealed interface ArtworkDecisionStoreLoadState {
    data object NotLoaded : ArtworkDecisionStoreLoadState
    data object Loading : ArtworkDecisionStoreLoadState

    data class LoadedAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int,
        val quarantinedDecisionCount: Int,
        val schemaVersion: Int?,
        val storedSchemaVersion: Int?,
        val authorityContext: ArtworkDecisionAuthorityContext?
    ) : ArtworkDecisionStoreLoadState

    data class LoadedPartialNonAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int,
        val quarantinedDecisionCount: Int,
        val schemaVersion: Int?,
        val storedSchemaVersion: Int?,
        val authorityContext: ArtworkDecisionAuthorityContext?,
        val errorClass: String?,
        val errorMessageHash: String?,
        val errorTopFrame: String?,
        val firstQuarantinedDecisionKeyHash: String?
    ) : ArtworkDecisionStoreLoadState

    data class FailedNonAuthoritative(
        val errorClass: String?,
        val errorMessageHash: String?,
        val errorTopFrame: String?,
        val authorityContext: ArtworkDecisionAuthorityContext?
    ) : ArtworkDecisionStoreLoadState
}

fun ArtworkDecisionStoreLoadState.isAuthoritativeForMissing(
    requiredContext: ArtworkDecisionAuthorityContext? = null
): Boolean =
    this is ArtworkDecisionStoreLoadState.LoadedAuthoritative &&
        droppedDecisionCount == 0 &&
        quarantinedDecisionCount == 0 &&
        authorityContext?.matches(requiredContext) != false

sealed interface ArtworkDecisionLookupResult {
    data class Found(
        val decision: ArtworkDecision
    ) : ArtworkDecisionLookupResult

    data class MissingAuthoritative(
        val decisionKey: ArtworkDecisionKey,
        val loadState: ArtworkDecisionStoreLoadState.LoadedAuthoritative
    ) : ArtworkDecisionLookupResult

    data class CacheNotAuthoritative(
        val decisionKey: ArtworkDecisionKey,
        val loadState: ArtworkDecisionStoreLoadState,
        val reason: String?,
        val errorClass: String?
    ) : ArtworkDecisionLookupResult

    data class LookupFailed(
        val decisionKey: ArtworkDecisionKey,
        val errorClass: String,
        val messageHash: String?
    ) : ArtworkDecisionLookupResult
}
```

Then update `ArtworkDecisionCacheSnapshotDiagnostics` to include authority fields:

```kotlin
data class ArtworkDecisionCacheSnapshotDiagnostics(
    val loaded: Boolean,
    val decisionCount: Int,
    val linkCount: Int,
    val storeFilePresent: Boolean?,
    val storeFileReadable: Boolean?,
    val storeFileBytes: Long?,
    val lastLoadSuccess: Boolean?,
    val lastLoadReason: String?,
    val lastLoadErrorClass: String?,
    val droppedDecisionCount: Int?,
    val quarantinedDecisionCount: Int? = null,
    val loadStateName: String? = null,
    val authoritative: Boolean? = null,
    val schemaVersion: Int? = null,
    val storedSchemaVersion: Int? = null,
    val errorMessageHash: String? = null,
    val errorTopFrame: String? = null,
    val firstQuarantinedDecisionKeyHash: String? = null,
    val authorityContext: ArtworkDecisionAuthorityContext? = null
)
```

- [ ] **Step 4: Implement in-memory lookup**

In `InMemoryArtworkDecisionCache`, add:

```kotlin
@Synchronized
override fun lookup(
    key: ArtworkDecisionKey,
    requiredContext: ArtworkDecisionAuthorityContext?
): ArtworkDecisionLookupResult {
    val state = loadState() as ArtworkDecisionStoreLoadState.LoadedAuthoritative
    return decisions[key]?.let(ArtworkDecisionLookupResult::Found)
        ?: ArtworkDecisionLookupResult.MissingAuthoritative(
            decisionKey = key,
            loadState = state
        )
}

@Synchronized
override fun loadState(): ArtworkDecisionStoreLoadState =
    ArtworkDecisionStoreLoadState.LoadedAuthoritative(
        decisionCount = decisions.size,
        droppedDecisionCount = 0,
        quarantinedDecisionCount = 0,
        schemaVersion = null,
        storedSchemaVersion = null,
        authorityContext = null
    )
```

Update `snapshotDiagnostics()` with the new defaults:

```kotlin
override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics =
    ArtworkDecisionCacheSnapshotDiagnostics(
        loaded = true,
        decisionCount = decisions.size,
        linkCount = previewToCanonical.size,
        storeFilePresent = null,
        storeFileReadable = null,
        storeFileBytes = null,
        lastLoadSuccess = true,
        lastLoadReason = null,
        lastLoadErrorClass = null,
        droppedDecisionCount = 0,
        quarantinedDecisionCount = 0,
        loadStateName = "LoadedAuthoritative",
        authoritative = true,
        schemaVersion = null,
        storedSchemaVersion = null,
        authorityContext = null
    )
```

- [ ] **Step 5: Run tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: PASS for in-memory contract tests. Durable cache tests may fail until Task 2 if `DurableArtworkDecisionCache` has not implemented the new methods; if so, continue Task 2 before committing.

Commit after Task 2 if compilation requires both tasks.

## Task 2: Implement Durable Load State, Typed Lookup, And Per-Record Quarantine

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt`

- [ ] **Step 1: Add failing tests for durable authority states**

Add these tests to `ArtworkDecisionCacheTest.kt` after existing durable cache restore tests:

```kotlin
@Test
fun `durable cache clean load is authoritative and missing lookup is authoritative`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val first = DurableArtworkDecisionCache(file = file, gson = Gson())
    val decision = durableRpdbDecision()
    first.put(decision)

    val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

    assertEquals(ArtworkDecisionLookupResult.Found(decision), restarted.lookup(decision.decisionKey))
    val missing = restarted.lookup(ArtworkDecisionKey("missing-after-clean-load"))
    assertTrue(missing is ArtworkDecisionLookupResult.MissingAuthoritative)
    val state = restarted.loadState()
    assertTrue(state is ArtworkDecisionStoreLoadState.LoadedAuthoritative)
    assertTrue(state.isAuthoritativeForMissing())
}

@Test
fun `durable cache quarantines malformed record and partial missing is not authoritative`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val valid = durableRpdbDecision()
    DurableArtworkDecisionCache(file = file, gson = Gson()).put(valid)
    val raw = file.readText()
    val malformed = raw.replace(
        "\"decisions\":[",
        "\"decisions\":[{\"decisionKey\":\"bad-decision\",\"owner\":{\"type\":\"unknown\"},\"canonicalContentId\":null,\"imageType\":\"POSTER\",\"selectedCandidate\":{\"provider\":{\"type\":\"placeholder\",\"integrationProvider\":null},\"sourceRole\":\"PLACEHOLDER\",\"sourceHash\":null,\"redactedSourceForTrace\":null,\"providerTemplate\":null,\"priority\":90},\"rejectedCandidates\":[],\"policyVersion\":1,\"imageLanguage\":\"en\",\"settingsHash\":null,\"credentialHash\":null,\"createdAtMs\":1,\"expiresAtMs\":2,\"staleUntilMs\":3},"
    )
    file.writeText(malformed)

    val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

    assertEquals(ArtworkDecisionLookupResult.Found(valid), restarted.lookup(valid.decisionKey))
    val missing = restarted.lookup(ArtworkDecisionKey("missing-after-partial-load"))
    assertTrue(missing is ArtworkDecisionLookupResult.CacheNotAuthoritative)
    val state = restarted.loadState()
    assertTrue(state is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative)
    assertFalse(state.isAuthoritativeForMissing())
}

@Test
fun `durable cache top level parse failure is failed non authoritative`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    file.writeText("{ not valid json")
    val cache = DurableArtworkDecisionCache(file = file, gson = Gson())

    val result = cache.lookup(ArtworkDecisionKey("any-decision"))

    assertTrue(result is ArtworkDecisionLookupResult.CacheNotAuthoritative)
    assertTrue(cache.loadState() is ArtworkDecisionStoreLoadState.FailedNonAuthoritative)
}

@Test
fun `missing authoritative requires matching authority context`() {
    val temp = TemporaryFolder().also { it.create() }
    val file = temp.newFile("artwork-decisions.json")
    val cache = DurableArtworkDecisionCache(file = file, gson = Gson())
    cache.put(durableRpdbDecision())
    cache.loadState()
    val actualContext = (cache.loadState() as ArtworkDecisionStoreLoadState.LoadedAuthoritative)
        .authorityContext
        ?: error("expected authority context")
    val mismatched = actualContext.copy(settingsHash = "different-settings")

    val result = cache.lookup(
        key = ArtworkDecisionKey("missing-with-context"),
        requiredContext = mismatched
    )

    assertTrue(result is ArtworkDecisionLookupResult.CacheNotAuthoritative)
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: compile/runtime failures because durable load state, context, and quarantine behavior are not implemented.

- [ ] **Step 3: Add durable state fields and helper methods**

In `DurableArtworkDecisionCache.kt`, add imports:

```kotlin
import java.security.MessageDigest
```

Add fields near existing load diagnostics:

```kotlin
private var currentLoadState: ArtworkDecisionStoreLoadState = ArtworkDecisionStoreLoadState.NotLoaded
private var lastQuarantinedDecisionCount: Int? = null
private var lastErrorMessageHash: String? = null
private var lastErrorTopFrame: String? = null
private var firstQuarantinedDecisionKeyHash: String? = null
```

Add helpers near `currentFileStats()`:

```kotlin
private fun authorityContext(): ArtworkDecisionAuthorityContext =
    ArtworkDecisionAuthorityContext(
        storeIdHash = file.absolutePath.sha256Short(),
        schemaVersion = SCHEMA_VERSION,
        providerPolicyHash = null,
        settingsHash = null,
        credentialHash = null,
        imageLanguage = "en"
    )

private fun Throwable.messageHash(): String? =
    message?.takeIf { it.isNotBlank() }?.sha256Short()

private fun Throwable.topFrame(): String? =
    stackTrace.firstOrNull()?.let { frame ->
        "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

private fun String.sha256Short(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }.take(16)
}

private fun loadStateName(state: ArtworkDecisionStoreLoadState): String =
    when (state) {
        ArtworkDecisionStoreLoadState.NotLoaded -> "NotLoaded"
        ArtworkDecisionStoreLoadState.Loading -> "Loading"
        is ArtworkDecisionStoreLoadState.LoadedAuthoritative -> "LoadedAuthoritative"
        is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative -> "LoadedPartialNonAuthoritative"
        is ArtworkDecisionStoreLoadState.FailedNonAuthoritative -> "FailedNonAuthoritative"
    }
```

- [ ] **Step 4: Implement `loadState()` and `lookup()`**

Add methods after `get()`:

```kotlin
override fun loadState(): ArtworkDecisionStoreLoadState = synchronized(lock) {
    ensureLoadedLocked()
    currentLoadState
}

override fun lookup(
    key: ArtworkDecisionKey,
    requiredContext: ArtworkDecisionAuthorityContext?
): ArtworkDecisionLookupResult = synchronized(lock) {
    runCatching {
        ensureLoadedLocked()
        decisions[key]?.let(ArtworkDecisionLookupResult::Found)
            ?: when (val state = currentLoadState) {
                is ArtworkDecisionStoreLoadState.LoadedAuthoritative ->
                    if (state.isAuthoritativeForMissing(requiredContext)) {
                        ArtworkDecisionLookupResult.MissingAuthoritative(
                            decisionKey = key,
                            loadState = state
                        )
                    } else {
                        ArtworkDecisionLookupResult.CacheNotAuthoritative(
                            decisionKey = key,
                            loadState = state,
                            reason = "authority_context_mismatch",
                            errorClass = null
                        )
                    }
                else -> ArtworkDecisionLookupResult.CacheNotAuthoritative(
                    decisionKey = key,
                    loadState = state,
                    reason = "cache_not_authoritative",
                    errorClass = when (state) {
                        is ArtworkDecisionStoreLoadState.FailedNonAuthoritative -> state.errorClass
                        is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative -> state.errorClass
                        else -> null
                    }
                )
            }
    }.getOrElse { error ->
        ArtworkDecisionLookupResult.LookupFailed(
            decisionKey = key,
            errorClass = error.javaClass.simpleName,
            messageHash = error.messageHash()
        )
    }
}
```

Keep `get()` as compatibility:

```kotlin
override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
    ensureLoadedLocked()
    decisions[key]
}
```

- [ ] **Step 5: Make load parsing set authoritative, partial, or failed state**

At the start of `ensureLoadedLocked()` set:

```kotlin
currentLoadState = ArtworkDecisionStoreLoadState.Loading
```

For missing file and blank file, call a new helper:

```kotlin
markLoadedAuthoritativeLocked(
    decisionCount = 0,
    linkCount = 0,
    droppedDecisionCount = 0,
    quarantinedDecisionCount = 0,
    storedSchemaVersion = SCHEMA_VERSION,
    filePresent = false
)
```

Replace the current `dto.decisions.orEmpty().mapNotNull` block with:

```kotlin
var droppedDecisionCount = 0
var quarantinedDecisionCount = 0
var firstQuarantineHash: String? = null
var firstErrorClass: String? = null
var firstErrorMessageHash: String? = null
var firstErrorTopFrame: String? = null

dto.decisions.orEmpty().forEach { decisionDto ->
    decisionDto.toDomainResult()
        .onSuccess { decision -> decisions[decision.decisionKey] = decision }
        .onFailure { error ->
            droppedDecisionCount += 1
            quarantinedDecisionCount += 1
            if (firstQuarantineHash == null) {
                firstQuarantineHash = decisionDto.decisionKey.sha256Short()
                firstErrorClass = error.javaClass.simpleName
                firstErrorMessageHash = error.messageHash()
                firstErrorTopFrame = error.topFrame()
            }
        }
}
```

Rename `DecisionDto.toDomainOrNull()` to:

```kotlin
fun toDomainResult(): Result<ArtworkDecision> = runCatching {
    ArtworkDecision(
        decisionKey = ArtworkDecisionKey(decisionKey),
        ownerKey = owner.toDomain(),
        canonicalContentId = canonicalContentId,
        imageType = ArtworkType.valueOf(imageType),
        selectedCandidate = selectedCandidate.toDomain(),
        rejectedCandidates = rejectedCandidates.orEmpty().map { rejected -> rejected.toDomain() },
        policyVersion = policyVersion,
        imageLanguage = imageLanguage,
        settingsHash = settingsHash,
        credentialHash = credentialHash,
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        staleUntilMs = staleUntilMs
    )
}
```

After preview links, set state:

```kotlin
if (droppedDecisionCount == 0 && quarantinedDecisionCount == 0) {
    markLoadedAuthoritativeLocked(
        decisionCount = decisions.size,
        linkCount = previewToCanonical.size,
        droppedDecisionCount = 0,
        quarantinedDecisionCount = 0,
        storedSchemaVersion = dto.schemaVersion,
        filePresent = true
    )
} else {
    markLoadedPartialLocked(
        decisionCount = decisions.size,
        linkCount = previewToCanonical.size,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        storedSchemaVersion = dto.schemaVersion,
        filePresent = true,
        errorClass = firstErrorClass,
        errorMessageHash = firstErrorMessageHash,
        errorTopFrame = firstErrorTopFrame,
        firstQuarantinedDecisionKeyHash = firstQuarantineHash
    )
}
```

For schema mismatch and top-level failure, use failed state:

```kotlin
markFailedLocked(
    decisionCount = 0,
    linkCount = 0,
    droppedDecisionCount = dto.decisions.orEmpty().size,
    quarantinedDecisionCount = dto.decisions.orEmpty().size,
    filePresent = true,
    reason = "schema_version_mismatch",
    storedSchemaVersion = dto.schemaVersion,
    errorClass = null,
    errorMessageHash = null,
    errorTopFrame = null
)
```

In `.onFailure { error -> ... }`, do not clear a partially restored store unless the failure occurred before record iteration. For the current top-level `runCatching`, keep:

```kotlin
decisions.clear()
previewToCanonical.clear()
markFailedLocked(
    decisionCount = 0,
    linkCount = 0,
    droppedDecisionCount = 0,
    quarantinedDecisionCount = 0,
    filePresent = true,
    reason = "load_exception",
    storedSchemaVersion = null,
    errorClass = error.javaClass.simpleName,
    errorMessageHash = error.messageHash(),
    errorTopFrame = error.topFrame()
)
```

- [ ] **Step 6: Add mark-state helpers and diagnostics payload**

Add these helpers near `traceDecisionStoreLoad`:

```kotlin
private fun markLoadedAuthoritativeLocked(
    decisionCount: Int,
    linkCount: Int,
    droppedDecisionCount: Int,
    quarantinedDecisionCount: Int,
    storedSchemaVersion: Int?,
    filePresent: Boolean
) {
    currentLoadState = ArtworkDecisionStoreLoadState.LoadedAuthoritative(
        decisionCount = decisionCount,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        schemaVersion = SCHEMA_VERSION,
        storedSchemaVersion = storedSchemaVersion,
        authorityContext = authorityContext()
    )
    traceDecisionStoreLoad(
        success = true,
        authoritative = true,
        loadState = currentLoadState,
        decisionCount = decisionCount,
        linkCount = linkCount,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        filePresent = filePresent,
        storedSchemaVersion = storedSchemaVersion
    )
}

private fun markLoadedPartialLocked(
    decisionCount: Int,
    linkCount: Int,
    droppedDecisionCount: Int,
    quarantinedDecisionCount: Int,
    storedSchemaVersion: Int?,
    filePresent: Boolean,
    errorClass: String?,
    errorMessageHash: String?,
    errorTopFrame: String?,
    firstQuarantinedDecisionKeyHash: String?
) {
    currentLoadState = ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative(
        decisionCount = decisionCount,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        schemaVersion = SCHEMA_VERSION,
        storedSchemaVersion = storedSchemaVersion,
        authorityContext = authorityContext(),
        errorClass = errorClass,
        errorMessageHash = errorMessageHash,
        errorTopFrame = errorTopFrame,
        firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash
    )
    traceDecisionStoreLoad(
        success = false,
        authoritative = false,
        loadState = currentLoadState,
        decisionCount = decisionCount,
        linkCount = linkCount,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        filePresent = filePresent,
        storedSchemaVersion = storedSchemaVersion,
        errorClass = errorClass,
        errorMessageHash = errorMessageHash,
        errorTopFrame = errorTopFrame,
        firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash
    )
}

private fun markFailedLocked(
    decisionCount: Int,
    linkCount: Int,
    droppedDecisionCount: Int,
    quarantinedDecisionCount: Int,
    filePresent: Boolean,
    reason: String?,
    storedSchemaVersion: Int?,
    errorClass: String?,
    errorMessageHash: String?,
    errorTopFrame: String?
) {
    currentLoadState = ArtworkDecisionStoreLoadState.FailedNonAuthoritative(
        errorClass = errorClass,
        errorMessageHash = errorMessageHash,
        errorTopFrame = errorTopFrame,
        authorityContext = authorityContext()
    )
    traceDecisionStoreLoad(
        success = false,
        authoritative = false,
        loadState = currentLoadState,
        decisionCount = decisionCount,
        linkCount = linkCount,
        droppedDecisionCount = droppedDecisionCount,
        quarantinedDecisionCount = quarantinedDecisionCount,
        filePresent = filePresent,
        reason = reason,
        storedSchemaVersion = storedSchemaVersion,
        errorClass = errorClass,
        errorMessageHash = errorMessageHash,
        errorTopFrame = errorTopFrame
    )
}
```

Extend `traceDecisionStoreLoad` signature and payload:

```kotlin
private fun traceDecisionStoreLoad(
    success: Boolean,
    authoritative: Boolean,
    loadState: ArtworkDecisionStoreLoadState,
    decisionCount: Int,
    linkCount: Int,
    droppedDecisionCount: Int,
    quarantinedDecisionCount: Int,
    filePresent: Boolean,
    reason: String? = null,
    storedSchemaVersion: Int? = null,
    errorClass: String? = null,
    errorMessageHash: String? = null,
    errorTopFrame: String? = null,
    firstQuarantinedDecisionKeyHash: String? = null
) {
    val fileStats = currentFileStats()
    lastLoadSuccess = success
    lastLoadReason = reason
    lastLoadErrorClass = errorClass
    lastDroppedDecisionCount = droppedDecisionCount
    lastQuarantinedDecisionCount = quarantinedDecisionCount
    lastErrorMessageHash = errorMessageHash
    lastErrorTopFrame = errorTopFrame
    this.firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash
    traceArtwork(
        eventType = "artwork.decision_store_load",
        payload = mapOf(
            "success" to success,
            "authoritative" to authoritative,
            "loadState" to loadStateName(loadState),
            "filePresent" to filePresent,
            "fileReadable" to fileStats.readable,
            "fileBytes" to fileStats.bytes,
            "decisionCount" to decisionCount,
            "linkCount" to linkCount,
            "droppedDecisionCount" to droppedDecisionCount,
            "quarantinedDecisionCount" to quarantinedDecisionCount,
            "reason" to reason,
            "schemaVersion" to SCHEMA_VERSION,
            "storedSchemaVersion" to storedSchemaVersion,
            "errorClass" to errorClass,
            "errorMessageHash" to errorMessageHash,
            "errorTopFrame" to errorTopFrame,
            "firstQuarantinedDecisionKeyHash" to firstQuarantinedDecisionKeyHash
        )
    )
}
```

Update `snapshotDiagnostics()` to fill the new fields from `currentLoadState`.

- [ ] **Step 7: Run tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkDecisionCache.kt \
  app/src/main/java/com/nexio/tv/core/artwork/DurableArtworkDecisionCache.kt \
  app/src/test/java/com/nexio/tv/core/artwork/ArtworkDecisionCacheTest.kt
git commit -m "fix(posters): model decision cache authority"
```

## Task 3: Make Snapshot Sanitization Non-Destructive For Non-Authoritative Lookup

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

- [ ] **Step 1: Replace old missing-decision tests with authority-aware failing tests**

Update the current test named `read clears missing decision refs and tag` to assert authoritative clearing only:

```kotlin
@Test
fun `read clears missing decision refs and tag only when cache miss is authoritative`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = InMemoryArtworkDecisionCache()
    val decisionKey = ArtworkDecisionKey("missing-decision")
    cache.put(sampleArtworkDecision(decisionKey))
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/missing-decision",
        posterProviderTag = "rpdb"
    )

    store.write(snapshot, "RPDB:12345")
    cache.remove(decisionKey)

    val restored = store.read("RPDB:12345")

    assertClearedPosterFields(restored)
}
```

Add tests for non-authoritative preservation:

```kotlin
@Test
fun `read preserves decision refs and provider tags when cache is not authoritative`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = NonAuthoritativeArtworkDecisionCache()
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache,
        traceSink = traceSink
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/unknown-decision",
        posterProviderTag = "rpdb"
    )

    store.write(snapshot, "RPDB:12345")
    val restored = store.read("RPDB:12345")

    assertEquals(snapshot, restored)
    assertTrue(traceSink.events.any { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            ((event.payload as Map<*, *>)["reason"] == "decision_cache_not_authoritative")
    })
}

@Test
fun `read preserves decision ref after lookup failure and requests hydration`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = ThrowingLookupArtworkDecisionCache()
    val traceSink = RecordingTraceSink()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache,
        traceSink = traceSink
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/lookup-fails",
        posterProviderTag = "rpdb"
    )

    store.write(snapshot, "RPDB:12345")
    val restored = store.read("RPDB:12345")

    assertEquals(snapshot, restored)
    assertTrue(traceSink.events.any { event ->
        event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
            ((event.payload as Map<*, *>)["reason"] == "lookup_failed")
    })
}

@Test
fun `read does not reject provider tag mismatch caused by non authoritative decision preservation`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val cache = NonAuthoritativeArtworkDecisionCache()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = cache
    )
    val snapshot = sampleSnapshotWithPoster(
        poster = "nexio-artwork://decision/provider-mismatch-non-authoritative",
        posterProviderTag = "top_posters"
    )

    store.write(snapshot, "RPDB:12345")

    assertEquals(snapshot, store.read("RPDB:12345"))
}
```

Add helper caches at the bottom of `HomeCatalogSnapshotStoreTest.kt`:

```kotlin
private open class NonAuthoritativeArtworkDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null
    override fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext?
    ): ArtworkDecisionLookupResult =
        ArtworkDecisionLookupResult.CacheNotAuthoritative(
            decisionKey = key,
            loadState = loadState(),
            reason = "cache_not_authoritative",
            errorClass = "ClassCastException"
        )

    override fun loadState(): ArtworkDecisionStoreLoadState =
        ArtworkDecisionStoreLoadState.FailedNonAuthoritative(
            errorClass = "ClassCastException",
            errorMessageHash = "errorhash",
            errorTopFrame = "DurableArtworkDecisionCache.load:123",
            authorityContext = null
        )

    override fun put(decision: ArtworkDecision) = Unit
    override fun remove(key: ArtworkDecisionKey) = Unit
    override fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey) = Unit
    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null
    override fun invalidateBySettingsHash(settingsHash: String) = Unit
    override fun invalidateByCredentialHash(credentialHash: String) = Unit
    override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit
    override fun invalidatePremiumArtworkPolicy() = Unit
    override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics =
        ArtworkDecisionCacheSnapshotDiagnostics(
            loaded = true,
            decisionCount = 700,
            linkCount = 0,
            storeFilePresent = true,
            storeFileReadable = true,
            storeFileBytes = 545_965L,
            lastLoadSuccess = false,
            lastLoadReason = null,
            lastLoadErrorClass = "ClassCastException",
            droppedDecisionCount = 0,
            quarantinedDecisionCount = 1,
            loadStateName = "FailedNonAuthoritative",
            authoritative = false,
            errorMessageHash = "errorhash",
            errorTopFrame = "DurableArtworkDecisionCache.load:123"
        )
}

private class ThrowingLookupArtworkDecisionCache : NonAuthoritativeArtworkDecisionCache() {
    override fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext?
    ): ArtworkDecisionLookupResult {
        throw ClassCastException("forced lookup failure")
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: failures because the sanitizer still converts missing/failed lookups to `missing_decision` and clears poster fields.

- [ ] **Step 3: Change lookup proof to typed result**

In `HomeCatalogSnapshotStore.kt`, replace `DecisionLookupProof` with:

```kotlin
private data class DecisionLookupProof(
    val lookupResult: ArtworkDecisionLookupResult,
    val decisionFound: Boolean?,
    val decisionKeyHash: String?,
    val diagnostics: ArtworkDecisionCacheSnapshotDiagnostics?,
    val nonAuthoritativePreserved: Boolean = false
)
```

Add imports:

```kotlin
import com.nexio.tv.core.artwork.ArtworkDecisionLookupResult
import com.nexio.tv.core.artwork.ArtworkDecisionStoreLoadState
```

Replace `lookupDurableDecision()` internals with:

```kotlin
val decisionKey = ArtworkDecisionKey(keyValue)
var lookupErrorClass: String? = null
val lookupResult = runCatching {
    artworkDecisionCache.lookup(decisionKey)
}.onFailure { error ->
    lookupErrorClass = error.javaClass.simpleName
}.getOrElse { error ->
    ArtworkDecisionLookupResult.LookupFailed(
        decisionKey = decisionKey,
        errorClass = error.javaClass.simpleName,
        messageHash = error.message?.takeIf { it.isNotBlank() }?.sha256Short()
    )
}

return DecisionLookupProof(
    lookupResult = lookupResult,
    decisionFound = lookupResult is ArtworkDecisionLookupResult.Found,
    decisionKeyHash = keyValue.sha256Short(),
    diagnostics = cacheDiagnostics(),
    nonAuthoritativePreserved = lookupResult is ArtworkDecisionLookupResult.CacheNotAuthoritative ||
        lookupResult is ArtworkDecisionLookupResult.LookupFailed
).also { proof ->
    traceState.recordDecisionLookup(
        scope = scope,
        posterKind = posterKind,
        posterProviderTag = posterProviderTag,
        proof = proof,
        lookupErrorClass = lookupErrorClass
    )
}
```

For blank decision keys, return `LookupFailed`:

```kotlin
val blankKey = ArtworkDecisionKey("")
return DecisionLookupProof(
    lookupResult = ArtworkDecisionLookupResult.LookupFailed(
        decisionKey = blankKey,
        errorClass = "BlankDecisionKey",
        messageHash = null
    ),
    decisionFound = null,
    decisionKeyHash = null,
    diagnostics = cacheDiagnostics(),
    nonAuthoritativePreserved = true
)
```

- [ ] **Step 4: Change sanitizer clear rules**

Replace `clearReasonForPosterRef(ref, decisionLookup?.decisionFound)` with:

```kotlin
val sanitizeDecision = sanitizeDecisionForPosterRef(posterRef, decisionLookup)
if (posterRef.isBlank() || sanitizeDecision == null) {
    if (decisionLookup?.nonAuthoritativePreserved == true) {
        traceState.recordRehydrateRequested(
            scope = scope,
            reason = when (decisionLookup.lookupResult) {
                is ArtworkDecisionLookupResult.LookupFailed -> "lookup_failed"
                else -> "decision_cache_not_authoritative"
            },
            posterKind = posterKind,
            posterProviderTag = posterProviderTag,
            decisionKeyHash = decisionLookup.decisionKeyHash
        )
    }
    return this
}
traceState.recordSanitized(
    scope = scope,
    reason = sanitizeDecision.reason,
    posterKind = posterKind,
    posterProviderTag = posterProviderTag,
    decisionFound = decisionLookup?.decisionFound,
    action = "clear",
    destructive = true,
    writeBackAllowed = true,
    posterProviderTagAction = "clear"
)
return copy(poster = null, posterProviderTag = null)
```

Add:

```kotlin
private data class ArtworkSanitizeDecision(
    val reason: String
)

private fun sanitizeDecisionForPosterRef(
    ref: String,
    lookup: DecisionLookupProof?
): ArtworkSanitizeDecision? {
    return when {
        isRawPremiumProviderUrl(ref) -> ArtworkSanitizeDecision("raw_premium_url")
        isLegacyIntegrationPosterRef(ref) -> ArtworkSanitizeDecision("legacy_integration_ref")
        isDecisionRef(ref) && lookup?.lookupResult is ArtworkDecisionLookupResult.MissingAuthoritative ->
            ArtworkSanitizeDecision("missing_decision_authoritative")
        else -> null
    }
}
```

Remove or stop using `isMissingDecisionRef()` for destructive cleanup.

- [ ] **Step 5: Let provider tag validation tolerate non-authoritative preserved refs**

Replace `hasValidPosterProviderTags(requiredTag: String?)` with:

```kotlin
private fun Snapshot.hasValidPosterProviderTags(requiredTag: String?): Boolean {
    if (requiredTag == null) return true
    return sequence {
        catalogRows.forEach { row -> yieldAll(row.items) }
        fullCatalogRows.forEach { row -> yieldAll(row.items) }
        yieldAll(heroItems)
    }.all { item ->
        item.posterProviderTag == null ||
            item.posterProviderTag == requiredTag ||
            item.poster?.trim().orEmpty().startsWith(ARTWORK_DECISION_PREFIX)
    }
}
```

This is deliberately narrow: only decision refs are allowed to bypass provider-tag mismatch, because they remain app-owned and will be rehydrated. Raw premium URLs and legacy integration refs are still sanitized.

- [ ] **Step 6: Add rehydration trace in `SnapshotSanitizeTraceState`**

Add fields:

```kotlin
private val rehydrateSamples = mutableListOf<String>()
var rehydrateRequestCount: Int = 0
    private set
```

Add method:

```kotlin
fun recordRehydrateRequested(
    scope: String,
    reason: String,
    posterKind: String,
    posterProviderTag: String?,
    decisionKeyHash: String?
) {
    rehydrateRequestCount += 1
    rememberSample(rehydrateSamples, "$scope:$reason:$posterKind:${posterProviderTag.orEmpty()}:$decisionKeyHash")
    traceSnapshot(
        eventType = "home.snapshot_artwork_rehydrate_requested",
        payload = mapOf(
            "scope" to scope,
            "reason" to reason,
            "posterKind" to posterKind,
            "providerTag" to posterProviderTag,
            "decisionKeyHash" to decisionKeyHash
        )
    )
}
```

Extend `home.snapshot_decision_lookup` payload with:

```kotlin
"rehydrateRequestCount" to rehydrateRequestCount,
"rehydrateSamples" to rehydrateSamples.joinToString("|")
```

- [ ] **Step 7: Update trace-state sanitize payload**

Change `recordSanitized` signature:

```kotlin
fun recordSanitized(
    scope: String,
    reason: String,
    posterKind: String,
    posterProviderTag: String?,
    decisionFound: Boolean?,
    action: String,
    destructive: Boolean,
    writeBackAllowed: Boolean,
    posterProviderTagAction: String
)
```

Record samples:

```kotlin
rememberSample(
    sanitizedSamples,
    "$scope:$reason:$posterKind:${posterProviderTag.orEmpty()}:$decisionFound:$action:$destructive:$writeBackAllowed:$posterProviderTagAction"
)
```

Extend `home.snapshot_sanitize_artwork` payload:

```kotlin
"action" to action,
"reason" to reason,
"destructive" to destructive,
"writeBackAllowed" to writeBackAllowed,
"posterProviderTagAction" to posterProviderTagAction
```

- [ ] **Step 8: Run tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
  app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "fix(home): preserve poster refs on non-authoritative cache"
```

## Task 4: Add Curated Logcat Fields For Authority And Rehydration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

- [ ] **Step 1: Add failing logcat tests**

Add tests near existing trace sink tests:

```kotlin
@Test
fun `decision store load event writes authority diagnostics`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("artwork.decision_store_load", mapOf(
        "success" to false,
        "authoritative" to false,
        "loadState" to "LoadedPartialNonAuthoritative",
        "filePresent" to true,
        "fileReadable" to true,
        "fileBytes" to 545_965L,
        "decisionCount" to 700,
        "linkCount" to 0,
        "droppedDecisionCount" to 1,
        "quarantinedDecisionCount" to 1,
        "reason" to null,
        "schemaVersion" to 1,
        "storedSchemaVersion" to 1,
        "errorClass" to "ClassCastException",
        "errorMessageHash" to "ab12cd34",
        "errorTopFrame" to "DecisionDto.toDomain:142",
        "firstQuarantinedDecisionKeyHash" to "deadbeef"
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
    assertTrue(msg.contains("t=artwork.decision_store_load"))
    assertTrue(msg.contains("authoritative=false"))
    assertTrue(msg.contains("loadState=LoadedPartialNonAuthoritative"))
    assertTrue(msg.contains("quarantinedDecisionCount=1"))
    assertTrue(msg.contains("errorMessageHash=ab12cd34"))
    assertTrue(msg.contains("errorTopFrame=DecisionDto.toDomain:142"))
}

@Test
fun `snapshot rehydrate event writes reason and decision hash`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("home.snapshot_artwork_rehydrate_requested", mapOf(
        "scope" to "catalogRows[0].items[0]",
        "reason" to "decision_cache_not_authoritative",
        "posterKind" to "decision",
        "providerTag" to "rpdb",
        "decisionKeyHash" to "abc123"
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.MetaRoute").first().msg
    assertTrue(msg.contains("t=home.snapshot_artwork_rehydrate_requested"))
    assertTrue(msg.contains("reason=decision_cache_not_authoritative"))
    assertTrue(msg.contains("posterKind=decision"))
    assertTrue(msg.contains("providerTag=rpdb"))
    assertTrue(msg.contains("decisionKeyHash=abc123"))
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest
```

Expected: failures because curated fields are missing.

- [ ] **Step 3: Add curated fields**

In `LogcatRuntimeTraceSink.kt`, extend `artwork.decision_store_load`:

```kotlin
"artwork.decision_store_load" -> linkedMapOf(
    "success" to payload["success"],
    "authoritative" to payload["authoritative"],
    "loadState" to payload["loadState"],
    "filePresent" to payload["filePresent"],
    "fileReadable" to payload["fileReadable"],
    "fileBytes" to payload["fileBytes"],
    "decisionCount" to payload["decisionCount"],
    "linkCount" to payload["linkCount"],
    "droppedDecisionCount" to payload["droppedDecisionCount"],
    "quarantinedDecisionCount" to payload["quarantinedDecisionCount"],
    "reason" to payload["reason"],
    "schemaVersion" to payload["schemaVersion"],
    "storedSchemaVersion" to payload["storedSchemaVersion"],
    "errorClass" to payload["errorClass"],
    "errorMessageHash" to payload["errorMessageHash"],
    "errorTopFrame" to payload["errorTopFrame"],
    "firstQuarantinedDecisionKeyHash" to payload["firstQuarantinedDecisionKeyHash"]
)
```

Extend `home.snapshot_decision_lookup`:

```kotlin
"lookupResult" to payload["lookupResult"],
"authoritative" to payload["authoritative"],
"loadState" to payload["loadState"],
"quarantinedDecisionCount" to payload["quarantinedDecisionCount"],
"errorTopFrame" to payload["errorTopFrame"],
"rehydrateRequestCount" to payload["rehydrateRequestCount"]
```

Extend `home.snapshot_sanitize_artwork`:

```kotlin
"action" to payload["action"],
"reason" to payload["reason"],
"destructive" to payload["destructive"],
"writeBackAllowed" to payload["writeBackAllowed"],
"posterProviderTagAction" to payload["posterProviderTagAction"]
```

Add event:

```kotlin
"home.snapshot_artwork_rehydrate_requested" -> linkedMapOf(
    "scope" to payload["scope"],
    "reason" to payload["reason"],
    "posterKind" to payload["posterKind"],
    "providerTag" to payload["providerTag"],
    "decisionKeyHash" to payload["decisionKeyHash"]
)
```

- [ ] **Step 4: Run tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt \
  app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt
git commit -m "chore(trace): expose artwork authority diagnostics"
```

## Task 5: Add Raw Premium URL Home Snapshot Audit

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- Optional Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogRefreshCoordinator.kt`

- [ ] **Step 1: Add or confirm no-raw-URL snapshot test**

If not already covered by existing tests, add:

```kotlin
@Test
fun `home snapshot never persists raw top posters or rpdb URLs`() {
    val snapshotPrefs = InMemorySharedPreferences()
    val localePrefs = localePrefs("en")
    val metadataStore = mockk<MetadataDiskCacheStore>()
    every { metadataStore.currentLanguageEpoch() } returns 0
    val posterResolver = mockk<PosterRatingsUrlResolver>()
    val store = HomeCatalogSnapshotStore(
        context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
        metadataDiskCacheStore = metadataStore,
        posterRatingsUrlResolver = posterResolver,
        artworkDecisionCache = InMemoryArtworkDecisionCache()
    )
    val rpdb = sampleSnapshotWithPoster(
        poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
        posterProviderTag = "rpdb"
    )
    val topPosters = sampleSnapshotWithPoster(
        poster = "https://api.top-posters.com/key/imdb/poster-default/tt123.jpg",
        posterProviderTag = "top_posters"
    )

    store.write(rpdb, "RPDB:12345")
    assertFalse(persistedSnapshotJson(snapshotPrefs).contains("api.ratingposterdb.com"))

    store.write(topPosters, "TOP_POSTERS:12345")
    assertFalse(persistedSnapshotJson(snapshotPrefs).contains("api.top-posters.com"))
}
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest
```

Expected: PASS if existing sanitizer covers both providers. If it fails, inspect whether `HomeCatalogRefreshCoordinator` writes raw premium URLs into snapshot items.

- [ ] **Step 3: Only if failing and tiny, fix direct raw URL write**

If `HomeCatalogRefreshCoordinator` directly calls `PosterRatingsUrlResolver.apply(...)` for snapshot poster strings, replace that specific path with existing `resolvePosterArtworkString(...)` or `resolvePosterArtworkRef(...)`, matching current nearby patterns. Do not rewrite provider selection or asset promotion.

Required check after fix:

```bash
rg -n "PosterRatingsUrlResolver\\.apply|posterRatingsUrlResolver\\.apply" app/src/main/java/com/nexio/tv/data/local app/src/main/java/com/nexio/tv/ui/screens/home
```

Expected: no home snapshot refresh path writes raw premium provider URLs. If the call is broad or tangled, stop and create a follow-up plan instead of delaying P0.

- [ ] **Step 4: Commit audit result**

If tests only:

```bash
git add app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt
git commit -m "test(home): guard snapshots from raw premium URLs"
```

If tiny fix included:

```bash
git add app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt \
  app/src/main/java/com/nexio/tv/data/local/HomeCatalogRefreshCoordinator.kt
git commit -m "fix(home): keep raw premium URLs out of snapshots"
```

## Task 6: Full Focused Verification And Device Evidence

**Files:**
- No production edits unless tests reveal a bug.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.core.artwork.ArtworkDecisionCacheTest \
  --tests com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest \
  --tests com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest \
  --tests com.nexio.tv.core.trace.LogcatTraceChannelTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Inspect staged/untracked scope**

Run:

```bash
git status --short
```

Expected: only intended committed task changes, plus known unrelated dirty files if they were already present. Do not stage unrelated dirty files.

- [ ] **Step 4: Build and install debug APK for device verification**

Run:

```bash
./gradlew :app:assembleDebug
adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Expected: Gradle build succeeds; `adb install` reports `Success`.

- [ ] **Step 5: Capture restart behavior on `192.168.50.71`**

Run:

```bash
adb -s 192.168.50.71:5555 logcat -c
adb -s 192.168.50.71:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.71:5555 shell monkey -p com.nexio.tv -c android.intent.category.LAUNCHER 1
sleep 45
adb -s 192.168.50.71:5555 logcat -d -v time Nexio.IntRuntime:D Nexio.MetaRoute:D Nexio.FirstPaint:D '*:S' > tmp/premium-poster-authority-50-71.log
```

Expected: log file is created.

- [ ] **Step 6: Prove non-authoritative misses are not destructive**

Run:

```bash
rg "home\\.snapshot_decision_lookup|home\\.snapshot_sanitize_artwork|home\\.snapshot_artwork_rehydrate_requested|artwork\\.decision_store_load|poster_provider_tag_mismatch" tmp/premium-poster-authority-50-71.log
```

Expected evidence when cache load is failed/partial:

```text
artwork.decision_store_load ... authoritative=false ... loadState=LoadedPartialNonAuthoritative|FailedNonAuthoritative
home.snapshot_decision_lookup ... lookupResult=CacheNotAuthoritative|LookupFailed
home.snapshot_artwork_rehydrate_requested ... reason=decision_cache_not_authoritative|lookup_failed
```

Expected absence:

```text
home.snapshot_sanitize_artwork ... reason=missing_decision
home.snapshot_read ... reason=poster_provider_tag_mismatch
```

If an authoritative clean cache reports a truly missing decision, acceptable evidence is:

```text
home.snapshot_sanitize_artwork ... reason=missing_decision_authoritative ... destructive=true ... writeBackAllowed=true
```

- [ ] **Step 7: Commit verification notes if requested**

If the user wants durable handover notes, update `review-dossier/android-premium-posters-restart-rca-handover.md` with:

```markdown
## 2026-05-07 Authority Fix Verification

- Build installed on `192.168.50.71:5555`.
- Non-authoritative decision cache misses produced `home.snapshot_artwork_rehydrate_requested`.
- No destructive `missing_decision` cleanup occurred while cache authority was failed or partial.
- `poster_provider_tag_mismatch` did not follow non-authoritative preservation.
```

Commit only if that doc update is requested:

```bash
git add review-dossier/android-premium-posters-restart-rca-handover.md
git commit -m "docs(posters): record authority fix verification"
```

## Self-Review Checklist

Spec coverage:

- P0-A stop destructive sanitization: Task 3.
- P0-B durable parsing/authority/quarantine: Tasks 1 and 2.
- P0-C rehydration request for unknown refs: Task 3 and Task 4.
- Diagnostics: Task 4 plus Task 2 payloads.
- Raw premium URL trailing audit: Task 5.
- Guardrail `LoadedAuthoritative` requires zero dropped/quarantined records: Task 1 and Task 2.
- Guardrail authority context token: Task 1 and Task 2.
- Guardrail non-persistent rehydration marker: Task 3 uses trace/event only.
- Dirty local worktree without data loss: Preflight and staging commands.

Placeholder scan:

- No unresolved placeholders are intended in this plan.
- If execution finds code shape drift, inspect the current file and adapt the exact snippets while preserving the tested behavior and commit boundaries.

Type consistency:

- `ArtworkDecisionLookupResult`, `ArtworkDecisionStoreLoadState`, `ArtworkDecisionAuthorityContext`, and `isAuthoritativeForMissing` are introduced in Task 1 and used consistently in later tasks.
- `home.snapshot_artwork_rehydrate_requested` is introduced in Task 3 and curated in Task 4.
- `missing_decision_authoritative` replaces destructive `missing_decision` for decision refs.
