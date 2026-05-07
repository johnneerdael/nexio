# Account Config V7 Conflict Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a v7 account-config sync contract so Supabase rejects or merges stale web/Android writes instead of letting full stale payloads overwrite newer changes.

**Architecture:** Keep v6 RPCs unchanged for older clients. Add a v7-only RPC that accepts a base revision and changed paths, stores per-path revision metadata, merges non-conflicting changed paths into the current server payload, and rejects same-path stale writes. Update web and Android clients to track changed paths and push with the last pulled server revision.

**Tech Stack:** Supabase/Postgres SQL RPCs, Nuxt server API and Vue store, Android Kotlin coroutines/DataStore sync, Kotlin serialization, Node `tsx --test`, Gradle unit tests.

---

## Root Cause Summary

The shared database already stores `sync_revision`, `updated_at`, and `updated_from` in `account_settings_public`, and `sync_pull_account_snapshot` returns the latest revision from `account_sync_events`.

The bug is that `sync_push_account_settings` accepts only:

```sql
p_settings_payload jsonb,
p_source text default 'app',
p_contract_version integer default 6
```

It unconditionally upserts a full normalized payload and allocates a new revision. Neither web nor Android sends the revision the client last pulled. This means any client can write a stale full snapshot later in time and become the newest accepted server write, even if the user changed the same settings from another client moments earlier.

The previous Android startup gate prevents one specific stale startup push, but it is not a general conflict-resolution system.

## File Structure

- Create `supabase/migrations/20260412193000_account_config_v7_conflict_resolution.sql`
  - Adds path-level revision metadata table.
  - Adds v7-only conflict-aware push RPC.
  - Leaves existing v6 `sync_push_account_settings` and `sync_pull_account_snapshot` behavior intact.
- Modify `supabase/account_settings_sync.sql`
  - Mirror the v7 migration for local/manual patch usage.
- Modify `nexio-web/types/portal.ts`
  - Bump web account config contract to 7.
  - Add v7 push response types.
- Create `nexio-web/utils/portal-sync-paths.ts`
  - Canonicalize web changed paths.
- Modify `nexio-web/composables/usePortalStore.ts`
  - Track local changed paths.
  - Send `baseRevision` and `changedPaths` during persist.
  - Handle v7 conflict responses by bootstrapping the latest server snapshot.
- Modify `nexio-web/server/api/account/persist.post.ts`
  - Route contract v7 persists through `sync_push_account_settings_v7`.
  - Return HTTP 409 on same-path conflicts.
- Add `nexio-web/tests/portal-sync-paths.test.ts`
  - Unit-test path canonicalization.
- Modify `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - Bump contract payload default to 7.
  - Add v7 push response model.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
  - Bump contract constant to 7.
  - Add changed-path flow support and v7 push params.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Accumulate changed paths from local observers.
  - Track last pulled server revision.
  - Push via `sync_push_account_settings_v7`.
  - Pull latest on conflict without overwriting Supabase.
- Modify `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
  - Add tests for v7 push params and changed-path emission.

## Conflict Semantics

- `p_base_revision` is the revision the client last applied from Supabase.
- `p_changed_paths` is the set of account-config paths changed locally since that base revision.
- If the current server revision equals `p_base_revision`, the server stores the incoming normalized payload and records each changed path at the new revision.
- If the server revision is newer than `p_base_revision`, the server checks whether any changed path has a path revision greater than `p_base_revision`.
- If no changed paths conflict, the server merges only those changed paths from the incoming payload into the current server payload and returns success.
- If any changed path conflicts, the server returns `applied=false` with `conflict_paths` and the current normalized server settings. The client must apply/pull current server state. A user can then make a new edit on top of that newer baseline.
- Empty `p_changed_paths` means there is no local settings write to apply; the server returns the current revision without mutating `account_settings_public`.

This defines source of truth as newest accepted server revision, with stale same-path writes rejected. It does not trust client wall-clock time.

---

### Task 1: Add Supabase v7 Conflict-Aware RPC

**Files:**
- Create: `supabase/migrations/20260412193000_account_config_v7_conflict_resolution.sql`
- Modify: `supabase/account_settings_sync.sql`
- Test: manual Supabase SQL checks

- [ ] **Step 1: Create the migration**

Create `supabase/migrations/20260412193000_account_config_v7_conflict_resolution.sql`:

```sql
create table if not exists public.account_settings_public_field_versions (
  user_id uuid not null references auth.users(id) on delete cascade,
  field_path text not null,
  sync_revision bigint not null,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'app',
  primary key (user_id, field_path)
);

alter table public.account_settings_public_field_versions enable row level security;

drop policy if exists "account_settings_field_versions_owner_select"
  on public.account_settings_public_field_versions;

create policy "account_settings_field_versions_owner_select"
  on public.account_settings_public_field_versions
  for select
  to authenticated
  using (user_id = public.sync_owner_id());

create or replace function public.account_settings_path_array(p_path text)
returns text[]
language sql
immutable
set search_path = public
as $$
  select string_to_array(trim(both '.' from coalesce(p_path, '')), '.')
$$;

create or replace function public.account_settings_path_value(p_payload jsonb, p_path text)
returns jsonb
language sql
immutable
set search_path = public
as $$
  select coalesce(p_payload, '{}'::jsonb) #> public.account_settings_path_array(p_path)
$$;

create or replace function public.account_settings_merge_changed_paths(
  p_current_payload jsonb,
  p_incoming_payload jsonb,
  p_changed_paths text[]
)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  v_result jsonb := coalesce(p_current_payload, '{}'::jsonb);
  v_path text;
  v_value jsonb;
begin
  foreach v_path in array coalesce(p_changed_paths, array[]::text[]) loop
    if v_path is null or trim(v_path) = '' then
      continue;
    end if;

    v_value := public.account_settings_path_value(p_incoming_payload, v_path);

    if v_value is not null then
      v_result := jsonb_set(
        v_result,
        public.account_settings_path_array(v_path),
        v_value,
        true
      );
    end if;
  end loop;

  return v_result;
end;
$$;

create or replace function public.sync_push_account_settings_v7(
  p_settings_payload jsonb,
  p_base_revision bigint,
  p_changed_paths text[],
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_current_payload jsonb := '{}'::jsonb;
  v_current_revision bigint := 0;
  v_current_updated_at timestamptz := null;
  v_current_updated_from text := null;
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
  v_source text := coalesce(nullif(trim(p_source), ''), 'app');
  v_changed_paths text[] := coalesce(
    array(
      select distinct trim(path)
      from unnest(coalesce(p_changed_paths, array[]::text[])) as path
      where trim(path) <> ''
      order by trim(path)
    ),
    array[]::text[]
  );
  v_conflict_paths text[] := array[]::text[];
  v_incoming_payload jsonb := '{}'::jsonb;
  v_next_payload jsonb := '{}'::jsonb;
  v_snapshot_payload jsonb := '{}'::jsonb;
begin
  select
    coalesce(settings_payload, '{}'::jsonb),
    coalesce(sync_revision, 0),
    updated_at,
    updated_from
  into
    v_current_payload,
    v_current_revision,
    v_current_updated_at,
    v_current_updated_from
  from public.account_settings_public
  where user_id = v_user_id;

  v_current_revision := coalesce(v_current_revision, 0);

  v_snapshot_payload := public.account_settings_v2_snapshot_payload(v_current_payload);

  if cardinality(v_changed_paths) = 0 then
    return jsonb_build_object(
      'applied', true,
      'sync_revision', v_current_revision,
      'updated_at', v_current_updated_at,
      'updated_from', v_current_updated_from,
      'settings', coalesce(v_snapshot_payload, '{}'::jsonb),
      'conflict_paths', '[]'::jsonb
    );
  end if;

  select coalesce(array_agg(field_path order by field_path), array[]::text[])
    into v_conflict_paths
  from public.account_settings_public_field_versions
  where user_id = v_user_id
    and field_path = any(v_changed_paths)
    and sync_revision > coalesce(p_base_revision, -1);

  if cardinality(v_conflict_paths) > 0 then
    return jsonb_build_object(
      'applied', false,
      'sync_revision', v_current_revision,
      'updated_at', v_current_updated_at,
      'updated_from', v_current_updated_from,
      'settings', coalesce(v_snapshot_payload, '{}'::jsonb),
      'conflict_paths', to_jsonb(v_conflict_paths)
    );
  end if;

  v_incoming_payload := public.account_settings_public_storage_payload(
    p_payload => p_settings_payload,
    p_existing_payload => v_current_payload,
    p_contract_version => 7
  );

  if v_current_revision = coalesce(p_base_revision, 0) then
    v_next_payload := v_incoming_payload;
  else
    v_next_payload := public.account_settings_merge_changed_paths(
      p_current_payload => v_current_payload,
      p_incoming_payload => v_incoming_payload,
      p_changed_paths => v_changed_paths
    );
  end if;

  insert into public.account_settings_public (
    user_id,
    settings_payload,
    sync_revision,
    updated_at,
    updated_from
  )
  values (
    v_user_id,
    v_next_payload,
    v_revision,
    v_updated_at,
    v_source
  )
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  insert into public.account_settings_public_field_versions (
    user_id,
    field_path,
    sync_revision,
    updated_at,
    updated_from
  )
  select
    v_user_id,
    path,
    v_revision,
    v_updated_at,
    v_source
  from unnest(v_changed_paths) as path
  on conflict (user_id, field_path) do update
    set sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', v_source);

  return jsonb_build_object(
    'applied', true,
    'sync_revision', v_revision,
    'updated_at', v_updated_at,
    'updated_from', v_source,
    'settings', public.account_settings_v2_snapshot_payload(v_next_payload),
    'conflict_paths', '[]'::jsonb
  );
end;
$$;

revoke all on function public.sync_push_account_settings_v7(jsonb, bigint, text[], text) from public;
grant execute on function public.sync_push_account_settings_v7(jsonb, bigint, text[], text) to authenticated;
```

- [ ] **Step 2: Mirror the migration in `supabase/account_settings_sync.sql`**

Append the same SQL block from Step 1 to the end of `supabase/account_settings_sync.sql`.

- [ ] **Step 3: Run migration list**

Run:

```bash
supabase migration list
```

Expected: command succeeds and shows `20260412193000_account_config_v7_conflict_resolution.sql` as a local migration.

- [ ] **Step 4: Apply locally**

Run:

```bash
supabase migration up
```

Expected: command succeeds without replacing the existing `sync_push_account_settings` v6 RPC.

- [ ] **Step 5: Manually verify old v6 writes still work**

Run a local SQL smoke check against the Supabase database:

```sql
select routine_name
from information_schema.routines
where routine_schema = 'public'
  and routine_name in ('sync_push_account_settings', 'sync_push_account_settings_v7')
order by routine_name;
```

Expected rows:

```text
sync_push_account_settings
sync_push_account_settings_v7
```

- [ ] **Step 6: Commit**

```bash
git add supabase/migrations/20260412193000_account_config_v7_conflict_resolution.sql supabase/account_settings_sync.sql
git commit -m "feat(sync): add v7 account config conflict RPC"
```

---

### Task 2: Add Web Changed-Path Tracking and V7 Persist API

**Files:**
- Modify: `nexio-web/types/portal.ts`
- Create: `nexio-web/utils/portal-sync-paths.ts`
- Modify: `nexio-web/server/api/account/persist.post.ts`
- Modify: `nexio-web/composables/usePortalStore.ts`
- Test: `nexio-web/tests/portal-sync-paths.test.ts`

- [ ] **Step 1: Add the path utility test**

Create `nexio-web/tests/portal-sync-paths.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { canonicalPortalSyncPath, uniquePortalSyncPaths } from '../utils/portal-sync-paths.ts'

test('canonicalPortalSyncPath keeps known synced leaf paths', () => {
  assert.equal(
    canonicalPortalSyncPath('integrations.subtitleTranslation.model'),
    'integrations.subtitleTranslation.model'
  )
  assert.equal(
    canonicalPortalSyncPath('integrations.imdb.baseUrl'),
    'integrations.imdb.baseUrl'
  )
  assert.equal(
    canonicalPortalSyncPath('integrations.theIntroDb.enabled'),
    'integrations.theIntroDb.enabled'
  )
})

test('canonicalPortalSyncPath maps nested formatter custom template fields', () => {
  assert.equal(
    canonicalPortalSyncPath('formatter.customTemplate.descriptionTemplate'),
    'formatter.customTemplate.descriptionTemplate'
  )
})

test('uniquePortalSyncPaths filters duplicates and unknown paths', () => {
  assert.deepEqual(
    uniquePortalSyncPaths([
      'integrations.imdb.baseUrl',
      'integrations.imdb.baseUrl',
      'local.ui.openPanel',
      'catalogs.home.homeCatalogOrderKeys'
    ]),
    ['integrations.imdb.baseUrl', 'catalogs.home.homeCatalogOrderKeys']
  )
})
```

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-sync-paths.test.ts
```

Expected: FAIL with module-not-found for `../utils/portal-sync-paths.ts`.

- [ ] **Step 3: Add the web path utility**

Create `nexio-web/utils/portal-sync-paths.ts`:

```ts
const syncedPathSet = new Set([
  'integrations.debrid.premiumize.configured',
  'integrations.debrid.premiumize.customerId',
  'integrations.debrid.realDebrid.connected',
  'integrations.debrid.realDebrid.username',
  'integrations.debrid.realDebrid.pending',
  'integrations.debrid.realDebrid.deviceCode',
  'integrations.debrid.realDebrid.userCode',
  'integrations.debrid.realDebrid.verificationUrl',
  'integrations.debrid.realDebrid.expiresAt',
  'integrations.debrid.torBox.configured',
  'integrations.debrid.torBox.email',
  'integrations.debrid.torBox.plan',
  'integrations.debrid.easyDebrid.configured',
  'integrations.debrid.easyDebrid.userId',
  'integrations.debrid.easyDebrid.paidUntil',
  'integrations.theIntroDb.enabled',
  'integrations.theIntroDb.showIntroButton',
  'integrations.theIntroDb.showRecapButton',
  'integrations.theIntroDb.showCreditsButton',
  'integrations.theIntroDb.showPreviewButton',
  'integrations.tmdb.enabled',
  'integrations.tmdb.useArtwork',
  'integrations.tmdb.useBasicInfo',
  'integrations.tmdb.useDetails',
  'integrations.tmdb.useCredits',
  'integrations.tmdb.useProductions',
  'integrations.tmdb.useNetworks',
  'integrations.tmdb.useEpisodes',
  'integrations.tmdb.useMoreLikeThis',
  'integrations.tmdb.useCollections',
  'integrations.omdb.enabled',
  'integrations.imdb.enabled',
  'integrations.imdb.baseUrl',
  'integrations.mdblist.enabled',
  'integrations.mdblist.showTrakt',
  'integrations.mdblist.showImdb',
  'integrations.mdblist.showTmdb',
  'integrations.mdblist.showLetterboxd',
  'integrations.mdblist.showTomatoes',
  'integrations.mdblist.showAudience',
  'integrations.mdblist.showMetacritic',
  'integrations.animeSkip.enabled',
  'integrations.animeSkip.clientId',
  'integrations.gemini.enabled',
  'integrations.subtitleTranslation.enabled',
  'integrations.subtitleTranslation.provider',
  'integrations.subtitleTranslation.model',
  'integrations.subtitleTranslation.baseUrl',
  'integrations.posterRatings.rpdbEnabled',
  'integrations.posterRatings.topPostersEnabled',
  'integrations.traktAuth.connected',
  'integrations.traktAuth.username',
  'integrations.traktAuth.userSlug',
  'integrations.traktAuth.connectedAt',
  'integrations.traktAuth.pending',
  'integrations.simklAuth.connected',
  'integrations.simklAuth.username',
  'integrations.simklAuth.accountId',
  'integrations.simklAuth.accountType',
  'integrations.simklAuth.pending',
  'catalogs.home.heroCatalogKeys',
  'catalogs.home.homeCatalogOrderKeys',
  'catalogs.home.disabledHomeCatalogKeys',
  'catalogs.trakt.catalogEnabledSet',
  'catalogs.trakt.catalogOrder',
  'catalogs.trakt.selectedPopularListKeys',
  'catalogs.simkl.catalogEnabledSet',
  'catalogs.simkl.catalogOrder',
  'catalogs.mdblist.hiddenPersonalListKeys',
  'catalogs.mdblist.selectedTopListKeys',
  'catalogs.mdblist.catalogOrder',
  'playback.streamSelection.trackingProvider',
  'formatter.enabled',
  'formatter.selectedTemplateId',
  'formatter.customTemplate',
  'formatter.customTemplate.id',
  'formatter.customTemplate.label',
  'formatter.customTemplate.nameTemplate',
  'formatter.customTemplate.descriptionTemplate'
])

export function canonicalPortalSyncPath(path: string): string | null {
  const normalized = path.trim()
  return syncedPathSet.has(normalized) ? normalized : null
}

export function uniquePortalSyncPaths(paths: Iterable<string>): string[] {
  const seen = new Set<string>()
  const result: string[] = []

  for (const path of paths) {
    const canonical = canonicalPortalSyncPath(path)
    if (!canonical || seen.has(canonical)) continue
    seen.add(canonical)
    result.push(canonical)
  }

  return result
}
```

- [ ] **Step 4: Run the path utility test and verify GREEN**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-sync-paths.test.ts
```

Expected: PASS, 3 tests passing.

- [ ] **Step 5: Update web types for v7**

In `nexio-web/types/portal.ts`, change:

```ts
export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 6
```

to:

```ts
export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 7
```

Add these types after `PortalSnapshot`:

```ts
export type AccountConfigV7PushResult = {
  applied: boolean
  sync_revision: number
  updated_at: string | null
  updated_from?: string | null
  settings?: PortalSettings
  conflict_paths?: string[]
}
```

- [ ] **Step 6: Update web persist request body**

In `nexio-web/server/api/account/persist.post.ts`, change `PersistBody` to:

```ts
type PersistBody = {
  settings?: PortalSettings
  addons?: AddonRecord[]
  baseRevision?: number
  changedPaths?: string[]
}
```

Add this type under `RpcMutationResult`:

```ts
type AccountConfigV7PushResult = {
  applied?: boolean
  sync_revision?: number
  updated_at?: string | null
  settings?: PortalSettings
  conflict_paths?: string[]
}
```

Replace the settings RPC call with:

```ts
  const changedPaths = Array.isArray(body.changedPaths) ? body.changedPaths.filter(Boolean) : []
  const baseRevision = Number.isFinite(body.baseRevision) ? Number(body.baseRevision) : 0

  const settingsResult = await supabaseFetch<AccountConfigV7PushResult>('/rest/v1/rpc/sync_push_account_settings_v7', {
    method: 'POST',
    body: JSON.stringify({
      p_settings_payload: body.settings,
      p_base_revision: baseRevision,
      p_changed_paths: changedPaths,
      p_source: 'web'
    })
  }, token)

  if (settingsResult.applied === false) {
    throw createError({
      statusCode: 409,
      statusMessage: 'Settings changed elsewhere. Reload before saving.',
      data: {
        syncRevision: settingsResult.sync_revision ?? baseRevision,
        lastSyncedAt: settingsResult.updated_at ?? null,
        settings: settingsResult.settings ?? body.settings,
        conflictPaths: settingsResult.conflict_paths ?? []
      }
    })
  }
```

In the `syncRevision` calculation, replace `settingsResult[0]?.sync_revision` with `settingsResult.sync_revision`.

In the `updatedAt` calculation, replace `settingsResult[0]?.updated_at` with `settingsResult.updated_at`.

- [ ] **Step 7: Track dirty paths in the portal store**

In `nexio-web/composables/usePortalStore.ts`, add this import:

```ts
import { uniquePortalSyncPaths } from '../utils/portal-sync-paths.ts'
```

Near `let persistTimer`, add:

```ts
let pendingChangedPaths = new Set<string>()
```

In `updateSetting(path: string, nextValue: unknown)`, after `state.value.settings = draft as PortalSettings`, add:

```ts
    const changedPath = uniquePortalSyncPaths([path])[0]
    if (changedPath) {
      pendingChangedPaths.add(changedPath)
    }
```

In `persistSnapshot()`, change the payload construction to:

```ts
    const changedPaths = uniquePortalSyncPaths(pendingChangedPaths)
    const baseRevision = state.value.syncRevision
    const payload: PersistPayload & { baseRevision: number; changedPaths: string[] } = {
      settings,
      addons: state.value.addons,
      baseRevision,
      changedPaths
    }
```

After a successful server response, add:

```ts
      pendingChangedPaths.clear()
```

Inside the catch block, before setting `state.value.error`, add:

```ts
      if ((error as { statusCode?: number })?.statusCode === 409) {
        await bootstrap(true)
      }
```

In `bootstrap()`, immediately after `remoteSignature = snapshotSignature(state.value.settings, state.value.addons)`, add:

```ts
      pendingChangedPaths.clear()
```

- [ ] **Step 8: Run focused web tests**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-sync-paths.test.ts tests/portal-contract-v4.test.ts
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add nexio-web/types/portal.ts nexio-web/utils/portal-sync-paths.ts nexio-web/server/api/account/persist.post.ts nexio-web/composables/usePortalStore.ts nexio-web/tests/portal-sync-paths.test.ts
git commit -m "feat(web): push account config with v7 revisions"
```

---

### Task 3: Add Android V7 Push Model and Changed Paths

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Write failing Android contract tests**

In `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`, add:

```kotlin
    @Test
    fun `buildAccountConfigSyncPushParamsV7 includes base revision and changed paths`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                imdb = ImdbSyncSettings(enabled = true, baseUrl = "https://ratings.example.com")
            ),
            heroCatalogKeys = emptyList(),
            homeCatalogOrderKeys = emptyList(),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = emptyList(),
            traktCatalogOrder = emptyList(),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        val params = buildAccountConfigSyncPushParamsV7(
            payload = payload,
            baseRevision = 123,
            changedPaths = listOf("integrations.imdb.baseUrl")
        )

        assertEquals("123", params["p_base_revision"].toString())
        assertEquals("\"app\"", params["p_source"].toString())
        assertTrue(params["p_changed_paths"].toString().contains("integrations.imdb.baseUrl"))
    }

    @Test
    fun `observeAccountConfigSyncChangedPaths emits changed path labels`() = runTest {
        val imdbSettings = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChangedPaths(
                heroCatalogSelections = MutableSharedFlow<Unit>(),
                homeCatalogOrderKeys = MutableSharedFlow<Unit>(),
                disabledHomeCatalogKeys = MutableSharedFlow<Unit>(),
                tmdbSettings = MutableSharedFlow<Unit>(),
                mdbListSettings = MutableSharedFlow<Unit>(),
                mdbListCatalogPreferences = MutableSharedFlow<Unit>(),
                omdbSettings = MutableSharedFlow<Unit>(),
                theIntroDbSettings = MutableSharedFlow<Unit>(),
                animeSkipEnabled = MutableSharedFlow<Unit>(),
                animeSkipClientId = MutableSharedFlow<Unit>(),
                subtitleTranslationSettings = MutableSharedFlow<Unit>(),
                imdbSettings = imdbSettings,
                posterRatingsSettings = MutableSharedFlow<Unit>(),
                premiumizeSettings = MutableSharedFlow<Unit>(),
                premiumizeAccountState = MutableSharedFlow<Unit>(),
                torBoxSettings = MutableSharedFlow<Unit>(),
                torBoxAccountState = MutableSharedFlow<Unit>(),
                easyDebridSettings = MutableSharedFlow<Unit>(),
                easyDebridAccountState = MutableSharedFlow<Unit>(),
                realDebridState = MutableSharedFlow<Unit>(),
                traktAuthState = MutableSharedFlow<Unit>(),
                traktCatalogPreferences = MutableSharedFlow<Unit>(),
                simklCatalogPreferences = MutableSharedFlow<Unit>(),
                simklAuthState = MutableSharedFlow<Unit>(),
                playerSettings = MutableSharedFlow<Unit>()
            ).first()
        }

        imdbSettings.emit(Unit)
        advanceUntilIdle()

        assertEquals("integrations.imdb", emission.await())
    }
```

- [ ] **Step 2: Run Android test and verify RED**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: FAIL because `buildAccountConfigSyncPushParamsV7` and `observeAccountConfigSyncChangedPaths` do not exist.

- [ ] **Step 3: Add Android response model**

In `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`, change:

```kotlin
val schemaVersion: Int = 6,
```

to:

```kotlin
val schemaVersion: Int = 7,
```

Add after `AccountSyncMutationResult`:

```kotlin
@Serializable
data class AccountConfigV7PushResult(
    val applied: Boolean = true,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_from") val updatedFrom: String? = null,
    val settings: AccountConfigSyncPayload = AccountConfigSyncPayload(),
    @SerialName("conflict_paths") val conflictPaths: List<String> = emptyList()
)
```

- [ ] **Step 4: Add Android v7 push params**

In `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`, change:

```kotlin
internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 6
```

to:

```kotlin
internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 7
```

Add:

```kotlin
internal fun buildAccountConfigSyncPushParamsV7(
    payload: AccountConfigSyncPayload,
    baseRevision: Long,
    changedPaths: List<String>
): JsonObject {
    return buildJsonObject {
        put(
            "p_settings_payload",
            Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload)
        )
        put("p_base_revision", baseRevision)
        put(
            "p_changed_paths",
            Json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()),
                changedPaths.distinct().filter(String::isNotBlank)
            )
        )
        put("p_source", "app")
    }
}
```

- [ ] **Step 5: Add Android changed-path flow**

In `AccountConfigSyncContract.kt`, add:

```kotlin
internal fun observeAccountConfigSyncChangedPaths(
    heroCatalogSelections: Flow<Unit>,
    homeCatalogOrderKeys: Flow<Unit>,
    disabledHomeCatalogKeys: Flow<Unit>,
    tmdbSettings: Flow<Unit>,
    mdbListSettings: Flow<Unit>,
    mdbListCatalogPreferences: Flow<Unit>,
    omdbSettings: Flow<Unit>,
    theIntroDbSettings: Flow<Unit>,
    animeSkipEnabled: Flow<Unit>,
    animeSkipClientId: Flow<Unit>,
    subtitleTranslationSettings: Flow<Unit>,
    imdbSettings: Flow<Unit>,
    posterRatingsSettings: Flow<Unit>,
    premiumizeSettings: Flow<Unit>,
    premiumizeAccountState: Flow<Unit>,
    torBoxSettings: Flow<Unit>,
    torBoxAccountState: Flow<Unit>,
    easyDebridSettings: Flow<Unit>,
    easyDebridAccountState: Flow<Unit>,
    realDebridState: Flow<Unit>,
    traktAuthState: Flow<Unit>,
    traktCatalogPreferences: Flow<Unit>,
    simklCatalogPreferences: Flow<Unit>,
    simklAuthState: Flow<Unit>,
    playerSettings: Flow<Unit>
): Flow<String> {
    return merge(
        heroCatalogSelections.map { "catalogs.home.heroCatalogKeys" },
        homeCatalogOrderKeys.map { "catalogs.home.homeCatalogOrderKeys" },
        disabledHomeCatalogKeys.map { "catalogs.home.disabledHomeCatalogKeys" },
        tmdbSettings.map { "integrations.tmdb" },
        mdbListSettings.map { "integrations.mdblist" },
        mdbListCatalogPreferences.map { "catalogs.mdblist" },
        omdbSettings.map { "integrations.omdb.enabled" },
        theIntroDbSettings.map { "integrations.theIntroDb" },
        animeSkipEnabled.map { "integrations.animeSkip.enabled" },
        animeSkipClientId.map { "integrations.animeSkip.clientId" },
        subtitleTranslationSettings.map { "integrations.subtitleTranslation" },
        imdbSettings.map { "integrations.imdb" },
        posterRatingsSettings.map { "integrations.posterRatings" },
        premiumizeSettings.map { "integrations.debrid.premiumize" },
        premiumizeAccountState.map { "integrations.debrid.premiumize" },
        torBoxSettings.map { "integrations.debrid.torBox" },
        torBoxAccountState.map { "integrations.debrid.torBox" },
        easyDebridSettings.map { "integrations.debrid.easyDebrid" },
        easyDebridAccountState.map { "integrations.debrid.easyDebrid" },
        realDebridState.map { "integrations.debrid.realDebrid" },
        traktAuthState.map { "integrations.traktAuth" },
        traktCatalogPreferences.map { "catalogs.trakt" },
        simklCatalogPreferences.map { "catalogs.simkl" },
        simklAuthState.map { "integrations.simklAuth" },
        playerSettings.map { "playback.streamSelection.trackingProvider" }
    )
}
```

Keep the existing `observeAccountConfigSyncChanges(...): Flow<Unit>` for old tests unchanged in this task. `AccountSettingsSyncService` will switch to the new path-emitting function in Task 4, while existing tests that only need an emission can keep using the existing Unit-emitting function.

Do not delete `observeAccountConfigSyncChanges`; `AccountConfigSyncContractTest` already calls it in `observeAccountConfigSyncChanges emits for account owned change signals` and `observeAccountConfigSyncChanges emits when imdb settings change`.

- [ ] **Step 6: Run Android contract test and verify GREEN**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(android): add v7 account config sync contract"
```

---

### Task 4: Wire Android Push Conflict Handling

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Add service-level state**

In `AccountSettingsSyncService`, add:

```kotlin
private val pendingChangedPaths = linkedSetOf<String>()

@Volatile
private var lastAppliedRemoteRevision: Long = 0L
```

- [ ] **Step 2: Accumulate changed paths from local observers**

In `observeLocalChanges()`, replace the `observeAccountConfigSyncChanges(...).collect { schedulePush() }` call with:

```kotlin
            observeAccountConfigSyncChangedPaths(
                heroCatalogSelections = layoutPreferenceDataStore.heroCatalogSelections.drop(1).map { Unit },
                homeCatalogOrderKeys = layoutPreferenceDataStore.homeCatalogOrderKeys.drop(1).map { Unit },
                disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.drop(1).map { Unit },
                tmdbSettings = tmdbSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettings = mdbListSettingsDataStore.settings.drop(1).map { Unit },
                mdbListCatalogPreferences = mdbListSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                omdbSettings = omdbSettingsDataStore.settings.drop(1).map { Unit },
                theIntroDbSettings = theIntroDbSettingsDataStore.settings
                    .map {
                        listOf(
                            it.enabled,
                            it.showIntroButton,
                            it.showRecapButton,
                            it.showCreditsButton,
                            it.showPreviewButton
                        ).joinToString("|")
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .map { Unit },
                animeSkipEnabled = animeSkipSettingsDataStore.enabled.drop(1).map { Unit },
                animeSkipClientId = animeSkipSettingsDataStore.clientId.drop(1).map { Unit },
                subtitleTranslationSettings = subtitleTranslationSettingsDataStore.settings.drop(1).map { Unit },
                imdbSettings = imdbSettingsDataStore.settings.drop(1).map { Unit },
                posterRatingsSettings = posterRatingsSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeSettings = premiumizeSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeAccountState = premiumizeService.observeAccountState().drop(1).map { Unit },
                torBoxSettings = torBoxSettingsDataStore.settings.drop(1).map { Unit },
                torBoxAccountState = torBoxService.observeAccountState().drop(1).map { Unit },
                easyDebridSettings = easyDebridSettingsDataStore.settings.drop(1).map { Unit },
                easyDebridAccountState = easyDebridService.observeAccountState().drop(1).map { Unit },
                realDebridState = realDebridAuthDataStore.state.drop(1).map { Unit },
                traktAuthState = traktAuthDataStore.state.drop(1).map { Unit },
                traktCatalogPreferences = traktSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                simklCatalogPreferences = simklSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                simklAuthState = simklAuthDataStore.state.drop(1).map { Unit },
                playerSettings = playerSettingsDataStore.playerSettings.drop(1).map { Unit }
            ).collect { changedPath ->
                synchronized(pendingChangedPaths) {
                    pendingChangedPaths.add(changedPath)
                }
                schedulePush()
            }
```

- [ ] **Step 3: Record the pulled revision**

In `pullFromRemoteAndApply()`, after successful `applyRemoteSecrets(snapshot.settings)`, add:

```kotlin
                lastAppliedRemoteRevision = snapshot.revision
                synchronized(pendingChangedPaths) {
                    pendingChangedPaths.clear()
                }
```

- [ ] **Step 4: Push through v7 RPC and handle conflicts**

In `pushToRemote()`, replace:

```kotlin
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_push_account_settings",
                    buildAccountConfigSyncPushParams(payload)
                ).decodeList<AccountSyncMutationResult>()
            }
```

with:

```kotlin
            val changedPaths = synchronized(pendingChangedPaths) { pendingChangedPaths.toList() }
            if (changedPaths.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            val pushResult = withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_push_account_settings_v7",
                    buildAccountConfigSyncPushParamsV7(
                        payload = payload,
                        baseRevision = lastAppliedRemoteRevision,
                        changedPaths = changedPaths
                    )
                ).decodeAs<AccountConfigV7PushResult>()
            }

            if (!pushResult.applied) {
                Log.w(TAG, "Account settings push conflicted paths=${pushResult.conflictPaths.joinToString(",")}")
                pullFromRemoteAndApply()
                return@withContext Result.success(Unit)
            }

            lastAppliedRemoteRevision = pushResult.syncRevision
            synchronized(pendingChangedPaths) {
                pendingChangedPaths.removeAll(changedPaths.toSet())
            }
```

- [ ] **Step 5: Run focused Android tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(android): prevent stale account config pushes"
```

---

### Task 5: End-to-End Verification and Rollout Notes

**Files:**
- Modify: `docs/supabase-settings-sync-guide.md`

- [ ] **Step 1: Document v7 behavior**

Add this section to `docs/supabase-settings-sync-guide.md`:

```md
## Account Config Contract v7

Contract v7 prevents stale full-payload overwrites by using optimistic concurrency.

- Pull returns the latest `revision`.
- Web and Android store the last applied remote revision.
- Web and Android send changed account-config paths with the push.
- `sync_push_account_settings_v7` merges changed paths when no same-path change happened after the client's base revision.
- `sync_push_account_settings_v7` returns `applied=false` when the same path changed elsewhere after the client's base revision.
- Older contract versions keep using `sync_push_account_settings` and are not affected by v7.

This makes Supabase the source of truth for the newest accepted server revision. It rejects stale same-path writes instead of letting a delayed client overwrite newer settings.
```

- [ ] **Step 2: Run web tests**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-sync-paths.test.ts tests/portal-contract-v4.test.ts
```

Expected: PASS.

- [ ] **Step 3: Run Android sync tests**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS.

- [ ] **Step 4: Run Android compile check**

Run:

```bash
./gradlew compileArm64DebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Run diff whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 6: Manual cross-client check**

Run this sequence against a local or staging Supabase project after applying the migration:

1. Sign into web and Android as the same account.
2. On web, set `integrations.subtitleTranslation.model` to `openrouter/free`.
3. Wait for web save to complete and note the displayed sync revision.
4. Start Android.
5. Confirm Android pulls the web value and does not push a stale value.
6. On Android, change IMDB enabled/base URL.
7. Confirm web receives a realtime event and bootstraps the Android change.
8. With web left open on an old state, change the same IMDB base URL on Android, then edit the same field in web without reloading.
9. Confirm web gets a conflict response or reloads from the newer server snapshot, and the stale web edit does not silently overwrite Android.

- [ ] **Step 7: Commit docs and final verification**

```bash
git add docs/supabase-settings-sync-guide.md
git commit -m "docs(sync): document account config v7 conflict handling"
```

---

## Self-Review

**Spec coverage:** The plan adds a new Supabase schema/contract version, updates web and Android to use it, and leaves older v6 RPCs unchanged. It addresses stale writes by requiring base revision plus changed paths before mutation.

**Placeholder scan:** No task uses TBD/TODO/fill-in language. SQL, TypeScript, and Kotlin snippets name concrete files and functions.

**Type consistency:** The plan consistently uses `AccountConfigV7PushResult`, `sync_push_account_settings_v7`, `p_base_revision`, and `p_changed_paths` across SQL, web, and Android.

**Known limitation:** This plan defines "newest" as newest accepted server revision, not client wall-clock time. That is deliberate because client clocks are not a safe source of truth. Same-path stale edits are rejected and require the user/client to retry on the newer server baseline.
