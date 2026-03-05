-- Account-owned settings and addon sync for the new Nexio portal.
-- Scope: settings, addons, linked devices, realtime account sync events.
-- Explicitly out of scope: library, watch progress, watched items.
-- Empty accounts are seeded by the web bootstrap route through the RPCs below.
-- Default built-in addons are Cinemeta and OpenSubtitles.
-- Trakt auth state, including tokens, currently lives inside `account_settings.settings_payload`.

create extension if not exists pgcrypto;

create table if not exists public.account_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  settings_payload jsonb not null default '{}'::jsonb,
  sync_revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'web'
);

create table if not exists public.account_addons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  url text not null,
  manifest_url text,
  name text,
  description text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  updated_at timestamptz not null default now()
);

create unique index if not exists account_addons_user_url_uidx
  on public.account_addons (user_id, lower(url));
create index if not exists account_addons_user_sort_idx
  on public.account_addons (user_id, sort_order);

create table if not exists public.account_sync_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  revision bigint not null,
  event_type text not null check (event_type in ('settings', 'addons', 'snapshot')),
  source text not null default 'web',
  created_at timestamptz not null default now()
);

create index if not exists account_sync_events_user_created_idx
  on public.account_sync_events (user_id, created_at desc);

alter table public.account_settings enable row level security;
alter table public.account_addons enable row level security;
alter table public.account_sync_events enable row level security;

create or replace function public.sync_owner_id()
returns uuid
language sql
security definer
set search_path = public
stable
as $$
  select coalesce(
    (select owner_id from public.linked_devices where device_user_id = auth.uid()),
    auth.uid()
  )
$$;

revoke all on function public.sync_owner_id() from public;
grant execute on function public.sync_owner_id() to authenticated;

drop policy if exists account_settings_owner_select on public.account_settings;
create policy account_settings_owner_select on public.account_settings
for select to authenticated
using (user_id = public.sync_owner_id());

drop policy if exists account_settings_owner_write on public.account_settings;
create policy account_settings_owner_write on public.account_settings
for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists account_addons_owner_select on public.account_addons;
create policy account_addons_owner_select on public.account_addons
for select to authenticated
using (user_id = public.sync_owner_id());

drop policy if exists account_addons_owner_write on public.account_addons;
create policy account_addons_owner_write on public.account_addons
for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists account_sync_events_owner_select on public.account_sync_events;
create policy account_sync_events_owner_select on public.account_sync_events
for select to authenticated
using (user_id = public.sync_owner_id());

drop policy if exists account_sync_events_owner_insert on public.account_sync_events;
create policy account_sync_events_owner_insert on public.account_sync_events
for insert to authenticated
with check (user_id = auth.uid());

create or replace function public.publish_account_sync_event(
  p_user_id uuid,
  p_revision bigint,
  p_event_type text,
  p_source text default 'web'
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.account_sync_events (user_id, revision, event_type, source)
  values (p_user_id, p_revision, p_event_type, p_source);
end;
$$;

revoke all on function public.publish_account_sync_event(uuid, bigint, text, text) from public;
grant execute on function public.publish_account_sync_event(uuid, bigint, text, text) to authenticated;

create or replace function public.sync_pull_account_snapshot()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_settings jsonb := '{}'::jsonb;
  v_revision bigint := 0;
  v_updated_at timestamptz := null;
  v_addons jsonb := '[]'::jsonb;
begin
  select settings_payload, sync_revision, updated_at
    into v_settings, v_revision, v_updated_at
  from public.account_settings
  where user_id = v_user_id;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', id,
        'url', url,
        'manifest_url', coalesce(manifest_url, url || '/manifest.json'),
        'name', name,
        'description', description,
        'enabled', enabled,
        'sort_order', sort_order
      ) order by sort_order asc
    ),
    '[]'::jsonb
  ) into v_addons
  from public.account_addons
  where user_id = v_user_id;

  return jsonb_build_object(
    'user_id', v_user_id,
    'revision', coalesce(v_revision, 0),
    'updated_at', v_updated_at,
    'settings', coalesce(v_settings, '{}'::jsonb),
    'addons', v_addons
  );
end;
$$;

revoke all on function public.sync_pull_account_snapshot() from public;
grant execute on function public.sync_pull_account_snapshot() to authenticated;

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text default 'app'
)
returns table(sync_revision bigint, updated_at timestamptz)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_revision bigint := extract(epoch from clock_timestamp() at time zone 'utc')::bigint;
  v_updated_at timestamptz := now();
begin
  -- `p_settings_payload` is the full account-scoped settings document after device-specific exclusions.
  insert into public.account_settings (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, coalesce(p_settings_payload, '{}'::jsonb), v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings', coalesce(nullif(trim(p_source), ''), 'app'));

  return query select v_revision, v_updated_at;
end;
$$;

revoke all on function public.sync_push_account_settings(jsonb, text) from public;
grant execute on function public.sync_push_account_settings(jsonb, text) to authenticated;

create or replace function public.sync_push_account_addons(
  p_addons jsonb,
  p_source text default 'app'
)
returns table(sync_revision bigint, updated_at timestamptz)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_revision bigint := extract(epoch from clock_timestamp() at time zone 'utc')::bigint;
  v_updated_at timestamptz := now();
begin
  -- `p_addons` replaces the full ordered addon list for the account owner.
  delete from public.account_addons where user_id = v_user_id;

  insert into public.account_addons (user_id, url, manifest_url, name, description, enabled, sort_order, updated_at)
  select
    v_user_id,
    trim(entry->>'url'),
    nullif(trim(entry->>'manifest_url'), ''),
    nullif(trim(entry->>'name'), ''),
    nullif(trim(entry->>'description'), ''),
    coalesce((entry->>'enabled')::boolean, true),
    coalesce((entry->>'sort_order')::integer, ordinal - 1),
    v_updated_at
  from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) with ordinality as rows(entry, ordinal)
  where trim(entry->>'url') <> '';

  perform public.publish_account_sync_event(v_user_id, v_revision, 'addons', coalesce(nullif(trim(p_source), ''), 'app'));

  return query select v_revision, v_updated_at;
end;
$$;

revoke all on function public.sync_push_account_addons(jsonb, text) from public;
grant execute on function public.sync_push_account_addons(jsonb, text) to authenticated;

do $$
begin
  if not exists (
    select 1
    from pg_publication_rel pr
    join pg_class c on c.oid = pr.prrelid
    join pg_namespace n on n.oid = c.relnamespace
    join pg_publication p on p.oid = pr.prpubid
    where p.pubname = 'supabase_realtime'
      and n.nspname = 'public'
      and c.relname = 'account_sync_events'
  ) then
    alter publication supabase_realtime add table public.account_sync_events;
  end if;
end;
$$;
