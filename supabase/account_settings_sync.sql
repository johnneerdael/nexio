-- Account settings sync contract v4.
-- v2 and v3 requests remain supported, but v4 is the current contract and includes
-- integrations.imdb, integrations.debrid.torBox, and integrations.debrid.easyDebrid.

create extension if not exists pgcrypto;
create extension if not exists supabase_vault with schema vault;

do $$
begin
  if to_regclass('public.account_secrets') is not null then
    alter table public.account_secrets drop constraint if exists account_secrets_secret_type_check;
    alter table public.account_secrets
      add constraint account_secrets_secret_type_check
      check (secret_type in (
        'addon_credential',
        'tmdb_api_key',
        'omdb_api_key',
        'imdb_api_key',
        'mdblist_api_key',
        'premiumize_api_key',
        'torbox_api_key',
        'easydebrid_api_key',
        'gemini_api_key',
        'rpdb_api_key',
        'top_posters_api_key',
        'realdebrid_access_token',
        'realdebrid_refresh_token',
        'simkl_access_token',
        'trakt_access_token',
        'trakt_refresh_token'
      ));
  else
    create table if not exists public.account_secrets (
      id uuid primary key default gen_random_uuid(),
      user_id uuid not null references auth.users(id) on delete cascade,
      secret_type text not null check (secret_type in (
        'addon_credential',
        'tmdb_api_key',
        'omdb_api_key',
        'imdb_api_key',
        'mdblist_api_key',
        'premiumize_api_key',
        'torbox_api_key',
        'easydebrid_api_key',
        'gemini_api_key',
        'rpdb_api_key',
        'top_posters_api_key',
        'realdebrid_access_token',
        'realdebrid_refresh_token',
        'simkl_access_token',
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
  end if;
end;
$$;

create or replace function public.sync_pull_account_snapshot(
  p_contract_version integer default 4
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_requested_version integer := case
    when coalesce(p_contract_version, 4) >= 4 then 4
    when coalesce(p_contract_version, 4) >= 3 then 3
    else 2
  end;
  v_settings jsonb := '{}'::jsonb;
  v_settings_updated_at timestamptz := null;
  v_addons jsonb := '[]'::jsonb;
  v_addons_updated_at timestamptz := null;
  v_revision bigint := 0;
  v_updated_at timestamptz := null;
  v_integrations jsonb := '{}'::jsonb;
begin
  select coalesce(settings_payload, '{}'::jsonb), updated_at
    into v_settings, v_settings_updated_at
  from public.account_settings_public
  where user_id = v_user_id;

  v_integrations := coalesce(v_settings->'integrations', '{}'::jsonb);
  if v_requested_version >= 3 then
    v_integrations := jsonb_set(
      v_integrations,
      '{imdb}',
      coalesce(v_integrations->'imdb', jsonb_build_object('enabled', false, 'baseUrl', '')),
      true
    );
  else
    v_integrations := v_integrations - 'imdb';
  end if;

  v_integrations := jsonb_set(
    v_integrations,
    '{debrid}',
    coalesce(v_integrations->'debrid', '{}'::jsonb),
    true
  );
  if v_requested_version >= 4 then
    v_integrations := jsonb_set(
      jsonb_set(
        v_integrations,
        '{debrid,torBox}',
        coalesce(v_integrations #> '{debrid,torBox}', jsonb_build_object('configured', false, 'email', '', 'plan', '')),
        true
      ),
      '{debrid,easyDebrid}',
      coalesce(v_integrations #> '{debrid,easyDebrid}', jsonb_build_object('configured', false, 'userId', '', 'paidUntil', '')),
      true
    );
  else
    v_integrations := jsonb_set(
      v_integrations,
      '{debrid}',
      coalesce(v_integrations->'debrid', '{}'::jsonb) - 'torBox' - 'easyDebrid',
      true
    );
  end if;

  v_settings := jsonb_set(
    jsonb_set(
      coalesce(v_settings, '{}'::jsonb),
      '{schemaVersion}',
      to_jsonb(v_requested_version),
      true
    ),
    '{integrations}',
    v_integrations,
    true
  );

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', id,
        'url', base_url,
        'manifest_url', coalesce(manifest_url, base_url || '/manifest.json'),
        'parser_preset', parser_preset,
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
    'settings', v_settings,
    'addons', v_addons
  );
end;
$$;

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text default 'app',
  p_contract_version integer default 4
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
  v_requested_version integer := case
    when coalesce(p_contract_version, 4) >= 4 then 4
    when coalesce(p_contract_version, 4) >= 3 then 3
    else 2
  end;
  v_settings jsonb := coalesce(p_settings_payload, '{}'::jsonb);
begin
  v_settings := coalesce(v_settings, '{}'::jsonb);
  v_settings := jsonb_set(
    v_settings,
    '{integrations}',
    coalesce(v_settings->'integrations', '{}'::jsonb),
    true
  );
  if v_requested_version = 2 then
    v_settings := jsonb_set(
      v_settings,
      '{integrations}',
      coalesce(v_settings->'integrations', '{}'::jsonb) - 'imdb',
      true
    );
  else
    v_settings := jsonb_set(
      v_settings,
      '{integrations,imdb}',
      coalesce(v_settings #> '{integrations,imdb}', jsonb_build_object('enabled', false, 'baseUrl', '')),
      true
    );
  end if;

  v_settings := jsonb_set(
    v_settings,
    '{integrations,debrid}',
    coalesce(v_settings #> '{integrations,debrid}', '{}'::jsonb),
    true
  );
  if v_requested_version >= 4 then
    v_settings := jsonb_set(
      jsonb_set(
        v_settings,
        '{integrations,debrid,torBox}',
        coalesce(v_settings #> '{integrations,debrid,torBox}', jsonb_build_object('configured', false, 'email', '', 'plan', '')),
        true
      ),
      '{integrations,debrid,easyDebrid}',
      coalesce(v_settings #> '{integrations,debrid,easyDebrid}', jsonb_build_object('configured', false, 'userId', '', 'paidUntil', '')),
      true
    );
  else
    v_settings := jsonb_set(
      v_settings,
      '{integrations,debrid}',
      coalesce(v_settings #> '{integrations,debrid}', '{}'::jsonb) - 'torBox' - 'easyDebrid',
      true
    );
  end if;

  v_settings := jsonb_set(
    v_settings,
    '{schemaVersion}',
    to_jsonb(v_requested_version),
    true
  );

  insert into public.account_settings_public (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, v_settings, v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;

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
  if trim(coalesce(p_secret_type, '')) not in (
    'addon_credential',
    'tmdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'trakt_access_token',
    'trakt_refresh_token'
  ) then
    raise exception 'Unsupported secret type';
  end if;

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
  if trim(coalesce(p_secret_type, '')) not in (
    'addon_credential',
    'tmdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'trakt_access_token',
    'trakt_refresh_token'
  ) then
    raise exception 'Unsupported secret type';
  end if;

  perform public.service_delete_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;

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
  if trim(coalesce(p_secret_type, '')) not in (
    'addon_credential',
    'tmdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'trakt_access_token',
    'trakt_refresh_token'
  ) then
    raise exception 'Unsupported secret type';
  end if;

  return public.service_resolve_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;
