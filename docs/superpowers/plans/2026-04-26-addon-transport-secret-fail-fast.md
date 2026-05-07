# Addon Transport Secret Invariant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce the invariant that every persisted addon has a populated `transport_secret_ref` pointing at a `manifest_suffix_v1` vault row whose `suffix` is exactly the path the user supplied (which must include `/manifest.json`). Reject anything that violates this at install time on the web; fail loudly on the Android side if it ever happens despite the invariant.

**Architecture:**
- **Web (prevention):** `parseAddonInstallUrl` rejects URLs whose path does not end with `/manifest.json` — no silent `/manifest.json` appending, no defaulting bare origins. The persist flow writes addon rows + their secret rows atomically (single ordered API sequence with rollback on partial failure).
- **Android (visibility):** `resolveRemoteAddonUrl` validates that v2 addons get a populated `manifest_suffix_v1` payload and v1 addons get populated `params`/`pathSegment`. Anything else throws inside the existing `runCatching` so the addon is dropped and the failure is logged with addon URL + secret ref.
- **No SQL changes.** `service_resolve_account_secret` continues to return `'{}'::jsonb` on missing rows. The web invariant means missing rows shouldn't happen in steady state; the Kotlin validators surface invariant violations when they do, without changing the contract for the seven other web/Android callers of the resolver.

**Tech Stack:** TypeScript/Nuxt server routes, Vitest for web tests; Kotlin/Android, kotlinx-serialization, supabase-kt postgrest, JUnit4 + Robolectric + MockK for Android tests.

**Why this matches your model:** Every addon, by definition, has at least `/manifest.json` as its suffix — that's the minimum valid Stremio install URL. The `transport_secret_payload.suffix` is always populated on install. Anything else is a data-integrity violation. The plan enforces that on the way in (web) and crashes on the way out (Android) instead of silently downgrading.

---

## File Structure

**Web — install validation + atomic persist:**
- Modify: `nexio-web/server/utils/account-secrets.ts:114-125` — replace `splitAddonTransportUrl`'s auto-completion with strict validation (reject paths that do not end with `/manifest.json`).
- Modify: `nexio-web/server/utils/account-secrets.ts:164-176` — `parseAddonInstallUrl` propagates the strict-validation rejection (already throws on blank URL via `createError({statusCode:400})`; extend the same pattern).
- Modify: `nexio-web/utils/account-secrets.ts` (the parallel client-side copy) — same rejection rules.
- Modify: `nexio-web/server/api/account/persist.post.ts` — fold secret persistence into the same request so addon-rows + their secrets land atomically (or order them so secrets are written before the addon rows that reference them, with explicit rollback on partial failure).
- Test: `nexio-web/tests/account-secrets.test.ts` — extend with strict-validation cases.
- Test: `nexio-web/server/tests/account-persist.test.ts` (create if absent, follow existing test runner convention).

**Android — defensive validators + call-site changes:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — add two extension helpers on `AccountAddonSecretPayload?`: `requireValidV2Transport(secretRef, addonUrl)` and `requireValidV1Secret(secretRef, addonUrl)`.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt:182-225` — `resolveRemoteAddonUrl` chains the validators after the decode.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:1783-1826` — mirror the change in the duplicate function.
- Test: `app/src/test/java/com/nexio/tv/data/remote/supabase/AccountAddonSecretPayloadValidatorsTest.kt` — pure-JUnit tests for the two new validators.

**Verification:**
- Build + unit test suite via Gradle.
- On-device manual verification against the Fire TV at 192.168.50.98.

---

## Task 1: Web — strict URL validation in `parseAddonInstallUrl`

**Why:** Today the codec auto-appends `/manifest.json` for paths that lack it and defaults to a bare `/manifest.json` for empty paths. Both behaviors mask invalid addon URLs and persist broken state. The fix: any URL whose path does not already end with `/manifest.json` is invalid and must be rejected.

**Files:**
- Modify: `nexio-web/server/utils/account-secrets.ts:114-176`
- Modify: `nexio-web/utils/account-secrets.ts` (matching client-side copy)
- Test: `nexio-web/tests/account-secrets.test.ts`

- [ ] **Step 1: Read both copies of the codec to confirm they are in sync**

```bash
diff -u nexio-web/server/utils/account-secrets.ts nexio-web/utils/account-secrets.ts | head -80
```

Expected: small differences (the client copy may not import `createError` etc.) but `splitAddonTransportUrl` and `parseAddonInstallUrl` should have parallel logic. Note the differences so step 4 mirrors them correctly.

- [ ] **Step 2: Write the failing test cases**

Open `nexio-web/tests/account-secrets.test.ts` and add (alongside the existing tests):

```typescript
import { parseAddonInstallUrl } from '~/server/utils/account-secrets'
import { describe, expect, it } from 'vitest'

describe('parseAddonInstallUrl strict validation', () => {
  it('rejects bare origin without a path', () => {
    expect(() => parseAddonInstallUrl('https://torrentio.strem.fun/'))
      .toThrowError(/must include \/manifest\.json/i)
  })

  it('rejects path that does not end with /manifest.json', () => {
    expect(() => parseAddonInstallUrl('https://torrentio.strem.fun/configure'))
      .toThrowError(/must include \/manifest\.json/i)
  })

  it('rejects path that contains manifest.json mid-path', () => {
    // /manifest.json/extra is not a valid Stremio install URL.
    expect(() => parseAddonInstallUrl('https://torrentio.strem.fun/manifest.json/extra'))
      .toThrowError(/must include \/manifest\.json/i)
  })

  it('accepts a bare manifest URL', () => {
    const parsed = parseAddonInstallUrl('https://cinemeta.strem.io/manifest.json')

    expect(parsed.addon.transportBaseUrl).toBe('https://cinemeta.strem.io')
    expect(parsed.transportSecretPayload.kind).toBe('manifest_suffix_v1')
    expect(parsed.transportSecretPayload.suffix).toBe('/manifest.json')
  })

  it('accepts a configured manifest URL with a secret-bearing path segment', () => {
    const parsed = parseAddonInstallUrl(
      'https://torrentio.strem.fun/debridoptions=nodownloadlinks%7Crealdebrid=KEY/manifest.json'
    )

    expect(parsed.addon.transportBaseUrl).toBe('https://torrentio.strem.fun')
    expect(parsed.transportSecretPayload.kind).toBe('manifest_suffix_v1')
    expect(parsed.transportSecretPayload.suffix).toBe(
      '/debridoptions=nodownloadlinks%7Crealdebrid=KEY/manifest.json'
    )
  })
})
```

If the existing test file imports differently (e.g. raw Node `node:test`), match that style — do not unilaterally migrate to Vitest.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd nexio-web && npm test -- tests/account-secrets.test.ts 2>&1 | tail -40
```

Expected: the four new tests fail (the rejection tests pass through, the acceptance tests probably already pass).

- [ ] **Step 4: Replace `splitAddonTransportUrl` with strict validation**

In `nexio-web/server/utils/account-secrets.ts`, replace lines 114-125 with:

```typescript
function splitAddonTransportUrl(rawUrl: string): { baseUrl: string; suffix: string } {
  const parsed = new URL(rawUrl.trim())
  // Strict invariant: every Stremio addon install URL ends with /manifest.json.
  // We do not auto-append or auto-default here — a bare origin or a path
  // without /manifest.json indicates the user supplied something that is not
  // an install URL, and the install must fail rather than silently persist a
  // broken transport suffix.
  const path = parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : ''
  if (!path.toLowerCase().endsWith('/manifest.json')) {
    throw createError({
      statusCode: 400,
      statusMessage: 'Addon URL must include /manifest.json — paste the install URL exactly as your addon provided it.'
    })
  }
  return {
    baseUrl: parsed.origin,
    suffix: `${path}${parsed.search}`
  }
}
```

The shape and return value are unchanged; the logic now rejects instead of synthesizing. `parseAddonInstallUrl` already calls this and propagates the throw to the install endpoint, which already maps to HTTP 400.

In `nexio-web/utils/account-secrets.ts` (client-side copy), apply the same change. If `createError` isn't available client-side, throw a plain `Error` with the same message — server-side and client-side validators must agree on the rejection rule.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd nexio-web && npm test -- tests/account-secrets.test.ts 2>&1 | tail -40
```

Expected: all five new tests pass, plus all pre-existing tests in the file still pass.

- [ ] **Step 6: Commit**

```bash
git add nexio-web/server/utils/account-secrets.ts \
        nexio-web/utils/account-secrets.ts \
        nexio-web/tests/account-secrets.test.ts
git commit -m "fix(web): reject addon URLs without /manifest.json

splitAddonTransportUrl previously appended /manifest.json silently for paths
that lacked it and defaulted to a bare /manifest.json for empty paths. Both
behaviors masked invalid install URLs and persisted broken state — a stale
transport_secret_payload that no longer matched the user's actual addon URL.

Reject these inputs at install time so users see a clear 400 instead of
discovering later (silently degraded streams, missing addons, etc.) that
their addon was persisted with a wrong suffix."
```

---

## Task 2: Web — atomic addon row + secret persistence in `persist.post.ts`

**Why:** Today `nexio-web/server/api/account/persist.post.ts` writes addon rows via `sync_push_account_addons` and secrets get written separately via `nexio-web/server/api/account/secrets/set.post.ts`. If the second call is missed (network blip, frontend bug, page navigation), `account_addons_public.transport_secret_ref` is set but the corresponding `account_secrets` row never lands. That is one of the ways the broken state we observed gets created.

**Approach:** Persist the secrets BEFORE the addon-row replace, in the same request. The `delete + insert` of `sync_push_account_addons` is destructive (line 41 of `20260420231500_add_addon_transport_v2.sql`); we want the secrets to exist before we point any addon rows at them. If a secret write fails, we abort before touching `account_addons_public` and the user retries.

**Files:**
- Modify: `nexio-web/server/api/account/persist.post.ts`
- Test: `nexio-web/server/tests/account-persist.test.ts` (or extend an existing test file in the same directory)

- [ ] **Step 1: Audit the request shape and find the secrets endpoint contract**

```bash
sed -n '1,60p' nexio-web/server/api/account/secrets/set.post.ts
```

Note the request body shape — likely `{ secret_type, secret_ref, payload, masked_preview }`. The `parseAddonInstallUrl` output already provides `transportSecretRef`, `transportSecretPayload`, `secretRef`, `secretPayload`. Both are needed when present.

- [ ] **Step 2: Write the failing test**

Create `nexio-web/server/tests/account-persist.test.ts` (or extend a sibling test that already mocks `supabaseFetch`). Add a test that supplies an addon entry whose `transportSecretRef` is set but whose secret-set RPC throws — assert the addon-rows RPC is never reached:

```typescript
import { describe, expect, it, vi } from 'vitest'

vi.mock('~/server/utils/supabase', () => ({
  bearerToken: () => 'token',
  okJson: (body: unknown) => body,
  readJsonBody: vi.fn(),
  supabaseUser: vi.fn(),
  supabaseFetch: vi.fn()
}))

describe('account/persist atomicity', () => {
  it('does not call sync_push_account_addons when an addon secret write fails', async () => {
    const { readJsonBody, supabaseFetch } = await import('~/server/utils/supabase')

    ;(readJsonBody as any).mockResolvedValue({
      settings: {} as any,
      addons: [{
        url: 'https://torrentio.strem.fun',
        transportBaseUrl: 'https://torrentio.strem.fun',
        transportSecretRef: 'addon:torrentio_strem_fun:transport:abc',
        transportSecretPayload: { kind: 'manifest_suffix_v1', suffix: '/manifest.json' },
        transportSchemaVersion: 2,
        parserPreset: 'TORRENTIO',
        enabled: true,
        sortOrder: 0
      }]
    })

    ;(supabaseFetch as any).mockImplementation(async (path: string) => {
      if (path === '/rest/v1/rpc/sync_set_account_secret') throw new Error('vault unavailable')
      throw new Error(`unexpected supabaseFetch call: ${path}`)
    })

    const handler = (await import('~/server/api/account/persist.post')).default
    await expect(handler({ event: 'fake' } as any)).rejects.toThrowError('vault unavailable')

    const calls = (supabaseFetch as any).mock.calls.map((c: unknown[]) => c[0])
    expect(calls).toContain('/rest/v1/rpc/sync_set_account_secret')
    expect(calls).not.toContain('/rest/v1/rpc/sync_push_account_addons')
  })
})
```

If the existing test infra differs, adapt this to it — the assertion is what matters.

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd nexio-web && npm test -- server/tests/account-persist.test.ts 2>&1 | tail -30
```

Expected: failure — currently the addon-rows RPC fires unconditionally; secret writes are not orchestrated from this endpoint.

- [ ] **Step 4: Reorder persistence**

Modify `nexio-web/server/api/account/persist.post.ts` so the addon list is iterated FIRST to write any `transportSecretPayload` and `secretPayload` via `sync_set_account_secret`, then the addon rows via `sync_push_account_addons`. Replace the body of the handler (after `await supabaseUser(event)`) with:

```typescript
  const changedPaths = uniquePortalSyncPaths(Array.isArray(body.changedPaths) ? body.changedPaths : [])
  const baseRevision = typeof body.baseRevision === 'number' && Number.isFinite(body.baseRevision) ? body.baseRevision : 0
  const incomingAddons = body.addons ?? []

  // Persist addon secrets BEFORE the destructive addon-row replace so that
  // every addon row we insert below points at a vault entry that already
  // exists. If any secret write fails we abort here and never touch
  // account_addons_public.
  for (const addon of incomingAddons) {
    if (addon.transportSecretRef && addon.transportSecretPayload) {
      await supabaseFetch('/rest/v1/rpc/sync_set_account_secret', {
        method: 'POST',
        body: JSON.stringify({
          p_secret_type: 'addon_credential',
          p_secret_ref: addon.transportSecretRef,
          p_secret_payload: addon.transportSecretPayload,
          p_masked_preview: null,
          p_source: 'web'
        })
      }, token)
    }
    if (addon.secretRef && addon.secretPayload) {
      await supabaseFetch('/rest/v1/rpc/sync_set_account_secret', {
        method: 'POST',
        body: JSON.stringify({
          p_secret_type: 'addon_credential',
          p_secret_ref: addon.secretRef,
          p_secret_payload: addon.secretPayload,
          p_masked_preview: null,
          p_source: 'web'
        })
      }, token)
    }
  }

  const addonsResult = await supabaseFetch<RpcMutationResult>('/rest/v1/rpc/sync_push_account_addons', {
    method: 'POST',
    body: JSON.stringify({
      p_addons: incomingAddons.map((addon, index) => ({
        url: normalizeAddonUrl(addon.url),
        manifest_url: normalizeAddonManifestUrl(addon.url, addon.manifestUrl),
        parser_preset: addon.parserPreset ?? 'GENERIC',
        name: addon.name,
        description: addon.description ?? null,
        enabled: addon.enabled,
        public_query_params: addon.publicQueryParams ?? {},
        install_kind: addon.installKind ?? 'manifest',
        secret_ref: addon.secretRef ?? null,
        transport_schema_version: addon.transportSchemaVersion ?? 1,
        transport_base_url: addon.transportBaseUrl ?? null,
        transport_secret_ref: addon.transportSecretRef ?? null,
        sort_order: index
      })),
      p_source: 'web'
    })
  }, token)

  // (settings persistence + return value unchanged from the existing implementation)
```

Verify the `AddonRecord` type in `~/types/portal` carries `transportSecretPayload` and `secretPayload` (the pre-encoded objects produced by `parseAddonInstallUrl`). If it does not, add them to the type so the persist endpoint can read them. Do not invent new persistence shapes — reuse what `parseAddonInstallUrl` already returns.

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd nexio-web && npm test 2>&1 | tail -30
```

Expected: the new atomicity test passes, and any pre-existing persist tests still pass.

- [ ] **Step 6: Manually verify the request flow against the install UI**

Run the dev server and exercise an addon install end-to-end:

```bash
cd nexio-web && npm run dev
```

Install a Torrentio addon with the working URL. Inspect the DevTools network panel — confirm `sync_set_account_secret` fires before `sync_push_account_addons` and both succeed. Then manually delete the `account_secrets` row in Supabase (via the SQL editor) to simulate the broken state and confirm a re-install recreates it cleanly.

- [ ] **Step 7: Commit**

```bash
git add nexio-web/server/api/account/persist.post.ts \
        nexio-web/server/tests/account-persist.test.ts
git commit -m "fix(web): persist addon secrets before sync_push_account_addons

Previously the persist endpoint only called sync_push_account_addons; the
secret-set RPC was a separate endpoint, leaving a window where addon rows
landed without their corresponding account_secrets entries. The Android
client then resolved the addon to an empty payload and silently downgraded
the URL.

Write all transportSecretPayload/secretPayload entries first; abort the
whole persist if any vault write fails. Once we reach the addon-rows RPC
every transport_secret_ref is guaranteed to point at an existing vault row."
```

---

## Task 3: Android — `AccountAddonSecretPayload` validator helpers + RED tests

**Why:** Centralize the "is this payload usable for v2/v1?" decision in pure functions that are easy to unit-test. The validators carry both the secret ref and the addon URL in their error messages so the dropped-addon log is actionable.

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/remote/supabase/AccountAddonSecretPayloadValidatorsTest.kt` (new)
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` (extend the file that already declares `data class AccountAddonSecretPayload(...)` around line 65)

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/data/remote/supabase/AccountAddonSecretPayloadValidatorsTest.kt`:

```kotlin
package com.nexio.tv.data.remote.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountAddonSecretPayloadValidatorsTest {

    @Test
    fun `requireValidV2Transport accepts manifest_suffix_v1 with non-blank suffix`() {
        val payload = AccountAddonSecretPayload(
            kind = "manifest_suffix_v1",
            suffix = "/debridoptions=nodownloadlinks%7Crealdebrid=KEY/manifest.json"
        )

        val resolved = payload.requireValidV2Transport(
            secretRef = "addon:torrentio_strem_fun:transport:abc",
            addonUrl = "https://torrentio.strem.fun"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV2Transport throws on null payload`() {
        val error = assertThrows(IllegalStateException::class.java) {
            (null as AccountAddonSecretPayload?).requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("addon:torrentio_strem_fun:transport:abc"))
        assertEquals(true, error.message?.contains("https://torrentio.strem.fun"))
        assertEquals(true, error.message?.contains("invariant"))
    }

    @Test
    fun `requireValidV2Transport throws when kind is not manifest_suffix_v1`() {
        val payload = AccountAddonSecretPayload(kind = "query_params")

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("kind=query_params"))
    }

    @Test
    fun `requireValidV2Transport throws when suffix is blank`() {
        val payload = AccountAddonSecretPayload(kind = "manifest_suffix_v1", suffix = "  ")

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("blank suffix"))
    }

    @Test
    fun `requireValidV1Secret accepts payload with populated params`() {
        val payload = AccountAddonSecretPayload(
            kind = "query_params",
            params = mapOf("token" to "deadbeef")
        )

        val resolved = payload.requireValidV1Secret(
            secretRef = "addon:legacy_addon",
            addonUrl = "https://legacy.example.com"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV1Secret accepts payload with non-blank pathSegment`() {
        val payload = AccountAddonSecretPayload(
            kind = "path_segment",
            pathSegment = "abc-123-def"
        )

        val resolved = payload.requireValidV1Secret(
            secretRef = "addon:legacy_addon",
            addonUrl = "https://legacy.example.com"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV1Secret throws on null payload`() {
        val error = assertThrows(IllegalStateException::class.java) {
            (null as AccountAddonSecretPayload?).requireValidV1Secret(
                secretRef = "addon:legacy_addon",
                addonUrl = "https://legacy.example.com"
            )
        }
        assertEquals(true, error.message?.contains("invariant"))
    }

    @Test
    fun `requireValidV1Secret throws when both params and pathSegment are empty`() {
        val payload = AccountAddonSecretPayload()

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV1Secret(
                secretRef = "addon:legacy_addon",
                addonUrl = "https://legacy.example.com"
            )
        }
        assertEquals(true, error.message?.contains("empty"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.remote.supabase.AccountAddonSecretPayloadValidatorsTest"
```

Expected: compilation failure — `requireValidV2Transport` and `requireValidV1Secret` don't exist yet.

- [ ] **Step 3: Add the validator helpers**

Open `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`. Find the existing `data class AccountAddonSecretPayload(...)` declaration (around line 65) and add immediately after the closing `)` of that data class:

```kotlin
/**
 * Validates that a payload retrieved from the Supabase vault is usable as a
 * v2 (transport_secret_ref) addon URL suffix. The web install path enforces
 * the invariant that every persisted v2 addon has a populated
 * manifest_suffix_v1 vault row whose suffix ends with /manifest.json. If we
 * see anything else here, that invariant has been violated upstream — we
 * fail loudly so the addon is dropped from the synced list and the user can
 * reinstall instead of silently degrading to the public manifest URL.
 */
internal fun AccountAddonSecretPayload?.requireValidV2Transport(
    secretRef: String,
    addonUrl: String
): AccountAddonSecretPayload {
    val payload = this
        ?: error(
            "transport-secret invariant violated for $addonUrl (ref=$secretRef): " +
                "vault returned an empty payload"
        )
    check(payload.kind == "manifest_suffix_v1") {
        "transport-secret invariant violated for $addonUrl (ref=$secretRef): " +
            "kind=${payload.kind}, expected manifest_suffix_v1"
    }
    check(!payload.suffix.isNullOrBlank()) {
        "transport-secret invariant violated for $addonUrl (ref=$secretRef): " +
            "blank suffix"
    }
    return payload
}

/**
 * Same contract as [requireValidV2Transport] but for legacy v1 addon secrets.
 * v1 secrets carry their data in [AccountAddonSecretPayload.params] (query
 * parameters) or [AccountAddonSecretPayload.pathSegment] (a single path
 * component embedding an API token). At least one of those must be populated.
 */
internal fun AccountAddonSecretPayload?.requireValidV1Secret(
    secretRef: String,
    addonUrl: String
): AccountAddonSecretPayload {
    val payload = this
        ?: error(
            "legacy-secret invariant violated for $addonUrl (ref=$secretRef): " +
                "vault returned an empty payload"
        )
    check(payload.params.isNotEmpty() || !payload.pathSegment.isNullOrBlank()) {
        "legacy-secret invariant violated for $addonUrl (ref=$secretRef): " +
            "empty params and pathSegment"
    }
    return payload
}
```

`error(...)` and `check(...)` both throw `IllegalStateException`, matching `assertThrows(IllegalStateException::class.java)` in the tests. The error messages include both `addonUrl` and `secretRef` so the dropped-addon log is grep-able.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.remote.supabase.AccountAddonSecretPayloadValidatorsTest"
```

Expected: all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt \
        app/src/test/java/com/nexio/tv/data/remote/supabase/AccountAddonSecretPayloadValidatorsTest.kt
git commit -m "feat(sync): add v2/v1 invariant validators for AccountAddonSecretPayload

Centralize the 'is this vault payload usable for resolving an addon URL?'
decision so the resolveRemoteAddonUrl call sites can fail loudly with a
log-grep-able error message that names both the addon URL and the secret
ref. The invariant is enforced on install in nexio-web; these validators
exist purely to surface invariant violations on the consumer side."
```

---

## Task 4: Android — `AddonSyncService.resolveRemoteAddonUrl` validates payloads

**Why:** This is the addon-sync code path. Wire the validators in so an addon whose vault row is missing or wrong-shaped fails inside `runCatching` and is dropped by `getRemoteAddonConfigs`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt:182-225`

**Note on testing:** The two RPC paths (`sync_pull_account_snapshot` for the snapshot, `sync_resolve_account_secret` for each secret) plus the supabase-kt `PostgrestResult` mocking surface make a clean Robolectric integration test costly relative to the coverage gain — Task 3 already proves the validators reject every shape we care about. We rely on Task 3's unit tests + Task 8's on-device verification for end-to-end coverage. If you want belt-and-suspenders, add a Robolectric test mirroring the existing `AddonSyncServiceTest` setup, but it is not required.

- [ ] **Step 1: Modify `resolveRemoteAddonUrl`**

Open `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt` and replace lines 182-225 with:

```kotlin
    private suspend fun resolveRemoteAddonUrl(addon: AccountAddonPayload): Result<String> {
        return runCatching {
            if (addon.transportSchemaVersion == 2 && !addon.transportSecretRef.isNullOrBlank()) {
                val transportPayload = withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_resolve_account_secret",
                        buildJsonObject {
                            put("p_secret_type", "addon_credential")
                            put("p_secret_ref", addon.transportSecretRef)
                            put("p_source", "app")
                        }
                    ).decodeAs<AccountAddonSecretPayload>()
                }.requireValidV2Transport(
                    secretRef = addon.transportSecretRef!!,
                    addonUrl = addon.url
                )
                return@runCatching buildResolvedAddonUrl(
                    baseUrl = addon.transportBaseUrl ?: addon.url,
                    manifestUrl = null,
                    publicQueryParams = emptyMap(),
                    secretPayload = transportPayload
                ).let(::normalizeAddonInstallUrl)
            }

            val secretPayload = addon.secretRef
                ?.takeIf { it.isNotBlank() }
                ?.let { secretRef ->
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_resolve_account_secret",
                            buildJsonObject {
                                put("p_secret_type", "addon_credential")
                                put("p_secret_ref", secretRef)
                                put("p_source", "app")
                            }
                        ).decodeAs<AccountAddonSecretPayload>()
                    }.requireValidV1Secret(
                        secretRef = secretRef,
                        addonUrl = addon.url
                    )
                }

            buildResolvedAddonUrl(
                baseUrl = addon.url,
                manifestUrl = addon.manifestUrl,
                publicQueryParams = addon.publicQueryParams,
                secretPayload = secretPayload
            ).let(::normalizeAddonInstallUrl)
        }
    }
```

Key changes vs the original:
- v2 branch: `.requireValidV2Transport(...)` chained after the decode. The `!!` on `addon.transportSecretRef` is safe because the surrounding `if` already gated on `!isNullOrBlank()`.
- v1 branch: `.requireValidV1Secret(...)` chained inside the `?.let { … }` lambda; the surrounding `?.takeIf { it.isNotBlank() }` guards against null/blank `secretRef`.
- `decodeAs<AccountAddonSecretPayload>()` (non-nullable) is preserved. When SQL returns `'{}'::jsonb`, kotlinx-serialization deserializes that into a default-constructed `AccountAddonSecretPayload` (kind=`query_params`, params=`{}`, pathSegment=null, suffix=null), which the validators then reject.

Add the imports at the top of the file if not already present:

```kotlin
import com.nexio.tv.data.remote.supabase.requireValidV1Secret
import com.nexio.tv.data.remote.supabase.requireValidV2Transport
```

- [ ] **Step 2: Run the existing test suite to verify nothing regressed**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.AddonSyncServiceTest"
```

Expected: all existing tests still pass. (They cover the SessionLost / not-syncing paths and don't exercise a populated snapshot.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt
git commit -m "fix(sync): drop v2/v1 addons whose vault payload violates the invariant

Apply requireValidV2Transport and requireValidV1Secret in
AddonSyncService.resolveRemoteAddonUrl so an addon whose vault row is
missing or wrong-shaped fails inside runCatching, gets logged by
getRemoteAddonConfigs, and is excluded from the synced addon list.

Public addons remain unaffected: every install (public or secret-bearing)
goes through parseAddonInstallUrl which writes a manifest_suffix_v1 vault
row, so the validator passes for both as long as the install invariant
held. Validation only fires when the invariant has been violated upstream."
```

---

## Task 5: Android — mirror the change in `AccountSettingsSyncService.resolveRemoteAddonUrl`

**Why:** `AccountSettingsSyncService.kt:1783-1826` is a near-1:1 copy of the `AddonSyncService` function and is invoked from a different sync entry point. Without this change, half the sync paths still silently downgrade.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:1783-1826`

- [ ] **Step 1: Read the current `resolveRemoteAddonUrl` in `AccountSettingsSyncService.kt`**

Confirm it matches the structure of the `AddonSyncService` version (decodeAs, two branches, same buildResolvedAddonUrl call). If the structures have diverged, make a note and apply the same conceptual change rather than a verbatim copy.

- [ ] **Step 2: Apply the same code change**

Replace the body of `AccountSettingsSyncService.resolveRemoteAddonUrl` (lines 1783-1826) with the same body shown in Task 4 Step 1. Add the same imports at the top of the file if not present.

- [ ] **Step 3: Run the full sync test suite**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.*"
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
git commit -m "fix(sync): apply transport-secret invariant validation in AccountSettingsSyncService

Mirror the AddonSyncService change so the account-settings sync path also
fails loudly when a v2 addon's vault row violates the install invariant
instead of silently downgrading to the public-manifest URL."
```

---

## Task 6: Verify failure logs preserve the cause

**Why:** `getRemoteAddonConfigs` already logs `getRemoteAddonConfigs: failed to resolve addon url=…` when `resolveRemoteAddonUrl` returns a `Result.failure`. Confirm both occurrences pass the throwable as the third argument so the validator's actionable error message lands in logcat.

**Files:**
- Verify (read-only): `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt:162-167`
- Verify (read-only): the equivalent site in `AccountSettingsSyncService.kt`

- [ ] **Step 1: Confirm the existing log lines preserve the cause**

```bash
grep -n -A 4 "getRemoteAddonConfigs: failed to resolve addon url" \
  app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt \
  app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
```

Expected: each call site already passes `error` (the throwable) as the third argument to `Log.w`. If a call site logs only the message, edit it to include `error`:

```kotlin
Log.w(TAG, "getRemoteAddonConfigs: failed to resolve addon url=${addon.url}", error)
```

- [ ] **Step 2: Skip commit if no edits were made; commit if edits were needed**

```bash
# Only if step 1 required an edit:
git add app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt \
        app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
git commit -m "chore(sync): include cause in getRemoteAddonConfigs failure logs"
```

---

## Task 7: Build + full Android unit test suite

**Why:** Confirm the whole module still compiles and no Robolectric/MockK incompatibility crept in.

- [ ] **Step 1: Build debug**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full app unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. New `AccountAddonSecretPayloadValidatorsTest` shows 8 passing tests in the report. No pre-existing tests regressed.

- [ ] **Step 3: If anything fails, diagnose root cause — do not paper over**

Common failure modes:
- `AccountAddonSecretPayload` field ordering/naming mismatch: verify the data-class declaration in `AccountSyncModels.kt`.
- Imports missing: add `import com.nexio.tv.data.remote.supabase.requireValidV1Secret` / `requireValidV2Transport` at the top of each modified file.
- Robolectric runner missing for a new test: the validator test file is plain JUnit (no Robolectric needed).

---

## Task 8: On-device verification at 192.168.50.98

**Why:** The probe data showed Torrentio returning `count=44` with bare `"Torrentio"` names — the no-debrid-config Torrentio response — matching a vault row that no longer carried the install URL. After the web invariant + Android validators are in place, two outcomes are observable:
- Vault row intact: Torrentio resolves to its full `…/debridoptions=…/manifest.json` URL → returns ~45 streams with `[RD+] Torrentio` prefix.
- Vault row missing or wrong-shaped: Torrentio is dropped from the addon list and a `getRemoteAddonConfigs: failed to resolve addon url=https://torrentio.strem.fun` warning appears in logcat with the cause `transport-secret invariant violated for https://torrentio.strem.fun (ref=…)`.

- [ ] **Step 1: Build, install, launch**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98:5555 shell am start -n com.nexio.tv/.MainActivity
```

- [ ] **Step 2: Trigger an addon sync and check logcat**

In the app, navigate to a series episode (e.g. Monarch S02E09) so `getRemoteAddonConfigs` fires. Then:

```bash
adb -s 192.168.50.98:5555 logcat -d | grep -E "AddonSyncService|getRemoteAddonConfigs|StreamRepositoryImpl"
```

Expected if the user's vault is intact: `Streams success addon=Torrentio count=45` (or any non-44 count, all with `[RD+]` markers in subsequent stream parsing).

Expected if the vault is missing: `getRemoteAddonConfigs: failed to resolve addon url=https://torrentio.strem.fun` accompanied by `IllegalStateException: transport-secret invariant violated for … (ref=…)`; Torrentio is absent from the resulting stream list entirely.

- [ ] **Step 3: Re-run the autoplay probe to confirm**

Trigger the same autoplay decision the user analyzed previously. Inspect the next `shadow_autoplay_decision` upload. Verify either:
- All Torrentio entries now arrive with `serviceId=RD` (no more `NOT_DEBRID_WRAPPED` rejections from Torrentio), OR
- Torrentio simply does not appear in the input set because it was dropped by the sync layer.

- [ ] **Step 4: Document the outcome**

If the vault row exists, no further action needed — the silent-downgrade pathway is closed.

If the vault row is missing, instruct the user to reinstall the Torrentio addon in the web UI. The new strict-validation `parseAddonInstallUrl` will reject any incomplete URL the user pastes; the new persist endpoint will write secrets atomically with the addon row. Subsequent Android syncs will pick up the corrected state.

---

## Out of scope (intentionally deferred)

- **UI surface for "addon dropped"**: a banner in the addon manager when a sync drops an addon for invariant reasons. The current `Log.w` is sufficient for diagnosis but invisible to end users; that's a follow-up.
- **SQL signal change**: making `service_resolve_account_secret` return NULL on missing rows. The web invariant means missing rows shouldn't happen in steady state; the Kotlin validators surface invariant violations cheaply on the consumer side. Changing the SQL contract would require auditing the seven other web/Android callers of the resolver and was rejected as out of scope.
- **Backfill of broken state**: this plan does not migrate corrupted accounts. Users with mismatched addon/secret rows must reinstall their addon to fix the invariant. The atomicity fix (Task 2) prevents new occurrences.
- **Local-install path on Android**: if the Android app supports installing an addon directly on-device without going through web sync, that path also needs the same strict URL validation. Out of scope here; check `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModel.kt` separately.
