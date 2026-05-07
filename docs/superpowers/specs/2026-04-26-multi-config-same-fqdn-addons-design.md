# Multi-config installs of the same FQDN addon

**Status:** Design approved · 2026-04-26
**Author:** John Neerdael (jneerdael)
**Scope:** `nexio-web`, Supabase, Android (`app/`)

## Problem

A user cannot install two different configurations of the same FQDN addon
simultaneously. Concretely, both of these Torrentio install URLs target the
same host but encode different debrid backends in the path:

```
stremio://torrentio.strem.fun/debridoptions=nodownloadlinks%7Crealdebrid=L7VSEJBKIQE52BW7NQMSJGYTP3IPDTLKR6RUBNF5GKNZ646G5ZRA/manifest.json
stremio://torrentio.strem.fun/debridoptions=nodownloadlinks%7Cpremiumize=szpwe4fx4ngs8u9q/manifest.json
```

Today three things break installing both at once:

1. The `stremio://` scheme is rejected by `parseAddonInstallUrl` on web and
   Android — `new URL(...)` produces a non-http origin and downstream parsing
   silently degrades.
2. Web `usePortalStore.addAddon` dedupes on
   `normalizeAddonUrl(addon.url) === parsed.addon.url`. Because
   `parseAddonInstallUrl` collapses to origin (`https://torrentio.strem.fun`),
   both URLs collide on dedup and the second install upgrades the first row in
   place instead of being added.
3. Supabase `account_addons_public_user_base_uidx` is unique on
   `(user_id, lower(base_url), coalesce(secret_ref, ''))`. Both URLs produce
   the same `base_url` (`https://torrentio.strem.fun`) and the same legacy
   `secret_ref` (`addon:torrentio_strem_fun`), so even if the client allowed
   two rows the SQL layer would reject the insert.

A secondary correctness issue surfaces from the v2 transport plumbing
(commit `b193a12` and follow-ups). The legacy `secret_ref` payload is no longer
load-bearing for routing — `buildResolvedManifestUrl` prefers
`transport_secret_payload.suffix` (per `6edf035`) — but it is still emitted
during install, and two same-FQDN installs would write to the *same* legacy
vault row, overwriting each other's payload.

## Goals

- Permit two (or more) configurations of the same FQDN addon to coexist as
  separate rows in `account_addons_public`, locally on the web client, and
  locally on the Android client.
- Accept `stremio://...` URLs verbatim from the user. Rewrite to `https://`
  internally; do not relax any of the strict validation introduced by
  `f9b6edf` (the `/manifest.json` suffix invariant).
- Keep credentials out of the public `base_url` column.
- Preserve the post-`7260a8a` "vault writes before state mutation" invariant
  so a partial failure cannot leave an addon row pointing at a non-existent
  vault entry.

## Non-goals

- Any UI redesign of `AddonManager.vue`. Two same-FQDN rows render
  identically by name; the user expects this and accepts it (decision D).
- Per-config catalog disable state on Android. The existing
  `normalizePublicAddonBaseUrl` keys catalog disable state on origin only,
  so disabling a catalog on Torrentio-RD also disables it on
  Torrentio-Premiumize. Documented as a known limitation; not addressed
  here.
- Pruning orphaned legacy `addon:<host>` vault rows that 1b leaves behind.
  Out of scope; future cleanup migration.

## Identity & dedup model

**Composite identity for an installed addon:**
`(user_id, base_url, transport_secret_ref)`.

- `base_url` stays origin-only. No credentials in this column.
- `transport_secret_ref` (already exists, already per-suffix-hashed via
  `addonTransportSecretRef(transport.baseUrl, transport.suffix)`) becomes the
  differentiator between two installs sharing a `base_url`. It is required
  for v2 addons and is unique-per-install by construction.
- **`secret_ref` is set to `null` for all newly created v2 installs (1b).**
  Stopping its emission removes the source of vault-payload overwrites
  between two same-FQDN installs. The legacy field is no longer load-bearing
  for v2 transport routing.
- Existing legacy v1 rows keep their `secret_ref` untouched. They are
  invisible to the new dedup key (which targets `transport_secret_ref`),
  continue to work, and migrate naturally when the user re-saves.
- Each row keeps its UUID `id`; sync push/pull already round-trips it. Local
  state can disambiguate by `id` even when `base_url` matches.

**Dedup key in `addAddon` becomes:**

```ts
const existingIndex = state.value.addons.findIndex((addon) =>
  addon.transportBaseUrl === parsed.addon.transportBaseUrl &&
  addon.transportSecretRef === parsed.transportSecretRef
)
```

A v1→v2 fallback comparator handles the case where the user re-pastes a
configured URL whose existing row is still v1: if no v2 match found, also
check `addon.transportSchemaVersion !== 2 && normalizeAddonUrl(addon.url) ===
parsed.addon.url`. Found → upgrade in place.

## Supabase schema migration

New file: `supabase/migrations/20260426XXXXXX_addon_transport_unique_index.sql`.

```sql
drop index if exists public.account_addons_public_user_base_uidx;

create unique index account_addons_public_user_transport_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(transport_secret_ref, '')
  );
```

**Why coalesce on `transport_secret_ref` instead of `secret_ref`:** for v2
installs, `transport_secret_ref` is always set and per-suffix unique → two
same-FQDN installs land on different keys. For legacy v1 rows that never went
through v2 backfill, `transport_secret_ref` may be NULL — `coalesce(... , '')`
keeps the old "one row per origin" behavior for them. The previous v2
backfill (`20260420231500_add_addon_transport_v2.sql`, lines 24–28) already
populated `transport_secret_ref` for every existing row from `secret_ref`, so
post-backfill there are no NULLs in practice.

**No RPC changes needed.** `sync_push_account_addons` already does
`delete + insert` keyed only on `user_id`. `sync_pull_account_snapshot`
already returns `transport_secret_ref` per row.
`service_get_account_addon_transport` already keys on the row UUID.

**No data migration needed.** Existing rows continue to satisfy the new
index without modification.

## Web client changes

### `server/utils/account-secrets.ts` and `utils/account-secrets.ts`

Both copies kept in sync; same logic in both.

**1. `stremio://` → `https://` rewrite at the top of `parseAddonInstallUrl`.**
Before the `new URL(candidate)` call:

```ts
const candidate = rawUrl.trim().replace(/^stremio:\/\//i, 'https://')
```

Downstream code (`splitAddonTransportUrl`, the strict `/manifest.json` check,
host-based parser-preset detection) sees a normal `https://` URL and is
unchanged. The strict validator from `f9b6edf` continues to reject anything
that does not end in `/manifest.json` after the rewrite.

**2. `secretRef` becomes `null` for v2 installs.** In `parseAddonInstallUrl`,
replace:

```ts
const secretRef = hasQuerySecrets || hasPathSecret ? addonSecretRef(normalized) : null
```

with:

```ts
const secretRef: string | null = null
```

The associated `secretType` and `secretPayload` returned from the function
become `null` accordingly. The local variables `hasQuerySecrets`,
`hasPathSecret`, `legacyPathSegment`, and the path-segment / query-param
secret extraction are deleted — they exist only to populate the legacy
fields. `installKind` derives from whether the transport suffix has any
path/query content beyond `/manifest.json` (i.e.,
`transport.suffix !== '/manifest.json'` → `'configured'`, else `'manifest'`).

**3. `addonSecretRef` and the `sensitiveQueryKeys` set:** kept for backward
compatibility (still referenced from any code path that reads legacy v1
rows), but no longer called from `parseAddonInstallUrl`.

### `composables/usePortalStore.ts` (`addAddon`, around line 1389)

**Dedup key change:** as in the identity model section above.

**`shouldUpgradeAddonTransport` simplification:** with the new dedup key, the
only way `existingIndex >= 0` is hit for v2 installs is when the user
re-pastes the *exact* same configured URL. The fields used to differ
(`secretRef`, `installKind`, `manifestUrl`, `transportSchemaVersion`,
`transportBaseUrl`, `transportSecretRef`) will, by construction, all match.
The "upgrade" branch becomes near-noop for v2; we keep it (truncated) for
v1→v2 re-saves where the user re-pastes a URL whose existing row is still
v1.

**`saveSecret` calls:** the post-`7260a8a` ordering (vault writes before
state mutation) is preserved. The legacy `parsed.secretType && parsed.secretRef
&& parsed.secretPayload` block becomes dead under 1b — delete it from the v2
paths to keep the code honest. The transport `saveSecret` call remains.

**`removeAddon`:** the `if (addon?.secretRef) deleteSecret(...)` block stays
for legacy v1 rows. The `transportSecretRef` `deleteSecret` call already
exists and continues to remove the per-install vault row — important since
two installs no longer share a vault entry.

### `AddonManager.vue`

No template changes (UI decision D). The existing `addonPathLabel` already
shows `host + ' · Custom Path'` for configured addons, which is fine for two
same-FQDN rows; both will read identically.

### Web tests

- New `tests/account-secrets-stremio-rewrite.test.ts`:
  `parseAddonInstallUrl('stremio://torrentio.strem.fun/.../manifest.json')`
  produces the same result as the `https://` form; input without
  `/manifest.json` still throws.
- New `tests/account-secrets-multi-config.test.ts`: two Torrentio install
  URLs (RD vs Premiumize) parse to records with the same `transportBaseUrl`
  and `url` but different `transportSecretRef`, and `secretRef` is `null` on
  both.
- Update `tests/addon-delete-persistence.test.mjs`: loosen source-grep
  assertions so they scope to `transportSecretRef` saveSecret +
  `markAddonsChanged` ordering only (the legacy `secretRef` saveSecret call
  no longer exists on v2 paths).
- New `tests/use-portal-store-multi-config.test.ts` (or extension to the
  existing portal store source-grep tests): assert the dedup key is
  `(transportBaseUrl, transportSecretRef)`, plus the legacy-fallback
  comparator string is present.

## Android client changes

### `app/src/main/java/com/nexio/tv/core/sync/AddonSyncCodec.kt`

**1. `stremio://` → `https://` rewrite at the top of `parseAddonInstallUrl`
and `normalizeAddonInstallUrl`.** Replace before the `URL(candidate)`
constructor:

```kotlin
val candidate = rawUrl.trim()
    .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
```

Both functions need the rewrite because `AddonPreferences.canonicalizeUrl`
gates local writes via `normalizeAddonInstallUrl`, and `pushToRemote` calls
`parseAddonInstallUrl`.

**2. `secretRef` becomes `null` for v2 installs** (matching web 1b). In
`parseAddonInstallUrl`, replace:

```kotlin
val secretRef = if (hasPathSecret || secretParams.isNotEmpty()) addonSecretRef(publicBaseUrl) else null
```

with `val secretRef: String? = null`. Drop the `secretPayload` legacy branch
(lines 143–155) so the returned `secretPayload` is always `null`. The
`pathSecretSegment`, `hasPathSecret`, `secretParams`, `publicQueryParams`
extraction stays — `publicQueryParams` is still used for sync, and
`installKind` derives from whether the transport suffix has any path/query
content beyond `/manifest.json`.

**3. Tighten `splitAddonTransportUrl` to match web's strict behavior**
(post-`f9b6edf`). Today it silently auto-appends `/manifest.json` to missing
paths; instead, throw `IllegalArgumentException` when the path does not end
in `/manifest.json`. This aligns Android with the same hardening rationale
(Torrentio raw-magnet incident from the `f9b6edf` commit message). The
`pushToRemote` callsite already wraps `parseAddonInstallUrl` in
`runCatching` and logs the malformed URL, so the change degrades gracefully
for any local addon URLs that fail validation after the upgrade.

### `AddonPreferences.kt`

No changes. `AddonInstallConfig.url` already stores the full install URL
with path/query, so two same-FQDN configs already coexist locally as
distinct URL strings. Dedup at `AddonPreferences.addAddon` (line 103) is
case-insensitive full-URL match — works correctly.

### `AddonSyncService.pushToRemote` (line 56)

No changes. Passes the full install URL into `parseAddonInstallUrl`, which
now produces distinct `transportSecretRef` per install. Each install writes
its own per-suffix vault row via `sync_set_account_secret`, then
`sync_push_account_addons` writes both rows.

### Android tests

In `app/src/test/java/com/nexio/tv/core/sync/AddonSyncCodecTest.kt` (or new
file):

- `parseAddonInstallUrl` accepts `stremio://...manifest.json` and equals
  the `https://` form.
- Two Torrentio install URLs with different path-suffix configs produce
  identical `transportBaseUrl` but distinct `transportSecretRef`, and both
  have `secretRef == null`.
- A URL without `/manifest.json` throws `IllegalArgumentException` from
  the tightened `splitAddonTransportUrl`.

## Rollout

Deploy in order:

1. **Supabase migration first.** Backwards-compatible: existing single-FQDN
   rows continue to work because `transport_secret_ref` is already populated
   for each row.
2. **Web changes.**
3. **Android changes** in the next release. In the gap, Android clients
   running the old codec keep working — they cannot create two same-FQDN
   installs from the Android UI until updated, which is fine.

The new index permits same-`base_url` rows when their `transport_secret_ref`
differs. Clients must not attempt two-same-FQDN installs before the
migration is applied; otherwise `sync_push_account_addons` errors at the SQL
level with a unique violation. With the deploy order above, this cannot
happen.

## Known leftover

With 1b, a future re-save of a legacy v1 row sets `secret_ref` to `null` on
the addon row but does not delete the legacy `addon:<host>` vault entry.
Acceptable — legacy entries are small JSON blobs and a separate cleanup
migration can prune them later.

## Test plan

**Supabase**

- Migration apply/rollback test in `supabase/rollback/`.
- Manual smoke: insert two rows with same `base_url`, different
  `transport_secret_ref` → both succeed. Insert a third with the same
  `transport_secret_ref` as one of them → unique violation.

**Web** (`tests/`): see test list in the web client section.

**Android** (`app/src/test/`): see test list in the Android client section.

**End-to-end (manual, optional):** using a staging Supabase, run web → install
Torrentio with RD; install Torrentio with Premiumize; verify two rows in
`account_addons_public`, two distinct `account_secrets` rows for
`addon:torrentio_strem_fun:transport:*`, and `buildResolvedManifestUrl`
produces the correct two URLs when the device pulls the snapshot.
