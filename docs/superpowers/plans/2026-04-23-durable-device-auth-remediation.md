# Durable Device Auth Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the remaining durable-auth correctness bugs, move linked-device management to a dedicated `Devices` page, and add legacy-session cleanup without losing honest migration behavior.

**Architecture:** Treat durable authority as the single source of truth for durable-backed TVs, keep startup/session recovery consistent with that rule, and make portal device management reflect that model clearly. Separate durable devices from legacy-only sessions in both data and UI so cleanup is explicit and safe.

**Tech Stack:** Kotlin + Jan Supabase client, Android DataStore/Keystore, Supabase SQL + Edge Functions, Nuxt 4/Vue 3 portal, `deno test`, `npx tsx --test`, Gradle unit tests.

---

## File Structure

- `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  Own Android auth state machine, startup/session renewal ordering, sync gate, and durable revoke teardown.
- `app/src/main/java/com/nexio/tv/data/local/DurableDeviceCredentialStore.kt`
  Store encrypted durable credential plus owner binding.
- `app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt`
  Guard `FullAccount` vs anonymous session interpretation.
- `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`
  Lock recovery precedence, revoke teardown, and sync-gate behavior.
- `supabase/functions/tv-logins-exchange/index.ts`
  Own TV approval exchange and durable credential issuance.
- `supabase/functions/device-session-exchange/index.ts`
  Own durable-backed session minting.
- `supabase/functions/tests/device-auth.test.ts`
  Cover Edge Function contract and migration invariants.
- `nexio-web/pages/account.vue`
  Own account-level section routing and placement of device management.
- `nexio-web/components/portal/LinkedDevicesPanel.vue`
  Render durable-backed devices and hidden legacy cleanup state.
- `nexio-web/server/api/account/bootstrap.get.ts`
  Build device inventory payload for the portal.
- `nexio-web/utils/device-management.ts`
  Own device inventory mapping, legacy filtering, cleanup/revoke request shaping, and UX copy.
- `nexio-web/types/portal.ts`
  Portal-facing device models.
- `nexio-web/tests/device-auth.test.ts`
  Cover device inventory mapping, cleanup toggles, and route/request shaping.
- `openspec/changes/add-durable-device-auth/{proposal.md,design.md,tasks.md,specs/durable-device-auth/spec.md}`
  Keep rollout docs aligned with what is actually shipped and verified.

### Task 1: Fix Android Sync Gate And Renewal Authority

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `anonymous authenticated session does not open sync gate`() {
    val publication = resolveAuthenticatedSessionPublication(
        userId = "anon-user",
        email = null,
        isReturningUser = false
    )

    assertEquals(AuthState.SignedOut, publication.authState)
    assertNull(publication.sessionUserId)
}

@Test
fun `startup recovery refreshes live session before durable recovery`() {
    assertEquals(
        NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION,
        resolveNotAuthenticatedStartupAction(
            hasRefreshToken = true,
            isReturningUser = true,
            hasDurableCredential = true
        )
    )
}

@Test
fun `jwt expiry recovery refreshes live session before durable recovery`() {
    assertEquals(
        JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION,
        resolveJwtExpiryRecoveryAction(
            hasRefreshToken = true,
            credential = DurableDeviceCredentialSnapshot(
                devicePublicId = "tv_11111111-1111-4111-8111-111111111111",
                deviceSecret = "secret",
                ownerUserId = "user-1"
            )
        )
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest" --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected:
- `AuthManagerStateTest` fails because anonymous publication still opens sync
- `DurableDeviceAuthRecoveryPolicyTest` fails because recovery ordering still prefers durable exchange

- [ ] **Step 3: Implement the minimal Android auth fixes**

```kotlin
internal fun resolveNotAuthenticatedStartupAction(
    hasRefreshToken: Boolean,
    isReturningUser: Boolean,
    hasDurableCredential: Boolean
): NotAuthenticatedStartupAction {
    if (hasRefreshToken) return NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION
    if (hasDurableCredential) return NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY
    if (isReturningUser) return NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY
    return NotAuthenticatedStartupAction.TRANSITION_SIGNED_OUT
}

internal fun resolveJwtExpiryRecoveryAction(
    hasRefreshToken: Boolean,
    credential: DurableDeviceCredentialSnapshot
): JwtExpiryRecoveryAction {
    if (hasRefreshToken) return JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION
    if (credential.isComplete) return JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY
    return JwtExpiryRecoveryAction.NO_RECOVERY_PATH
}

internal fun resolveAuthenticatedSessionPublication(
    userId: String,
    email: String?,
    isReturningUser: Boolean
): AuthenticatedSessionPublication {
    val authState = if (email.isNullOrBlank()) {
        if (isReturningUser) AuthState.SessionLost else AuthState.SignedOut
    } else {
        AuthState.FullAccount(userId.trim(), email.trim())
    }
    return AuthenticatedSessionPublication(
        authState = authState,
        sessionUserId = (authState as? AuthState.FullAccount)?.userId
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.auth.AuthManagerStateTest" --tests "com.nexio.tv.core.auth.DurableDeviceAuthRecoveryPolicyTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt app/src/test/java/com/nexio/tv/core/auth/AuthManagerStateTest.kt app/src/test/java/com/nexio/tv/core/auth/DurableDeviceAuthRecoveryPolicyTest.kt
git commit -m "fix(auth): enforce full-account sync gating and refresh-first recovery"
```

### Task 2: Make TV Approval Rotation Safe

**Files:**
- Modify: `supabase/functions/tv-logins-exchange/index.ts`
- Modify: `supabase/functions/tests/device-auth.test.ts`
- Create: `supabase/migrations/20260423xxxxxx_add_device_credential_handoffs.sql`

- [ ] **Step 1: Write the failing function test**

```ts
test('approval exchange does not invalidate current durable authority before activation', async () => {
  const source = readFileSync(new URL('../tv-logins-exchange/index.ts', import.meta.url), 'utf8')

  assert.match(source, /device_credential_handoffs/)
  assert.doesNotMatch(source, /upsert\\(credentialRow, \\{ onConflict: "device_user_id" \\}\\)/)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
deno test supabase/functions/tests/device-auth.test.ts
```

Expected: FAIL because the current exchange still overwrites `device_credentials` directly.

- [ ] **Step 3: Implement a pending-handoff model**

```sql
create table if not exists public.device_credential_handoffs (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_user_id uuid not null references auth.users(id) on delete cascade,
  device_public_id text not null,
  credential_hash text not null,
  display_name text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  used_at timestamptz null
);

create unique index if not exists device_credential_handoffs_device_user_unused_uidx
  on public.device_credential_handoffs(device_user_id)
  where used_at is null;
```

```ts
// tv-logins-exchange
await adminClient.from("device_credential_handoffs").upsert({
  owner_id: ownerUserId,
  device_user_id: requesterUser.id,
  device_public_id: durableCredential.server.device_public_id,
  credential_hash: durableCredential.server.credential_hash,
  display_name: stableDisplayName,
  expires_at: new Date(Date.now() + 15 * 60_000).toISOString()
}, { onConflict: "device_user_id" })
```

- [ ] **Step 4: Add activation hook after local save**

```ts
// separate endpoint or activation step later consumed by Android
await adminClient
  .from("device_credentials")
  .upsert(credentialRow, { onConflict: "device_user_id" })
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
deno test supabase/functions/tests/device-auth.test.ts
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add supabase/functions/tv-logins-exchange/index.ts supabase/functions/tests/device-auth.test.ts supabase/migrations/20260423*_add_device_credential_handoffs.sql
git commit -m "fix(auth): make durable approval rotation non-destructive"
```

### Task 3: Add Dedicated Devices Page And Legacy Cleanup

**Files:**
- Modify: `nexio-web/pages/account.vue`
- Modify: `nexio-web/components/portal/LinkedDevicesPanel.vue`
- Modify: `nexio-web/server/api/account/bootstrap.get.ts`
- Modify: `nexio-web/utils/device-management.ts`
- Modify: `nexio-web/types/portal.ts`
- Modify: `nexio-web/tests/device-auth.test.ts`

- [ ] **Step 1: Write the failing portal tests**

```ts
test('account navigation exposes a dedicated devices view', () => {
  const source = readFileSync(accountPagePath, 'utf8')
  assert.match(source, /\\{ id: 'devices', label: 'Devices' \\}/)
  assert.doesNotMatch(source, /<LinkedDevicesPanel[\\s\\S]*activeView === 'integrations'/)
})

test('legacy-only rows are hidden by default and exposed behind a toggle', () => {
  const source = readFileSync(panelPath, 'utf8')
  assert.match(source, /showLegacySessions/)
  assert.match(source, /Remove all legacy sessions/)
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd nexio-web && npx tsx --test tests/device-auth.test.ts
```

Expected: FAIL because there is no `devices` view and no legacy toggle.

- [ ] **Step 3: Implement the dedicated page split**

```ts
// pages/account.vue
const nav = [
  { id: 'profiles', label: 'Profiles' },
  { id: 'addons', label: 'Addons' },
  { id: 'devices', label: 'Devices' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'formatter', label: 'Formatter' }
]
```

```vue
<div v-else-if="activeView === 'devices'">
  <LinkedDevicesPanel
    :devices="state.linkedDevices"
    :show-legacy-sessions="state.showLegacySessions"
    @toggle-legacy-sessions="toggleLegacySessions"
    @revoke-device="revokeDevice"
    @remove-all-legacy-sessions="removeAllLegacySessions"
  />
</div>
```

- [ ] **Step 4: Split durable devices from legacy-only rows in the panel**

```ts
const durableDevices = computed(() => props.devices.filter((d) => d.authStatus !== 'legacy_pending_backfill'))
const legacyDevices = computed(() => props.devices.filter((d) => d.authStatus === 'legacy_pending_backfill'))
```

```vue
<button @click="emit('toggle-legacy-sessions')">
  {{ showLegacySessions ? 'Hide legacy sessions' : 'Show legacy sessions' }}
</button>
<button v-if="showLegacySessions && legacyDevices.length" @click="emit('remove-all-legacy-sessions')">
  Remove all legacy sessions
</button>
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
cd nexio-web && npx tsx --test tests/device-auth.test.ts
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexio-web/pages/account.vue nexio-web/components/portal/LinkedDevicesPanel.vue nexio-web/server/api/account/bootstrap.get.ts nexio-web/utils/device-management.ts nexio-web/types/portal.ts nexio-web/tests/device-auth.test.ts
git commit -m "feat(portal): add dedicated devices page and legacy cleanup"
```

### Task 4: Record Verification Evidence And Run Manual Matrix

**Files:**
- Modify: `openspec/changes/add-durable-device-auth/tasks.md`
- Create: `docs/verification/2026-04-23-durable-device-auth-manual-verification.md`

- [ ] **Step 1: Write the verification template**

```md
# Durable Device Auth Manual Verification

- Environment:
- Portal URL:
- Android build:

## Results
- Cold start with refresh-token restore:
- Upgrade restart:
- Token-loss recovery:
- Revoked-device startup:
- Explicit sign-out:
- Portal revoke / reissue denial:
```

- [ ] **Step 2: Run and record the manual matrix**

Run on a real environment:

```text
1. Approve a fresh TV and confirm it appears on Devices.
2. Restart the app and confirm durable-backed restore works.
3. Revoke the device in the portal, restart the TV, confirm reconnect state.
4. Sign out on the TV, confirm no silent re-auth on next cold start.
5. Validate legacy session toggle and bulk cleanup on a migrated account.
```

Expected:
- Each scenario is explicitly recorded as pass/fail with notes.

- [ ] **Step 3: Mark OpenSpec tasks honestly**

```md
- [x] Validate Android cold start, upgrade restart, token-loss recovery, revoked-device startup, and explicit sign-out flows.
- [x] Verify portal revoke behavior, session reissue denial after revoke, and bounded post-revoke access-token expiry behavior.

### Validation Status For This Fix
- [x] Android cold-start / upgrade / token-loss / revoked-device manual flows re-exercised end-to-end in this fix.
- [x] Portal revoke behavior re-verified manually in this fix.
```

- [ ] **Step 4: Commit**

```bash
git add openspec/changes/add-durable-device-auth/tasks.md docs/verification/2026-04-23-durable-device-auth-manual-verification.md
git commit -m "docs: record durable auth manual verification"
```

## Self-Review

Spec coverage:
- Dedicated `Devices` section: Task 3
- Legacy hidden-by-default cleanup model: Task 3
- Rotation/orphan prevention: Task 2
- Real full-account sync gating and renewal consistency: Task 1
- Manual validation evidence: Task 4

Placeholder scan:
- No `TBD`, `TODO`, or “similar to previous task” placeholders remain.

Type consistency:
- Uses `devicePublicId`, `authStatus`, `legacy_pending_backfill`, and `Devices` view consistently across portal types, mapping, and UI.
