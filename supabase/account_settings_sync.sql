-- Nexio account portal schema for a brand new, empty Supabase project.
-- Scope: public settings, public addons, secret metadata, audit rows, linked devices, and realtime sync events.
-- Explicitly out of scope: library sync, watch progress sync, watched-items sync.
-- Secret payloads live in Supabase Vault and are never returned through the public snapshot.

create extension if not exists pgcrypto;
create extension if not exists supabase_vault with schema vault;

create table if not exists public.account_settings_public (
  user_id uuid primary key references auth.users(id) on delete cascade,
  settings_payload jsonb not null default '{}'::jsonb,
  sync_revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'web'
);

create table if not exists public.account_addons_public (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  base_url text not null,
  manifest_url text,
  name text,
  description text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  public_query_params jsonb not null default '{}'::jsonb,
  install_kind text not null default 'manifest' check (install_kind in ('manifest', 'configured')),
  secret_ref text,
  updated_at timestamptz not null default now()
);

create unique index if not exists account_addons_public_user_base_uidx
  on public.account_addons_public (user_id, lower(base_url), coalesce(secret_ref, ''));
create index if not exists account_addons_public_user_sort_idx
  on public.account_addons_public (user_id, sort_order);

create table if not exists public.account_secrets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  secret_type text not null check (secret_type in (
    'addon_credential',
    'mdblist_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'trakt_access_token',
    'trakt_refresh_token'
  )),
  secret_ref text not null,
  vault_secret_id uuid not null unique,
  masked_preview text,
  status text not null default 'configured' check (status in ('configured', 'missing', 'error')),
  version integer not null default 1,
  updated_from text not null default 'web',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists account_secrets_user_type_ref_uidx
  on public.account_secrets (user_id, secret_type, secret_ref);
create index if not exists account_secrets_user_ref_idx
  on public.account_secrets (user_id, secret_ref);

create table if not exists public.account_secret_audit (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  secret_type text not null,
  secret_ref text not null,
  event_type text not null check (event_type in ('set', 'delete', 'resolve')),
  source text not null default 'web',
  created_at timestamptz not null default now()
);

create index if not exists account_secret_audit_user_created_idx
  on public.account_secret_audit (user_id, created_at desc);

create table if not exists public.account_sync_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  revision bigint not null,
  event_type text not null check (event_type in ('settings_public', 'addons_public', 'secret', 'snapshot')),
  source text not null default 'web',
  created_at timestamptz not null default now()
);

create index if not exists account_sync_events_user_created_idx
  on public.account_sync_events (user_id, created_at desc);

alter table public.account_settings_public enable row level security;
alter table public.account_addons_public enable row level security;
alter table public.account_secrets enable row level security;
alter table public.account_secret_audit enable row level security;
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

create or replace function public.next_sync_revision()
returns bigint
language sql
stable
as $$
  select floor(extract(epoch from clock_timestamp()) * 1000)::bigint
$$;

revoke all on function public.next_sync_revision() from public;
grant execute on function public.next_sync_revision() to authenticated;

drop policy if exists account_settings_public_owner_select on public.account_settings_public;
create policy account_settings_public_owner_select on public.account_settings_public
for select to authenticated
using (user_id = public.sync_owner_id());

drop policy if exists account_settings_public_owner_write on public.account_settings_public;
create policy account_settings_public_owner_write on public.account_settings_public
for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists account_addons_public_owner_select on public.account_addons_public;
create policy account_addons_public_owner_select on public.account_addons_public
for select to authenticated
using (user_id = public.sync_owner_id());

drop policy if exists account_addons_public_owner_write on public.account_addons_public;
create policy account_addons_public_owner_write on public.account_addons_public
for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

drop policy if exists account_secrets_owner_select on public.account_secrets;
create policy account_secrets_owner_select on public.account_secrets
for select to authenticated
using (user_id = public.sync_owner_id());

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
  values (p_user_id, p_revision, p_event_type, coalesce(nullif(trim(p_source), ''), 'web'));
end;
$$;

revoke all on function public.publish_account_sync_event(uuid, bigint, text, text) from public;
grant execute on function public.publish_account_sync_event(uuid, bigint, text, text) to authenticated;

create or replace function public.record_account_secret_audit(
  p_user_id uuid,
  p_secret_type text,
  p_secret_ref text,
  p_event_type text,
  p_source text default 'web'
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.account_secret_audit (user_id, secret_type, secret_ref, event_type, source)
  values (p_user_id, p_secret_type, p_secret_ref, p_event_type, coalesce(nullif(trim(p_source), ''), 'web'));
end;
$$;

revoke all on function public.record_account_secret_audit(uuid, text, text, text, text) from public;
grant execute on function public.record_account_secret_audit(uuid, text, text, text, text) to service_role;

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
  v_settings_updated_at timestamptz := null;
  v_addons_updated_at timestamptz := null;
begin
  select settings_payload, updated_at
    into v_settings, v_settings_updated_at
  from public.account_settings_public
  where user_id = v_user_id;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', id,
        'url', base_url,
        'manifest_url', coalesce(manifest_url, base_url || '/manifest.json'),
        'name', name,
        'description', description,
        'enabled', enabled,
        'sort_order', sort_order,
        'public_query_params', public_query_params,
        'install_kind', install_kind,
        'secret_ref', secret_ref
      ) order by sort_order asc
    ),
    '[]'::jsonb
  ), max(updated_at)
    into v_addons, v_addons_updated_at
  from public.account_addons_public
  where user_id = v_user_id;

  select revision, created_at
    into v_revision, v_updated_at
  from public.account_sync_events
  where user_id = v_user_id
  order by created_at desc
  limit 1;

  v_updated_at := coalesce(v_updated_at, greatest(v_settings_updated_at, v_addons_updated_at), v_settings_updated_at, v_addons_updated_at);

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
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
begin
  insert into public.account_settings_public (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, coalesce(p_settings_payload, '{}'::jsonb), v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', p_source);

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
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
begin
  delete from public.account_addons_public where user_id = v_user_id;

  insert into public.account_addons_public (
    user_id,
    base_url,
    manifest_url,
    name,
    description,
    enabled,
    sort_order,
    public_query_params,
    install_kind,
    secret_ref,
    updated_at
  )
  select
    v_user_id,
    trim(entry->>'url'),
    nullif(trim(entry->>'manifest_url'), ''),
    nullif(trim(entry->>'name'), ''),
    nullif(trim(entry->>'description'), ''),
    coalesce((entry->>'enabled')::boolean, true),
    coalesce((entry->>'sort_order')::integer, ordinal - 1),
    coalesce(entry->'public_query_params', '{}'::jsonb),
    coalesce(nullif(trim(entry->>'install_kind'), ''), 'manifest'),
    nullif(trim(entry->>'secret_ref'), ''),
    v_updated_at
  from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) with ordinality as rows(entry, ordinal)
  where trim(entry->>'url') <> '';

  perform public.publish_account_sync_event(v_user_id, v_revision, 'addons_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;

revoke all on function public.sync_push_account_addons(jsonb, text) from public;
grant execute on function public.sync_push_account_addons(jsonb, text) to authenticated;

create or replace function public.service_set_account_secret(
  p_user_id uuid,
  p_secret_type text,
  p_secret_ref text,
  p_secret_payload jsonb,
  p_masked_preview text,
  p_status text default 'configured',
  p_source text default 'web'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_secret public.account_secrets%rowtype;
  v_secret_body text := coalesce(p_secret_payload::text, '{}'::text);
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
  v_description text := format('Nexio %s %s', trim(p_secret_type), trim(p_secret_ref));
begin
  if p_user_id is null then
    raise exception 'p_user_id is required';
  end if;

  if trim(coalesce(p_secret_type, '')) = '' or trim(coalesce(p_secret_ref, '')) = '' then
    raise exception 'secret_type and secret_ref are required';
  end if;

  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref)
  for update;

  if found then
    perform vault.update_secret(v_secret.vault_secret_id, v_secret_body, null, v_description);

    update public.account_secrets
      set masked_preview = nullif(trim(p_masked_preview), ''),
          status = coalesce(nullif(trim(p_status), ''), 'configured'),
          version = version + 1,
          updated_at = v_updated_at,
          updated_from = coalesce(nullif(trim(p_source), ''), 'web')
    where id = v_secret.id
    returning * into v_secret;
  else
    insert into public.account_secrets (
      user_id,
      secret_type,
      secret_ref,
      vault_secret_id,
      masked_preview,
      status,
      version,
      updated_at,
      updated_from
    )
    values (
      p_user_id,
      trim(p_secret_type),
      trim(p_secret_ref),
      vault.create_secret(v_secret_body, null, v_description),
      nullif(trim(p_masked_preview), ''),
      coalesce(nullif(trim(p_status), ''), 'configured'),
      1,
      v_updated_at,
      coalesce(nullif(trim(p_source), ''), 'web')
    )
    returning * into v_secret;
  end if;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'set', p_source);
  perform public.publish_account_sync_event(p_user_id, v_revision, 'secret', p_source);

  return jsonb_build_object(
    'id', v_secret.id,
    'secret_type', v_secret.secret_type,
    'secret_ref', v_secret.secret_ref,
    'masked_preview', v_secret.masked_preview,
    'status', v_secret.status,
    'version', v_secret.version,
    'updated_at', v_secret.updated_at
  );
end;
$$;

revoke all on function public.service_set_account_secret(uuid, text, text, jsonb, text, text, text) from public;
grant execute on function public.service_set_account_secret(uuid, text, text, jsonb, text, text, text) to service_role;

create or replace function public.service_delete_account_secret(
  p_user_id uuid,
  p_secret_type text,
  p_secret_ref text,
  p_source text default 'web'
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_secret public.account_secrets%rowtype;
  v_revision bigint := public.next_sync_revision();
begin
  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref)
  for update;

  if not found then
    return;
  end if;

  delete from vault.secrets where id = v_secret.vault_secret_id;
  delete from public.account_secrets where id = v_secret.id;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'delete', p_source);
  perform public.publish_account_sync_event(p_user_id, v_revision, 'secret', p_source);
end;
$$;

revoke all on function public.service_delete_account_secret(uuid, text, text, text) from public;
grant execute on function public.service_delete_account_secret(uuid, text, text, text) to service_role;

create or replace function public.service_resolve_account_secret(
  p_user_id uuid,
  p_secret_type text,
  p_secret_ref text,
  p_source text default 'web'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_secret public.account_secrets%rowtype;
  v_decrypted text;
begin
  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref);

  if not found then
    return '{}'::jsonb;
  end if;

  select decrypted_secret
    into v_decrypted
  from vault.decrypted_secrets
  where id = v_secret.vault_secret_id;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'resolve', p_source);

  return coalesce(v_decrypted::jsonb, '{}'::jsonb);
end;
$$;

revoke all on function public.service_resolve_account_secret(uuid, text, text, text) from public;
grant execute on function public.service_resolve_account_secret(uuid, text, text, text) to service_role;

create or replace function public.service_get_account_addon_transport(
  p_user_id uuid,
  p_addon_id uuid,
  p_source text default 'web'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_addon public.account_addons_public%rowtype;
  v_secret_payload jsonb := '{}'::jsonb;
begin
  select *
    into v_addon
  from public.account_addons_public
  where id = p_addon_id
    and user_id = p_user_id;

  if not found then
    return '{}'::jsonb;
  end if;

  if v_addon.secret_ref is not null then
    v_secret_payload := public.service_resolve_account_secret(p_user_id, 'addon_credential', v_addon.secret_ref, p_source);
  end if;

  return jsonb_build_object(
    'addon_id', v_addon.id,
    'addon_name', v_addon.name,
    'base_url', v_addon.base_url,
    'manifest_url', coalesce(v_addon.manifest_url, v_addon.base_url || '/manifest.json'),
    'public_query_params', v_addon.public_query_params,
    'secret_payload', v_secret_payload
  );
end;
$$;

revoke all on function public.service_get_account_addon_transport(uuid, uuid, text) from public;
grant execute on function public.service_get_account_addon_transport(uuid, uuid, text) to service_role;

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
