-- Nexio account portal schema for a brand new, empty Supabase project.
-- Scope: public settings, public addons, secret metadata, audit rows, linked devices, and realtime sync events.
-- Explicitly out of scope: library sync, watch progress sync, watched-items sync.
-- Secret payloads live in Supabase Vault and are never returned through the public snapshot.

create extension if not exists pgcrypto;
create extension if not exists supabase_vault with schema vault;

create table if not exists public.linked_devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_user_id uuid not null unique references auth.users(id) on delete cascade,
  device_name text,
  device_model text,
  device_platform text,
  status text not null default 'idle' check (status in ('online', 'idle', 'offline')),
  linked_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

create index if not exists linked_devices_owner_idx
  on public.linked_devices (owner_id);
create index if not exists linked_devices_last_seen_idx
  on public.linked_devices (owner_id, last_seen_at desc);

create table if not exists public.sync_codes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  code text not null unique,
  pin_hash text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  claimed_at timestamptz,
  claimed_by uuid references auth.users(id) on delete set null
);

create index if not exists sync_codes_owner_idx
  on public.sync_codes (owner_id, expires_at desc);
create index if not exists sync_codes_code_idx
  on public.sync_codes (code);

create table if not exists public.tv_login_sessions (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  requester_user_id uuid not null references auth.users(id) on delete cascade,
  approved_by_user_id uuid references auth.users(id) on delete set null,
  device_nonce text not null,
  device_name text,
  device_model text,
  device_platform text,
  redirect_base_url text not null,
  web_url text not null,
  status text not null default 'pending' check (status in ('pending', 'approved', 'used', 'expired', 'cancelled')),
  poll_interval_seconds integer not null default 3,
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  used_at timestamptz,
  expires_at timestamptz not null
);

create unique index if not exists tv_login_sessions_code_nonce_uidx
  on public.tv_login_sessions (code, device_nonce);
create index if not exists tv_login_sessions_requester_idx
  on public.tv_login_sessions (requester_user_id, created_at desc);
create index if not exists tv_login_sessions_approved_idx
  on public.tv_login_sessions (approved_by_user_id, created_at desc);

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
      'tmdb_api_key',
      'mdblist_api_key',
      'gemini_api_key',
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
alter table public.linked_devices enable row level security;
alter table public.sync_codes enable row level security;
alter table public.tv_login_sessions enable row level security;

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

drop policy if exists linked_devices_owner_or_device_select on public.linked_devices;
create policy linked_devices_owner_or_device_select on public.linked_devices
for select to authenticated
using (owner_id = auth.uid() or device_user_id = auth.uid());

drop policy if exists tv_login_sessions_requester_select on public.tv_login_sessions;
create policy tv_login_sessions_requester_select on public.tv_login_sessions
for select to authenticated
using (requester_user_id = auth.uid() or approved_by_user_id = auth.uid());

create or replace function public.generate_human_code(p_length integer default 6)
returns text
language plpgsql
set search_path = public, extensions
as $$
declare
  v_code text := '';
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_index integer;
  v_target_length integer := greatest(4, least(coalesce(p_length, 6), 12));
begin
  while char_length(v_code) < v_target_length loop
    v_index := get_byte(gen_random_bytes(1), 0) % char_length(v_alphabet);
    v_code := v_code || substr(v_alphabet, v_index + 1, 1);
  end loop;

  return v_code;
end;
$$;

revoke all on function public.generate_human_code(integer) from public;

create or replace function public.generate_unique_human_code(
  p_table_name text,
  p_column_name text,
  p_length integer default 6
)
returns text
language plpgsql
set search_path = public
as $$
declare
  v_code text;
  v_exists boolean;
  v_sql text;
begin
  v_sql := format('select exists(select 1 from public.%I where %I = $1)', p_table_name, p_column_name);

  loop
    v_code := public.generate_human_code(p_length);
    execute v_sql into v_exists using v_code;
    exit when not v_exists;
  end loop;

  return v_code;
end;
$$;

revoke all on function public.generate_unique_human_code(text, text, integer) from public;

create or replace function public.generate_sync_code(p_pin text)
returns table (code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code text;
  v_pin text := trim(coalesce(p_pin, ''));
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if char_length(v_pin) < 4 then
    raise exception 'Invalid pin';
  end if;

  delete from public.sync_codes
  where owner_id = auth.uid();

  v_code := public.generate_unique_human_code('sync_codes', 'code', 6);

  insert into public.sync_codes (
    owner_id,
    code,
    pin_hash,
    expires_at
  )
  values (
    auth.uid(),
    v_code,
    crypt(v_pin, gen_salt('bf')),
    now() + interval '10 minutes'
  );

  return query select v_code;
end;
$$;

revoke all on function public.generate_sync_code(text) from public;
grant execute on function public.generate_sync_code(text) to authenticated;

create or replace function public.get_sync_code(p_pin text)
returns table (code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row public.sync_codes%rowtype;
  v_pin text := trim(coalesce(p_pin, ''));
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.sync_codes
  where owner_id = auth.uid()
    and claimed_at is null
    and expires_at > now()
  order by created_at desc
  limit 1;

  if not found then
    raise exception 'No sync code found';
  end if;

  if v_row.pin_hash <> crypt(v_pin, v_row.pin_hash) then
    raise exception 'Incorrect pin';
  end if;

  return query select v_row.code;
end;
$$;

revoke all on function public.get_sync_code(text) from public;
grant execute on function public.get_sync_code(text) to authenticated;

create or replace function public.claim_sync_code(
  p_code text,
  p_pin text,
  p_device_name text default null
)
returns table (
  result_owner_id uuid,
  success boolean,
  message text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row public.sync_codes%rowtype;
  v_code text := upper(trim(coalesce(p_code, '')));
  v_pin text := trim(coalesce(p_pin, ''));
  v_device_name text := nullif(trim(coalesce(p_device_name, '')), '');
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if v_code = '' then
    raise exception 'Invalid sync code';
  end if;

  if exists (
    select 1
    from public.linked_devices
    where device_user_id = auth.uid()
  ) then
    raise exception 'Device is already linked';
  end if;

  select *
    into v_row
  from public.sync_codes
  where code = v_code
  for update;

  if not found then
    raise exception 'Sync code not found';
  end if;

  if v_row.expires_at <= now() then
    delete from public.sync_codes where id = v_row.id;
    raise exception 'Sync code has expired';
  end if;

  if v_row.claimed_at is not null then
    raise exception 'Sync code already claimed';
  end if;

  if v_row.pin_hash <> crypt(v_pin, v_row.pin_hash) then
    raise exception 'Incorrect pin';
  end if;

  insert into public.linked_devices (
    owner_id,
    device_user_id,
    device_name,
    linked_at,
    last_seen_at,
    status
  )
  values (
    v_row.owner_id,
    auth.uid(),
    v_device_name,
    now(),
    now(),
    'online'
  );

  update public.sync_codes
    set claimed_at = now(),
        claimed_by = auth.uid()
  where id = v_row.id;

  return query select v_row.owner_id, true, 'Device linked successfully';
end;
$$;

revoke all on function public.claim_sync_code(text, text, text) from public;
grant execute on function public.claim_sync_code(text, text, text) to authenticated;

create or replace function public.unlink_device(p_device_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  delete from public.linked_devices
  where owner_id = auth.uid()
    and device_user_id = p_device_user_id;
end;
$$;

revoke all on function public.unlink_device(uuid) from public;
grant execute on function public.unlink_device(uuid) to authenticated;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table (
  code text,
  web_url text,
  expires_at timestamptz,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_code text;
  v_expires_at timestamptz := now() + interval '10 minutes';
  v_poll_interval integer := 3;
  v_base_url text := trim(coalesce(p_redirect_base_url, ''));
  v_nonce text := trim(coalesce(p_device_nonce, ''));
  v_web_url text;
  v_device_name text := nullif(trim(coalesce(p_device_name, '')), '');
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if v_nonce = '' then
    raise exception 'Invalid device nonce';
  end if;

  if v_base_url !~* '^https?://[^ ]+$' then
    raise exception 'Invalid TV login redirect base url';
  end if;

  update public.tv_login_sessions tls
    set status = 'expired'
  where tls.requester_user_id = auth.uid()
    and tls.status = 'pending'
    and tls.expires_at <= now();

  v_code := public.generate_unique_human_code('tv_login_sessions', 'code', 6);
  v_base_url := regexp_replace(v_base_url, '/+$', '');
  v_web_url := v_base_url || '/approve?code=' || v_code || '&nonce=' || replace(v_nonce, '+', '%2B');

  insert into public.tv_login_sessions (
    code,
    requester_user_id,
    device_nonce,
    device_name,
    redirect_base_url,
    web_url,
    status,
    poll_interval_seconds,
    expires_at
  )
  values (
    v_code,
    auth.uid(),
    v_nonce,
    v_device_name,
    v_base_url,
    v_web_url,
    'pending',
    v_poll_interval,
    v_expires_at
  );

  return query select v_code, v_web_url, v_expires_at, v_poll_interval;
end;
$$;

revoke all on function public.start_tv_login_session(text, text, text) from public;
grant execute on function public.start_tv_login_session(text, text, text) to authenticated;

create or replace function public.poll_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table (
  status text,
  expires_at timestamptz,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row public.tv_login_sessions%rowtype;
  v_status text;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.tv_login_sessions
  where code = upper(trim(coalesce(p_code, '')))
    and device_nonce = trim(coalesce(p_device_nonce, ''))
    and requester_user_id = auth.uid()
  order by created_at desc
  limit 1;

  if not found then
    raise exception 'Invalid TV login code or nonce';
  end if;

  v_status := v_row.status;

  if v_row.expires_at <= now() and v_row.status = 'pending' then
    update public.tv_login_sessions
      set status = 'expired'
    where id = v_row.id;
    v_status := 'expired';
  end if;

  return query select v_status, v_row.expires_at, v_row.poll_interval_seconds;
end;
$$;

revoke all on function public.poll_tv_login_session(text, text) from public;
grant execute on function public.poll_tv_login_session(text, text) to authenticated;

create or replace function public.approve_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table (message text)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_row public.tv_login_sessions%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.tv_login_sessions
  where code = upper(trim(coalesce(p_code, '')))
    and device_nonce = trim(coalesce(p_device_nonce, ''))
  for update;

  if not found then
    raise exception 'Invalid TV login code or nonce';
  end if;

  if v_row.expires_at <= now() then
    update public.tv_login_sessions
      set status = 'expired'
    where id = v_row.id;
    raise exception 'TV login expired';
  end if;

  if v_row.status = 'used' then
    raise exception 'TV login already used';
  end if;

  if v_row.status = 'cancelled' then
    raise exception 'TV login cancelled';
  end if;

  update public.tv_login_sessions
    set approved_by_user_id = auth.uid(),
        approved_at = now(),
        status = 'approved'
  where id = v_row.id;

  return query select 'TV login approved.';
end;
$$;

revoke all on function public.approve_tv_login_session(text, text) from public;
grant execute on function public.approve_tv_login_session(text, text) to authenticated;

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

create or replace function public.sync_set_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_secret_payload jsonb,
    p_masked_preview text,
    p_status text default 'configured',
    p_source text default 'app'
  )
  returns jsonb
  language plpgsql
  security definer
  set search_path = public
  as $$
  begin
    return public.service_set_account_secret(
      public.sync_owner_id(),
      p_secret_type,
      p_secret_ref,
      p_secret_payload,
      p_masked_preview,
      p_status,
      p_source
    );
  end;
  $$;

revoke all on function public.sync_set_account_secret(text, text, jsonb, text, text, text) from public;
grant execute on function public.sync_set_account_secret(text, text, jsonb, text, text, text) to authenticated;

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

create or replace function public.sync_delete_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_source text default 'app'
  )
  returns void
  language plpgsql
  security definer
  set search_path = public
  as $$
  begin
    perform public.service_delete_account_secret(
      public.sync_owner_id(),
      p_secret_type,
      p_secret_ref,
      p_source
    );
  end;
  $$;

revoke all on function public.sync_delete_account_secret(text, text, text) from public;
grant execute on function public.sync_delete_account_secret(text, text, text) to authenticated;

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

create or replace function public.sync_resolve_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_source text default 'app'
  )
  returns jsonb
  language plpgsql
  security definer
  set search_path = public
  as $$
  begin
    return public.service_resolve_account_secret(
      public.sync_owner_id(),
      p_secret_type,
      p_secret_ref,
      p_source
    );
  end;
  $$;

revoke all on function public.sync_resolve_account_secret(text, text, text) from public;
grant execute on function public.sync_resolve_account_secret(text, text, text) to authenticated;

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
