# Shared Resolution P0 Bypass Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the P0 shared-resolution bypasses by making provider mutation outbox work account-scoped, making WatchProgress/Continue Watching profile-session scoped, and removing the direct UI TMDB ID lookup in detail enrichment.

**Architecture:** This plan does not redesign `MetadataRouter`. It tightens ownership boundaries around existing systems: mutation execution must carry `Account(profileId, provider, credentialHash)`, WatchProgress persistence must use captured `ActiveProfileSession`, and detail identity lookup must use the existing stable ID bundle produced by metadata resolution instead of UI-side `TmdbService.ensureTmdbId`. Follow-on plans should handle P1/P2 migrations for sidecar metadata, ratings, trailers, skip segments, artwork, and localization.

**Tech Stack:** Kotlin, Android/Hilt, Coroutines/Flow, DataStore/SharedPreferences, MockK, JUnit4, Gradle unit tests.

---

## Scope Boundary

This plan covers only P0 blockers:

- Provider mutation envelope account scope.
- Enqueue/drain/execute credential validation.
- Explicit WatchProgress/CW profile-session APIs.
- Removal of `MetaDetailsViewModel` direct TMDB ID bridge.

Do not fix P1/P2 bypasses in this plan. Do not add more facade calls from ViewModels. Do not add screensaver-specific provider paths.

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`  
  Add account scope fields to persisted envelopes and add an account-scope key helper.

- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt`  
  Include provider + credentialHash in collapse/fairness ordering.

- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`  
  Quarantine legacy or malformed envelopes that do not carry account scope.

- `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationAccountScopeValidator.kt`  
  Create a focused validator for enqueue and execute account scope checks.

- `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt`  
  Validate envelopes before enqueue and before adapter execution.

- `app/src/main/java/com/nexio/tv/core/di/TraktMutationOutboxModule.kt`  
  Inject the account scope validator into the coordinator.

- `app/src/main/java/com/nexio/tv/data/repository/TrackingAccountScopeProvider.kt`  
  Create one service that resolves current `TrackingAuthSession` with credential hash for Trakt or Simkl.

- `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`  
  Ensure mutation-scope credential hashes identify the provider account binding, not the current access token.

- `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`  
  Ensure mutation-scope credential hashes identify the provider account binding, not the current access token.

- `app/src/main/java/com/nexio/tv/data/repository/TraktRepositoryAuthGateway.kt`  
  Expose `mutationAccountScopedSession`.

- `app/src/main/java/com/nexio/tv/data/repository/*MutationAdapter.kt`  
  Update envelope builders to require a scoped `TrackingAuthSession`.

- `app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt`  
  Replace implicit active-profile API with explicit profile/session API.

- `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt`  
  Remove active-profile defaults from store selection and require profile/session on writes.

- `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt`  
  Thread `ActiveProfileSession` through WatchProgress persistence and outbox enqueue.

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`  
  Remove `tmdbService.ensureTmdbId` from detail enrichment and use stable ID output from metadata resolution.

Create tests:

- `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationEnvelopeAccountScopeTest.kt`
- `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCredentialValidationTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesExplicitScopeTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/WatchProgressProfileScopeArchitectureTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt`

---

### Task 1: Add Architecture Tests For P0 Guards

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/WatchProgressProfileScopeArchitectureTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt`
- Test: same files

- [ ] **Step 1: Write failing WatchProgress architecture test**

Create `app/src/test/java/com/nexio/tv/architecture/WatchProgressProfileScopeArchitectureTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressProfileScopeArchitectureTest {
    @Test
    fun `WatchProgressPreferences must not default to active profile for store selection`() {
        val source = File("app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt").readText()

        assertFalse(
            "WatchProgressPreferences.store must not default profileId to profileManager.activeProfileId.value",
            source.contains("private fun store(profileId: Int = profileManager.activeProfileId.value)")
        )
        assertFalse(
            "WatchProgressPreferences write paths must not call store() without explicit profile/session",
            Regex("""store\(\)\.edit""").containsMatchIn(source)
        )
    }

    @Test
    fun `WatchProgressRepository API must expose explicit profile and session scope`() {
        val source = File("app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt").readText()

        assertTrue(source.contains("observeContinueWatching(profileId: Int)"))
        assertTrue(source.contains("observeProgress(profileId: Int)"))
        assertTrue(source.contains("upsertProgress(profileSession: ActiveProfileSession"))
        assertTrue(source.contains("removeProgress(profileSession: ActiveProfileSession"))

        assertFalse(source.contains("val continueWatching: Flow<List<WatchProgress>>"))
        assertFalse(source.contains("suspend fun saveProgress(progress: WatchProgress"))
    }
}
```

- [ ] **Step 2: Write failing direct UI identity architecture test**

Create `app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDetailUiTmdbEnsureIdArchitectureTest {
    @Test
    fun `detail metadata enrichment must not call TmdbService ensureTmdbId`() {
        val file = File("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt")
        val source = file.readText()
        val offenders = Regex("""tmdbService\.ensureTmdbId|\.ensureTmdbId\(""")
            .findAll(source)
            .map { "${file.path}:${source.substring(0, it.range.first).count { ch -> ch == '\n' } + 1}" }
            .toList()

        assertTrue(
            "Detail metadata enrichment must use CanonicalIdentityResolver/StableIdBundleResolver output, not TmdbService.ensureTmdbId: $offenders",
            offenders.isEmpty()
        )
    }
}
```

Do not broaden this test to all `ui/` in the P0 packet. The global `NoUiTmdbEnsureIdArchitectureTest` belongs to Packet C, after the remaining P1 home trailer identity paths are migrated.

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest" --tests "com.nexio.tv.architecture.NoDetailUiTmdbEnsureIdArchitectureTest"
```

Expected: FAIL. The failure should cite `WatchProgressPreferences.store(profileId = profileManager.activeProfileId.value)`, old repository API members, and `MetaDetailsViewModel` calling `ensureTmdbId`.

- [ ] **Step 4: Commit tests**

```bash
git add app/src/test/java/com/nexio/tv/architecture/WatchProgressProfileScopeArchitectureTest.kt app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt
git commit -m "test: pin P0 shared resolution bypass guards"
```

---

### Task 2: Add Account Scope To Mutation Envelopes

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationEnvelopeAccountScopeTest.kt`

- [ ] **Step 1: Write failing envelope account-scope tests**

Create `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationEnvelopeAccountScopeTest.kt`:

```kotlin
package com.nexio.tv.data.trakt.outbox

import com.google.gson.JsonObject
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class ProviderMutationEnvelopeAccountScopeTest {
    @Test
    fun `envelope requires provider and credential hash`() {
        assertFailsWith<IllegalArgumentException> {
            scopedEnvelope(credentialHash = "")
        }
    }

    @Test
    fun `account scope key includes profile provider and credential`() {
        val envelope = scopedEnvelope(
            profileId = 2,
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-credential-hash"
        )

        assertEquals(
            "profile:2:provider:SIMKL:credential:simkl-credential-hash",
            envelope.accountScopeKey
        )
    }

    @Test
    fun `collapse does not cross credential hashes`() {
        val policy = TraktMutationOutboxPolicy()
        val first = scopedEnvelope(id = "first", credentialHash = "credential-a", collapseKey = "same-title")
        val second = scopedEnvelope(id = "second", credentialHash = "credential-b", collapseKey = "same-title")

        val afterFirst = policy.enqueue(TraktMutationOutboxSnapshot(), first, nowMs = 1_000L)
        val afterSecond = policy.enqueue(afterFirst, second, nowMs = 1_001L)

        assertEquals(2, afterSecond.items.count { it.state == TraktMutationLifecycleState.QUEUED })
    }

    private fun scopedEnvelope(
        id: String = "id",
        profileId: Int = 1,
        provider: TrackingProvider = TrackingProvider.TRAKT,
        credentialHash: String = "credential-hash",
        collapseKey: String? = "collapse"
    ): TraktMutationEnvelope =
        TraktMutationEnvelope(
            id = id,
            profileId = profileId,
            provider = provider,
            credentialHash = credentialHash,
            adapterKey = "progress",
            mutationKind = "history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = collapseKey,
            payload = JsonObject()
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationEnvelopeAccountScopeTest"
```

Expected: FAIL because `TraktMutationEnvelope` has no `provider`, `credentialHash`, or `accountScopeKey`.

- [ ] **Step 3: Add account fields to the envelope**

Modify `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`:

```kotlin
import com.nexio.tv.domain.model.TrackingProvider
```

Update `TraktMutationEnvelope`:

```kotlin
data class TraktMutationEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val profileId: Int = 1,
    val provider: TrackingProvider,
    val credentialHash: String,
    val adapterKey: String,
    val mutationKind: String,
    val priority: TraktMutationPriorityBucket,
    val collapseKey: String? = null,
    val payload: JsonObject = JsonObject(),
    val rollbackPayload: JsonObject? = null,
    val metadata: JsonObject = JsonObject(),
    val state: TraktMutationLifecycleState = TraktMutationLifecycleState.QUEUED,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val nextAttemptAtMs: Long = 0L,
    val attemptCount: Int = 0,
    val leaseToken: String? = null,
    val leaseExpiresAtMs: Long? = null,
    val supersededById: String? = null,
    val lastError: String? = null,
    val lastHttpStatusCode: Int? = null,
    val completedAtMs: Long? = null
) {
    init {
        require(profileId >= 1) { "TraktMutationEnvelope.profileId must be positive" }
        require(credentialHash.isNotBlank()) { "TraktMutationEnvelope.credentialHash must not be blank" }
    }

    val accountScopeKey: String
        get() = "profile:$profileId:provider:${provider.name}:credential:${credentialHash.trim()}"

    fun isReadyLike(): Boolean =
        state == TraktMutationLifecycleState.QUEUED ||
            state == TraktMutationLifecycleState.WAITING_RETRY

    fun isQueueCollapsible(): Boolean =
        state == TraktMutationLifecycleState.QUEUED ||
            state == TraktMutationLifecycleState.WAITING_RETRY

    fun deepCopy(): TraktMutationEnvelope {
        return copy(
            payload = payload.deepCopy(),
            rollbackPayload = rollbackPayload?.deepCopy(),
            metadata = metadata.deepCopy()
        )
    }
}
```

- [ ] **Step 4: Include account scope in collapse and ordering**

Modify `collapsePending` in `TraktMutationOutboxPolicy.kt`:

```kotlin
if (
    existing.isQueueCollapsible() &&
    existing.adapterKey == incoming.adapterKey &&
    existing.mutationKind == incoming.mutationKind &&
    existing.collapseKey == collapseKey &&
    existing.priority == incoming.priority &&
    existing.profileId == incoming.profileId &&
    existing.provider == incoming.provider &&
    existing.credentialHash == incoming.credentialHash
) {
    existing.copy(
        state = TraktMutationLifecycleState.COLLAPSED,
        updatedAtMs = nowMs,
        supersededById = incoming.id,
        leaseToken = null,
        leaseExpiresAtMs = null,
        completedAtMs = nowMs
    )
} else {
    existing
}
```

Modify `queueComparator`:

```kotlin
private val queueComparator = compareBy<TraktMutationEnvelope>(
    { it.profileId },
    { it.provider.name },
    { it.credentialHash },
    { it.priority.sortOrder },
    { it.nextAttemptAtMs },
    { it.createdAtMs },
    { it.updatedAtMs }
)
```

- [ ] **Step 5: Update existing test helper constructors**

Any test helper that constructs `TraktMutationEnvelope` must include:

```kotlin
provider = TrackingProvider.TRAKT,
credentialHash = "test-credential-hash",
```

For SIMKL-specific test data, use:

```kotlin
provider = TrackingProvider.SIMKL,
credentialHash = "simkl-test-credential-hash",
```

- [ ] **Step 6: Run focused outbox policy tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationEnvelopeAccountScopeTest" --tests "com.nexio.tv.data.trakt.outbox.TraktMutationOutboxPolicyTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt app/src/test/java/com/nexio/tv/data/trakt/outbox
git commit -m "feat: account-scope provider mutation envelopes"
```

---

### Task 3: Quarantine Legacy Or Malformed Persisted Envelopes

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt`

- [ ] **Step 1: Write failing store test for legacy envelopes**

Add this test to `TraktMutationOutboxStoreTest.kt`:

```kotlin
@Test
fun `legacy envelopes without account scope are quarantined instead of executed`() = runTest {
    val prefs = InMemorySharedPreferences()
    val context = mockContext(prefs)
    val store = TraktMutationOutboxStore(context = context)
    val legacyEnvelope = JsonObject().apply {
        addProperty("id", "legacy")
        addProperty("profileId", 1)
        addProperty("adapterKey", "progress-history")
        addProperty("mutationKind", "progress.history.add")
        addProperty("priority", "WATCHED")
        add("payload", JsonObject())
        add("metadata", JsonObject())
        addProperty("state", "QUEUED")
    }
    val root = JsonObject().apply {
        addProperty("schemaVersion", 1)
        add("snapshot", JsonObject().apply {
            add("items", com.google.gson.JsonArray().apply { add(legacyEnvelope) })
            addProperty("nextWritableAtMs", 0L)
            addProperty("updatedAtMs", 0L)
        })
    }
    prefs.edit().putString("snapshot", root.toString()).commit()

    val snapshot = store.read(profileId = 1)

    assertEquals(1, snapshot.items.size)
    assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, snapshot.items.single().state)
    assertEquals("MISSING_ACCOUNT_SCOPE", snapshot.items.single().lastError)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest.legacy envelopes without account scope are quarantined instead of executed"
```

Expected: FAIL because deserialization currently drops or accepts legacy envelopes without explicit quarantine.

- [ ] **Step 3: Add lifecycle value for quarantined legacy data by using terminal failed**

No new enum is required. Use `TERMINAL_FAILED` with `lastError = "MISSING_ACCOUNT_SCOPE"` for persisted envelopes that cannot prove account scope.

Modify `deserializeEnvelope` in `TraktMutationOutboxStore.kt`:

```kotlin
private fun deserializeEnvelope(element: JsonElement?): TraktMutationEnvelope? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        val obj = element.asJsonObject
        val provider = obj.stringOrNull("provider")
        val credentialHash = obj.stringOrNull("credentialHash")
        if (provider.isNullOrBlank() || credentialHash.isNullOrBlank()) {
            return@runCatching quarantinedLegacyEnvelope(obj)
        }
        gson.fromJson(obj, TraktMutationEnvelope::class.java)
            .copy(profileId = obj.intOrNull("profileId") ?: 1)
            .sanitizedOrNull()
    }.getOrNull()
}

private fun quarantinedLegacyEnvelope(obj: JsonObject): TraktMutationEnvelope? {
    val adapterKey = obj.stringOrNull("adapterKey")?.takeIf { it.isNotBlank() } ?: return null
    val mutationKind = obj.stringOrNull("mutationKind")?.takeIf { it.isNotBlank() } ?: return null
    val priority = runCatching {
        TraktMutationPriorityBucket.valueOf(obj.stringOrNull("priority") ?: "")
    }.getOrNull() ?: return null
    return TraktMutationEnvelope(
        id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null,
        profileId = (obj.intOrNull("profileId") ?: 1).coerceAtLeast(1),
        provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
        credentialHash = "legacy-missing-account-scope",
        adapterKey = adapterKey,
        mutationKind = mutationKind,
        priority = priority,
        payload = obj.objectOrNull("payload") ?: JsonObject(),
        metadata = obj.objectOrNull("metadata") ?: JsonObject(),
        state = TraktMutationLifecycleState.TERMINAL_FAILED,
        lastError = "MISSING_ACCOUNT_SCOPE",
        completedAtMs = System.currentTimeMillis()
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    runCatching {
        get(key)?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()
```

- [ ] **Step 4: Persist provider and credential hash explicitly**

In `TraktMutationOutboxStore.toJson`, keep the current Gson serialization and make the account fields explicit:

```kotlin
val obj = gson.toJsonTree(envelope).asJsonObject
obj.addProperty("profileId", envelope.profileId)
obj.addProperty("provider", envelope.provider.name)
obj.addProperty("credentialHash", envelope.credentialHash)
add(obj)
```

- [ ] **Step 5: Run store tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStoreTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt app/src/test/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStoreTest.kt
git commit -m "fix: quarantine unscoped persisted provider mutations"
```

---

### Task 4: Validate Account Scope Before Enqueue And Execute

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TrackingAccountScopeProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationAccountScopeValidator.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/TraktMutationOutboxModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCredentialValidationTest.kt`

**Invariant:** Mutation envelope `credentialHash` must identify the provider account binding, not the current access token value. Access-token refresh must not invalidate queued mutations for the same account. Re-auth to a different account must reject/quarantine old queued mutations.

- [ ] **Step 1: Write failing credential validation tests**

Create `app/src/test/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCredentialValidationTest.kt`:

```kotlin
package com.nexio.tv.data.trakt.outbox

import com.google.gson.JsonObject
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMutationOutboxCredentialValidationTest {
    @Test
    fun `enqueue rejects stale credential hash`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "current"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val stale = envelope(credentialHash = "stale")

        val result = runCatching { coordinator.enqueueAndDrain(stale) }

        assertTrue(result.exceptionOrNull() is ProviderMutationAccountScopeException)
        assertTrue(adapter.executed.isEmpty())
    }

    @Test
    fun `drain rejects credential hash changed after persistence`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "old"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "old"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "new"))

        val settled = coordinator.snapshot(profileId = 1).items.first { it.id == queued.id }

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(null, settled.lastHttpStatusCode)
    }

    @Test
    fun `account scope mismatch does not record provider 401 backoff`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "old"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "old"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "new"))

        val settled = coordinator.snapshot(profileId = 1).items.first { it.id == queued.id }

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(null, settled.lastHttpStatusCode)
    }

    @Test
    fun `token refresh does not invalidate outbox for same account`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "account-hash"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)

        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "account-hash"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "account-hash"))

        val settled = coordinator.snapshot(profileId = 1).items.first { it.id == queued.id }

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertEquals(listOf(queued.id), adapter.executed)
    }

    @Test
    fun `reauth different account rejects old outbox mutations`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.SIMKL to TrackingAuthSession(TrackingProvider.SIMKL, 1, "account-a"))
        )
        val adapter = RecordingAdapter("simkl.progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)

        val queued = coordinator.enqueueAndDrain(
            envelope(provider = TrackingProvider.SIMKL, credentialHash = "account-a", adapterKey = "simkl.progress")
        )
        validator.sessions = mapOf(TrackingProvider.SIMKL to TrackingAuthSession(TrackingProvider.SIMKL, 1, "account-b"))

        val settled = coordinator.snapshot(profileId = 1).items.first { it.id == queued.id }

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(emptyList<String>(), adapter.executed)
    }

    private fun envelope(
        provider: TrackingProvider = TrackingProvider.TRAKT,
        credentialHash: String,
        adapterKey: String = "progress"
    ): TraktMutationEnvelope =
        TraktMutationEnvelope(
            profileId = 1,
            provider = provider,
            credentialHash = credentialHash,
            adapterKey = adapterKey,
            mutationKind = "progress.history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            payload = JsonObject()
        )
}
```

The test references `RecordingAdapter`, `testCoordinator`, and `FakeValidator`. Add these helpers in the same file:

```kotlin
private class FakeValidator(
    var sessions: Map<TrackingProvider, TrackingAuthSession>
) : ProviderMutationAccountScopeValidator {
    override suspend fun validateForEnqueue(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    override suspend fun validateForExecute(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    private fun validate(envelope: TraktMutationEnvelope) {
        val session = sessions[envelope.provider]
        if (session?.profileId != envelope.profileId || session.credentialHash != envelope.credentialHash) {
            throw ProviderMutationAccountScopeException("ACCOUNT_SCOPE_MISMATCH")
        }
    }
}

private class RecordingAdapter(
    override val adapterKey: String,
    private val result: TraktMutationExecutionResult
) : TraktMutationAdapter {
    val executed = mutableListOf<String>()

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        executed += envelope.id
        return result
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) = Unit

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) = Unit
}

private fun testCoordinator(
    adapter: TraktMutationAdapter,
    validator: ProviderMutationAccountScopeValidator
): ProviderMutationOutboxCoordinator {
    val store = TraktMutationOutboxStore(context = mockk(relaxed = true))
    val worker = TraktMutationOutboxWorker(store = store)
    return ProviderMutationOutboxCoordinator(worker, setOf(adapter), validator)
}
```

If `mockk` is used in the helper, add:

```kotlin
import io.mockk.mockk
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCredentialValidationTest"
```

Expected: FAIL because the validator and exception do not exist and the coordinator does not validate.

- [ ] **Step 3: Add local terminal result for account-scope mismatch**

Modify `TraktMutationExecutionResult` in `TraktMutationOutboxModels.kt`:

```kotlin
sealed interface TraktMutationExecutionResult {
    data class Success(
        val httpStatusCode: Int? = null
    ) : TraktMutationExecutionResult

    data class AccountScopeMismatch(
        val reason: String = "ACCOUNT_SCOPE_MISMATCH",
        val throwable: Throwable? = null
    ) : TraktMutationExecutionResult

    data class Failure(
        val httpStatusCode: Int? = null,
        val retryAfterHeader: String? = null,
        val reason: String? = null,
        val throwable: Throwable? = null
    ) : TraktMutationExecutionResult
}
```

Do not represent account-scope mismatch as provider HTTP `401`. It is a local terminal/quarantine condition and must settle with `lastHttpStatusCode = null`.

- [ ] **Step 4: Make auth services expose mutation-stable account scope**

In `TraktAuthService`, add:

```kotlin
suspend fun mutationAccountScopedSession(session: TrackingAuthSession = currentAuthSession()): TrackingAuthSession {
    val state = getAuthState(session)
    val accountMaterial = state.userSlug
        ?.takeIf { it.isNotBlank() }
        ?: state.username?.takeIf { it.isNotBlank() }
        ?: fetchUserSettings(session)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Trakt mutation scope requires provider account identity")
    return session.copy(
        credentialHash = integrationCredentialHash(IntegrationProvider.TRAKT, "account:$accountMaterial"),
        accountIdHash = integrationCredentialHash(IntegrationProvider.TRAKT, accountMaterial)
    )
}
```

In `SimklAuthService`, add:

```kotlin
suspend fun mutationAccountScopedSession(session: TrackingAuthSession = currentAuthSession()): TrackingAuthSession {
    val state = getCurrentAuthState(session)
    val accountMaterial = state.accountId?.toString()
        ?: state.username?.takeIf { it.isNotBlank() }
        ?: fetchUserSettings(session)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("SIMKL mutation scope requires provider account identity")
    return session.copy(
        credentialHash = integrationCredentialHash(IntegrationProvider.SIMKL, "account:$accountMaterial"),
        accountIdHash = integrationCredentialHash(IntegrationProvider.SIMKL, accountMaterial)
    )
}
```

- [ ] **Step 5: Create tracking account scope provider**

Create `app/src/main/java/com/nexio/tv/data/repository/TrackingAccountScopeProvider.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingAccountScopeProvider @Inject constructor(
    private val traktAuthService: TraktAuthService,
    private val simklAuthService: SimklAuthService
) {
    suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession {
        val base = TrackingAuthSession(provider = provider, profileId = profileId)
        return when (provider) {
            TrackingProvider.TRAKT -> traktAuthService.mutationAccountScopedSession(base)
            TrackingProvider.SIMKL -> simklAuthService.mutationAccountScopedSession(base)
        }
    }
}
```

- [ ] **Step 6: Create validator**

Create `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationAccountScopeValidator.kt`:

```kotlin
package com.nexio.tv.data.trakt.outbox

import com.nexio.tv.data.repository.TrackingAccountScopeProvider
import javax.inject.Inject
import javax.inject.Singleton

class ProviderMutationAccountScopeException(
    message: String
) : IllegalStateException(message)

interface ProviderMutationAccountScopeValidator {
    suspend fun validateForEnqueue(envelope: TraktMutationEnvelope)
    suspend fun validateForExecute(envelope: TraktMutationEnvelope)
}

@Singleton
class DefaultProviderMutationAccountScopeValidator @Inject constructor(
    private val accountScopeProvider: TrackingAccountScopeProvider
) : ProviderMutationAccountScopeValidator {
    override suspend fun validateForEnqueue(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    override suspend fun validateForExecute(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    private suspend fun validate(envelope: TraktMutationEnvelope) {
        val current = accountScopeProvider.accountScopedSession(
            provider = envelope.provider,
            profileId = envelope.profileId
        )
        if (current.credentialHash.isNullOrBlank() || current.credentialHash != envelope.credentialHash) {
            throw ProviderMutationAccountScopeException("ACCOUNT_SCOPE_MISMATCH")
        }
    }
}
```

- [ ] **Step 7: Validate coordinator enqueue and execute**

Modify `ProviderMutationOutboxCoordinator` constructor:

```kotlin
class ProviderMutationOutboxCoordinator @Inject constructor(
    private val worker: TraktMutationOutboxWorker,
    adapters: Set<@JvmSuppressWildcards TraktMutationAdapter>,
    private val accountScopeValidator: ProviderMutationAccountScopeValidator
) {
```

Modify `enqueueAndDrain`:

```kotlin
suspend fun enqueueAndDrain(envelope: TraktMutationEnvelope): TraktMutationEnvelope {
    accountScopeValidator.validateForEnqueue(envelope)
    val adapter = adapterFor(envelope.adapterKey)
    adapter.applyOptimistic(envelope)
    val queued = worker.enqueue(envelope)
    ensureDraining(queued.profileId)
    return queued
}
```

Modify `drainLoop` before adapter execution:

```kotlin
val execution = runCatching {
    accountScopeValidator.validateForExecute(lease.envelope)
    adapter.execute(lease.envelope)
}.getOrElse { error ->
    if (error is ProviderMutationAccountScopeException) {
        TraktMutationExecutionResult.AccountScopeMismatch(
            reason = "ACCOUNT_SCOPE_MISMATCH",
            throwable = error
        )
    } else {
        TraktMutationExecutionResult.Failure(
            reason = error.message ?: error::class.java.simpleName,
            throwable = error
        )
    }
}
```

When classifying the execution result, account-scope mismatch must settle terminally without HTTP status:

```kotlin
val settlement = when (execution) {
    is TraktMutationExecutionResult.Success -> TraktMutationSettlement.Succeeded(
        httpStatusCode = execution.httpStatusCode
    )
    is TraktMutationExecutionResult.AccountScopeMismatch -> TraktMutationSettlement.TerminalFailure(
        reason = execution.reason,
        httpStatusCode = null
    )
    is TraktMutationExecutionResult.Failure -> worker.classifyFailure(
        failure = execution,
        attemptCount = lease.envelope.attemptCount
    )
}
```

When settling a terminal failure, the existing rollback path should run. Preserve rollback behavior, but do not trip provider HTTP 401/backoff/circuit behavior for `ACCOUNT_SCOPE_MISMATCH`.

- [ ] **Step 8: Update DI provider**

Modify `TraktMutationOutboxModule.provideProviderMutationOutboxCoordinator`:

```kotlin
fun provideProviderMutationOutboxCoordinator(
    worker: TraktMutationOutboxWorker,
    adapters: Set<@JvmSuppressWildcards TraktMutationAdapter>,
    accountScopeValidator: ProviderMutationAccountScopeValidator
): ProviderMutationOutboxCoordinator {
    return ProviderMutationOutboxCoordinator(
        worker = worker,
        adapters = adapters,
        accountScopeValidator = accountScopeValidator
    )
}
```

- [ ] **Step 9: Run credential validation tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCredentialValidationTest" --tests "com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinatorTest"
```

Expected: PASS after updating coordinator test constructors with a fake validator that accepts test envelopes.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TrackingAccountScopeProvider.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationAccountScopeValidator.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt app/src/main/java/com/nexio/tv/core/di/TraktMutationOutboxModule.kt app/src/test/java/com/nexio/tv/data/trakt/outbox
git commit -m "fix: validate provider mutation account scope"
```

---

### Task 5: Require Scoped Sessions In Mutation Envelope Builders

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktSeasonMarkMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktDiscoveryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklProgressHistoryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklScrobbleMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklLibraryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/simkl/SimklSeasonMarkMutationAdapter.kt`
- Test: existing outbox and repository tests

- [ ] **Step 1: Add shared helper pattern to each adapter companion**

For Trakt adapters, replace `profileId: Int = 1` builder parameters with:

```kotlin
session: TrackingAuthSession
```

Then construct envelopes with:

```kotlin
profileId = session.profileId,
provider = TrackingProvider.TRAKT,
credentialHash = requireNotNull(session.credentialHash) {
    "Trakt mutation envelopes require account-scoped credentialHash"
},
```

For Simkl adapters, use:

```kotlin
profileId = session.profileId,
provider = TrackingProvider.SIMKL,
credentialHash = requireNotNull(session.credentialHash) {
    "SIMKL mutation envelopes require account-scoped credentialHash"
},
```

- [ ] **Step 2: Update one builder first and compile**

Start with `TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope`:

```kotlin
fun buildHistoryAddEnvelope(
    progress: WatchProgress,
    title: String?,
    year: Int?,
    session: TrackingAuthSession
): TraktMutationEnvelope {
    val payload = JsonObject().apply {
        add(PAYLOAD_PROGRESS, gson.toJsonTree(progress))
    }
    val metadata = JsonObject().apply {
        title?.let { addProperty(METADATA_TITLE, it) }
        year?.let { addProperty(METADATA_YEAR, it) }
    }
    val collapseKey = buildString {
        append(progress.contentId.trim())
        progress.season?.let { append(":s$it") }
        progress.episode?.let { append(":e$it") }
    }.ifBlank { null }

    return TraktMutationEnvelope(
        profileId = session.profileId,
        provider = TrackingProvider.TRAKT,
        credentialHash = requireNotNull(session.credentialHash) {
            "Trakt mutation envelopes require account-scoped credentialHash"
        },
        adapterKey = ADAPTER_KEY,
        mutationKind = MUTATION_KIND_HISTORY_ADD,
        priority = TraktMutationPriorityBucket.WATCHED,
        collapseKey = collapseKey,
        payload = payload,
        metadata = metadata
    )
}
```

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: FAIL at call sites still passing `profileId`.

- [ ] **Step 3: Update all remaining builders using the same session contract**

Every `TraktMutationEnvelope(` constructor in production code must now include provider and credential hash. Use this scan:

```bash
rg -n "TraktMutationEnvelope\\(" app/src/main/java
```

Expected after edits: every hit has `provider =` and `credentialHash =`.

- [ ] **Step 4: Run compile**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: FAIL only at services that have not yet been changed to request account-scoped sessions. Those are handled in Task 6.

- [ ] **Step 5: Commit adapter builder API changes after compile reaches call-site failures**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/trakt app/src/main/java/com/nexio/tv/data/repository/simkl
git commit -m "refactor: require account-scoped sessions for mutation builders"
```

---

### Task 6: Thread Account-Scoped Sessions Into Mutation Enqueue Call Sites

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/SeasonMarkBatcher.kt`
- Test: existing repository/scrobble/library tests

- [ ] **Step 1: Expose mutation-stable Trakt gateway session**

Modify `TraktRepositoryAuthGateway.kt`:

```kotlin
suspend fun mutationAccountScopedSession(session: TrackingAuthSession = authService.currentAuthSession()): TrackingAuthSession =
    authService.mutationAccountScopedSession(session)
```

- [ ] **Step 2: Inject `TrackingAccountScopeProvider` where both providers are possible**

In `WatchProgressRepositoryImpl`, add constructor dependency:

```kotlin
private val accountScopeProvider: TrackingAccountScopeProvider,
```

Add helper:

```kotlin
private suspend fun accountSessionFor(
    provider: com.nexio.tv.domain.model.TrackingProvider,
    profileId: Int
): TrackingAuthSession =
    accountScopeProvider.accountScopedSession(provider = provider, profileId = profileId)
```

- [ ] **Step 3: Replace profileId-only Trakt history add call**

In `WatchProgressRepositoryImpl.markAsCompleted`, replace the envelope creation block with:

```kotlin
val envelope = when (providerState.effectiveProvider) {
    com.nexio.tv.domain.model.TrackingProvider.SIMKL -> {
        val session = accountSessionFor(providerState.effectiveProvider, profileId)
        SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = completed,
            title = completed.name.takeIf { it.isNotBlank() },
            year = null,
            session = session
        )
    }
    com.nexio.tv.domain.model.TrackingProvider.TRAKT -> {
        val session = accountSessionFor(providerState.effectiveProvider, profileId)
        TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = completed,
            title = completed.name.takeIf { it.isNotBlank() },
            year = null,
            session = session
        )
    }
}
```

- [ ] **Step 4: Replace all remaining WatchProgress outbox builders**

Apply the same pattern in:

- `removeProgress`
- `removeFromHistory`
- `clearShowProgress`
- `markAsCompletedBatch`

Use `accountSessionFor(providerState.effectiveProvider, profileId)` before building each envelope.

- [ ] **Step 5: Update scrobble services**

In `TraktScrobbleService.authSession`, return account scoped session:

```kotlin
private suspend fun authSession(ownerProfileId: Int?): TrackingAuthSession {
    val base = ownerProfileId?.let {
        TrackingAuthSession(com.nexio.tv.domain.model.TrackingProvider.TRAKT, it)
    } ?: traktAuthService.currentAuthSession()
    return traktAuthService.mutationAccountScopedSession(base)
}
```

Then pass `session = authSession(request.profileId)` to `TraktScrobbleMutationAdapter.buildCheckinEnvelope` and `buildScrobbleEnvelope`.

For `SimklScrobbleService`, inject `TrackingAccountScopeProvider` and use:

```kotlin
private suspend fun accountSession(profileId: Int): TrackingAuthSession =
    accountScopeProvider.accountScopedSession(
        provider = com.nexio.tv.domain.model.TrackingProvider.SIMKL,
        profileId = profileId
    )
```

Then pass `session = accountSession(request.profileId)` to SIMKL scrobble builders.

- [ ] **Step 6: Update library/discovery/season-mark services**

For Trakt-only services, obtain:

```kotlin
val session = traktAuthService.mutationAccountScopedSession(
    TrackingAuthSession(com.nexio.tv.domain.model.TrackingProvider.TRAKT, profileId)
)
```

For SIMKL-only services, obtain:

```kotlin
val session = accountScopeProvider.accountScopedSession(
    provider = com.nexio.tv.domain.model.TrackingProvider.SIMKL,
    profileId = profileId
)
```

Pass `session = session` into builder calls.

- [ ] **Step 7: Run compile and focused tests**

Run:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktScrobbleServiceTest" --tests "com.nexio.tv.data.repository.SimklScrobbleServiceTest" --tests "com.nexio.tv.data.repository.WatchProgressRepositoryProviderRoutingTest"
```

Expected: PASS. If tests build fake envelopes directly, update test data with provider + credentialHash.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository app/src/test/java/com/nexio/tv/data/repository
git commit -m "fix: enqueue provider mutations with account-scoped sessions"
```

---

### Task 7: Make WatchProgressPreferences Explicitly Profile-Scoped

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesExplicitScopeTest.kt`

- [ ] **Step 1: Write failing explicit scope tests**

Create `app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesExplicitScopeTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressPreferencesExplicitScopeTest {
    @Test
    fun `progress written for profile one is not visible in profile two`() = runTest {
        val preferences = testPreferences()
        val profileOne = ActiveProfileSession(profileId = 1, sessionId = "p1", sessionOrdinal = 1L, startedAtMs = 1L)
        val profileTwo = ActiveProfileSession(profileId = 2, sessionId = "p2", sessionOrdinal = 1L, startedAtMs = 1L)

        preferences.saveProgress(profileOne, progress("tt1"))

        assertEquals(listOf("tt1"), preferences.allProgress(profileOne.profileId).first().map { it.contentId })
        assertEquals(emptyList<String>(), preferences.allProgress(profileTwo.profileId).first().map { it.contentId })
    }

    @Test
    fun `writes reject stale profile session`() = runTest {
        val preferences = testPreferences(
            activeSession = ActiveProfileSession(profileId = 2, sessionId = "p2", sessionOrdinal = 2L, startedAtMs = 2L)
        )
        val stale = ActiveProfileSession(profileId = 1, sessionId = "p1", sessionOrdinal = 1L, startedAtMs = 1L)

        val result = runCatching { preferences.saveProgress(stale, progress("tt1")) }

        assertEquals("STALE_PROFILE_STATE_WRITE", result.exceptionOrNull()?.message)
    }

    private fun progress(id: String) = WatchProgress(
        contentId = id,
        videoId = id,
        name = "Title $id",
        poster = null,
        backdrop = null,
        logo = null,
        type = ContentType.MOVIE,
        position = 10L,
        duration = 100L,
        lastWatched = 1_000L
    )
}
```

Add helpers in the same file using the project’s existing fake datastore factory pattern:

```kotlin
private fun testPreferences(
    activeSession: ActiveProfileSession = ActiveProfileSession(profileId = 1, sessionId = "p1", sessionOrdinal = 1L, startedAtMs = 1L)
): WatchProgressPreferences {
    val factory = FakeProfileDataStoreFactory.create()
    return WatchProgressPreferences(
        factory = factory,
        activeProfileSession = { activeSession }
    )
}
```

If `FakeProfileDataStoreFactory.create()` does not exist, add a small factory helper in this test file that mirrors `app/src/test/java/com/nexio/tv/data/local/FakeProfileDataStoreFactory.kt`.

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.local.WatchProgressPreferencesExplicitScopeTest"
```

Expected: FAIL because `WatchProgressPreferences` does not accept an `ActiveProfileSession` write scope and still defaults to active profile.

- [ ] **Step 3: Change WatchProgressPreferences constructor for testable active session**

Modify `WatchProgressPreferences` primary constructor:

```kotlin
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val activeProfileSession: () -> ActiveProfileSession = {
        profileManager.activeProfileSession.value
    }
```

Add secondary internal constructor:

```kotlin
internal constructor(
    factory: ProfileDataStoreFactory,
    activeProfileSession: () -> ActiveProfileSession
) : this(
    factory = factory,
    profileManager = error("ProfileManager is not available in explicit-session test constructor")
) {
    this.activeProfileSession = activeProfileSession
}
```

If Kotlin rejects assigning to `val`, use a private primary constructor:

```kotlin
class WatchProgressPreferences private constructor(
    private val factory: ProfileDataStoreFactory,
    private val activeProfileSession: () -> ActiveProfileSession
) {
    @Inject
    constructor(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ) : this(
        factory = factory,
        activeProfileSession = { profileManager.activeProfileSession.value }
    )
```

- [ ] **Step 4: Remove store default and add write boundary assertion**

Replace:

```kotlin
private fun store(profileId: Int = profileManager.activeProfileId.value) =
    factory.get(profileId, FEATURE)
```

with:

```kotlin
private fun store(profileId: Int) =
    factory.get(profileId, FEATURE)

private fun assertCanWrite(profileSession: ActiveProfileSession) {
    val active = activeProfileSession()
    ProfileBoundaryEnforcer.assertCanWriteProfileState(
        resultSession = profileSession,
        activeSession = active
    )
}
```

Add import:

```kotlin
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
```

- [ ] **Step 5: Convert read APIs to require profileId**

Replace properties with functions:

```kotlin
fun allProgress(profileId: Int): Flow<List<WatchProgress>> =
    store(profileId).data.map { preferences ->
        val json = preferences[watchProgressKey] ?: "{}"
        val allItems = parseProgressMap(json)
        val contentLevelEntries = allItems.entries
            .filter { (key, progress) -> key == progress.contentId }
            .associate { it.value.contentId to it.value }
            .toMutableMap()
        val latestEpisodeFallbacks = allItems.values
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.maxByOrNull { it.lastWatched } }
        latestEpisodeFallbacks.forEach { (contentId, latest) ->
            if (contentLevelEntries[contentId] == null && latest != null) {
                contentLevelEntries[contentId] = latest
            }
        }
        contentLevelEntries.values.sortedByDescending { it.lastWatched }
    }

fun allRawProgress(profileId: Int): Flow<List<WatchProgress>> =
    store(profileId).data.map { preferences ->
        val json = preferences[watchProgressKey] ?: "{}"
        parseProgressMap(json).values.sortedByDescending { it.lastWatched }
    }

fun continueWatching(profileId: Int): Flow<List<WatchProgress>> =
    allProgress(profileId).map { list -> list.filter { it.isInProgress() } }
```

- [ ] **Step 6: Convert write APIs to require profile session**

Change:

```kotlin
suspend fun saveProgress(progress: WatchProgress)
```

to:

```kotlin
suspend fun saveProgress(profileSession: ActiveProfileSession, progress: WatchProgress) {
    assertCanWrite(profileSession)
    store(profileSession.profileId).edit { preferences ->
        val json = preferences[watchProgressKey] ?: "{}"
        val map = parseProgressMap(json).toMutableMap()
        val key = createKey(progress)
        map[key] = progress
        if (progress.season != null && progress.episode != null) {
            val seriesKey = progress.contentId
            val existingSeriesProgress = map[seriesKey]
            if (existingSeriesProgress == null || progress.lastWatched > existingSeriesProgress.lastWatched) {
                map[seriesKey] = progress.copy(videoId = progress.videoId)
            }
        }
        val pruned = pruneOldItems(map)
        preferences[watchProgressKey] = gson.toJson(pruned)
    }
}
```

Make the same signature change for `removeProgress`, `clearAll`, and any write method in this file:

```kotlin
suspend fun removeProgress(profileSession: ActiveProfileSession, contentId: String, season: Int? = null, episode: Int? = null)
suspend fun clearAll(profileSession: ActiveProfileSession)
```

- [ ] **Step 7: Run preferences tests and architecture test**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.local.WatchProgressPreferencesExplicitScopeTest" --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest"
```

Expected: PASS for preferences-specific tests. The architecture test can still fail until repository interface is updated in Task 8.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesExplicitScopeTest.kt
git commit -m "fix: require explicit profile session for watch progress persistence"
```

---

### Task 8: Replace WatchProgressRepository API With Explicit Scope

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt`
- Modify call sites returned by `rg -n "watchProgressRepository\\." app/src/main/java`
- Test: `app/src/test/java/com/nexio/tv/architecture/WatchProgressProfileScopeArchitectureTest.kt`

- [ ] **Step 1: Update repository interface**

Replace the existing interface body with:

```kotlin
interface WatchProgressRepository {
    fun observeProgress(profileId: Int): Flow<List<WatchProgress>>

    fun observeContinueWatching(profileId: Int): Flow<List<WatchProgress>>

    fun observeProgress(
        profileId: Int,
        contentId: String
    ): Flow<WatchProgress?>

    fun observeEpisodeProgress(
        profileId: Int,
        contentId: String,
        season: Int,
        episode: Int
    ): Flow<WatchProgress?>

    fun observeAllEpisodeProgress(
        profileId: Int,
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>>

    fun observeWatched(
        profileId: Int,
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<Boolean>

    suspend fun upsertProgress(
        profileSession: ActiveProfileSession,
        progress: WatchProgress,
        syncRemote: Boolean = true
    )

    suspend fun removeProgress(
        profileSession: ActiveProfileSession,
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    )

    suspend fun removeFromHistory(
        profileSession: ActiveProfileSession,
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    )

    suspend fun clearShowProgress(
        profileSession: ActiveProfileSession,
        contentId: String
    )

    suspend fun markAsCompleted(
        profileSession: ActiveProfileSession,
        progress: WatchProgress
    )

    suspend fun markAsCompletedBatch(
        profileSession: ActiveProfileSession,
        meta: Meta,
        seasonNumber: Int,
        episodes: List<SeasonEpisodeMark>
    )

    suspend fun clearAll(profileSession: ActiveProfileSession)

    fun invalidateLocalizedMetadata()
}
```

Add import:

```kotlin
import com.nexio.tv.core.integration.ActiveProfileSession
```

- [ ] **Step 2: Update repository implementation read methods**

In `WatchProgressRepositoryImpl`, replace:

```kotlin
override val allProgress: Flow<List<WatchProgress>> = ...
override val continueWatching: Flow<List<WatchProgress>> = ...
override fun getProgress(contentId: String): Flow<WatchProgress?> = ...
```

with:

```kotlin
override fun observeProgress(profileId: Int): Flow<List<WatchProgress>> =
    watchProgressPreferences.allProgress(profileId)
        .map { progressList ->
            hydrateMetadata(progressList)
            progressList.map { progress -> progress.withHydratedMetadata() }
        }

override fun observeContinueWatching(profileId: Int): Flow<List<WatchProgress>> =
    observeProgress(profileId).map { list -> list.filter { it.isInProgress() } }

override fun observeProgress(profileId: Int, contentId: String): Flow<WatchProgress?> =
    watchProgressPreferences.getProgress(profileId, contentId)

override fun observeEpisodeProgress(profileId: Int, contentId: String, season: Int, episode: Int): Flow<WatchProgress?> =
    watchProgressPreferences.getEpisodeProgress(profileId, contentId, season, episode)

override fun observeAllEpisodeProgress(profileId: Int, contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
    watchProgressPreferences.getAllEpisodeProgress(profileId, contentId)
```

If `withHydratedMetadata()` is currently inline inside the old property chain, extract it as:

```kotlin
private fun WatchProgress.withHydratedMetadata(): WatchProgress {
    val metadata = metadataState.value[contentId] ?: return this
    return copy(
        name = metadata.name ?: name,
        poster = metadata.poster ?: poster,
        backdrop = metadata.backdrop ?: backdrop,
        logo = metadata.logo ?: logo
    )
}
```

- [ ] **Step 3: Update write methods to pass profileSession to persistence**

Change:

```kotlin
override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean)
```

to:

```kotlin
override suspend fun upsertProgress(
    profileSession: ActiveProfileSession,
    progress: WatchProgress,
    syncRemote: Boolean
) {
    watchProgressPreferences.saveProgress(profileSession, progress)

    if (syncRemote && trackingProviderStateService.currentState(profileSession.profileId).hasAuthenticatedProvider) {
        trackingProgressService.applyOptimisticProgress(progress)
        enqueueRemoteProgressMutation(profileSession, progress)
    }
}
```

Apply the same pattern:

```kotlin
watchProgressPreferences.removeProgress(profileSession, contentId, season, episode)
watchProgressPreferences.saveProgress(profileSession, completed)
watchProgressPreferences.clearAll(profileSession)
```

Local progress persistence is unconditional after profile-session validation. Only remote sync/outbox work is conditional on `syncRemote` and provider authentication. Add these tests while updating repository tests:

Add this helper in `WatchProgressRepositoryImpl` if there is not already one:

```kotlin
private suspend fun enqueueRemoteProgressMutation(
    profileSession: ActiveProfileSession,
    progress: WatchProgress
) {
    val providerState = trackingProviderStateService.currentState(profileSession.profileId)
    if (!providerState.hasAuthenticatedProvider) return
    val session = accountSessionFor(providerState.effectiveProvider, profileSession.profileId)
    val envelope = when (providerState.effectiveProvider) {
        com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
            SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
                progress = progress,
                title = progress.name.takeIf { it.isNotBlank() },
                year = null,
                session = session
            )
        com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
            TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
                progress = progress,
                title = progress.name.takeIf { it.isNotBlank() },
                year = null,
                session = session
            )
    }
    traktMutationOutboxCoordinator.enqueueAndDrain(envelope)
}
```

```kotlin
@Test
fun `watch progress persists locally without tracking account`() = runTest {
    val profileSession = ActiveProfileSession(profileId = 1, sessionId = "p1", sessionOrdinal = 1L, startedAtMs = 1L)
    val repository = repositoryWithTrackingState(hasAuthenticatedProvider = false)
    val progress = sampleProgress(contentId = "tt1")

    repository.upsertProgress(profileSession, progress, syncRemote = true)

    coVerify { watchProgressPreferences.saveProgress(profileSession, progress) }
    coVerify(exactly = 0) { traktMutationOutboxCoordinator.enqueueAndDrain(any()) }
}

@Test
fun `watch progress remote sync skipped when no tracking account`() = runTest {
    val profileSession = ActiveProfileSession(profileId = 1, sessionId = "p1", sessionOrdinal = 1L, startedAtMs = 1L)
    val repository = repositoryWithTrackingState(hasAuthenticatedProvider = false)

    repository.upsertProgress(profileSession, sampleProgress(contentId = "tt1"), syncRemote = true)

    verify(exactly = 0) { trackingProgressService.applyOptimisticProgress(any()) }
    coVerify(exactly = 0) { traktMutationOutboxCoordinator.enqueueAndDrain(any()) }
}
```

- [ ] **Step 4: Update call sites with captured active profile session**

For UI/ViewModel call sites, capture the session before launching async work. Example replacement in `PlayerRuntimeControllerPlaybackEvents.kt`:

```kotlin
val profileSession = profileManager.activeProfileSession.value
watchProgressRepository.upsertProgress(
    profileSession = profileSession,
    progress = progress,
    syncRemote = syncRemote
)
```

For detail VM reads, use the active profile id at read time:

```kotlin
val profileId = profileManager.activeProfileSession.value.profileId
watchProgressRepository.observeAllEpisodeProgress(profileId, contentId)
```

For write actions in detail/home, pass the captured session:

```kotlin
val profileSession = profileManager.activeProfileSession.value
watchProgressRepository.markAsCompleted(profileSession, buildCompletedMovieProgress(meta, progressContentId))
```

Run this scan until no old API calls remain:

```bash
rg -n "watchProgressRepository\\.(continueWatching|allProgress|getProgress|getEpisodeProgress|getAllEpisodeProgress|saveProgress|removeProgress|removeFromHistory|clearShowProgress|markAsCompleted|markAsCompletedBatch|clearAll)" app/src/main/java
```

Expected: no hits for old method names `continueWatching`, `allProgress`, `getProgress`, `getEpisodeProgress`, `getAllEpisodeProgress`, `saveProgress`. Hits for new write names are acceptable only when they pass `profileSession =`.

- [ ] **Step 5: Run compile and architecture test**

Run:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest"
```

Expected: PASS.

- [ ] **Step 6: Run focused WatchProgress tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.repository.WatchProgressRepositoryProviderRoutingTest" --tests "com.nexio.tv.data.repository.WatchProgressRepositoryRouterRoutingTest" --tests "com.nexio.tv.data.local.WatchProgressPreferencesExplicitScopeTest"
```

Expected: PASS after updating mocks to the new interface names.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt app/src/main/java/com/nexio/tv/ui app/src/test/java/com/nexio/tv
git commit -m "fix: require profile session for watch progress repository"
```

---

### Task 9: Remove Detail ViewModel Direct TMDB ID Bridge

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt`

- [ ] **Step 1: Identify existing stable ID bundle in detail flow**

In `MetaDetailsViewModel.enrichMeta`, find the existing metadata resolution result near the first `metadataRouterFacade.resolveRequest(...)` call. Preserve that `StableIdBundle` or route output as a local value:

```kotlin
val stableIdBundle = metadataRouterFacade.resolveStableIds(
    MetadataRequest(
        contentId = meta.id,
        contentType = tmdbContentType,
        sourceContext = MetadataSourceContext(itemType = itemType),
        language = tvdbLanguage,
        depth = MetadataDepth.DETAIL_CORE
    )
)
```

If there is already a `MetadataResolutionResult` in scope, do not make a second facade call. Use:

```kotlin
val tmdbId = resolution.stableIdBundle?.sidecars?.tmdbId
```

- [ ] **Step 2: Replace direct TMDB lookup in non-TV branch**

Replace:

```kotlin
val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbContentType.toApiString())
    ?: tmdbService.ensureTmdbId(itemId, itemType)
    ?: return result(meta)
```

with:

```kotlin
val tmdbId = stableIdBundle
    ?.sidecars
    ?.tmdbId
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: return result(meta)
```

- [ ] **Step 3: Replace any remaining direct UI TMDB lookups in detail**

Run:

```bash
rg -n "tmdbService\\.ensureTmdbId|\\.ensureTmdbId\\(" app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
```

For each remaining hit in `MetaDetailsViewModel`, use the stable ID bundle already produced for the relevant detail operation. Do not add a new one-off facade call if the method already has a metadata resolution result.

- [ ] **Step 4: Run identity architecture test**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.architecture.NoDetailUiTmdbEnsureIdArchitectureTest"
```

Expected: PASS. This P0 test scans only `MetaDetailsViewModel.kt`. The global all-UI `NoUiTmdbEnsureIdArchitectureTest` is intentionally deferred to Packet C.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/test/java/com/nexio/tv/architecture/NoDetailUiTmdbEnsureIdArchitectureTest.kt
git commit -m "fix: use resolved stable ids in detail enrichment"
```

---

### Task 10: Final P0 Verification And Audit Update

**Files:**
- Modify: `review-dossier/shared-resolution-bypass-audit.md`
- Modify: `review-dossier/shared-resolution-bypass-audit.csv`

- [ ] **Step 1: Run static scans**

Run:

```bash
rg -n "private fun store\\(profileId: Int = profileManager\\.activeProfileId\\.value\\)|store\\(\\)\\.edit" app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt
rg -n "TraktMutationEnvelope\\(" app/src/main/java app/src/test/java
rg -n "tmdbService\\.ensureTmdbId|\\.ensureTmdbId\\(" app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
```

Expected:

- First command: no output.
- Second command: every constructor includes `provider =` and `credentialHash =`, or uses a helper that supplies both.
- Third command: no output.

- [ ] **Step 2: Run focused P0 tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationEnvelopeAccountScopeTest" \
  --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCredentialValidationTest" \
  --tests "com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCrossProfileTest" \
  --tests "com.nexio.tv.data.local.WatchProgressPreferencesExplicitScopeTest" \
  --tests "com.nexio.tv.architecture.WatchProgressProfileScopeArchitectureTest" \
  --tests "com.nexio.tv.architecture.NoDetailUiTmdbEnsureIdArchitectureTest"
```

Expected: PASS.

- [ ] **Step 3: Run compile**

Run:

```bash
./gradlew compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Update audit statuses**

In `review-dossier/shared-resolution-bypass-audit.md`, move these rows from confirmed bypasses to fixed/verified notes:

- `TraktMutationEnvelope` account boundary bypass.
- `WatchProgressRepository` explicit scope bypass.
- `WatchProgressPreferences` active-profile default bypass.
- `MetaDetailsViewModel.kt:1499` direct UI TMDB ID bypass.

In `review-dossier/shared-resolution-bypass-audit.csv`, update their `status` values to:

```csv
fixed verified
```

and add the focused test name that proved the fix.

- [ ] **Step 5: Validate CSV**

Run:

```bash
ruby -rcsv -e 'CSV.read("review-dossier/shared-resolution-bypass-audit.csv", headers: true); puts "csv ok"'
```

Expected: `csv ok`.

- [ ] **Step 6: Commit final P0 packet**

```bash
git add review-dossier/shared-resolution-bypass-audit.md review-dossier/shared-resolution-bypass-audit.csv
git commit -m "docs: mark P0 bypass removals verified"
```

---

## Follow-On Plans Required

Create separate plans after this P0 packet lands:

- Packet B: resolved detail/home metadata ownership.
- Packet C: stable identity ownership for remaining home trailer and rail paths.
- Packet D: RatingResolver ownership.
- Packet E: TrailerResolver ownership.
- Packet F: SkipSegmentResolver ownership.
- Packet G: artwork typed-ref migration.
- Packet H: localization policy.

Do not combine those into the P0 packet.

## Self-Review

Spec coverage:

- Provider mutation envelopes include provider + credentialHash: Task 2.
- Enqueue/drain/execute validation: Task 4.
- Collapse/fairness account keys: Task 2.
- Trakt/Simkl builder and call-site migration: Tasks 5 and 6.
- WatchProgress explicit profile/session APIs: Tasks 7 and 8.
- Direct UI TMDB ID P0 removal: Task 9.
- Audit update and static scans: Task 10.

Placeholder scan:

- The plan contains concrete file paths, code snippets, commands, expected outcomes, and commit points for the P0 packet.
- Follow-on packets are intentionally out of scope and named as separate required plans.

Type consistency:

- Provider mutation scope uses the existing `TrackingProvider` enum for Trakt/Simkl, matching current code.
- Account credential identity uses existing `TrackingAuthSession.credentialHash`.
- WatchProgress write scope uses existing `ActiveProfileSession`.
