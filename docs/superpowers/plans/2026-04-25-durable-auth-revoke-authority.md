# Durable Auth Revoke Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make durable-auth revoke authoritative for active Android sessions and make Android logout reliably revoke the remote durable credential.

**Architecture:** Add a no-JWT Supabase Edge Function that validates a durable device credential by `device_public_id + device_secret` and supports both `status` and idempotent `revoke` actions. Android treats the durable credential as the device authorization source even when a Supabase refresh token exists, and keeps an encrypted pending-revoke record so logout can retry remote revoke after the local session is cleared. Local stock reset remains local-only and push-suppressed.

**Tech Stack:** Kotlin, Hilt, Android DataStore, OkHttp, kotlinx.serialization, Supabase Edge Functions, Deno/TypeScript, OpenSpec, Gradle JVM tests.

---

## File Structure

- `openspec/changes/enforce-durable-revoke-authority/proposal.md`
  Defines the revoke-authority gap and the intended behavior.
- `openspec/changes/enforce-durable-revoke-authority/tasks.md`
  Tracks implementation and verification.
- `openspec/changes/enforce-durable-revoke-authority/specs/durable-device-auth/spec.md`
  Adds requirements for active-session revoke enforcement and reliable logout revoke.
- `supabase/functions/device-credential-self-service/index.ts`
  New no-JWT Edge Function. Validates possession of the durable credential secret and returns active/revoked status or revokes the credential idempotently.
- `supabase/functions/tests/device-auth.test.ts`
  Adds pure-function tests for request normalization, response payloads, and idempotent revoke/update payload contracts.
- `nexio-web/SETUP.md`
  Adds deployment instructions for the new Edge Function.
- `app/src/main/java/com/nexio/tv/data/local/DurableDeviceCredentialStore.kt`
  Adds encrypted pending-revoke storage separate from the active durable credential.
- `app/src/test/java/com/nexio/tv/data/local/DurableDeviceCredentialStoreTest.kt`
  Verifies pending-revoke storage does not affect active credential recovery and survives active credential clearing.
- `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  Calls the self-service Edge Function to enforce status on active sessions and to retry pending logout revokes.
- `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`
  Adds policy/contract tests for active-session validation, logout pending revoke ordering, and no recovery from pending revoke records.

## Task 1: Record The OpenSpec Change

**Files:**
- Create: `openspec/changes/enforce-durable-revoke-authority/proposal.md`
- Create: `openspec/changes/enforce-durable-revoke-authority/tasks.md`
- Create: `openspec/changes/enforce-durable-revoke-authority/specs/durable-device-auth/spec.md`

- [ ] **Step 1: Create the proposal**

Write `openspec/changes/enforce-durable-revoke-authority/proposal.md`:

```markdown
# Enforce Durable Revoke Authority

## Why

Remote durable-device revoke currently prevents future durable session exchange, but an Android TV device with an already-valid Supabase refresh token can continue as authenticated until that token is rejected. Manual logout also attempts remote revoke only while the local session is still present; if the attempt fails, the local credential is cleared and the app loses the data needed to retry.

## What Changes

- Add a no-JWT device credential self-service Edge Function that validates `device_public_id + device_secret`.
- Let Android query durable credential status during active-session publication and refresh recovery.
- Treat a revoked durable credential as authoritative: reset local state to stock, clear local credential/session, and transition to reconnect/session-lost.
- Store encrypted pending-revoke credentials during manual logout so failed remote revoke attempts can retry after local session teardown.
- Keep local stock reset push-suppressed so logout/revoke never pushes stock defaults to the account.

## Impact

- Android TV app auth lifecycle.
- Supabase Edge Functions deployment required.
- No schema migration required.
- Existing web revoke RPC remains unchanged and continues to mark rows revoked.
```

- [ ] **Step 2: Create the OpenSpec task list**

Write `openspec/changes/enforce-durable-revoke-authority/tasks.md`:

```markdown
## 1. Implementation

- [ ] 1.1 Add a durable credential self-service Edge Function with status and revoke actions.
- [ ] 1.2 Add encrypted pending-revoke storage to Android durable credential storage.
- [ ] 1.3 Make Android logout persist pending revoke before clearing local durable auth and call self-revoke idempotently.
- [ ] 1.4 Make active Android sessions validate local durable credential status and reset to stock on revoked status.
- [ ] 1.5 Retry pending durable credential revokes on startup/auth lifecycle without enabling recovery from pending credentials.
- [ ] 1.6 Document Supabase function deployment.

## 2. Verification

- [ ] 2.1 Run `deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts`.
- [ ] 2.2 Run focused Android auth and durable credential store tests.
- [ ] 2.3 Run `openspec validate enforce-durable-revoke-authority --strict`.
- [ ] 2.4 Run `./gradlew :app:compileUniversalReleaseKotlin` after unrelated benchmark transport compile errors are fixed.
```

- [ ] **Step 3: Create the spec delta**

Write `openspec/changes/enforce-durable-revoke-authority/specs/durable-device-auth/spec.md`:

```markdown
## ADDED Requirements

### Requirement: Durable Credential Revoke Is Authoritative For Active Devices

An Android TV device with a local durable credential SHALL treat that credential's remote status as authoritative even when a Supabase refresh token is still locally available.

#### Scenario: Active session detects remote durable revoke

- **GIVEN** an Android TV device has a full Supabase session and a complete local durable credential
- **AND** the credential row in Supabase has `status = 'revoked'`
- **WHEN** the app starts, publishes an authenticated session, or handles session refresh recovery
- **THEN** the app resets local account-owned state to stock defaults
- **AND** the app clears the local durable credential
- **AND** the app clears the local Supabase session
- **AND** the app transitions to reconnect/session-lost instead of remaining authenticated

#### Scenario: Active session tolerates transient durable status failure

- **GIVEN** an Android TV device has a full Supabase session and a complete local durable credential
- **WHEN** the durable credential status endpoint fails with a transient network or server error
- **THEN** the app does not clear local account state
- **AND** the app keeps the current auth state so a later status check can retry

### Requirement: Manual Logout Reliably Revokes Durable Credential

Manual logout SHALL make the local durable credential unusable for future sessions and SHALL retry remote durable credential revoke when the first revoke attempt cannot complete.

#### Scenario: Manual logout revokes online

- **GIVEN** an Android TV device has a full account session and a complete local durable credential
- **WHEN** the user manually logs out while the network is available
- **THEN** the app stores a pending revoke record before clearing the active durable credential
- **AND** the app calls the self-service revoke endpoint with the durable credential secret
- **AND** the remote credential row becomes `status = 'revoked'`
- **AND** the app clears the pending revoke record
- **AND** the active local durable credential is cleared

#### Scenario: Manual logout queues revoke while offline

- **GIVEN** an Android TV device has a full account session and a complete local durable credential
- **WHEN** the user manually logs out while the remote revoke endpoint is unavailable
- **THEN** the app resets local account-owned state to stock defaults
- **AND** the app clears the active local durable credential
- **AND** the app retains only an encrypted pending-revoke record
- **AND** future durable recovery does not use the pending-revoke record
- **AND** the app retries remote revoke when network/auth lifecycle work resumes
```

- [ ] **Step 4: Validate the OpenSpec change**

Run:

```bash
openspec validate enforce-durable-revoke-authority --strict
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/enforce-durable-revoke-authority
git commit -m "docs(auth): specify durable revoke authority hardening"
```

## Task 2: Add The Supabase Credential Self-Service Function

**Files:**
- Create: `supabase/functions/device-credential-self-service/index.ts`
- Modify: `supabase/functions/tests/device-auth.test.ts`

- [ ] **Step 1: Write failing function tests**

Append these imports to `supabase/functions/tests/device-auth.test.ts`:

```ts
import {
  buildCredentialSelfServicePayload,
  buildCredentialSelfServiceUpdate,
  normalizeCredentialSelfServiceBody,
} from "../device-credential-self-service/index.ts";
```

Append these tests to `supabase/functions/tests/device-auth.test.ts`:

```ts
test("normalizeCredentialSelfServiceBody trims status requests", () => {
  assert.deepEqual(
    normalizeCredentialSelfServiceBody({
      device_public_id: "  tv_public  ",
      device_secret: "  secret  ",
      action: " status ",
    }),
    {
      devicePublicId: "tv_public",
      deviceSecret: "secret",
      action: "status",
    },
  );
});

test("normalizeCredentialSelfServiceBody trims revoke requests", () => {
  assert.deepEqual(
    normalizeCredentialSelfServiceBody({
      device_public_id: "  tv_public  ",
      device_secret: "  secret  ",
      action: " revoke ",
    }),
    {
      devicePublicId: "tv_public",
      deviceSecret: "secret",
      action: "revoke",
    },
  );
});

test("normalizeCredentialSelfServiceBody rejects unsupported actions", () => {
  assert.throws(
    () =>
      normalizeCredentialSelfServiceBody({
        device_public_id: "tv_public",
        device_secret: "secret",
        action: "delete",
      }),
    /Invalid durable device credential action/,
  );
});

test("buildCredentialSelfServicePayload exposes active and revoked states", () => {
  assert.deepEqual(
    buildCredentialSelfServicePayload("active"),
    { status: "active", active: true, revoked: false },
  );
  assert.deepEqual(
    buildCredentialSelfServicePayload("revoked"),
    { status: "revoked", active: false, revoked: true },
  );
});

test("buildCredentialSelfServiceUpdate revokes with a timestamp", () => {
  assert.deepEqual(
    buildCredentialSelfServiceUpdate("2026-04-25T12:00:00.000Z"),
    { status: "revoked", revoked_at: "2026-04-25T12:00:00.000Z" },
  );
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts
```

Expected: FAIL with an import error for `../device-credential-self-service/index.ts`.

- [ ] **Step 3: Create the Edge Function**

Create `supabase/functions/device-credential-self-service/index.ts`:

```ts
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  hashDeviceCredential,
  normalizeDeviceExchangeBody,
} from "../_shared/device-auth.ts";

type CredentialStatus = "active" | "revoked";
type CredentialAction = "status" | "revoke";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function requireEnv(name: string): string {
  const value = Deno.env.get(name) ?? "";
  if (!value) {
    throw new Error(`Missing ${name}`);
  }
  return value;
}

function createAdminClient() {
  return createClient(
    requireEnv("SUPABASE_URL"),
    requireEnv("SUPABASE_SERVICE_ROLE_KEY"),
    { auth: { persistSession: false, autoRefreshToken: false } },
  );
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders,
    },
  });
}

export function invalidCredentialResponse(): Response {
  return json({ error: "Invalid durable device credential" }, 401);
}

export function normalizeCredentialSelfServiceBody(body: unknown): {
  devicePublicId: string;
  deviceSecret: string;
  action: CredentialAction;
} {
  const credential = normalizeDeviceExchangeBody(body);
  const actionValue =
    body &&
    typeof body === "object" &&
    !Array.isArray(body) &&
    "action" in body
      ? (body as { action?: unknown }).action
      : "status";

  const action = typeof actionValue === "string" ? actionValue.trim() : "";
  if (action !== "status" && action !== "revoke") {
    throw new Error("Invalid durable device credential action");
  }

  return { ...credential, action };
}

export function buildCredentialSelfServicePayload(status: CredentialStatus) {
  return {
    status,
    active: status === "active",
    revoked: status === "revoked",
  };
}

export function buildCredentialSelfServiceUpdate(now: string) {
  return { status: "revoked", revoked_at: now };
}

async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    const body = normalizeCredentialSelfServiceBody(await req.json());
    const adminClient = createAdminClient();
    const candidateHash = await hashDeviceCredential(
      body.devicePublicId,
      body.deviceSecret,
    );

    const { data: credentialRow, error: credentialError } = await adminClient
      .from("device_credentials")
      .select("id, credential_hash, status")
      .eq("device_public_id", body.devicePublicId)
      .maybeSingle();

    if (credentialError) {
      return json({
        error: `Device credential lookup failed: ${credentialError.message}`,
      }, 500);
    }

    if (!credentialRow || credentialRow.credential_hash !== candidateHash) {
      return invalidCredentialResponse();
    }

    if (body.action === "status") {
      return json(buildCredentialSelfServicePayload(
        credentialRow.status === "revoked" ? "revoked" : "active",
      ));
    }

    if (credentialRow.status !== "revoked") {
      const { error: updateError } = await adminClient
        .from("device_credentials")
        .update(buildCredentialSelfServiceUpdate(new Date().toISOString()))
        .eq("id", credentialRow.id);

      if (updateError) {
        return json({
          error: `Device credential revoke failed: ${updateError.message}`,
        }, 500);
      }
    }

    return json(buildCredentialSelfServicePayload("revoked"));
  } catch (error) {
    if (
      error instanceof Error &&
      (
        error.message === "Invalid durable device credential" ||
        error.message === "Invalid durable device credential action"
      )
    ) {
      return invalidCredentialResponse();
    }

    const message = error instanceof Error ? error.message : "Unknown error";
    return json({ error: message }, 500);
  }
}

Deno.serve(handleRequest);
```

- [ ] **Step 4: Run function tests**

Run:

```bash
deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add supabase/functions/device-credential-self-service/index.ts supabase/functions/tests/device-auth.test.ts
git commit -m "feat(auth): add durable credential self service function"
```

## Task 3: Add Pending Revoke Storage To Durable Credential Store

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/DurableDeviceCredentialStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/DurableDeviceCredentialStoreTest.kt`

- [ ] **Step 1: Write failing pending-revoke storage test**

Append this test to `DurableDeviceCredentialStoreTest`:

```kotlin
@Test
fun `pending revoke survives active credential clear without becoming recoverable`() = runTest {
    val harness = createStore()
    val store = harness.store

    store.save(
        devicePublicId = "active-public-id",
        deviceSecret = "active-secret",
        ownerUserId = "owner-id"
    )
    store.savePendingRevoke(
        devicePublicId = "active-public-id",
        deviceSecret = "active-secret"
    )
    store.clear()

    val active = store.snapshot()
    val pending = store.pendingRevokeSnapshot()

    assertFalse(active.isComplete)
    assertEquals("active-public-id", pending.devicePublicId)
    assertEquals("active-secret", pending.deviceSecret)
    assertTrue(pending.isComplete)
}
```

Append this test to `DurableDeviceCredentialStoreTest`:

```kotlin
@Test
fun `clear pending revoke removes only pending revoke data`() = runTest {
    val harness = createStore()
    val store = harness.store

    store.save(
        devicePublicId = "active-public-id",
        deviceSecret = "active-secret",
        ownerUserId = "owner-id"
    )
    store.savePendingRevoke(
        devicePublicId = "pending-public-id",
        deviceSecret = "pending-secret"
    )
    store.clearPendingRevoke()

    val active = store.snapshot()
    val pending = store.pendingRevokeSnapshot()

    assertTrue(active.isComplete)
    assertEquals("active-public-id", active.devicePublicId)
    assertFalse(pending.isComplete)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DurableDeviceCredentialStoreTest"
```

Expected: FAIL with unresolved references for `savePendingRevoke`, `pendingRevokeSnapshot`, and `clearPendingRevoke`.

- [ ] **Step 3: Add pending revoke model and keys**

In `DurableDeviceCredentialStore.kt`, add this data class near `DurableDeviceCredentialSnapshot`:

```kotlin
data class PendingDurableCredentialRevokeSnapshot(
    val devicePublicId: String? = null,
    val deviceSecret: String? = null
) {
    val isComplete: Boolean
        get() = !devicePublicId.isNullOrBlank() && !deviceSecret.isNullOrBlank()
}
```

In the companion/key area, add:

```kotlin
private val pendingRevokeDevicePublicIdKey = stringPreferencesKey("pending_revoke_device_public_id")
private val pendingRevokeDeviceSecretKey = stringPreferencesKey("pending_revoke_device_secret")
```

- [ ] **Step 4: Add pending revoke methods**

Add these methods inside `DurableDeviceCredentialStore`:

```kotlin
suspend fun pendingRevokeSnapshot(): PendingDurableCredentialRevokeSnapshot {
    val prefs = dataStore.data.first()
    val rawSecret = prefs[pendingRevokeDeviceSecretKey]
    val secret = rawSecret?.let { stored ->
        runCatching { secretProtector.decrypt(stored) }.getOrNull()
    }

    if (rawSecret != null && secret == null) {
        dataStore.edit { mutablePrefs ->
            mutablePrefs.remove(pendingRevokeDeviceSecretKey)
        }
    }

    return PendingDurableCredentialRevokeSnapshot(
        devicePublicId = prefs[pendingRevokeDevicePublicIdKey]?.trim()?.takeIf { it.isNotBlank() },
        deviceSecret = secret
    )
}

suspend fun savePendingRevoke(devicePublicId: String, deviceSecret: String) {
    val normalizedPublicId = devicePublicId.trim()
    val normalizedSecret = deviceSecret.trim()
    dataStore.edit { prefs ->
        if (normalizedPublicId.isBlank() || normalizedSecret.isBlank()) {
            prefs.remove(pendingRevokeDevicePublicIdKey)
            prefs.remove(pendingRevokeDeviceSecretKey)
        } else {
            prefs[pendingRevokeDevicePublicIdKey] = normalizedPublicId
            prefs[pendingRevokeDeviceSecretKey] = secretProtector.encrypt(normalizedSecret)
        }
    }
}

suspend fun clearPendingRevoke() {
    dataStore.edit { prefs ->
        prefs.remove(pendingRevokeDevicePublicIdKey)
        prefs.remove(pendingRevokeDeviceSecretKey)
    }
}
```

- [ ] **Step 5: Run focused store tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.DurableDeviceCredentialStoreTest"
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If the command stops before tests execute with benchmark transport compile errors, record the blocker and continue only after verifying `git diff --check`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/DurableDeviceCredentialStore.kt app/src/test/java/com/nexio/tv/data/local/DurableDeviceCredentialStoreTest.kt
git commit -m "feat(auth): store pending durable credential revokes"
```

## Task 4: Add Android Durable Credential Self-Service Client Logic

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Add policy tests for self-service response handling**

Append this enum and helper expectations to `DurableDeviceAuthRecoveryPolicyTest` usage by adding tests:

```kotlin
@Test
fun `revoked durable credential status resets local auth state`() {
    assertEquals(
        DurableCredentialStatusAction.RESET_TO_STOCK_AND_SESSION_LOST,
        resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.REVOKED)
    )
}

@Test
fun `active durable credential status keeps current auth state`() {
    assertEquals(
        DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
        resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.ACTIVE)
    )
}

@Test
fun `unknown durable credential status keeps current auth state`() {
    assertEquals(
        DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
        resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.UNKNOWN)
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: FAIL with unresolved references for `DurableCredentialStatusAction`, `DurableCredentialRemoteStatus`, and `resolveDurableCredentialStatusAction`.

- [ ] **Step 3: Add response models and policy helpers**

In `AuthManager.kt`, add imports:

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
```

Add these declarations near the other internal auth policy enums:

```kotlin
@Serializable
private data class DurableCredentialSelfServiceResponse(
    val status: String = "",
    val active: Boolean = false,
    val revoked: Boolean = false,
    @SerialName("error") val error: String? = null
)

internal enum class DurableCredentialRemoteStatus {
    ACTIVE,
    REVOKED,
    UNKNOWN
}

internal enum class DurableCredentialStatusAction {
    KEEP_CURRENT_AUTH_STATE,
    RESET_TO_STOCK_AND_SESSION_LOST
}

internal fun resolveDurableCredentialStatusAction(
    status: DurableCredentialRemoteStatus
): DurableCredentialStatusAction {
    return when (status) {
        DurableCredentialRemoteStatus.REVOKED ->
            DurableCredentialStatusAction.RESET_TO_STOCK_AND_SESSION_LOST
        DurableCredentialRemoteStatus.ACTIVE,
        DurableCredentialRemoteStatus.UNKNOWN ->
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE
    }
}
```

- [ ] **Step 4: Add self-service HTTP method**

Add this private method inside `AuthManager`:

```kotlin
private suspend fun callDurableCredentialSelfService(
    devicePublicId: String,
    deviceSecret: String,
    action: String
): DurableCredentialSelfServiceResponse {
    val payload = buildJsonObject {
        put("device_public_id", devicePublicId)
        put("device_secret", deviceSecret)
        put("action", action)
    }.toString()
    val request = Request.Builder()
        .url("${BuildConfig.SUPABASE_URL}/functions/v1/device-credential-self-service")
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()

    val body = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw AuthoritativeDurableCredentialRejectionException(
                        "Durable credential self-service failed (${response.code}): $responseBody"
                    )
                }
                throw IllegalStateException(
                    "Durable credential self-service failed (${response.code}): $responseBody"
                )
            }
            responseBody
        }
    }

    return json.decodeFromString<DurableCredentialSelfServiceResponse>(body)
}
```

- [ ] **Step 5: Add status check method**

Add this private method inside `AuthManager`:

```kotlin
private suspend fun validateDurableCredentialStillActive(): DurableCredentialRemoteStatus {
    val credential = durableDeviceCredentialStore.snapshot()
    if (!credential.isComplete) return DurableCredentialRemoteStatus.UNKNOWN

    return try {
        val response = callDurableCredentialSelfService(
            devicePublicId = credential.devicePublicId.orEmpty(),
            deviceSecret = credential.deviceSecret.orEmpty(),
            action = "status"
        )
        when {
            response.revoked || response.status.equals("revoked", ignoreCase = true) ->
                DurableCredentialRemoteStatus.REVOKED
            response.active || response.status.equals("active", ignoreCase = true) ->
                DurableCredentialRemoteStatus.ACTIVE
            else -> DurableCredentialRemoteStatus.UNKNOWN
        }
    } catch (e: AuthoritativeDurableCredentialRejectionException) {
        DurableCredentialRemoteStatus.REVOKED
    } catch (e: Exception) {
        Log.w(TAG, "Durable credential status check failed; keeping current auth state", e)
        DurableCredentialRemoteStatus.UNKNOWN
    }
}
```

- [ ] **Step 6: Add status enforcement method**

Add this private method inside `AuthManager`:

```kotlin
private suspend fun enforceDurableCredentialStillActive() {
    when (
        resolveDurableCredentialStatusAction(validateDurableCredentialStillActive())
    ) {
        DurableCredentialStatusAction.RESET_TO_STOCK_AND_SESSION_LOST -> {
            Log.w(TAG, "Durable credential is revoked; clearing local auth state")
            clearLocalAuthStateAfterAuthoritativeDurableRejection()
        }
        DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE -> Unit
    }
}
```

- [ ] **Step 7: Run focused policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If the command stops before tests execute with benchmark transport compile errors, record the blocker and continue only after `git diff --check` passes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "feat(auth): add durable credential status policy"
```

## Task 5: Enforce Revoke Authority In Active Android Sessions

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Write source-contract tests for active-session enforcement points**

Append this test to `DurableDeviceAuthRecoveryPolicyTest`:

```kotlin
@Test
fun `authenticated session publication validates durable credential status`() {
    val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
    val authenticatedBranchStart = source.indexOf("is SessionStatus.Authenticated ->")
    val notAuthenticatedBranchStart = source.indexOf("is SessionStatus.NotAuthenticated ->")
    val authenticatedBranch = source.substring(authenticatedBranchStart, notAuthenticatedBranchStart)

    assertTrue(authenticatedBranch.contains("enforceDurableCredentialStillActive()"))
    assertTrue(authenticatedBranch.indexOf("enforceDurableCredentialStillActive()") < authenticatedBranch.indexOf("publishAuthenticatedUser("))
}
```

Append this test to `DurableDeviceAuthRecoveryPolicyTest`:

```kotlin
@Test
fun `refresh token recovery validates durable credential status before refreshing`() {
    val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
    val refreshStart = source.indexOf("suspend fun refreshSessionIfJwtExpired")
    val refreshEnd = source.indexOf("private suspend fun clearLocalAuthStateAfterAuthoritativeDurableRejection")
    val refreshBody = source.substring(refreshStart, refreshEnd)

    assertTrue(refreshBody.contains("enforceDurableCredentialStillActive()"))
    assertTrue(refreshBody.indexOf("enforceDurableCredentialStillActive()") < refreshBody.indexOf("auth.refreshCurrentSession()"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: FAIL because `enforceDurableCredentialStillActive()` is not called from those paths yet.

- [ ] **Step 3: Enforce status before publishing authenticated users**

In `observeSessionStatus()`, update the `SessionStatus.Authenticated` branch so the first lines inside `if (user != null)` are:

```kotlin
val hasDurableCredential = durableDeviceCredentialStore.snapshot().isComplete
if (hasDurableCredential) {
    enforceDurableCredentialStillActive()
    if (_authState.value is AuthState.SessionLost) return@collect
}
```

Keep the existing `shouldDiscardAuthenticatedSupabaseSessionForDurableRecovery(...)` call after this block, reusing `hasDurableCredential`.

- [ ] **Step 4: Enforce status before refresh-token recovery**

In `refreshSessionIfJwtExpired()`, insert this block immediately after `val credential = durableDeviceCredentialStore.snapshot()`:

```kotlin
if (credential.isComplete) {
    enforceDurableCredentialStillActive()
    if (_authState.value is AuthState.SessionLost) return false
}
```

- [ ] **Step 5: Enforce status before silent refresh recovery**

In `attemptSilentSessionRecovery()`, insert this block immediately after `val credential = durableDeviceCredentialStore.snapshot()` inside the retry loop:

```kotlin
if (credential.isComplete) {
    enforceDurableCredentialStillActive()
    if (_authState.value is AuthState.SessionLost) return
}
```

- [ ] **Step 6: Run focused policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If blocked by benchmark transport compile errors, record that no auth tests executed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "fix(auth): enforce durable revoke on active sessions"
```

## Task 6: Make Logout Revoke Retryable And Independent From Supabase Session

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Write source-contract test for pending revoke ordering**

Append this test to `DurableDeviceAuthRecoveryPolicyTest`:

```kotlin
@Test
fun `manual sign out saves pending revoke before remote revoke and local clear`() {
    val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
    val signOutStart = source.indexOf("suspend fun signOut()")
    val signOutEnd = source.indexOf("fun clearEffectiveUserIdCache()", startIndex = signOutStart)
    val signOutBody = source.substring(signOutStart, signOutEnd)

    assertTrue(signOutBody.contains("prepareDurableCredentialRevokeForLogout()"))
    assertTrue(signOutBody.contains("revokePendingDurableCredentialIfPresent()"))
    assertTrue(signOutBody.indexOf("prepareDurableCredentialRevokeForLogout()") < signOutBody.indexOf("revokePendingDurableCredentialIfPresent()"))
    assertTrue(signOutBody.indexOf("revokePendingDurableCredentialIfPresent()") < signOutBody.indexOf("durableDeviceCredentialStore.clear()"))
}
```

Append this test to `DurableDeviceAuthRecoveryPolicyTest`:

```kotlin
@Test
fun `startup retries pending durable credential revoke`() {
    val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
    val initStart = source.indexOf("init {")
    val initEnd = source.indexOf("private fun observeSessionStatus()", startIndex = initStart)
    val initBody = source.substring(initStart, initEnd)

    assertTrue(initBody.contains("retryPendingDurableCredentialRevoke()"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: FAIL because the pending revoke methods are not wired into `AuthManager`.

- [ ] **Step 3: Add logout preparation method**

Add this private method inside `AuthManager`:

```kotlin
private suspend fun prepareDurableCredentialRevokeForLogout() {
    val credential = durableDeviceCredentialStore.snapshot()
    if (!credential.isComplete) return
    durableDeviceCredentialStore.savePendingRevoke(
        devicePublicId = credential.devicePublicId.orEmpty(),
        deviceSecret = credential.deviceSecret.orEmpty()
    )
}
```

- [ ] **Step 4: Add pending revoke method**

Replace `revokeDurableDeviceCredentialIfPresent()` with:

```kotlin
private suspend fun revokePendingDurableCredentialIfPresent() {
    val pending = durableDeviceCredentialStore.pendingRevokeSnapshot()
    if (!pending.isComplete) return

    val response = callDurableCredentialSelfService(
        devicePublicId = pending.devicePublicId.orEmpty(),
        deviceSecret = pending.deviceSecret.orEmpty(),
        action = "revoke"
    )
    if (response.revoked || response.status.equals("revoked", ignoreCase = true)) {
        durableDeviceCredentialStore.clearPendingRevoke()
    }
}
```

- [ ] **Step 5: Add startup retry method**

Add this private method inside `AuthManager`:

```kotlin
private fun retryPendingDurableCredentialRevoke() {
    scope.launch {
        try {
            revokePendingDurableCredentialIfPresent()
        } catch (e: Exception) {
            Log.w(TAG, "Pending durable credential revoke retry failed", e)
        }
    }
}
```

- [ ] **Step 6: Wire pending revoke into init and signOut**

Update `init`:

```kotlin
init {
    retryPendingDurableCredentialRevoke()
    observeSessionStatus()
}
```

Update the `handleManualSignOut(...)` call in `signOut()`:

```kotlin
handleManualSignOut(
    resetLocalAccountState = {
        localAccountResetCoordinator.resetToSignedOutStockState()
    },
    clearPresenceMarker = {
        authPresenceDataStore.clear()
    },
    prepareDurableCredentialRevoke = {
        prepareDurableCredentialRevokeForLogout()
    },
    revokeDurableCredential = {
        revokePendingDurableCredentialIfPresent()
    },
    clearDurableCredential = {
        durableDeviceCredentialStore.clear()
    },
    clearSupabaseSession = {
        auth.signOut()
    }
)
```

- [ ] **Step 7: Update manual sign-out helper signature**

Change `handleManualSignOut` signature and body:

```kotlin
internal suspend fun handleManualSignOut(
    resetLocalAccountState: suspend () -> Unit,
    clearPresenceMarker: suspend () -> Unit,
    prepareDurableCredentialRevoke: suspend () -> Unit,
    revokeDurableCredential: suspend () -> Unit,
    clearDurableCredential: suspend () -> Unit,
    clearSupabaseSession: suspend () -> Unit
) {
    try {
        resetLocalAccountState()
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed resetting local account state on sign-out", clearError)
    }
    try {
        clearPresenceMarker()
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed clearing auth presence marker on sign-out", clearError)
    }
    try {
        prepareDurableCredentialRevoke()
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed preparing durable device credential revoke on sign-out", clearError)
    }
    try {
        revokeDurableCredential()
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed revoking durable device credential on sign-out", clearError)
    }
    try {
        clearDurableCredential()
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed clearing durable device credential on sign-out", clearError)
    }
    clearSupabaseSession()
}
```

- [ ] **Step 8: Update existing manual sign-out tests**

In existing `handleManualSignOut(...)` test calls, add:

```kotlin
prepareDurableCredentialRevoke = {},
```

In the ordering test, expect:

```kotlin
listOf(
    "reset-local-stock",
    "clear-presence",
    "prepare-durable-revoke",
    "revoke-durable-remote",
    "clear-durable-local",
    "clear-supabase-session"
)
```

Use:

```kotlin
prepareDurableCredentialRevoke = { events += "prepare-durable-revoke" },
```

- [ ] **Step 9: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest" --tests "com.nexio.tv.data.local.DurableDeviceCredentialStoreTest"
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If blocked, record the benchmark compile errors.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "fix(auth): retry durable credential revoke after logout"
```

## Task 7: Document Deployment And Run Verification

**Files:**
- Modify: `nexio-web/SETUP.md`
- Modify: `openspec/changes/enforce-durable-revoke-authority/tasks.md`

- [ ] **Step 1: Update deployment docs**

In `nexio-web/SETUP.md`, update the Edge Function list to include:

```markdown
{SUPABASE_URL}/functions/v1/device-credential-self-service
```

Update the deploy command block to include:

```bash
supabase functions deploy device-credential-self-service --no-verify-jwt
```

Update the explanatory paragraph to say:

```markdown
`device-credential-self-service` also uses `--no-verify-jwt` intentionally. It authenticates the device by hashing `device_public_id + device_secret` and comparing that proof against `device_credentials.credential_hash`; it does not trust the caller's Supabase JWT.
```

- [ ] **Step 2: Run OpenSpec validation**

Run:

```bash
openspec validate enforce-durable-revoke-authority --strict
```

Expected: PASS.

- [ ] **Step 3: Run Supabase function tests**

Run:

```bash
deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts
```

Expected: PASS.

- [ ] **Step 4: Run focused Android tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest" --tests "com.nexio.tv.data.local.DurableDeviceCredentialStoreTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If blocked by benchmark transport compile errors, capture the first six Kotlin errors and leave OpenSpec task `2.2` unchecked with the blocker note.

- [ ] **Step 5: Run release compile**

Run:

```bash
./gradlew :app:compileUniversalReleaseKotlin
```

Expected: PASS after unrelated benchmark transport compile errors are fixed. If blocked by benchmark transport compile errors, leave OpenSpec task `2.4` unchecked with the blocker note.

- [ ] **Step 6: Update OpenSpec tasks**

If verification passes, update `openspec/changes/enforce-durable-revoke-authority/tasks.md`:

```markdown
## 2. Verification

- [x] 2.1 Run `deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts`.
- [x] 2.2 Run focused Android auth and durable credential store tests.
- [x] 2.3 Run `openspec validate enforce-durable-revoke-authority --strict`.
- [x] 2.4 Run `./gradlew :app:compileUniversalReleaseKotlin`.
```

If Android Gradle verification is blocked by the existing benchmark transport compile errors, update only the verified lines:

```markdown
## 2. Verification

- [x] 2.1 Run `deno test --allow-env --allow-net supabase/functions/tests/device-auth.test.ts`.
- [ ] 2.2 Run focused Android auth and durable credential store tests. Blocked before test execution by unrelated benchmark transport compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
- [x] 2.3 Run `openspec validate enforce-durable-revoke-authority --strict`.
- [ ] 2.4 Run `./gradlew :app:compileUniversalReleaseKotlin`. Blocked by unrelated benchmark transport compile errors in `app/src/main/java/com/nexio/tv/data/integration/benchmark/transport/`.
```

- [ ] **Step 7: Commit**

```bash
git add nexio-web/SETUP.md openspec/changes/enforce-durable-revoke-authority/tasks.md
git commit -m "docs(auth): document durable credential self service deploy"
```

## Self-Review

- Spec coverage: The plan covers active-session remote revoke enforcement, local stock reset on revoked status, online logout revoke, offline logout pending retry, and deployment documentation.
- Placeholder scan: No task relies on placeholder code; each code-changing step includes concrete code snippets and exact file paths.
- Type consistency: The plan consistently uses `DurableCredentialRemoteStatus`, `DurableCredentialStatusAction`, `DurableCredentialSelfServiceResponse`, `savePendingRevoke`, `pendingRevokeSnapshot`, `clearPendingRevoke`, `prepareDurableCredentialRevokeForLogout`, and `revokePendingDurableCredentialIfPresent`.
- Risk callout: Android Gradle verification is expected to remain blocked until the unrelated benchmark transport compile errors are fixed; the plan explicitly records that blocker instead of hiding it.
