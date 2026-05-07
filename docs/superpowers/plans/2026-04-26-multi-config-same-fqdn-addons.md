# Multi-config same-FQDN addons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow two configurations of the same FQDN addon (e.g. Torrentio with Real-Debrid + Torrentio with Premiumize) to coexist as separate rows in `account_addons_public`, web local state, and Android local state, and accept `stremio://` install URLs verbatim.

**Architecture:** Identity becomes `(user_id, base_url, transport_secret_ref)`. The Supabase unique index swaps `secret_ref` for `transport_secret_ref` (already per-suffix-hashed). New v2 installs stop emitting the legacy `secret_ref` (set to `null`) so two same-FQDN installs don't overwrite a shared vault row. `parseAddonInstallUrl` rewrites `stremio://` → `https://` at the top of the function on both web and Android. `usePortalStore.addAddon` dedupes on `(transportBaseUrl, transportSecretRef)` instead of origin URL, with a v1→v2 fallback comparator. Android's `splitAddonTransportUrl` is tightened to match web's strict `/manifest.json` invariant.

**Tech Stack:** Supabase (PostgreSQL), TypeScript / Nuxt 4, Vue 3 Composition API, Kotlin (Android), `node:test` with `tsx` runner (web), JUnit + Robolectric (Android).

**Reference design doc:** `docs/superpowers/specs/2026-04-26-multi-config-same-fqdn-addons-design.md`

**Repo layout note:** `nexio-web/` is a git submodule of the parent `nexio` repo. All commits to web files go inside the submodule; the parent repo gets a final pointer-bump commit at the end.

---

## Task 1: Supabase migration — swap unique index to transport_secret_ref

**Why first:** the migration is backwards-compatible (existing rows already have `transport_secret_ref` populated by the v2 backfill in `20260420231500_add_addon_transport_v2.sql`). Deploying first means both old and new clients keep working. The new clients require the new index before they can persist a second same-FQDN install.

**Files:**
- Create: `supabase/migrations/20260426201500_addon_transport_unique_index.sql`
- Create: `supabase/rollback/20260426201500_rollback_addon_transport_unique_index.sql`

- [ ] **Step 1: Write the forward migration**

Create `supabase/migrations/20260426201500_addon_transport_unique_index.sql`:

```sql
-- Replace the addon dedup unique index so two installs of the same FQDN can
-- coexist when they target different transport suffixes. Identity becomes
-- (user_id, base_url, transport_secret_ref) where transport_secret_ref is
-- already per-suffix-hashed by addonTransportSecretRef on web/Android.
--
-- The previous index keyed on COALESCE(secret_ref, '') which collides for two
-- configured installs of the same origin (e.g. Torrentio with Real-Debrid vs
-- Torrentio with Premiumize both produce secret_ref =
-- 'addon:torrentio_strem_fun').
--
-- transport_secret_ref was backfilled for every existing row by
-- 20260420231500_add_addon_transport_v2.sql, so no NULLs are expected in
-- practice. The COALESCE('') guard preserves the legacy
-- "one-row-per-origin" behaviour for any unmigrated v1 rows that may exist.

drop index if exists public.account_addons_public_user_base_uidx;

create unique index account_addons_public_user_transport_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(transport_secret_ref, '')
  );
```

- [ ] **Step 2: Write the rollback migration**

Create `supabase/rollback/20260426201500_rollback_addon_transport_unique_index.sql`:

```sql
-- Restore the pre-2026-04-26 dedup index. Note: any rows inserted while the
-- forward migration was active that share (user_id, lower(base_url),
-- coalesce(secret_ref, '')) with another row will block this rollback with a
-- unique-violation. Reconcile such rows manually before rolling back.

drop index if exists public.account_addons_public_user_transport_uidx;

create unique index account_addons_public_user_base_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(secret_ref, '')
  );
```

- [ ] **Step 3: Apply the migration locally and verify**

Run (from repo root):

```bash
supabase db reset
```

Expected: `supabase db reset` runs the full migration set including the new file without error. Look for `Applying migration 20260426201500_addon_transport_unique_index.sql` in stdout.

- [ ] **Step 4: Smoke test the new index**

In a `psql` session against the local Supabase DB (or `supabase db remote psql`):

```sql
-- Insert two rows with same base_url, different transport_secret_ref.
-- Substitute a real test user_id from auth.users.
insert into public.account_addons_public
  (user_id, base_url, manifest_url, parser_preset, name, enabled, sort_order,
   public_query_params, install_kind, secret_ref,
   transport_schema_version, transport_base_url, transport_secret_ref)
values
  ('00000000-0000-0000-0000-000000000001'::uuid,
   'https://torrentio.strem.fun',
   'https://torrentio.strem.fun/manifest.json',
   'TORRENTIO', 'torrentio.strem.fun', true, 0,
   '{}'::jsonb, 'configured', null,
   2, 'https://torrentio.strem.fun',
   'addon:torrentio_strem_fun:transport:aaaa1111'),
  ('00000000-0000-0000-0000-000000000001'::uuid,
   'https://torrentio.strem.fun',
   'https://torrentio.strem.fun/manifest.json',
   'TORRENTIO', 'torrentio.strem.fun', true, 1,
   '{}'::jsonb, 'configured', null,
   2, 'https://torrentio.strem.fun',
   'addon:torrentio_strem_fun:transport:bbbb2222');

-- Insert a third row with a transport_secret_ref that collides with the first.
-- Expected: ERROR: duplicate key value violates unique constraint
insert into public.account_addons_public
  (user_id, base_url, manifest_url, parser_preset, name, enabled, sort_order,
   public_query_params, install_kind, secret_ref,
   transport_schema_version, transport_base_url, transport_secret_ref)
values
  ('00000000-0000-0000-0000-000000000001'::uuid,
   'https://torrentio.strem.fun',
   'https://torrentio.strem.fun/manifest.json',
   'TORRENTIO', 'duplicate', true, 2,
   '{}'::jsonb, 'configured', null,
   2, 'https://torrentio.strem.fun',
   'addon:torrentio_strem_fun:transport:aaaa1111');

-- Cleanup
delete from public.account_addons_public
  where user_id = '00000000-0000-0000-0000-000000000001'::uuid;
```

Expected: rows 1 & 2 insert successfully. Row 3 fails with a unique-violation message naming `account_addons_public_user_transport_uidx`.

- [ ] **Step 5: Commit**

```bash
git -C /Users/jneerdael/Scripts/nexio add \
  supabase/migrations/20260426201500_addon_transport_unique_index.sql \
  supabase/rollback/20260426201500_rollback_addon_transport_unique_index.sql
git -C /Users/jneerdael/Scripts/nexio commit -m "$(cat <<'EOF'
feat(addons): swap unique index to transport_secret_ref

Allow two same-FQDN addon installs to coexist when their transport
suffixes differ. New identity is (user_id, base_url, transport_secret_ref);
the previous index collided on origin for configured installs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Web — add stremio:// scheme rewrite to parseAddonInstallUrl

**Files:**
- Create: `nexio-web/tests/account-secrets-stremio-rewrite.test.ts`
- Modify: `nexio-web/server/utils/account-secrets.ts` (top of `parseAddonInstallUrl`)
- Modify: `nexio-web/utils/account-secrets.ts` (top of `parseAddonInstallUrl`)

- [ ] **Step 1: Write the failing test**

Create `nexio-web/tests/account-secrets-stremio-rewrite.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseAddonInstallUrl } from '../utils/account-secrets.ts'
import { parseAddonInstallUrl as parseServerAddonInstallUrl } from '../server/utils/account-secrets.ts'

const torrentioRd = '/debridoptions=nodownloadlinks%7Crealdebrid=L7VSEJBKIQE52BW7NQMSJGYTP3IPDTLKR6RUBNF5GKNZ646G5ZRA/manifest.json'

test('parseAddonInstallUrl accepts stremio:// scheme and rewrites to https://', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    const stremio = parse(`stremio://torrentio.strem.fun${torrentioRd}`)
    const https = parse(`https://torrentio.strem.fun${torrentioRd}`)

    assert.equal(stremio.addon.url, 'https://torrentio.strem.fun')
    assert.equal(stremio.addon.transportBaseUrl, 'https://torrentio.strem.fun')
    assert.equal(stremio.transportSecretPayload.suffix, torrentioRd)
    assert.equal(stremio.transportSecretRef, https.transportSecretRef)
  }
})

test('parseAddonInstallUrl rewrites uppercase STREMIO:// scheme', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    const upper = parse(`STREMIO://torrentio.strem.fun${torrentioRd}`)
    assert.equal(upper.addon.url, 'https://torrentio.strem.fun')
  }
})

test('parseAddonInstallUrl still rejects stremio:// URLs without /manifest.json', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    assert.throws(
      () => parse('stremio://torrentio.strem.fun/'),
      /must include \/manifest\.json/i
    )
  }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `nexio-web/`):

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test tests/account-secrets-stremio-rewrite.test.ts
```

Expected: FAIL. The first test throws because `new URL('stremio://torrentio.strem.fun/...')` produces a non-http origin and `splitAddonTransportUrl`'s `parsed.origin` is `'null'`, so the resulting `transportBaseUrl` does not match `'https://torrentio.strem.fun'`.

- [ ] **Step 3: Add the scheme rewrite to the server-side parser**

In `nexio-web/server/utils/account-secrets.ts`, locate `parseAddonInstallUrl` (around line 172). Replace the line:

```ts
  const candidate = rawUrl.trim()
```

with:

```ts
  const candidate = rawUrl.trim().replace(/^stremio:\/\//i, 'https://')
```

- [ ] **Step 4: Add the same rewrite to the client-side parser**

In `nexio-web/utils/account-secrets.ts`, locate `parseAddonInstallUrl` (around line 125). Apply the identical replacement:

```ts
  const candidate = rawUrl.trim().replace(/^stremio:\/\//i, 'https://')
```

- [ ] **Step 5: Run the new test and the existing account-secrets test to verify both pass**

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test tests/account-secrets-stremio-rewrite.test.ts tests/account-secrets.test.ts
```

Expected: PASS. New file: 3 tests pass. Existing `account-secrets.test.ts`: all tests pass (the rewrite is idempotent for `https://` inputs, so existing behavior is unchanged).

- [ ] **Step 6: Commit (in nexio-web submodule)**

```bash
git -C /Users/jneerdael/Scripts/nexio/nexio-web add \
  tests/account-secrets-stremio-rewrite.test.ts \
  server/utils/account-secrets.ts \
  utils/account-secrets.ts
git -C /Users/jneerdael/Scripts/nexio/nexio-web commit -m "$(cat <<'EOF'
feat(addons): accept stremio:// install URLs

Rewrite the scheme to https:// at the top of parseAddonInstallUrl so the
strict /manifest.json invariant and downstream URL parsing see a normal
http(s) URL. Mirrors the rewrite on both server and client copies of the
parser.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Web — stop emitting legacy secret_ref for new installs (1b)

This is the source-of-truth change for "secrets stop overwriting each other on same-FQDN installs". After this task, two configured Torrentio URLs both produce `secretRef: null` and the legacy vault row is no longer touched.

**Files:**
- Modify: `nexio-web/server/utils/account-secrets.ts` (`parseAddonInstallUrl`)
- Modify: `nexio-web/utils/account-secrets.ts` (`parseAddonInstallUrl`)
- Create: `nexio-web/tests/account-secrets-multi-config.test.ts`
- Modify: `nexio-web/tests/account-secrets.test.ts` (update two expectations)

- [ ] **Step 1: Write the failing multi-config test**

Create `nexio-web/tests/account-secrets-multi-config.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseAddonInstallUrl } from '../utils/account-secrets.ts'
import { parseAddonInstallUrl as parseServerAddonInstallUrl } from '../server/utils/account-secrets.ts'

const rdSuffix = '/debridoptions=nodownloadlinks%7Crealdebrid=L7VSEJBKIQE52BW7NQMSJGYTP3IPDTLKR6RUBNF5GKNZ646G5ZRA/manifest.json'
const pmSuffix = '/debridoptions=nodownloadlinks%7Cpremiumize=szpwe4fx4ngs8u9q/manifest.json'

test('two same-FQDN installs share base_url but produce distinct transport_secret_ref', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    const rd = parse(`https://torrentio.strem.fun${rdSuffix}`)
    const pm = parse(`https://torrentio.strem.fun${pmSuffix}`)

    assert.equal(rd.addon.url, pm.addon.url, 'same origin → same base url')
    assert.equal(rd.addon.transportBaseUrl, pm.addon.transportBaseUrl)
    assert.notEqual(rd.transportSecretRef, pm.transportSecretRef, 'different suffix → different transport secret ref')
    assert.equal(rd.transportSecretPayload.suffix, rdSuffix)
    assert.equal(pm.transportSecretPayload.suffix, pmSuffix)
  }
})

test('configured installs no longer emit legacy secret_ref / secret_payload', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    const rd = parse(`https://torrentio.strem.fun${rdSuffix}`)
    assert.equal(rd.secretRef, null, 'legacy secret_ref must be null for v2 installs')
    assert.equal(rd.secretPayload, null, 'legacy secret_payload must be null for v2 installs')
    assert.equal(rd.secretType, null, 'legacy secret_type must be null for v2 installs')
    assert.equal(rd.addon.installKind, 'configured', 'configured installs still keep installKind=configured')
  }
})

test('manifest-only (non-configured) installs also emit secret_ref=null', () => {
  for (const parse of [parseAddonInstallUrl, parseServerAddonInstallUrl]) {
    const bare = parse('https://thepiratebay-plus.strem.fun/manifest.json')
    assert.equal(bare.secretRef, null)
    assert.equal(bare.secretPayload, null)
    assert.equal(bare.addon.installKind, 'manifest')
  }
})
```

- [ ] **Step 2: Run multi-config test to verify it fails**

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test tests/account-secrets-multi-config.test.ts
```

Expected: FAIL. Today's parser sets `secretRef = addonSecretRef(normalized)` for any path-secret install (`hasPathSecret = true` because the suffix contains a path segment), so `rd.secretRef === 'addon:torrentio_strem_fun'` rather than `null`.

- [ ] **Step 3: Update `parseAddonInstallUrl` in `nexio-web/server/utils/account-secrets.ts`**

Locate the current body (around lines 180–235). Replace the section starting at:

```ts
  const parsed = new URL(candidate)
  const transport = splitAddonTransportUrl(candidate)
  const suffixUrl = new URL(`https://suffix.invalid${transport.suffix}`)
  const suffixPath = suffixUrl.pathname
  const legacyPathSegment = suffixPath === '/manifest.json'
    ? null
    : suffixPath.replace(/\/manifest\.json$/i, '').replace(/^\//, '')
  const hasPathSecret = Boolean(legacyPathSegment)
  const normalized = normalizeAddonUrl(`${parsed.origin}/manifest.json`)
  const publicQueryParams: Record<string, string> = {}
  const secretParams: Record<string, string> = {}

  parsed.searchParams.forEach((value, key) => {
    if (sensitiveQueryKeys.has(key.trim().toLowerCase())) {
      secretParams[key] = value
      return
    }
    publicQueryParams[key] = value
  })

  const hasQuerySecrets = Object.keys(secretParams).length > 0
  const secretRef = hasQuerySecrets || hasPathSecret ? addonSecretRef(normalized) : null
  const transportSecretRef = addonTransportSecretRef(transport.baseUrl, transport.suffix)
  const transportSecretPayload: AddonSecretPayload = {
    kind: 'manifest_suffix_v1',
    suffix: transport.suffix
  }
  const manifestUrl = `${normalized}/manifest.json`
  const addon: AddonRecord = {
    id: crypto.randomUUID(),
    url: normalized,
    manifestUrl,
    transportSchemaVersion: 2,
    transportBaseUrl: transport.baseUrl,
    transportSecretRef,
    parserPreset: recommendParserPresetForAddonUrl(manifestUrl) ?? 'GENERIC',
    name: parsed.hostname.replace(/^www\./, ''),
    enabled: true,
    installKind: secretRef ? 'configured' : 'manifest',
    publicQueryParams,
    secretRef,
    sortOrder: 0
  }

  return {
    addon,
    secretType: secretRef ? 'addon_credential' : null,
    secretRef,
    secretPayload: secretRef
      ? {
          kind: hasQuerySecrets && hasPathSecret ? 'composite' : hasPathSecret ? 'path_segment' : 'query_params',
          ...(hasQuerySecrets ? { params: secretParams } : {}),
          ...(hasPathSecret ? { pathSegment: legacyPathSegment as string } : {})
        }
      : null,
    transportSecretRef,
    transportSecretPayload
  }
}
```

with:

```ts
  const parsed = new URL(candidate)
  const transport = splitAddonTransportUrl(candidate)
  const normalized = normalizeAddonUrl(`${parsed.origin}/manifest.json`)
  const publicQueryParams: Record<string, string> = {}

  // Public (non-sensitive) query params still travel on the addon record so
  // the UI can replay them when constructing display URLs. Sensitive params
  // are no longer extracted into a legacy secret_payload — for v2 installs
  // the entire path+query suffix lives in transport_secret_payload.suffix.
  parsed.searchParams.forEach((value, key) => {
    if (!sensitiveQueryKeys.has(key.trim().toLowerCase())) {
      publicQueryParams[key] = value
    }
  })

  const transportSecretRef = addonTransportSecretRef(transport.baseUrl, transport.suffix)
  const transportSecretPayload: AddonSecretPayload = {
    kind: 'manifest_suffix_v1',
    suffix: transport.suffix
  }
  const manifestUrl = `${normalized}/manifest.json`
  const installKind: AddonRecord['installKind'] =
    transport.suffix === '/manifest.json' ? 'manifest' : 'configured'
  const addon: AddonRecord = {
    id: crypto.randomUUID(),
    url: normalized,
    manifestUrl,
    transportSchemaVersion: 2,
    transportBaseUrl: transport.baseUrl,
    transportSecretRef,
    parserPreset: recommendParserPresetForAddonUrl(manifestUrl) ?? 'GENERIC',
    name: parsed.hostname.replace(/^www\./, ''),
    enabled: true,
    installKind,
    publicQueryParams,
    secretRef: null,
    sortOrder: 0
  }

  return {
    addon,
    secretType: null,
    secretRef: null,
    secretPayload: null,
    transportSecretRef,
    transportSecretPayload
  }
}
```

- [ ] **Step 4: Apply the equivalent change to `nexio-web/utils/account-secrets.ts`**

Same edit, in the client copy of `parseAddonInstallUrl` (around lines 125–180). The two files are kept symmetric; the body text differs only by the `createError` import availability (the client copy uses `throw new Error(...)` instead of `createError(...)`, which is already the case in `splitAddonTransportUrl` — leave that path unchanged).

After this step, the new `parseAddonInstallUrl` body in `nexio-web/utils/account-secrets.ts` should match the server copy line-for-line in the affected region.

- [ ] **Step 5: Update existing `tests/account-secrets.test.ts` expectations**

Two existing tests assert the legacy `secretType`/`secretPayload` fields. Under 1b they should now assert `null` for those fields and read the suffix from `transportSecretPayload` instead.

In `nexio-web/tests/account-secrets.test.ts`, replace the test starting `parseAddonInstallUrl stores Top Streaming UUID manifest path as secret-backed suffix` (lines 9–19) with:

```ts
test('parseAddonInstallUrl stores Top Streaming UUID manifest path as v2 transport suffix', () => {
  const parsed = parseAddonInstallUrl('https://top-streaming.stream/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json')

  assert.equal(parsed.addon.url, 'https://top-streaming.stream')
  assert.equal(parsed.addon.manifestUrl, 'https://top-streaming.stream/manifest.json')
  assert.equal(parsed.secretType, null)
  assert.equal(parsed.secretRef, null)
  assert.equal(parsed.secretPayload, null)
  assert.equal(parsed.addon.installKind, 'configured')
  assert.equal(parsed.addon.transportBaseUrl, 'https://top-streaming.stream')
  assert.equal(parsed.transportSecretPayload.kind, 'manifest_suffix_v1')
  assert.equal(parsed.transportSecretPayload.suffix, '/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json')
})
```

Replace the test starting `parseAddonInstallUrl stores configured path addons as secret-backed custom paths` (lines 21–38) with:

```ts
test('parseAddonInstallUrl stores configured path addons as v2 transport suffix', () => {
  const pathSegment = 'eyJjb25maWciOnRydWUsImRlYnJpZCI6ImNvbmZpZ3VyZWQifQ=='
  const input = `https://cometfortheweebs.midnightignite.me/${pathSegment}/manifest.json`
  const parsed = parseServerAddonInstallUrl(input)

  assert.equal(parsed.addon.url, 'https://cometfortheweebs.midnightignite.me')
  assert.equal(parsed.addon.manifestUrl, 'https://cometfortheweebs.midnightignite.me/manifest.json')
  assert.equal(parsed.addon.installKind, 'configured')
  assert.equal(parsed.secretType, null)
  assert.equal(parsed.secretRef, null)
  assert.equal(parsed.secretPayload, null)
  assert.equal(parsed.transportSecretPayload.kind, 'manifest_suffix_v1')
  assert.equal(parsed.transportSecretPayload.suffix, `/${pathSegment}/manifest.json`)
  assert.equal(buildResolvedManifestUrl({
    baseUrl: parsed.addon.transportBaseUrl,
    secretPayload: parsed.transportSecretPayload
  }), input)
})
```

The other tests in `account-secrets.test.ts` (rejection tests, and the "v2 transport suffix" test on line 68) remain unchanged.

- [ ] **Step 6: Run all account-secrets tests to verify they pass**

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test tests/account-secrets.test.ts tests/account-secrets-multi-config.test.ts tests/account-secrets-stremio-rewrite.test.ts
```

Expected: PASS for all three files. `account-secrets-multi-config.test.ts` runs 3 tests, `account-secrets-stremio-rewrite.test.ts` runs 3, `account-secrets.test.ts` runs the existing set including the two updated tests.

- [ ] **Step 7: Commit**

```bash
git -C /Users/jneerdael/Scripts/nexio/nexio-web add \
  server/utils/account-secrets.ts \
  utils/account-secrets.ts \
  tests/account-secrets-multi-config.test.ts \
  tests/account-secrets.test.ts
git -C /Users/jneerdael/Scripts/nexio/nexio-web commit -m "$(cat <<'EOF'
fix(addons): drop legacy secret_ref emission for new v2 installs

Two configured installs of the same FQDN previously wrote to a shared
'addon:<host>' vault row, with the second saveSecret silently overwriting
the first's payload. v2 transport routing already prefers
transport_secret_payload.suffix (treat-suffixes-as-opaque), so the legacy
field is no longer load-bearing. Set secret_ref/secret_payload/secret_type
to null for new installs so two same-FQDN configs stop colliding.

Existing legacy v1 rows keep their secret_ref untouched.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Web — change addAddon dedup key to (transportBaseUrl, transportSecretRef)

**Files:**
- Modify: `nexio-web/composables/usePortalStore.ts` (`addAddon`, around line 1384)
- Modify: `nexio-web/tests/addon-delete-persistence.test.mjs` (loosen assertions)
- Create: `nexio-web/tests/use-portal-store-multi-config.test.ts`

- [ ] **Step 1: Write the failing source-grep test**

Create `nexio-web/tests/use-portal-store-multi-config.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('addAddon dedupes by (transportBaseUrl, transportSecretRef), with v1→v2 fallback', async () => {
  const source = await readFile(new URL('../composables/usePortalStore.ts', import.meta.url), 'utf8')
  const match = source.match(/async function addAddon\(url: string,[\s\S]*?\n  async function removeAddon/)
  assert.ok(match, 'addAddon should be present in portal store')
  const body = match[0]

  // Primary v2 dedup: must compare transportBaseUrl AND transportSecretRef.
  assert.match(body, /addon\.transportBaseUrl === parsed\.addon\.transportBaseUrl/)
  assert.match(body, /addon\.transportSecretRef === parsed\.transportSecretRef/)

  // v1→v2 fallback: must keep upgrading legacy v1 rows when the user re-pastes
  // the same configured URL whose existing row hasn't migrated to v2 yet.
  assert.match(body, /transportSchemaVersion !== 2/)
  assert.match(
    body,
    /normalizeAddonUrl\(addon\.url\)\s*===\s*parsed\.addon\.url/,
    'legacy URL-based comparator must remain for v1 fallback'
  )

  // Two installs that differ only in transportSecretRef must NOT trigger the
  // upgrade branch. The dedup must therefore key on transportSecretRef, not
  // just on origin.
  assert.doesNotMatch(
    body,
    /findIndex\(\(addon\) => normalizeAddonUrl\(addon\.url\) === parsed\.addon\.url\)/,
    'origin-only dedup must be removed from the v2 dedup path'
  )
})

test('addAddon saves transport secret before mutating local state', async () => {
  const source = await readFile(new URL('../composables/usePortalStore.ts', import.meta.url), 'utf8')
  const match = source.match(/async function addAddon\(url: string,[\s\S]*?\n  async function removeAddon/)
  assert.ok(match)
  const body = match[0]

  const saveTransportSecretIndex = body.indexOf("secretRef: parsed.transportSecretRef")
  const stateMutationIndex = body.search(/state\.value\.addons\s*=/)
  assert.notEqual(saveTransportSecretIndex, -1, 'transport saveSecret must remain in addAddon')
  assert.notEqual(stateMutationIndex, -1, 'addAddon must mutate state.value.addons')
  assert.ok(
    saveTransportSecretIndex < stateMutationIndex,
    'transport secret must be persisted before state mutation (post-7260a8a invariant)'
  )
})
```

- [ ] **Step 2: Run the new test to verify it fails**

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test tests/use-portal-store-multi-config.test.ts
```

Expected: FAIL on the first test — the current `addAddon` matches `findIndex((addon) => normalizeAddonUrl(addon.url) === parsed.addon.url)`, which the `assert.doesNotMatch` rejects.

- [ ] **Step 3: Rewrite `addAddon`'s dedup logic**

In `nexio-web/composables/usePortalStore.ts`, locate `async function addAddon` (around line 1384). Replace the body up through (and including) the closing `return` of the upgrade branch (around line 1448) with the version below. The fresh-install branch that follows (lines 1449–1480) stays unchanged — only the dedup-and-upgrade portion changes.

Find this block:

```ts
    const existingIndex = state.value.addons.findIndex((addon) => normalizeAddonUrl(addon.url) === parsed.addon.url)
    if (existingIndex >= 0) {
      const existingAddon = state.value.addons[existingIndex]
      const shouldUpgradeAddonTransport = Boolean(
        existingAddon &&
        (
          existingAddon.secretRef !== parsed.secretRef ||
          existingAddon.installKind !== parsed.addon.installKind ||
          existingAddon.manifestUrl !== parsed.addon.manifestUrl ||
          existingAddon.transportSchemaVersion !== 2 ||
          existingAddon.transportBaseUrl !== parsed.addon.transportBaseUrl ||
          existingAddon.transportSecretRef !== parsed.transportSecretRef
        )
      )

      if (!shouldUpgradeAddonTransport || !existingAddon) {
        return
      }
```

Replace it with:

```ts
    // Primary v2 dedup: same transport endpoint = same install.
    let existingIndex = state.value.addons.findIndex((addon) =>
      addon.transportBaseUrl === parsed.addon.transportBaseUrl &&
      addon.transportSecretRef === parsed.transportSecretRef
    )

    // v1→v2 fallback: a legacy v1 row hasn't migrated to a transportSecretRef
    // yet. If the user re-pastes a configured URL that maps to the same
    // origin as a v1 row, upgrade that row in place instead of creating a
    // duplicate. Two installs that share an origin but differ in
    // transportSecretRef will NOT be merged here because the v2 match above
    // would have caught a true duplicate.
    if (existingIndex < 0) {
      existingIndex = state.value.addons.findIndex((addon) =>
        addon.transportSchemaVersion !== 2 &&
        normalizeAddonUrl(addon.url) === parsed.addon.url
      )
    }

    if (existingIndex >= 0) {
      const existingAddon = state.value.addons[existingIndex]
      const shouldUpgradeAddonTransport = Boolean(
        existingAddon &&
        (
          existingAddon.installKind !== parsed.addon.installKind ||
          existingAddon.manifestUrl !== parsed.addon.manifestUrl ||
          existingAddon.transportSchemaVersion !== 2 ||
          existingAddon.transportBaseUrl !== parsed.addon.transportBaseUrl ||
          existingAddon.transportSecretRef !== parsed.transportSecretRef ||
          existingAddon.secretRef !== null
        )
      )

      if (!shouldUpgradeAddonTransport || !existingAddon) {
        return
      }
```

(The `secretRef !== null` clause keeps the upgrade fired for legacy v1 rows that still have a populated `secretRef`. After upgrade the row is rewritten with `secretRef: null` per the rebuild block below.)

- [ ] **Step 4: Drop the legacy saveSecret call from the v2 upgrade and fresh-install paths**

Still inside `addAddon`, locate the legacy guard inside the upgrade branch (lines 1413–1419) and the symmetric one in the fresh-install branch (lines 1452–1458). Both look like:

```ts
      if (parsed.secretType && parsed.secretRef && parsed.secretPayload) {
        await saveSecret({
          secretType: parsed.secretType,
          secretRef: parsed.secretRef,
          secretPayload: parsed.secretPayload
        })
      }
```

Delete both blocks. With Task 3 applied, `parsed.secretType`/`parsed.secretRef`/`parsed.secretPayload` are always `null`, so the guard never fires. Deleting it removes dead code.

Also locate the `state.value.addons.map(...)` rebuild inside the upgrade branch (line 1426) and update the field assignments. Find this:

```ts
      state.value.addons = state.value.addons.map((addon, index) => {
        if (index !== existingIndex) return addon
        return sanitizeAddonRecord({
          ...addon,
          url: parsed.addon.url,
          manifestUrl: parsed.addon.manifestUrl,
          publicQueryParams: parsed.addon.publicQueryParams,
          installKind: parsed.addon.installKind,
          secretRef: parsed.secretRef,
          transportSchemaVersion: 2,
          transportBaseUrl: parsed.addon.transportBaseUrl,
          transportSecretRef: parsed.transportSecretRef,
          parserPreset: recommendParserPresetForAddonUrl(parsed.addon.manifestUrl) ?? addon.parserPreset ?? parserPreset,
          sortOrder: addon.sortOrder ?? existingIndex
        }, existingIndex)
      })
```

Change `secretRef: parsed.secretRef` to `secretRef: null` so the upgraded row clears any legacy secret_ref it carried in v1:

```ts
      state.value.addons = state.value.addons.map((addon, index) => {
        if (index !== existingIndex) return addon
        return sanitizeAddonRecord({
          ...addon,
          url: parsed.addon.url,
          manifestUrl: parsed.addon.manifestUrl,
          publicQueryParams: parsed.addon.publicQueryParams,
          installKind: parsed.addon.installKind,
          secretRef: null,
          transportSchemaVersion: 2,
          transportBaseUrl: parsed.addon.transportBaseUrl,
          transportSecretRef: parsed.transportSecretRef,
          parserPreset: recommendParserPresetForAddonUrl(parsed.addon.manifestUrl) ?? addon.parserPreset ?? parserPreset,
          sortOrder: addon.sortOrder ?? existingIndex
        }, existingIndex)
      })
```

- [ ] **Step 5: Update `addon-delete-persistence.test.mjs` to match the new dedup body**

The existing test at lines 33–63 asserts `body.match(/secretRef: parsed\.secretRef/)`. After Step 4 that string no longer exists. Update those assertions to scope to the transport-secret invariants only.

In `nexio-web/tests/addon-delete-persistence.test.mjs`, replace the third test (starting at line 33) with:

```js
test('adding addon upgrades matching v1 row to v2 transport, otherwise creates a fresh row', async () => {
  const source = await readFile(new URL('../composables/usePortalStore.ts', import.meta.url), 'utf8')
  const match = source.match(/async function addAddon\(url: string,[\s\S]*?\n  async function removeAddon/)

  assert.ok(match, 'addAddon should be present in portal store')

  const body = match[0]
  assert.match(body, /const existingIndex = state\.value\.addons\.findIndex/)
  assert.match(body, /addon\.transportBaseUrl === parsed\.addon\.transportBaseUrl/)
  assert.match(body, /addon\.transportSecretRef === parsed\.transportSecretRef/)
  assert.match(body, /transportSchemaVersion !== 2/)
  assert.match(body, /const shouldUpgradeAddonTransport = Boolean/)
  assert.match(body, /transportSchemaVersion: 2/)
  assert.match(body, /transportBaseUrl: parsed\.addon\.transportBaseUrl/)
  assert.match(body, /transportSecretRef: parsed\.transportSecretRef/)

  const saveTransportSecretIndex = body.indexOf('secretRef: parsed.transportSecretRef')
  const persistIndex = body.indexOf('await persistSnapshot()')
  const stateMutationIndex = body.search(/state\.value\.addons\s*=/)

  assert.ok(saveTransportSecretIndex !== -1, 'addAddon must save the v2 transport secret payload')
  assert.ok(persistIndex !== -1, 'addAddon must persist the snapshot after state mutation')
  assert.ok(stateMutationIndex !== -1, 'addAddon must mutate state.value.addons')
  assert.ok(
    saveTransportSecretIndex < stateMutationIndex,
    'transport secret must be persisted before state mutation (post-7260a8a invariant)'
  )
  assert.ok(
    stateMutationIndex < persistIndex,
    'state mutation must precede persistSnapshot so the snapshot reflects the new state'
  )
})
```

- [ ] **Step 6: Run all affected tests**

```bash
cd /Users/jneerdael/Scripts/nexio/nexio-web && npx tsx --test \
  tests/use-portal-store-multi-config.test.ts \
  tests/addon-delete-persistence.test.mjs \
  tests/account-secrets.test.ts \
  tests/account-secrets-multi-config.test.ts \
  tests/account-secrets-stremio-rewrite.test.ts \
  tests/account-persist-atomicity.test.ts
```

Expected: PASS across all five files. The persist atomicity test (`account-persist-atomicity.test.ts`) is unaffected by these changes but is included to confirm the circuit-breaker invariant from `7260a8a` still source-greps clean.

- [ ] **Step 7: Commit**

```bash
git -C /Users/jneerdael/Scripts/nexio/nexio-web add \
  composables/usePortalStore.ts \
  tests/use-portal-store-multi-config.test.ts \
  tests/addon-delete-persistence.test.mjs
git -C /Users/jneerdael/Scripts/nexio/nexio-web commit -m "$(cat <<'EOF'
fix(addons): dedupe addAddon by transport endpoint, not origin

Two installs that share an origin but target different transport suffixes
(e.g. Torrentio with Real-Debrid vs Premiumize) now coexist as separate
rows instead of upgrading each other. v1→v2 re-paste of the same
configured URL still upgrades in place via a fallback comparator on origin
restricted to non-v2 rows. The upgrade rebuild now nulls secret_ref on
the rewritten row to match Task 3's emission rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Android — stremio:// rewrite + secret_ref=null + strict splitAddonTransportUrl

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AddonSyncCodec.kt` (`parseAddonInstallUrl`, `normalizeAddonInstallUrl`, `splitAddonTransportUrl`)
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt` (append new tests)

- [ ] **Step 1: Write failing tests by appending to `AddonSyncCodecTest.kt`**

Append the following tests to `app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt`. Add them at the bottom of the existing class body (before the closing `}`). Existing tests stay unchanged; the test class already imports `org.junit.Assert.*` and `org.junit.Test`.

```kotlin
    private val torrentioRdSuffix = "/debridoptions=nodownloadlinks%7Crealdebrid=L7VSEJBKIQE52BW7NQMSJGYTP3IPDTLKR6RUBNF5GKNZ646G5ZRA/manifest.json"
    private val torrentioPmSuffix = "/debridoptions=nodownloadlinks%7Cpremiumize=szpwe4fx4ngs8u9q/manifest.json"

    @Test
    fun `parseAddonInstallUrl rewrites stremio scheme to https`() {
        val stremio = parseAddonInstallUrl("stremio://torrentio.strem.fun$torrentioRdSuffix")
        val https = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")

        assertEquals("https://torrentio.strem.fun", stremio.publicBaseUrl)
        assertEquals("https://torrentio.strem.fun", stremio.transportBaseUrl)
        assertEquals(torrentioRdSuffix, stremio.transportSecretPayload.suffix)
        assertEquals(https.transportSecretRef, stremio.transportSecretRef)
    }

    @Test
    fun `normalizeAddonInstallUrl rewrites stremio scheme to https`() {
        val normalized = normalizeAddonInstallUrl("stremio://torrentio.strem.fun$torrentioRdSuffix")
        assertEquals(
            "https://torrentio.strem.fun" + torrentioRdSuffix.removeSuffix("/manifest.json"),
            normalized
        )
    }

    @Test
    fun `parseAddonInstallUrl produces null secretRef for v2 installs`() {
        val configured = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")
        assertNull(configured.secretRef)
        assertNull(configured.secretPayload)
        assertEquals("configured", configured.installKind)

        val bare = parseAddonInstallUrl("https://thepiratebay-plus.strem.fun/manifest.json")
        assertNull(bare.secretRef)
        assertNull(bare.secretPayload)
        assertEquals("manifest", bare.installKind)
    }

    @Test
    fun `two same-FQDN configured installs share base url but differ in transportSecretRef`() {
        val rd = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")
        val pm = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioPmSuffix")

        assertEquals(rd.publicBaseUrl, pm.publicBaseUrl)
        assertEquals(rd.transportBaseUrl, pm.transportBaseUrl)
        assertNotEquals(rd.transportSecretRef, pm.transportSecretRef)
        assertEquals(torrentioRdSuffix, rd.transportSecretPayload.suffix)
        assertEquals(torrentioPmSuffix, pm.transportSecretPayload.suffix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAddonInstallUrl rejects URLs without manifest path`() {
        parseAddonInstallUrl("https://torrentio.strem.fun/configure")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAddonInstallUrl rejects bare origin`() {
        parseAddonInstallUrl("https://torrentio.strem.fun/")
    }
```

The `assertNotEquals` import may need to be added at the top of the file:

```kotlin
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
```

(`assertNull` may already be imported transitively via `org.junit.Assert.*` if the file uses a star import; check the existing import block and add only what's missing.)

- [ ] **Step 2: Run the test class to verify the new tests fail**

```bash
cd /Users/jneerdael/Scripts/nexio && ./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.AddonSyncCodecTest"
```

Expected: FAIL. The stremio:// tests throw `MalformedURLException` from `URL(candidate)`. The `secretRef=null` test fails because today's parser sets `secretRef = "addon:torrentio_strem_fun"`. The "rejects URLs without manifest path" tests pass-or-fail depending on URL — they currently *do not* throw because `splitAddonTransportUrl` silently appends `/manifest.json`.

- [ ] **Step 3: Add the stremio:// rewrite to both `parseAddonInstallUrl` and `normalizeAddonInstallUrl`**

In `app/src/main/java/com/nexio/tv/core/sync/AddonSyncCodec.kt`, locate `normalizeAddonInstallUrl` (line 42). Replace:

```kotlin
fun normalizeAddonInstallUrl(rawUrl: String): String {
    val candidate = rawUrl.trim()
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
```

with:

```kotlin
fun normalizeAddonInstallUrl(rawUrl: String): String {
    val candidate = rawUrl.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
```

Then locate `parseAddonInstallUrl` (line 103). Replace:

```kotlin
fun parseAddonInstallUrl(rawUrl: String): ParsedAddonSyncEntry {
    val candidate = rawUrl.trim()
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
```

with:

```kotlin
fun parseAddonInstallUrl(rawUrl: String): ParsedAddonSyncEntry {
    val candidate = rawUrl.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
```

- [ ] **Step 4: Tighten `splitAddonTransportUrl` to enforce `/manifest.json`**

Still in `AddonSyncCodec.kt`, locate `splitAddonTransportUrl` (line 231). Replace:

```kotlin
private fun splitAddonTransportUrl(rawUrl: String): TransportParts {
    val parsed = URL(rawUrl.trim())
    val path = parsed.path?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
    val suffixPath = when {
        path.isBlank() -> "/manifest.json"
        path.endsWith("/manifest.json", ignoreCase = true) -> path
        else -> path.trimEnd('/') + "/manifest.json"
    }
    val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return TransportParts(
        baseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}",
        suffix = suffixPath + querySuffix
    )
}
```

with:

```kotlin
private fun splitAddonTransportUrl(rawUrl: String): TransportParts {
    val parsed = URL(rawUrl.trim())
    // Strict invariant (mirrors web post-f9b6edf): every Stremio addon install
    // URL ends with /manifest.json. Auto-appending or auto-defaulting masked
    // invalid install URLs and produced stale transport_secret_payload.suffix
    // values that no longer matched what the user actually pasted.
    val path = parsed.path?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
    require(path.endsWith("/manifest.json", ignoreCase = true)) {
        "Addon URL must include /manifest.json — paste the install URL exactly as your addon provided it."
    }
    val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return TransportParts(
        baseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}",
        suffix = path + querySuffix
    )
}
```

- [ ] **Step 5: Set `secretRef`/`secretPayload` to null in `parseAddonInstallUrl`**

Still in `AddonSyncCodec.kt`, locate the body of `parseAddonInstallUrl` from the `pathSecretSegment` block down through the `ParsedAddonSyncEntry(...)` return (lines 109–167). Replace it with:

```kotlin
    val parsed = URL(candidate)
    val transport = splitAddonTransportUrl(candidate)
    val publicBaseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}"

    // Public (non-sensitive) query params still travel on the addon record so
    // the UI can replay them when constructing display URLs. Sensitive params
    // are no longer extracted into a legacy secret_payload — for v2 installs
    // the entire path+query suffix lives in transport_secret_payload.suffix.
    val publicQueryParams = linkedMapOf<String, String>()
    parsed.query
        ?.split('&')
        ?.mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            val key = pieces[0]
            val value = pieces.getOrElse(1) { "" }
            key to value
        }
        ?.forEach { (key, value) ->
            if (key.trim().lowercase() !in sensitiveQueryKeys) {
                publicQueryParams[key] = value
            }
        }

    val transportSecretRef = addonTransportSecretRef(transport.baseUrl, transport.suffix)
    val transportSecretPayload = AccountAddonSecretPayload(
        kind = "manifest_suffix_v1",
        suffix = transport.suffix
    )
    val installKind = if (transport.suffix == "/manifest.json") "manifest" else "configured"

    return ParsedAddonSyncEntry(
        publicBaseUrl = publicBaseUrl,
        manifestUrl = "$publicBaseUrl/manifest.json",
        publicQueryParams = publicQueryParams,
        installKind = installKind,
        secretRef = null,
        secretPayload = null,
        transportBaseUrl = transport.baseUrl,
        transportSecretRef = transportSecretRef,
        transportSecretPayload = transportSecretPayload
    )
}
```

This drops `pathSecretSegment`, `hasPathSecret`, `secretParams`, `secretRef` (now hardcoded null), and the legacy `secretPayload` branch. The unused `addonSecretRef` private function may be left in place — it's still referenced elsewhere if any helper imports it; otherwise the Kotlin compiler will warn but not error. (Searching the file confirms no other callsite — leaving it in for forward symmetry with the web-side `addonSecretRef` export, which is also retained for backward compat.)

- [ ] **Step 6: Run the AddonSyncCodecTest suite to verify all tests pass**

```bash
cd /Users/jneerdael/Scripts/nexio && ./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.sync.AddonSyncCodecTest"
```

Expected: PASS for the full class — both the existing tests (which used `https://` URLs and either path-segment or bare-manifest installs) and the six new tests added in Step 1.

If an existing test asserts the old `secret_ref = "addon:..."` behavior on a configured URL, update its expectations to match the new `null` semantics in the same step. To check, scan the existing class body for any `assertEquals("addon:` or `assertEquals(..., parsed.secretRef)` assertions. If any survive after the rewrite, update them inline (set the expected to `null`) and rerun.

- [ ] **Step 7: Run the broader Android codec/sync test set as a regression check**

```bash
cd /Users/jneerdael/Scripts/nexio && ./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.core.sync.AddonSyncCodecTest" \
  --tests "com.nexio.tv.core.sync.AddonSyncServiceTest"
```

Expected: PASS. `AddonSyncServiceTest` exercises `pushToRemote` and may construct fixture addon entries. Inspect any failures — if they assert legacy `secret_ref` on a fixture, mirror the same `null` update.

- [ ] **Step 8: Commit**

```bash
git -C /Users/jneerdael/Scripts/nexio add \
  app/src/main/java/com/nexio/tv/core/sync/AddonSyncCodec.kt \
  app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt
git -C /Users/jneerdael/Scripts/nexio commit -m "$(cat <<'EOF'
feat(addons): mirror multi-config + stremio:// support in Android codec

- Rewrite stremio:// scheme to https:// at the top of parseAddonInstallUrl
  and normalizeAddonInstallUrl so users can paste install URLs verbatim.
- Tighten splitAddonTransportUrl to require /manifest.json, matching the
  web hardening from f9b6edf (Torrentio raw-magnet incident).
- Stop emitting legacy secret_ref/secret_payload for v2 installs so two
  same-FQDN configurations don't overwrite each other's vault row.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Bump nexio-web submodule pointer in parent repo

**Files:**
- Modify: `nexio-web` (submodule pointer in parent repo)

- [ ] **Step 1: Verify the submodule has the new commits**

```bash
git -C /Users/jneerdael/Scripts/nexio/nexio-web log --oneline -5
```

Expected: the most recent commits include those created in Tasks 2, 3, and 4 (stremio rewrite, secret_ref=null emission, addAddon dedup change).

- [ ] **Step 2: Stage the submodule pointer update in the parent repo**

```bash
git -C /Users/jneerdael/Scripts/nexio status --short nexio-web
git -C /Users/jneerdael/Scripts/nexio add nexio-web
```

Expected: `git status` shows `M nexio-web` before and an empty diff after `git add nexio-web`.

- [ ] **Step 3: Commit the pointer bump**

```bash
git -C /Users/jneerdael/Scripts/nexio commit -m "$(cat <<'EOF'
build: bump nexio-web for multi-config addons + stremio:// support

Picks up the web-side changes for two-same-FQDN addon installs:
parseAddonInstallUrl scheme rewrite, secret_ref nullification, addAddon
dedup by (transportBaseUrl, transportSecretRef).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: End-to-end smoke (manual, optional but recommended)**

Against a staging Supabase that has Task 1's migration applied:

1. Sign in to the web portal.
2. Install `https://torrentio.strem.fun/<rd-config>/manifest.json` — verify a row appears.
3. Install `stremio://torrentio.strem.fun/<premiumize-config>/manifest.json` — verify a *second* row appears (not an upgrade of the first).
4. In `psql`:
   ```sql
   select id, base_url, secret_ref, transport_secret_ref, install_kind, sort_order
     from public.account_addons_public
     where user_id = auth.uid()
     order by sort_order;
   ```
   Expected: two rows, same `base_url`, `secret_ref` is `null` on both, distinct `transport_secret_ref` values, both `install_kind = 'configured'`.
5. ```sql
   select secret_ref from public.account_secrets
     where secret_type = 'addon_credential'
     and secret_ref like 'addon:torrentio_strem_fun:transport:%';
   ```
   Expected: two rows with distinct hash suffixes.
6. On a paired Android device, force a sync pull and inspect logs / addon list — both Torrentio installs should appear, and stream resolution for each should hit the correct debrid config.

If any step fails, file an issue rather than reverting; the commits are atomic and can be cherry-picked.

---

## Self-review notes (post-write)

- **Spec coverage check:** Migration (✓ Task 1), stremio rewrite web (✓ Task 2), secret_ref=null web (✓ Task 3), dedup-by-transport-secret-ref (✓ Task 4), stremio rewrite Android (✓ Task 5 Step 3), secret_ref=null Android (✓ Task 5 Step 5), strict splitAddonTransportUrl Android (✓ Task 5 Step 4), test plan (covered across each task), rollout ordering (Task 1 first; Task 6 bumps web after web tasks; Android lands separately in next release as designed).
- **Submodule note:** Task 1 (`supabase/`) and Task 5 (`app/`) commit directly to the parent repo. Tasks 2–4 commit inside the `nexio-web` submodule. Task 6 bumps the parent's submodule pointer. The Android changes (Task 5) and submodule bump (Task 6) can land in either order — they're independent.
- **No type drift:** `transportBaseUrl`, `transportSecretRef`, `secretRef`, `installKind` field names are consistent with `types/portal.ts` and Android `ParsedAddonSyncEntry`.
- **No placeholder steps:** every step has the actual file/code/command.
