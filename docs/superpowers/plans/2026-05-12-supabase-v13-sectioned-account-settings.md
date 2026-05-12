# Supabase Contract v13 Sectioned Account Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the monolithic account settings JSONB source of truth with sectioned JSONB rows and v13 RPCs while keeping the current full-payload contract working through adapters.

**Architecture:** Supabase becomes authoritative per `(user_id, section_key)` in `account_settings_sections`; v13 clients pull/push only dirty sections with independent watermarks. The existing full-payload account settings RPCs become compatibility adapters over the section table, so current v12 clients continue to work during rollout. Android and `nexio-web` keep composed UI settings objects locally, but remote sync state becomes section-aware.

**Tech Stack:** PostgreSQL/PLpgSQL Supabase migrations and RPCs, Kotlin/Hilt/DataStore/kotlinx.serialization on Android, Nuxt server routes and TypeScript tests in `nexio-web`.

**Implementation note:** v13 names the Supabase RPC/envelope contract for sectioned settings. Android's `AccountConfigSyncPayload.schemaVersion` remains `12` by design because it describes the composed compatibility payload shape; per-section rows carry their own schema metadata through the v13 envelope.

---

## Current Checkout Notes

- At plan start, the current app/web contract constant was `12`.
- The latest v12 baseline includes `supabase/migrations/20260512050000_v12_remove_tmdb_tvdb_secrets_and_drop_legacy_blocks.sql` and `supabase/migrations/20260512060000_v12_align_pull_rpc_contract_version_label.sql`.
- The timestamped account snapshot/settings RPC names still end in `_v10` (`sync_pull_account_snapshot_v10`, `sync_push_account_settings_v10`), but their envelope contract label is now `12`. Treat those as the current legacy full-payload compatibility surface.
- Do not reintroduce `integrations.wyzie`, `integrations.theIntroDb`, `integrations.tvdb`, `wyzie_api_key`, `tmdb_api_key`, or `tvdb_api_key`.
- Keep `integrations.tmdb` as a synced settings section, but assume credentials come from `BuildConfig.TMDB_API_KEY` and clients force `enabled = true`.
- Keep `integrations.imdb` and `integrations.gemini` as synced settings sections because Android and web still expose them as current settings surfaces.
- Keep `integrations.kitsuAuth` synced without an `enabled` field.

## File Structure

### New Files

| Path | Responsibility |
|------|----------------|
| `supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql` | Creates `account_settings_sections`, section-key helpers, backfill, v13 pull/push RPCs, and legacy full-payload adapters. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSectionKey.kt` | Android section-key registry and path-to-section mapping. |
| `app/src/main/java/com/nexio/tv/data/remote/supabase/V13ContractModels.kt` | Kotlin serialization models for v13 sectioned snapshots and push outcomes. |
| `app/src/test/java/com/nexio/tv/core/sync/AccountSettingsSectionKeyTest.kt` | Android tests for section mapping, Wyzie exclusion, and high-level coverage. |
| `app/src/test/java/com/nexio/tv/data/remote/supabase/V13ContractModelsTest.kt` | Android v13 envelope decode tests, including unknown sections. |
| `nexio-web/utils/account-settings-sections.ts` | Web section registry plus compose/extract helpers. |
| `nexio-web/tests/account-settings-sections.test.ts` | Web tests for compose/extract behavior and Wyzie exclusion. |

### Modified Files

| Path | Responsibility |
|------|----------------|
| `supabase/account_settings_sync.sql` | Keep canonical SQL snapshot aligned for static tests that read this file. |
| `app/src/main/java/com/nexio/tv/core/sync/SyncWatermarkSurface.kt` | Add `ACCOUNT_SETTINGS_SECTION` surface. |
| `app/src/main/java/com/nexio/tv/data/local/SyncWatermarkDataStore.kt` | Add section-key watermark helpers. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` | Keep the Android composed payload schema at 12 and add v13 section encode/decode helpers. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | Pull/apply v13 sections and push only dirty sections. |
| `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt` | Decode v13 account snapshot for addon pull paths. |
| `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` | Persist v13 section watermarks on startup pull. |
| `app/src/test/java/com/nexio/tv/data/local/SyncWatermarkDataStoreTest.kt` | Cover section-key watermark isolation. |
| `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` | Cover the v13 RPC/envelope split from Android payload schema 12, section extraction, and Wyzie absence. |
| `app/src/test/java/com/nexio/tv/ui/screens/settings/SettingsViewModelSyncTest.kt` | Keep Sync Now coverage using the account snapshot path. |
| `nexio-web/types/portal.ts` | Bump contract to 13 and add v13 envelope/push types. |
| `nexio-web/server/api/account/bootstrap.get.ts` | Pull v13 sections and compose `PortalSettings`. |
| `nexio-web/server/api/account/persist.post.ts` | Push only dirty sections via v13 batch RPC. |
| `nexio-web/tests/account-persist-atomicity.test.ts` | Update persistence expectations to section batch writes. |
| `nexio-web/tests/use-portal-store-multi-config.test.ts` | Keep UI store behavior while remote dirty state is sectioned. |

---

### Task 1: Supabase v13 Schema, Section Helpers, And Backfill

**Files:**
- Create: `supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql`
- Modify: `supabase/account_settings_sync.sql`
- Test: static SQL review plus local Supabase smoke queries

- [ ] **Step 1: Write the failing static check for the migration content**

Create `app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt`:

```kotlin
package com.nexio.tv.core.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V13SupabaseMigrationStaticTest {
    private val migration = File("supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql")

    @Test
    fun `v13 migration creates section table and RPCs without wyzie`() {
        assertTrue("v13 migration must exist", migration.exists())
        val sql = migration.readText()

        assertTrue(sql.contains("create table if not exists public.account_settings_sections"))
        assertTrue(sql.contains("create or replace function public.account_settings_section_key_allowed"))
        assertTrue(sql.contains("create or replace function public.account_settings_section_payload"))
        assertTrue(sql.contains("create or replace function public.account_settings_sections_to_payload"))
        assertTrue(sql.contains("insert into public.account_settings_sections"))

        assertTrue(sql.contains("'integrations.subtitleTranslation'"))
        assertTrue(sql.contains("'playback.streamSelection'"))
        assertTrue(sql.contains("'formatter'"))

        assertFalse(sql.contains("'integrations.wyzie'"))
        assertFalse(sql.contains("'integrations.theIntroDb'"))
        assertFalse(sql.contains("'integrations.tvdb'"))
        assertFalse(sql.contains("wyzie_api_key"))
        assertFalse(sql.contains("tmdb_api_key"))
        assertFalse(sql.contains("tvdb_api_key"))
    }
}
```

- [ ] **Step 2: Run the static test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: FAIL with `v13 migration must exist`.

- [ ] **Step 3: Create the migration table, allowed-key helper, and backfill helpers**

Create `supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql` with this first block:

```sql
-- Contract v13: sectioned account settings.
-- Authoritative account settings live in account_settings_sections.

create table if not exists public.account_settings_sections (
  user_id uuid not null references auth.users(id) on delete cascade,
  section_key text not null,
  payload jsonb not null default '{}'::jsonb,
  schema_version integer not null default 1,
  sync_revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'app',
  primary key (user_id, section_key)
);

alter table public.account_settings_sections enable row level security;

drop policy if exists "account_settings_sections_owner_select"
  on public.account_settings_sections;

create policy "account_settings_sections_owner_select"
  on public.account_settings_sections
  for select
  to authenticated
  using (user_id = public.sync_owner_id());

drop policy if exists "account_settings_sections_owner_write"
  on public.account_settings_sections;

create policy "account_settings_sections_owner_write"
  on public.account_settings_sections
  for all
  to authenticated
  using (user_id = public.sync_owner_id())
  with check (user_id = public.sync_owner_id());

create or replace function public.account_settings_section_key_allowed(p_section_key text)
returns boolean
language sql
immutable
parallel safe
set search_path = public
as $$
  select coalesce(p_section_key = trim(p_section_key), false)
    and p_section_key = any (array[
    'integrations.subtitleTranslation',
    'integrations.imdb',
    'integrations.gemini',
    'integrations.tmdb',
    'integrations.omdb',
    'integrations.posterRatings',
    'integrations.animeSkip',
    'integrations.mdblist',
    'integrations.kitsu',
    'integrations.traktAuth',
    'integrations.simklAuth',
    'integrations.kitsuAuth',
    'integrations.debrid.premiumize',
    'integrations.debrid.realDebrid',
    'integrations.debrid.torBox',
    'integrations.debrid.easyDebrid',
    'catalogs.mdblist',
    'catalogs.trakt',
    'catalogs.simkl',
    'catalogs.tmdb',
    'catalogs.kitsu',
    'catalogs.home',
    'playback.streamSelection',
    'formatter'
  ]);
$$;

alter table public.account_settings_sections
  drop constraint if exists account_settings_sections_section_key_check;

alter table public.account_settings_sections
  add constraint account_settings_sections_section_key_check
  check (public.account_settings_section_key_allowed(section_key));

create or replace function public.account_settings_section_payload(
  p_settings jsonb,
  p_section_key text
)
returns jsonb
language sql
immutable
parallel safe
set search_path = public
as $$
  select case trim(coalesce(p_section_key, ''))
    when 'integrations.subtitleTranslation' then p_settings #> '{integrations,subtitleTranslation}'
    when 'integrations.imdb' then p_settings #> '{integrations,imdb}'
    when 'integrations.gemini' then p_settings #> '{integrations,gemini}'
    when 'integrations.tmdb' then p_settings #> '{integrations,tmdb}'
    when 'integrations.omdb' then p_settings #> '{integrations,omdb}'
    when 'integrations.posterRatings' then p_settings #> '{integrations,posterRatings}'
    when 'integrations.animeSkip' then p_settings #> '{integrations,animeSkip}'
    when 'integrations.mdblist' then p_settings #> '{integrations,mdblist}'
    when 'integrations.kitsu' then p_settings #> '{integrations,kitsu}'
    when 'integrations.traktAuth' then p_settings #> '{integrations,traktAuth}'
    when 'integrations.simklAuth' then p_settings #> '{integrations,simklAuth}'
    when 'integrations.kitsuAuth' then p_settings #> '{integrations,kitsuAuth}'
    when 'integrations.debrid.premiumize' then p_settings #> '{integrations,debrid,premiumize}'
    when 'integrations.debrid.realDebrid' then p_settings #> '{integrations,debrid,realDebrid}'
    when 'integrations.debrid.torBox' then p_settings #> '{integrations,debrid,torBox}'
    when 'integrations.debrid.easyDebrid' then p_settings #> '{integrations,debrid,easyDebrid}'
    when 'catalogs.mdblist' then p_settings #> '{catalogs,mdblist}'
    when 'catalogs.trakt' then p_settings #> '{catalogs,trakt}'
    when 'catalogs.simkl' then p_settings #> '{catalogs,simkl}'
    when 'catalogs.tmdb' then p_settings #> '{catalogs,tmdb}'
    when 'catalogs.kitsu' then p_settings #> '{catalogs,kitsu}'
    when 'catalogs.home' then p_settings #> '{catalogs,home}'
    when 'playback.streamSelection' then p_settings #> '{playback,streamSelection}'
    when 'formatter' then p_settings #> '{formatter}'
    else null
  end;
$$;
```

This intentionally includes only the current v12 synced settings roots. It keeps auth status sections while excluding the v12-removed TheIntroDb, TVDB, TMDB secret, TVDB secret, Kitsu enabled, and Wyzie surfaces.

- [ ] **Step 4: Add the section merge helper and backfill**

Append this to the same migration:

```sql
create or replace function public.account_settings_sections_to_payload(p_user_id uuid)
returns jsonb
language plpgsql
stable
set search_path = public, pg_temp
as $$
declare
  v_payload jsonb := '{"integrations":{},"catalogs":{},"playback":{}}'::jsonb;
  v_row record;
begin
  for v_row in
    select section_key, payload
      from public.account_settings_sections
     where user_id = p_user_id
  loop
    v_payload := case v_row.section_key
      when 'integrations.subtitleTranslation' then jsonb_set(v_payload, '{integrations,subtitleTranslation}', v_row.payload, true)
      when 'integrations.imdb' then jsonb_set(v_payload, '{integrations,imdb}', v_row.payload, true)
      when 'integrations.gemini' then jsonb_set(v_payload, '{integrations,gemini}', v_row.payload, true)
      when 'integrations.tmdb' then jsonb_set(v_payload, '{integrations,tmdb}', v_row.payload, true)
      when 'integrations.omdb' then jsonb_set(v_payload, '{integrations,omdb}', v_row.payload, true)
      when 'integrations.posterRatings' then jsonb_set(v_payload, '{integrations,posterRatings}', v_row.payload, true)
      when 'integrations.animeSkip' then jsonb_set(v_payload, '{integrations,animeSkip}', v_row.payload, true)
      when 'integrations.mdblist' then jsonb_set(v_payload, '{integrations,mdblist}', v_row.payload, true)
      when 'integrations.kitsu' then jsonb_set(v_payload, '{integrations,kitsu}', v_row.payload, true)
      when 'integrations.traktAuth' then jsonb_set(v_payload, '{integrations,traktAuth}', v_row.payload, true)
      when 'integrations.simklAuth' then jsonb_set(v_payload, '{integrations,simklAuth}', v_row.payload, true)
      when 'integrations.kitsuAuth' then jsonb_set(v_payload, '{integrations,kitsuAuth}', v_row.payload, true)
      when 'integrations.debrid.premiumize' then jsonb_set(v_payload, '{integrations,debrid,premiumize}', v_row.payload, true)
      when 'integrations.debrid.realDebrid' then jsonb_set(v_payload, '{integrations,debrid,realDebrid}', v_row.payload, true)
      when 'integrations.debrid.torBox' then jsonb_set(v_payload, '{integrations,debrid,torBox}', v_row.payload, true)
      when 'integrations.debrid.easyDebrid' then jsonb_set(v_payload, '{integrations,debrid,easyDebrid}', v_row.payload, true)
      when 'catalogs.mdblist' then jsonb_set(v_payload, '{catalogs,mdblist}', v_row.payload, true)
      when 'catalogs.trakt' then jsonb_set(v_payload, '{catalogs,trakt}', v_row.payload, true)
      when 'catalogs.simkl' then jsonb_set(v_payload, '{catalogs,simkl}', v_row.payload, true)
      when 'catalogs.tmdb' then jsonb_set(v_payload, '{catalogs,tmdb}', v_row.payload, true)
      when 'catalogs.kitsu' then jsonb_set(v_payload, '{catalogs,kitsu}', v_row.payload, true)
      when 'catalogs.home' then jsonb_set(v_payload, '{catalogs,home}', v_row.payload, true)
      when 'playback.streamSelection' then jsonb_set(v_payload, '{playback,streamSelection}', v_row.payload, true)
      when 'formatter' then jsonb_set(v_payload, '{formatter}', v_row.payload, true)
      else v_payload
    end;
  end loop;

  return v_payload;
end;
$$;

with section_keys(section_key) as (
  values
    ('integrations.subtitleTranslation'),
    ('integrations.imdb'),
    ('integrations.gemini'),
    ('integrations.tmdb'),
    ('integrations.omdb'),
    ('integrations.posterRatings'),
    ('integrations.animeSkip'),
    ('integrations.mdblist'),
    ('integrations.kitsu'),
    ('integrations.traktAuth'),
    ('integrations.simklAuth'),
    ('integrations.kitsuAuth'),
    ('integrations.debrid.premiumize'),
    ('integrations.debrid.realDebrid'),
    ('integrations.debrid.torBox'),
    ('integrations.debrid.easyDebrid'),
    ('catalogs.mdblist'),
    ('catalogs.trakt'),
    ('catalogs.simkl'),
    ('catalogs.tmdb'),
    ('catalogs.kitsu'),
    ('catalogs.home'),
    ('playback.streamSelection'),
    ('formatter')
)
insert into public.account_settings_sections (
  user_id,
  section_key,
  payload,
  schema_version,
  sync_revision,
  updated_at,
  updated_from
)
select
  s.user_id,
  k.section_key,
  public.account_settings_section_payload(s.settings_payload, k.section_key),
  1,
  coalesce(s.sync_revision, 0),
  coalesce(s.updated_at, now()),
  coalesce(nullif(trim(s.updated_from), ''), 'v13-backfill')
from public.account_settings_public s
cross join section_keys k
where public.account_settings_section_payload(s.settings_payload, k.section_key) is not null
on conflict (user_id, section_key) do update
  set payload = excluded.payload,
      schema_version = excluded.schema_version,
      sync_revision = excluded.sync_revision,
      updated_at = excluded.updated_at,
      updated_from = excluded.updated_from;
```

- [ ] **Step 5: Run the static test and verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: PASS.

- [ ] **Step 6: Apply migration locally and smoke-test the backfill helpers**

Run against local Supabase or an isolated branch database:

```bash
supabase db reset
```

Then run:

```sql
select public.account_settings_section_key_allowed('integrations.subtitleTranslation') as allowed;
select public.account_settings_section_key_allowed('integrations.wyzie') as wyzie_allowed;
select public.account_settings_section_payload(
  '{"integrations":{"subtitleTranslation":{"model":"openai/gpt-5.5"}}}'::jsonb,
  'integrations.subtitleTranslation'
) as subtitle_section;
```

Expected:

```text
allowed = true
wyzie_allowed = false
subtitle_section = {"model":"openai/gpt-5.5"}
```

- [ ] **Step 7: Commit**

```bash
git add supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql supabase/account_settings_sync.sql app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt
git commit -m "feat(supabase): add v13 account settings section store"
```

---

### Task 2: Supabase v13 Pull And Push RPCs

**Files:**
- Modify: `supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql`
- Modify: `supabase/account_settings_sync.sql`
- Test: `app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt`

- [ ] **Step 1: Extend the static test for RPC details**

Append this test to `V13SupabaseMigrationStaticTest`:

```kotlin
@Test
fun `v13 migration has section scoped stale base and batch outcomes`() {
    val sql = migration.readText()

    assertTrue(sql.contains("p_base_updated_at_ms"))
    assertTrue(sql.contains("current section updated_at"))
    assertTrue(sql.contains("'stale_base'"))
    assertTrue(sql.contains("'sections'"))
    assertTrue(sql.contains("jsonb_agg"))
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: FAIL until RPC bodies contain section stale-base and batch result code.

- [ ] **Step 3: Add the v13 pull RPC**

Append:

```sql
create or replace function public.sync_pull_account_settings_sections_v13()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_sections jsonb;
  v_settings_ms bigint;
begin
  select
    coalesce(jsonb_agg(jsonb_build_object(
      'section_key', section_key,
      'payload', payload,
      'schema_version', schema_version,
      'sync_revision', sync_revision,
      'updated_at_ms', public.sync_to_ms(updated_at)
    ) order by section_key), '[]'::jsonb),
    coalesce(max(public.sync_to_ms(updated_at)), 0)
  into v_sections, v_settings_ms
  from public.account_settings_sections
  where user_id = v_user_id;

  return jsonb_build_object(
    'contract_version', 13,
    'settings', jsonb_build_object(
      'sections', v_sections,
      'updated_at_ms', v_settings_ms
    )
  );
end;
$$;

create or replace function public.sync_pull_account_snapshot_v13()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_settings jsonb;
  v_addons jsonb;
  v_addons_ms bigint;
  v_secrets jsonb;
  v_secrets_ms bigint;
begin
  v_settings := public.sync_pull_account_settings_sections_v13()->'settings';

  select coalesce(jsonb_agg(row_to_json(a)::jsonb order by a.sort_order), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(a.updated_at)), 0)
  into v_addons, v_addons_ms
  from public.account_addons_public a
  where a.user_id = v_user_id;

  select coalesce(jsonb_agg(jsonb_build_object(
           'secret_type', s.secret_type,
           'secret_ref', s.secret_ref,
           'masked_preview', s.masked_preview,
           'status', s.status,
           'updated_at_ms', public.sync_to_ms(s.updated_at)
         )), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(s.updated_at)), 0)
  into v_secrets, v_secrets_ms
  from public.account_secrets s
  where s.user_id = v_user_id;

  return jsonb_build_object(
    'contract_version', 13,
    'settings', v_settings,
    'addons', jsonb_build_object('items', v_addons, 'updated_at_ms', v_addons_ms),
    'secrets', jsonb_build_object('items', v_secrets, 'updated_at_ms', v_secrets_ms)
  );
end;
$$;
```

- [ ] **Step 4: Add the single-section push RPC**

Append:

```sql
create or replace function public.sync_push_account_settings_section_v13(
  p_section_key text,
  p_payload jsonb,
  p_base_updated_at_ms bigint,
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_key text := trim(coalesce(p_section_key, ''));
  v_current_ms bigint := 0;
  v_revision bigint;
  v_updated_at timestamptz;
begin
  if not public.account_settings_section_key_allowed(v_key) then
    raise exception 'Unsupported account settings section: %', v_key using errcode = '22023';
  end if;

  if jsonb_typeof(coalesce(p_payload, 'null'::jsonb)) <> 'object' then
    raise exception 'Account settings section payload must be a JSON object' using errcode = '22023';
  end if;

  select coalesce(public.sync_to_ms(updated_at), 0)
  into v_current_ms
  from public.account_settings_sections
  where user_id = v_user_id and section_key = v_key;

  v_current_ms := coalesce(v_current_ms, 0);

  -- current section updated_at stale-base guard
  if coalesce(p_base_updated_at_ms, 0) < v_current_ms then
    return jsonb_build_object(
      'applied', false,
      'section_key', v_key,
      'reason', 'stale_base',
      'current_updated_at_ms', v_current_ms
    );
  end if;

  v_revision := public.next_sync_revision();
  v_updated_at := now();

  insert into public.account_settings_sections (
    user_id,
    section_key,
    payload,
    schema_version,
    sync_revision,
    updated_at,
    updated_from
  )
  values (
    v_user_id,
    v_key,
    p_payload,
    1,
    v_revision,
    v_updated_at,
    coalesce(nullif(trim(p_source), ''), 'app')
  )
  on conflict (user_id, section_key) do update
    set payload = excluded.payload,
        schema_version = excluded.schema_version,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', coalesce(nullif(trim(p_source), ''), 'app'));

  return jsonb_build_object(
    'applied', true,
    'section_key', v_key,
    'sync_revision', v_revision,
    'current_updated_at_ms', public.sync_to_ms(v_updated_at)
  );
end;
$$;
```

- [ ] **Step 5: Add the batch-section push RPC**

Append:

```sql
create or replace function public.sync_push_account_settings_sections_v13(
  p_sections jsonb,
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_item jsonb;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_all_applied boolean := true;
begin
  if jsonb_typeof(coalesce(p_sections, 'null'::jsonb)) <> 'array' then
    raise exception 'p_sections must be a JSON array' using errcode = '22023';
  end if;

  for v_item in select value from jsonb_array_elements(p_sections)
  loop
    v_result := public.sync_push_account_settings_section_v13(
      p_section_key => v_item->>'section_key',
      p_payload => coalesce(v_item->'payload', '{}'::jsonb),
      p_base_updated_at_ms => coalesce((v_item->>'base_updated_at_ms')::bigint, 0),
      p_source => p_source
    );

    v_results := v_results || jsonb_build_array(v_result);
    if coalesce((v_result->>'applied')::boolean, false) = false then
      v_all_applied := false;
    end if;
  end loop;

  return jsonb_build_object(
    'applied', v_all_applied,
    'sections', v_results
  );
end;
$$;
```

- [ ] **Step 6: Grant RPC execute permissions**

Append:

```sql
revoke all on function public.sync_pull_account_settings_sections_v13() from public;
grant execute on function public.sync_pull_account_settings_sections_v13() to authenticated;

revoke all on function public.sync_pull_account_snapshot_v13() from public;
grant execute on function public.sync_pull_account_snapshot_v13() to authenticated;

revoke all on function public.sync_push_account_settings_section_v13(text, jsonb, bigint, text) from public;
grant execute on function public.sync_push_account_settings_section_v13(text, jsonb, bigint, text) to authenticated;

revoke all on function public.sync_push_account_settings_sections_v13(jsonb, text) from public;
grant execute on function public.sync_push_account_settings_sections_v13(jsonb, text) to authenticated;
```

- [ ] **Step 7: Run static tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: PASS.

- [ ] **Step 8: Run local SQL smoke tests**

After `supabase db reset`, smoke the function definitions:

```sql
select proname
from pg_proc
where proname in (
  'sync_pull_account_snapshot_v13',
  'sync_push_account_settings_section_v13',
  'sync_push_account_settings_sections_v13'
)
order by proname;
```

Expected: all three v13 RPCs are listed.

- [ ] **Step 9: Commit**

```bash
git add supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql supabase/account_settings_sync.sql app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt
git commit -m "feat(supabase): add v13 sectioned settings RPCs"
```

---

### Task 3: Supabase Legacy Full-Payload Adapter

**Files:**
- Modify: `supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql`
- Modify: `supabase/account_settings_sync.sql`
- Test: `app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt`

- [ ] **Step 1: Add a static test for the adapter**

Append:

```kotlin
@Test
fun `legacy account settings RPCs are adapters over sections`() {
    val sql = migration.readText()

    assertTrue(sql.contains("account_settings_sections_to_payload"))
    assertTrue(sql.contains("sync_push_account_settings_v10"))
    assertTrue(sql.contains("sync_pull_account_snapshot_v10"))
    assertTrue(sql.contains("sync_push_account_settings_sections_v13"))
}
```

- [ ] **Step 2: Run the static test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: FAIL until adapter bodies are added.

- [ ] **Step 3: Replace current full-payload pull with a section-backed adapter**

Append a new `create or replace function public.sync_pull_account_snapshot_v10()` body to the v13 migration. Preserve the existing addon/secret envelope behavior from `supabase/migrations/20260512020000_v10_fix_pull_addon_url_and_push_settings_record.sql`, but replace settings row reads with:

```sql
select public.account_settings_sections_to_payload(v_user_id),
       coalesce(max(sync_revision), 0),
       coalesce(max(public.sync_to_ms(updated_at)), 0)
into v_settings_payload, v_settings_revision, v_settings_ms
from public.account_settings_sections
where user_id = v_user_id;

if v_settings_payload is null or v_settings_payload = '{}'::jsonb then
  select settings_payload, sync_revision, public.sync_to_ms(updated_at)
  into v_settings_payload, v_settings_revision, v_settings_ms
  from public.account_settings_public
  where user_id = v_user_id;
end if;

v_settings_payload := coalesce(v_settings_payload, public.account_settings_v1_default_payload());
v_settings_revision := coalesce(v_settings_revision, 0);
v_settings_ms := coalesce(v_settings_ms, 0);
```

Keep the returned `contract_version` at the legacy value expected by existing clients.

- [ ] **Step 4: Replace current full-payload push with a section-backed adapter**

Append a new `create or replace function public.sync_push_account_settings_v10(...)` body. Inside it, compute the affected sections from `p_changed_paths`:

```sql
with changed(path) as (
  select unnest(coalesce(p_changed_paths, array[]::text[]))
),
section_keys(section_key) as (
  values
    ('integrations.subtitleTranslation'),
    ('integrations.imdb'),
    ('integrations.gemini'),
    ('integrations.tmdb'),
    ('integrations.omdb'),
    ('integrations.posterRatings'),
    ('integrations.animeSkip'),
    ('integrations.mdblist'),
    ('integrations.kitsu'),
    ('integrations.traktAuth'),
    ('integrations.simklAuth'),
    ('integrations.kitsuAuth'),
    ('integrations.debrid.premiumize'),
    ('integrations.debrid.realDebrid'),
    ('integrations.debrid.torBox'),
    ('integrations.debrid.easyDebrid'),
    ('catalogs.mdblist'),
    ('catalogs.trakt'),
    ('catalogs.simkl'),
    ('catalogs.tmdb'),
    ('catalogs.kitsu'),
    ('catalogs.home'),
    ('playback.streamSelection'),
    ('formatter')
)
select coalesce(jsonb_agg(jsonb_build_object(
  'section_key', section_key,
  'payload', public.account_settings_section_payload(p_settings_payload, section_key),
  'base_updated_at_ms', p_base_updated_at_ms
)), '[]'::jsonb)
into v_sections
from section_keys
where public.account_settings_section_payload(p_settings_payload, section_key) is not null
  and (
    not exists (select 1 from changed)
    or exists (
      select 1
      from changed
      where path = section_key
         or path like section_key || '.%'
         or section_key like path || '.%'
    )
  );
```

Then call:

```sql
v_batch_result := public.sync_push_account_settings_sections_v13(v_sections, coalesce(nullif(trim(p_source), ''), 'legacy-adapter'));
```

Return a legacy-compatible shape:

```sql
if coalesce((v_batch_result->>'applied')::boolean, false) = false then
  return jsonb_build_object(
    'applied', false,
    'reason', 'stale_base',
    'current_updated_at_ms', (
      select coalesce(max((item->>'current_updated_at_ms')::bigint), 0)
      from jsonb_array_elements(v_batch_result->'sections') item
    )
  );
end if;

return jsonb_build_object(
  'applied', true,
  'sync_revision', (
    select coalesce(max((item->>'sync_revision')::bigint), 0)
    from jsonb_array_elements(v_batch_result->'sections') item
  ),
  'current_updated_at_ms', (
    select coalesce(max((item->>'current_updated_at_ms')::bigint), public.sync_now_ms())
    from jsonb_array_elements(v_batch_result->'sections') item
  )
);
```

- [ ] **Step 5: Run static tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.V13SupabaseMigrationStaticTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql supabase/account_settings_sync.sql app/src/test/java/com/nexio/tv/core/sync/V13SupabaseMigrationStaticTest.kt
git commit -m "feat(supabase): adapt legacy account settings RPCs to sections"
```

---

### Task 4: Android V13 Models, Section Registry, And Watermarks

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSectionKey.kt`
- Create: `app/src/main/java/com/nexio/tv/data/remote/supabase/V13ContractModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/SyncWatermarkSurface.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SyncWatermarkDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/sync/AccountSettingsSectionKeyTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/supabase/V13ContractModelsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SyncWatermarkDataStoreTest.kt`

- [ ] **Step 1: Write section-key tests**

Create `AccountSettingsSectionKeyTest.kt`:

```kotlin
package com.nexio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsSectionKeyTest {
    @Test
    fun `known paths map to owning sections`() {
        assertEquals(
            AccountSettingsSectionKey.SUBTITLE_TRANSLATION,
            AccountSettingsSectionKey.fromChangedPath("integrations.subtitleTranslation.model")
        )
        assertEquals(
            AccountSettingsSectionKey.REAL_DEBRID,
            AccountSettingsSectionKey.fromChangedPath("integrations.debrid.realDebrid.connected")
        )
        assertEquals(
            AccountSettingsSectionKey.STREAM_SELECTION,
            AccountSettingsSectionKey.fromChangedPath("playback.streamSelection.trackingProvider")
        )
        assertEquals(
            AccountSettingsSectionKey.FORMATTER,
            AccountSettingsSectionKey.fromChangedPath("formatter.customTemplate.nameTemplate")
        )
    }

    @Test
    fun `wyzie is not a v13 settings section`() {
        assertFalse(AccountSettingsSectionKey.values().any { it.key.contains("wyzie", ignoreCase = true) })
        assertEquals(null, AccountSettingsSectionKey.fromChangedPath("integrations.wyzie.enabled"))
    }

    @Test
    fun `section registry follows v12 integration removals`() {
        val keys = AccountSettingsSectionKey.values().map { it.key }.toSet()
        assertFalse("TheIntroDb is device-local only", "integrations.theIntroDb" in keys)
        assertFalse("TVDB is build-config only", "integrations.tvdb" in keys)
        assertTrue("Trakt auth status must not be dropped", "integrations.traktAuth" in keys)
        assertTrue("SIMKL auth status must not be dropped", "integrations.simklAuth" in keys)
        assertTrue("Kitsu auth status must not be dropped", "integrations.kitsuAuth" in keys)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountSettingsSectionKeyTest
```

Expected: FAIL because `AccountSettingsSectionKey` does not exist.

- [ ] **Step 3: Add the section registry**

Create `AccountSettingsSectionKey.kt`:

```kotlin
package com.nexio.tv.core.sync

enum class AccountSettingsSectionKey(val key: String) {
    SUBTITLE_TRANSLATION("integrations.subtitleTranslation"),
    IMDB("integrations.imdb"),
    GEMINI("integrations.gemini"),
    TMDB("integrations.tmdb"),
    OMDB("integrations.omdb"),
    POSTER_RATINGS("integrations.posterRatings"),
    ANIME_SKIP("integrations.animeSkip"),
    MDBLIST_INTEGRATION("integrations.mdblist"),
    KITSU_INTEGRATION("integrations.kitsu"),
    TRAKT_AUTH("integrations.traktAuth"),
    SIMKL_AUTH("integrations.simklAuth"),
    KITSU_AUTH("integrations.kitsuAuth"),
    PREMIUMIZE("integrations.debrid.premiumize"),
    REAL_DEBRID("integrations.debrid.realDebrid"),
    TORBOX("integrations.debrid.torBox"),
    EASY_DEBRID("integrations.debrid.easyDebrid"),
    MDBLIST_CATALOGS("catalogs.mdblist"),
    TRAKT_CATALOGS("catalogs.trakt"),
    SIMKL_CATALOGS("catalogs.simkl"),
    TMDB_CATALOGS("catalogs.tmdb"),
    KITSU_CATALOGS("catalogs.kitsu"),
    HOME_CATALOGS("catalogs.home"),
    STREAM_SELECTION("playback.streamSelection"),
    FORMATTER("formatter");

    companion object {
        fun fromKey(key: String?): AccountSettingsSectionKey? {
            val normalized = key?.trim().orEmpty()
            return values().firstOrNull { it.key == normalized }
        }

        fun fromChangedPath(path: String?): AccountSettingsSectionKey? {
            val normalized = path?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return values()
                .sortedByDescending { it.key.length }
                .firstOrNull { normalized == it.key || normalized.startsWith("${it.key}.") }
        }
    }
}
```

- [ ] **Step 4: Add v13 serialization model tests**

Create `V13ContractModelsTest.kt`:

```kotlin
package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class V13ContractModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v13 account snapshot decodes sectioned settings and unknown sections`() {
        val raw = """
            {
              "contract_version": 13,
              "settings": {
                "sections": [
                  {
                    "section_key": "integrations.subtitleTranslation",
                    "payload": { "model": "openai/gpt-5.5" },
                    "schema_version": 1,
                    "sync_revision": 7,
                    "updated_at_ms": 1747000000000
                  },
                  {
                    "section_key": "future.clientOnly",
                    "payload": { "x": true },
                    "schema_version": 1,
                    "sync_revision": 8,
                    "updated_at_ms": 1747000001000
                  }
                ],
                "updated_at_ms": 1747000001000
              },
              "addons": { "items": [], "updated_at_ms": 0 },
              "secrets": { "items": [], "updated_at_ms": 0 }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(V13AccountSnapshotEnvelope.serializer(), raw)

        assertEquals(12, envelope.contractVersion)
        assertEquals(2, envelope.settings.sections.size)
        assertEquals("integrations.subtitleTranslation", envelope.settings.sections[0].sectionKey)
        assertEquals(1747000001000L, envelope.settings.updatedAtMs)
    }
}
```

- [ ] **Step 5: Add v13 serialization models**

Create `V13ContractModels.kt`:

```kotlin
package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class V13AccountSnapshotEnvelope(
    @SerialName("contract_version") val contractVersion: Int,
    val settings: V13AccountSettingsSections,
    val addons: V10AccountAddonsSection,
    val secrets: V10AccountSecretsSection
)

@Serializable
data class V13AccountSettingsSections(
    val sections: List<V13AccountSettingsSectionRow> = emptyList(),
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0
)

@Serializable
data class V13AccountSettingsSectionRow(
    @SerialName("section_key") val sectionKey: String,
    val payload: JsonElement,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0
)

@Serializable
data class V13SectionPushResult(
    val applied: Boolean,
    @SerialName("section_key") val sectionKey: String,
    val reason: String? = null,
    @SerialName("sync_revision") val syncRevision: Long? = null,
    @SerialName("current_updated_at_ms") val currentUpdatedAtMs: Long = 0
)

@Serializable
data class V13BatchPushResult(
    val applied: Boolean,
    val sections: List<V13SectionPushResult> = emptyList()
)
```

- [ ] **Step 6: Add section watermarks**

Modify `SyncWatermarkSurface.kt`:

```kotlin
enum class SyncWatermarkSurface {
    ACCOUNT_SETTINGS,
    ACCOUNT_SETTINGS_SECTION,
    ACCOUNT_ADDONS,
    ACCOUNT_SECRETS,
    PROFILE_SETTINGS,
    PROFILE_AUTH_TOKENS,
}
```

Modify `SyncWatermarkDataStore.kt`:

```kotlin
private fun sectionKey(sectionKey: AccountSettingsSectionKey): Preferences.Key<Long> {
    return longPreferencesKey("watermark.${SyncWatermarkSurface.ACCOUNT_SETTINGS_SECTION.name}:${sectionKey.key}")
}

suspend fun getAccountSettingsSection(sectionKey: AccountSettingsSectionKey): Long {
    return dataStore.data.first()[sectionKey(sectionKey)] ?: 0L
}

suspend fun setAccountSettingsSection(sectionKey: AccountSettingsSectionKey, ms: Long) {
    dataStore.edit { prefs -> prefs[sectionKey(sectionKey)] = ms }
}
```

- [ ] **Step 7: Add watermark tests**

Append to `SyncWatermarkDataStoreTest.kt`:

```kotlin
@Test
fun `account settings section watermarks are isolated by section key`() = runTest {
    val store = SyncWatermarkDataStore(context)

    store.setAccountSettingsSection(AccountSettingsSectionKey.SUBTITLE_TRANSLATION, 100L)
    store.setAccountSettingsSection(AccountSettingsSectionKey.FORMATTER, 200L)

    assertEquals(100L, store.getAccountSettingsSection(AccountSettingsSectionKey.SUBTITLE_TRANSLATION))
    assertEquals(200L, store.getAccountSettingsSection(AccountSettingsSectionKey.FORMATTER))
}
```

- [ ] **Step 8: Run focused Android tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountSettingsSectionKeyTest --tests com.nexio.tv.data.remote.supabase.V13ContractModelsTest --tests com.nexio.tv.data.local.SyncWatermarkDataStoreTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSectionKey.kt app/src/main/java/com/nexio/tv/data/remote/supabase/V13ContractModels.kt app/src/main/java/com/nexio/tv/core/sync/SyncWatermarkSurface.kt app/src/main/java/com/nexio/tv/data/local/SyncWatermarkDataStore.kt app/src/test/java/com/nexio/tv/core/sync/AccountSettingsSectionKeyTest.kt app/src/test/java/com/nexio/tv/data/remote/supabase/V13ContractModelsTest.kt app/src/test/java/com/nexio/tv/data/local/SyncWatermarkDataStoreTest.kt
git commit -m "feat(sync): add v13 account settings section models"
```

---

### Task 5: Android Pull And Apply V13 Sections

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/remote/supabase/V13ContractModelsTest.kt`

- [ ] **Step 1: Add a v13 section-apply contract test**

Append to `AccountConfigSyncContractTest.kt`:

```kotlin
@Test
fun `v13 subtitle translation section can be composed into account payload`() {
    val sectionPayload = buildJsonObject {
        put("enabled", true)
        put("provider", "OPENAI")
        put("model", "openai/gpt-5.5")
        put("baseUrl", "https://openrouter.ai/api/v1")
    }

    val payload = AccountSettingsSectionKey.SUBTITLE_TRANSLATION.applyToPayload(
        AccountConfigSyncPayload(),
        sectionPayload
    )

    assertEquals("openai/gpt-5.5", payload.integrations.subtitleTranslation.model)
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: FAIL because `applyToPayload` does not exist.

- [ ] **Step 3: Add section-to-payload helpers**

Add to `AccountSettingsSectionKey.kt`:

```kotlin
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayloadJson
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings
import kotlinx.serialization.json.JsonElement

fun AccountSettingsSectionKey.applyToPayload(
    current: AccountConfigSyncPayload,
    sectionPayload: JsonElement
): AccountConfigSyncPayload {
    return when (this) {
        AccountSettingsSectionKey.SUBTITLE_TRANSLATION -> current.copy(
            integrations = current.integrations.copy(
                subtitleTranslation = AccountConfigSyncPayloadJson.decodeFromJsonElement(
                    com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings.serializer(),
                    sectionPayload
                )
            )
        )
        AccountSettingsSectionKey.STREAM_SELECTION -> current.copy(
            playback = current.playback.copy(
                streamSelection = AccountConfigSyncPayloadJson.decodeFromJsonElement(
                    com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings.serializer(),
                    sectionPayload
                )
            )
        )
        AccountSettingsSectionKey.FORMATTER -> current.copy(
            formatter = AccountConfigSyncPayloadJson.decodeFromJsonElement(
                com.nexio.tv.data.remote.supabase.FormatterSyncSettings.serializer(),
                sectionPayload
            )
        )
        else -> current
    }
}
```

Then extend this helper section-by-section for the typed sections Android already applies in `AccountSettingsSyncService.applySharedAccountConfigSyncSettings`. Keep unknown or web-only sections as no-ops instead of failing.

- [ ] **Step 4: Route `pullFromRemoteAndApply` through v13**

In `AccountSettingsSyncService.pullFromRemoteAndApply`, replace:

```kotlin
postgrest.rpc("sync_pull_account_snapshot_v10")
    .decodeAs<V10AccountSnapshotEnvelope>()
```

with:

```kotlin
postgrest.rpc("sync_pull_account_snapshot_v13")
    .decodeAs<V13AccountSnapshotEnvelope>()
```

Build the payload:

```kotlin
var settingsPayload = AccountConfigSyncPayload(schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
for (section in envelope.settings.sections) {
    val key = AccountSettingsSectionKey.fromKey(section.sectionKey) ?: continue
    settingsPayload = key.applyToPayload(settingsPayload, section.payload)
    syncWatermarkStore.setAccountSettingsSection(key, section.updatedAtMs)
}
syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SETTINGS, profileId = null, ms = envelope.settings.updatedAtMs)
```

Keep existing addon/secrets handling from the v10 envelope.

- [ ] **Step 5: Keep unknown sections non-fatal**

In the loop above, unknown sections must use:

```kotlin
val key = AccountSettingsSectionKey.fromKey(section.sectionKey) ?: return@forEach
```

Do not log as error. At most log `Log.d(TAG, "Ignoring unknown account settings section ${section.sectionKey}")`.

- [ ] **Step 6: Update startup and addon pull decode**

Any account snapshot pull in `StartupSyncService` or `AddonSyncService` should decode `V13AccountSnapshotEnvelope` and read:

```kotlin
envelope.addons.items
envelope.addons.updatedAtMs
envelope.secrets.updatedAtMs
```

Do not reintroduce full settings payload decode in these paths.

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.data.remote.supabase.V13ContractModelsTest --tests com.nexio.tv.ui.screens.settings.SettingsViewModelSyncTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSectionKey.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt app/src/test/java/com/nexio/tv/data/remote/supabase/V13ContractModelsTest.kt
git commit -m "feat(sync): pull v13 account settings sections"
```

---

### Task 6: Android Push Dirty Sections Only

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Test: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Add a push mapping test**

Append:

```kotlin
@Test
fun `v13 push groups dirty paths by section`() {
    val dirty = listOf(
        "integrations.subtitleTranslation.model",
        "integrations.subtitleTranslation.provider",
        "formatter.selectedTemplateId"
    )

    val sections = dirty.mapNotNull(AccountSettingsSectionKey::fromChangedPath).toSet()

    assertEquals(
        setOf(AccountSettingsSectionKey.SUBTITLE_TRANSLATION, AccountSettingsSectionKey.FORMATTER),
        sections
    )
}
```

- [ ] **Step 2: Run and verify the focused test passes against Task 4 registry**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: PASS for the mapping test before push implementation.

- [ ] **Step 3: Add section extraction from local payload**

In `AccountConfigSyncContract.kt`, add:

```kotlin
fun AccountConfigSyncPayload.sectionPayload(sectionKey: AccountSettingsSectionKey): JsonElement? {
    return when (sectionKey) {
        AccountSettingsSectionKey.IMDB -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.ImdbSyncSettings.serializer(),
            integrations.imdb
        )
        AccountSettingsSectionKey.GEMINI -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.GeminiSyncSettings.serializer(),
            integrations.gemini
        )
        AccountSettingsSectionKey.TMDB -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.TmdbSyncSettings.serializer(),
            integrations.tmdb
        )
        AccountSettingsSectionKey.OMDB -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.OmdbSyncSettings.serializer(),
            integrations.omdb
        )
        AccountSettingsSectionKey.POSTER_RATINGS -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings.serializer(),
            integrations.posterRatings
        )
        AccountSettingsSectionKey.ANIME_SKIP -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings.serializer(),
            integrations.animeSkip
        )
        AccountSettingsSectionKey.MDBLIST_INTEGRATION -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.MDBListSyncSettings.serializer(),
            integrations.mdblist
        )
        AccountSettingsSectionKey.SUBTITLE_TRANSLATION -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings.serializer(),
            integrations.subtitleTranslation
        )
        AccountSettingsSectionKey.PREMIUMIZE -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings.serializer(),
            integrations.debrid.premiumize
        )
        AccountSettingsSectionKey.REAL_DEBRID -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.RealDebridSyncSettings.serializer(),
            integrations.debrid.realDebrid
        )
        AccountSettingsSectionKey.TORBOX -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.TorBoxSyncSettings.serializer(),
            integrations.debrid.torBox
        )
        AccountSettingsSectionKey.EASY_DEBRID -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.EasyDebridSyncSettings.serializer(),
            integrations.debrid.easyDebrid
        )
        AccountSettingsSectionKey.KITSU_AUTH -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.KitsuAuthSyncSettings.serializer(),
            integrations.kitsuAuth
        )
        AccountSettingsSectionKey.TRAKT_AUTH -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings.serializer(),
            integrations.traktAuth
        )
        AccountSettingsSectionKey.SIMKL_AUTH -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.SimklAuthSyncSettings.serializer(),
            integrations.simklAuth
        )
        AccountSettingsSectionKey.MDBLIST_CATALOGS -> catalogs.mdblist?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.TRAKT_CATALOGS -> catalogs.trakt?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.SIMKL_CATALOGS -> catalogs.simkl?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.SimklCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.TMDB_CATALOGS -> catalogs.tmdb?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.KITSU_CATALOGS -> catalogs.kitsu?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.KitsuCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.HOME_CATALOGS -> catalogs.home?.let {
            Json.encodeToJsonElement(com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings.serializer(), it)
        }
        AccountSettingsSectionKey.STREAM_SELECTION -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings.serializer(),
            playback.streamSelection
        )
        AccountSettingsSectionKey.FORMATTER -> Json.encodeToJsonElement(
            com.nexio.tv.data.remote.supabase.FormatterSyncSettings.serializer(),
            formatter
        )
        else -> null
    }
}
```

`KITSU_INTEGRATION` stays `null` in Android until `AccountConfigSyncPayload` has a typed integration-settings field for it; the section row remains preserved server-side and on web.

- [ ] **Step 4: Build v13 batch params**

Add:

```kotlin
suspend fun buildAccountSettingsSectionsPushParamsV13(
    payload: AccountConfigSyncPayload,
    changedPaths: List<String>,
    watermarkStore: SyncWatermarkDataStore
): JsonObject {
    val sections = changedPaths
        .mapNotNull(AccountSettingsSectionKey::fromChangedPath)
        .distinct()
        .mapNotNull { key ->
            val sectionPayload = payload.sectionPayload(key) ?: return@mapNotNull null
            buildJsonObject {
                put("section_key", key.key)
                put("payload", sectionPayload)
                put("base_updated_at_ms", watermarkStore.getAccountSettingsSection(key))
            }
        }

    return buildJsonObject {
        put("p_sections", JsonArray(sections))
        put("p_source", "android-v13")
    }
}
```

- [ ] **Step 5: Route push through v13 batch RPC**

In `AccountSettingsSyncService.pushToRemote`, replace the v10 settings push call with:

```kotlin
val params = buildAccountSettingsSectionsPushParamsV13(
    payload = snapshot.payload,
    changedPaths = snapshot.changedPaths,
    watermarkStore = syncWatermarkStore
)

val result = withJwtRefreshRetry {
    postgrest.rpc("sync_push_account_settings_sections_v13", params).decodeAs<V13BatchPushResult>()
}
```

For each result section:

```kotlin
val key = AccountSettingsSectionKey.fromKey(section.sectionKey) ?: return@forEach
if (section.applied) {
    syncWatermarkStore.setAccountSettingsSection(key, section.currentUpdatedAtMs)
    appliedSections += key
} else if (section.reason == "stale_base") {
    staleSections += key
}
```

Only clear pending paths whose section applied:

```kotlin
val appliedPathPrefixes = appliedSections.map { it.key }
pendingChangedPaths.removeAll { path ->
    appliedPathPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix.") }
}
```

If any stale section exists, call `pullFromRemoteAndApply(clearPendingChanges = false)` and leave local dirty paths intact for retry.

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.data.local.SyncWatermarkDataStoreTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(sync): push account settings by v13 section"
```

---

### Task 7: Web Section Compose/Extract Utilities

**Files:**
- Create: `nexio-web/utils/account-settings-sections.ts`
- Test: `nexio-web/tests/account-settings-sections.test.ts`
- Modify: `nexio-web/types/portal.ts`

- [ ] **Step 1: Write web tests**

Create `nexio-web/tests/account-settings-sections.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { composePortalSettingsFromSections, dirtyPathsToSectionKeys, extractPortalSettingsSections, validAccountSettingsSectionKeys } from '../utils/account-settings-sections'
import { defaultSettings } from '../utils/portal-defaults'

describe('account settings sections', () => {
  it('maps dirty paths to section keys without wyzie', () => {
    expect(dirtyPathsToSectionKeys([
      'integrations.subtitleTranslation.model',
      'formatter.selectedTemplateId',
      'integrations.wyzie.enabled'
    ])).toEqual(['integrations.subtitleTranslation', 'formatter'])

    expect(validAccountSettingsSectionKeys).not.toContain('integrations.wyzie')
  })

  it('extracts and composes subtitle translation section', () => {
    const settings = defaultSettings()
    settings.integrations.subtitleTranslation.model = 'openai/gpt-5.5'

    const sections = extractPortalSettingsSections(settings, ['integrations.subtitleTranslation'])
    const composed = composePortalSettingsFromSections(defaultSettings(), sections)

    expect(sections).toHaveLength(1)
    expect(sections[0].section_key).toBe('integrations.subtitleTranslation')
    expect(composed.integrations.subtitleTranslation.model).toBe('openai/gpt-5.5')
  })
})
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
cd nexio-web
npm test -- account-settings-sections.test.ts
```

Expected: FAIL because the utility file does not exist.

- [ ] **Step 3: Add v13 web types**

In `nexio-web/types/portal.ts`, change:

```ts
export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 12
```

to:

```ts
export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 13
```

Add:

```ts
export type AccountSettingsSectionKey =
  | 'integrations.subtitleTranslation'
  | 'integrations.imdb'
  | 'integrations.gemini'
  | 'integrations.tmdb'
  | 'integrations.omdb'
  | 'integrations.posterRatings'
  | 'integrations.animeSkip'
  | 'integrations.mdblist'
  | 'integrations.kitsu'
  | 'integrations.traktAuth'
  | 'integrations.simklAuth'
  | 'integrations.kitsuAuth'
  | 'integrations.debrid.premiumize'
  | 'integrations.debrid.realDebrid'
  | 'integrations.debrid.torBox'
  | 'integrations.debrid.easyDebrid'
  | 'catalogs.mdblist'
  | 'catalogs.trakt'
  | 'catalogs.simkl'
  | 'catalogs.tmdb'
  | 'catalogs.kitsu'
  | 'catalogs.home'
  | 'playback.streamSelection'
  | 'formatter'

export type AccountSettingsSectionRecord = {
  section_key: AccountSettingsSectionKey | string
  payload: Record<string, unknown>
  schema_version: number
  sync_revision: number
  updated_at_ms: number
}

export type V13AccountSnapshotEnvelope = {
  contract_version: 13
  settings: {
    sections: AccountSettingsSectionRecord[]
    updated_at_ms: number
  }
  addons: V10AccountSnapshotEnvelope['addons']
  secrets: V10AccountSnapshotEnvelope['secrets']
}
```

- [ ] **Step 4: Add compose/extract utilities**

Create `nexio-web/utils/account-settings-sections.ts`:

```ts
import type { AccountSettingsSectionKey, AccountSettingsSectionRecord, PortalSettings } from '../types/portal'

export const validAccountSettingsSectionKeys: AccountSettingsSectionKey[] = [
  'integrations.subtitleTranslation',
  'integrations.imdb',
  'integrations.gemini',
  'integrations.tmdb',
  'integrations.omdb',
  'integrations.posterRatings',
  'integrations.animeSkip',
  'integrations.mdblist',
  'integrations.kitsu',
  'integrations.traktAuth',
  'integrations.simklAuth',
  'integrations.kitsuAuth',
  'integrations.debrid.premiumize',
  'integrations.debrid.realDebrid',
  'integrations.debrid.torBox',
  'integrations.debrid.easyDebrid',
  'catalogs.mdblist',
  'catalogs.trakt',
  'catalogs.simkl',
  'catalogs.tmdb',
  'catalogs.kitsu',
  'catalogs.home',
  'playback.streamSelection',
  'formatter'
]

export function dirtyPathsToSectionKeys(paths: Iterable<string>): AccountSettingsSectionKey[] {
  const keys = new Set<AccountSettingsSectionKey>()
  for (const raw of paths) {
    const path = raw.trim()
    const key = validAccountSettingsSectionKeys
      .slice()
      .sort((a, b) => b.length - a.length)
      .find((candidate) => path === candidate || path.startsWith(`${candidate}.`))
    if (key) keys.add(key)
  }
  return Array.from(keys)
}

function payloadFor(settings: PortalSettings, sectionKey: AccountSettingsSectionKey): Record<string, unknown> | null {
  switch (sectionKey) {
    case 'integrations.subtitleTranslation': return settings.integrations.subtitleTranslation
    case 'integrations.imdb': return settings.integrations.imdb
    case 'integrations.gemini': return settings.integrations.gemini
    case 'integrations.tmdb': return settings.integrations.tmdb
    case 'integrations.omdb': return settings.integrations.omdb
    case 'integrations.posterRatings': return settings.integrations.posterRatings
    case 'integrations.animeSkip': return settings.integrations.animeSkip
    case 'integrations.mdblist': return settings.integrations.mdblist
    case 'integrations.kitsu': return settings.integrations.kitsu
    case 'integrations.traktAuth': return settings.integrations.traktAuth
    case 'integrations.simklAuth': return settings.integrations.simklAuth
    case 'integrations.kitsuAuth': return settings.integrations.kitsuAuth
    case 'integrations.debrid.premiumize': return settings.integrations.debrid.premiumize
    case 'integrations.debrid.realDebrid': return settings.integrations.debrid.realDebrid
    case 'integrations.debrid.torBox': return settings.integrations.debrid.torBox
    case 'integrations.debrid.easyDebrid': return settings.integrations.debrid.easyDebrid
    case 'catalogs.mdblist': return settings.catalogs.mdblist
    case 'catalogs.trakt': return settings.catalogs.trakt
    case 'catalogs.simkl': return settings.catalogs.simkl
    case 'catalogs.tmdb': return settings.catalogs.tmdb
    case 'catalogs.kitsu': return settings.catalogs.kitsu
    case 'catalogs.home': return settings.catalogs.home
    case 'playback.streamSelection': return settings.playback.streamSelection
    case 'formatter': return settings.formatter
  }
}

export function extractPortalSettingsSections(settings: PortalSettings, sectionKeys: AccountSettingsSectionKey[]): Array<{ section_key: AccountSettingsSectionKey, payload: Record<string, unknown> }> {
  return sectionKeys
    .map((sectionKey) => ({ section_key: sectionKey, payload: payloadFor(settings, sectionKey) }))
    .filter((entry): entry is { section_key: AccountSettingsSectionKey, payload: Record<string, unknown> } => Boolean(entry.payload))
}

export function composePortalSettingsFromSections(base: PortalSettings, sections: AccountSettingsSectionRecord[]): PortalSettings {
  const next = structuredClone(base)
  for (const section of sections) {
    const payload = section.payload as any
    switch (section.section_key) {
      case 'integrations.subtitleTranslation': next.integrations.subtitleTranslation = { ...next.integrations.subtitleTranslation, ...payload }; break
      case 'integrations.imdb': next.integrations.imdb = { ...next.integrations.imdb, ...payload }; break
      case 'integrations.gemini': next.integrations.gemini = { ...next.integrations.gemini, ...payload }; break
      case 'integrations.tmdb': next.integrations.tmdb = { ...next.integrations.tmdb, ...payload }; break
      case 'integrations.omdb': next.integrations.omdb = { ...next.integrations.omdb, ...payload }; break
      case 'integrations.posterRatings': next.integrations.posterRatings = { ...next.integrations.posterRatings, ...payload }; break
      case 'integrations.animeSkip': next.integrations.animeSkip = { ...next.integrations.animeSkip, ...payload }; break
      case 'integrations.mdblist': next.integrations.mdblist = { ...next.integrations.mdblist, ...payload }; break
      case 'integrations.kitsu': next.integrations.kitsu = { ...next.integrations.kitsu, ...payload }; break
      case 'integrations.traktAuth': next.integrations.traktAuth = { ...next.integrations.traktAuth, ...payload }; break
      case 'integrations.simklAuth': next.integrations.simklAuth = { ...next.integrations.simklAuth, ...payload }; break
      case 'integrations.kitsuAuth': next.integrations.kitsuAuth = { ...next.integrations.kitsuAuth, ...payload }; break
      case 'integrations.debrid.premiumize': next.integrations.debrid.premiumize = { ...next.integrations.debrid.premiumize, ...payload }; break
      case 'integrations.debrid.realDebrid': next.integrations.debrid.realDebrid = { ...next.integrations.debrid.realDebrid, ...payload }; break
      case 'integrations.debrid.torBox': next.integrations.debrid.torBox = { ...next.integrations.debrid.torBox, ...payload }; break
      case 'integrations.debrid.easyDebrid': next.integrations.debrid.easyDebrid = { ...next.integrations.debrid.easyDebrid, ...payload }; break
      case 'catalogs.mdblist': next.catalogs.mdblist = { ...next.catalogs.mdblist, ...payload }; break
      case 'catalogs.trakt': next.catalogs.trakt = { ...next.catalogs.trakt, ...payload }; break
      case 'catalogs.simkl': next.catalogs.simkl = { ...next.catalogs.simkl, ...payload }; break
      case 'catalogs.tmdb': next.catalogs.tmdb = { ...next.catalogs.tmdb, ...payload }; break
      case 'catalogs.kitsu': next.catalogs.kitsu = { ...next.catalogs.kitsu, ...payload }; break
      case 'catalogs.home': next.catalogs.home = { ...next.catalogs.home, ...payload }; break
      case 'formatter': next.formatter = { ...next.formatter, ...payload }; break
      case 'playback.streamSelection': next.playback.streamSelection = { ...next.playback.streamSelection, ...payload }; break
      default: break
    }
  }
  return next
}
```

- [ ] **Step 5: Run web tests**

Run:

```bash
cd nexio-web
npm test -- account-settings-sections.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add nexio-web/types/portal.ts nexio-web/utils/account-settings-sections.ts nexio-web/tests/account-settings-sections.test.ts
git commit -m "feat(web): add v13 account settings section helpers"
```

---

### Task 8: Web Bootstrap And Persist Use V13 Sections

**Files:**
- Modify: `nexio-web/server/api/account/bootstrap.get.ts`
- Modify: `nexio-web/server/api/account/persist.post.ts`
- Test: `nexio-web/tests/account-persist-atomicity.test.ts`
- Test: `nexio-web/tests/use-portal-store-multi-config.test.ts`

- [ ] **Step 1: Update persist tests for v13 batch RPC**

In `nexio-web/tests/account-persist-atomicity.test.ts`, update the expected settings RPC from:

```ts
expect(calls.some((call) => call.path.includes('sync_push_account_settings_v10'))).toBe(true)
```

to:

```ts
const settingsCall = calls.find((call) => call.path.includes('sync_push_account_settings_sections_v13'))
expect(settingsCall).toBeTruthy()
expect(JSON.parse(String(settingsCall!.body)).p_sections).toEqual(
  expect.arrayContaining([
    expect.objectContaining({ section_key: 'integrations.subtitleTranslation' })
  ])
)
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
cd nexio-web
npm test -- account-persist-atomicity.test.ts
```

Expected: FAIL because `persist.post.ts` still calls the legacy full-payload settings RPC.

- [ ] **Step 3: Update bootstrap to pull v13 and compose settings**

In `bootstrap.get.ts`, replace:

```ts
const envelope = await supabaseFetch<V10AccountSnapshotEnvelope>('/rest/v1/rpc/sync_pull_account_snapshot_v10', {
```

with:

```ts
const envelope = await supabaseFetch<V13AccountSnapshotEnvelope>('/rest/v1/rpc/sync_pull_account_snapshot_v13', {
```

Then replace:

```ts
const pulledSettings = envelope.settings?.payload
```

with:

```ts
const pulledSections = envelope.settings?.sections ?? []
const pulledSettings = composePortalSettingsFromSections(defaultSettings(), pulledSections)
```

Keep addon/secret handling as-is.

- [ ] **Step 4: Update first-time seed to push sections**

Replace the seeded settings push with:

```ts
const seededSettings = await supabaseFetch<V13BatchPushResult>('/rest/v1/rpc/sync_push_account_settings_sections_v13', {
  method: 'POST',
  body: JSON.stringify({
    p_sections: extractPortalSettingsSections(settings, validAccountSettingsSectionKeys).map((section) => ({
      section_key: section.section_key,
      payload: section.payload,
      base_updated_at_ms: 0
    })),
    p_source: 'web-v13-bootstrap'
  })
}, token)
```

Set `settingsUpdatedAtMs` to the max `current_updated_at_ms` from `seededSettings.sections`.

- [ ] **Step 5: Update persist to push dirty sections**

In `persist.post.ts`, compute section keys:

```ts
const dirtySectionKeys = dirtyPathsToSectionKeys(changedPaths)
const sectionPayloads = extractPortalSettingsSections(body.settings, dirtySectionKeys)
```

Replace `sync_push_account_settings_v10` call with:

```ts
const settingsResult = await supabaseFetch<V13BatchPushResult>('/rest/v1/rpc/sync_push_account_settings_sections_v13', {
  method: 'POST',
  body: JSON.stringify({
    p_sections: sectionPayloads.map((section) => ({
      section_key: section.section_key,
      payload: section.payload,
      base_updated_at_ms: body.sectionWatermarks?.[section.section_key] ?? body.settingsUpdatedAtMs ?? 0
    })),
    p_source: 'web-v13'
  })
}, token)
```

Add `sectionWatermarks?: Record<string, number>` to `PersistBody`.

If any result section has `applied === false`, return 409 with:

```ts
data: {
  reason: failed.reason ?? 'stale_base',
  conflictSections: failedSections.map((section) => section.section_key),
  sectionWatermarks: Object.fromEntries(settingsResult.sections.map((section) => [section.section_key, section.current_updated_at_ms]))
}
```

- [ ] **Step 6: Run web tests**

Run:

```bash
cd nexio-web
npm test -- account-settings-sections.test.ts account-persist-atomicity.test.ts use-portal-store-multi-config.test.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add nexio-web/server/api/account/bootstrap.get.ts nexio-web/server/api/account/persist.post.ts nexio-web/tests/account-persist-atomicity.test.ts nexio-web/tests/use-portal-store-multi-config.test.ts
git commit -m "feat(web): sync account settings through v13 sections"
```

---

### Task 9: End-To-End Verification And Documentation

**Files:**
- Modify: `docs/supabase-settings-sync-guide.md`
- Modify: `docs/superpowers/notes/2026-05-12-contract-v10-handoff.md` only if it still describes current rollout state as active

- [ ] **Step 1: Run Android focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.core.sync.AccountSettingsSectionKeyTest --tests com.nexio.tv.data.remote.supabase.V13ContractModelsTest --tests com.nexio.tv.data.local.SyncWatermarkDataStoreTest --tests com.nexio.tv.ui.screens.settings.SettingsViewModelSyncTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run web focused tests**

Run:

```bash
cd nexio-web
npm test -- account-settings-sections.test.ts account-persist-atomicity.test.ts use-portal-store-multi-config.test.ts
```

Expected: all selected tests pass.

- [ ] **Step 3: Run SQL migration reset**

Run:

```bash
supabase db reset
```

Expected: reset completes without SQL errors.

- [ ] **Step 4: Add documentation note**

In `docs/supabase-settings-sync-guide.md`, add:

```md
## Contract v13 Sectioned Account Settings

Contract v13 makes `account_settings_sections` the authoritative account-settings source. Each row is keyed by `(user_id, section_key)` and carries its own JSONB payload, `sync_revision`, `updated_at`, and `updated_from`.

V13 clients pull `sync_pull_account_snapshot_v13` and push `sync_push_account_settings_sections_v13`. Clients must send only dirty sections and carry per-section `base_updated_at_ms` watermarks.

The legacy full-payload account settings RPCs remain available during rollout as adapters over `account_settings_sections`. They reconstruct the old settings payload for legacy pulls and split legacy pushes back into section rows.

Wyzie is not a synced account-settings section in v13. Wyzie subtitle access is build-time configured on Android.
```

- [ ] **Step 5: Run doc/static checks**

Run:

```bash
rg -n "integrations\\.wyzie|wyzie_api_key|sync_push_account_settings_v10\\(|sync_pull_account_snapshot_v10\\(" docs/supabase-settings-sync-guide.md docs/superpowers/specs/2026-05-12-supabase-v13-sectioned-settings-design.md
```

Expected: no matches.

- [ ] **Step 6: Commit**

```bash
git add docs/supabase-settings-sync-guide.md docs/superpowers/notes/2026-05-12-contract-v10-handoff.md
git commit -m "docs(sync): document v13 sectioned account settings"
```

---

## Self-Review Checklist

- Spec coverage: schema, section keys, pull RPC, push RPC, backward compatibility, migration/backfill, Android, web, tests, observability, and rollout are covered by Tasks 1-9.
- Wyzie removal: every section list in this plan excludes `integrations.wyzie`; static checks assert Wyzie is not reintroduced.
- Source-of-truth risk: Task 1 keeps the current v12 synced auth sections while preserving v12 removals for TheIntroDb, TVDB, TMDB/TVDB user secrets, Kitsu `enabled`, and Wyzie.
- Compatibility: Tasks 2-3 add v13 RPCs and adapt the latest legacy full-payload RPC surface.
- TDD: every code-bearing task starts with a failing or focused test before implementation.
