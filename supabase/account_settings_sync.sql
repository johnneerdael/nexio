-- Account settings sync contract v6.
-- v2 and v5 requests remain supported, but v6 is the current contract and includes
-- provider-agnostic integrations.subtitleTranslation.

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
        'tvdb_api_key',
        'omdb_api_key',
        'imdb_api_key',
        'mdblist_api_key',
        'premiumize_api_key',
        'torbox_api_key',
        'easydebrid_api_key',
        'gemini_api_key',
        'translation_api_key',
        'rpdb_api_key',
        'top_posters_api_key',
        'realdebrid_access_token',
        'realdebrid_refresh_token',
        'simkl_access_token',
        'kitsu_access_token',
        'kitsu_refresh_token',
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
        'tvdb_api_key',
        'omdb_api_key',
        'imdb_api_key',
        'mdblist_api_key',
        'premiumize_api_key',
        'torbox_api_key',
        'easydebrid_api_key',
        'gemini_api_key',
        'translation_api_key',
        'rpdb_api_key',
        'top_posters_api_key',
        'realdebrid_access_token',
        'realdebrid_refresh_token',
        'simkl_access_token',
        'kitsu_access_token',
        'kitsu_refresh_token',
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
  v_settings_revision bigint := 0;
  v_addons jsonb := '[]'::jsonb;
  v_addons_updated_at timestamptz := null;
  v_revision bigint := 0;
  v_updated_at timestamptz := null;
  v_integrations jsonb := '{}'::jsonb;
begin
  select coalesce(settings_payload, '{}'::jsonb), updated_at, coalesce(sync_revision, 0)
    into v_settings, v_settings_updated_at, v_settings_revision
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
    'settings_revision', coalesce(v_settings_revision, 0),
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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

-- v6 provider-agnostic subtitle translation sync definitions.
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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
    'tvdb_api_key',
    'omdb_api_key',
    'imdb_api_key',
    'mdblist_api_key',
    'premiumize_api_key',
    'torbox_api_key',
    'easydebrid_api_key',
    'gemini_api_key',
    'translation_api_key',
    'rpdb_api_key',
    'top_posters_api_key',
    'realdebrid_access_token',
    'realdebrid_refresh_token',
    'simkl_access_token',
    'kitsu_access_token',
    'kitsu_refresh_token',
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

create or replace function public.account_settings_v2_default_payload()
returns jsonb
language sql
immutable
set search_path = public
as $$
  select $json$
  {
    "schemaVersion": 2,
    "integrations": {
      "debrid": {
        "premiumize": {
          "configured": false,
          "customerId": null
        },
        "realDebrid": {
          "connected": false,
          "username": "",
          "pending": false,
          "deviceCode": "",
          "userCode": "",
          "verificationUrl": "",
          "expiresAt": null
        }
      },
      "theIntroDb": {
        "enabled": false,
        "showIntroButton": true,
        "showRecapButton": true,
        "showCreditsButton": true,
        "showPreviewButton": true
      },
      "tmdb": {
        "enabled": false,
        "useArtwork": true,
        "useBasicInfo": true,
        "useDetails": true,
        "useCredits": true,
        "useProductions": true,
        "useNetworks": true,
        "useEpisodes": true,
        "useMoreLikeThis": true,
        "useCollections": true
      },
      "tvdb": {
        "enabled": false,
        "configured": false,
        "validationStatus": "NOT_CONFIGURED",
        "lastFailure": ""
      },
      "omdb": {
        "enabled": false
      },
      "imdb": {
        "enabled": false,
        "baseUrl": ""
      },
      "mdblist": {
        "enabled": false,
        "showTrakt": true,
        "showImdb": true,
        "showTmdb": true,
        "showLetterboxd": true,
        "showTomatoes": true,
        "showAudience": true,
        "showMetacritic": true
      },
      "animeSkip": {
        "enabled": false,
        "clientId": ""
      },
      "subtitleTranslation": {
        "enabled": false,
        "provider": "OPENAI",
        "model": "openrouter/free",
        "baseUrl": "https://openrouter.ai/api/v1"
      },
      "gemini": {
        "enabled": false
      },
      "posterRatings": {
        "rpdbEnabled": false,
        "topPostersEnabled": false
      },
      "kitsuAuth": {
        "enabled": false,
        "connected": false,
        "username": "",
        "accessTokenSecretRef": null,
        "refreshTokenSecretRef": null,
        "expiresAtEpochSeconds": null,
        "includeNsfw": false
      },
      "simklAuth": {
        "connected": false,
        "username": "",
        "accountId": null,
        "accountType": "",
        "connectedAt": null,
        "pending": false
      },
      "traktAuth": {
        "connected": false,
        "username": "",
        "userSlug": "",
        "connectedAt": null,
        "pending": false
      }
    },
    "catalogs": {
      "home": {
        "heroCatalogKeys": [],
        "homeCatalogOrderKeys": [],
        "disabledHomeCatalogKeys": []
      },
      "trakt": {
        "catalogEnabledSet": [],
        "catalogOrder": [],
        "selectedPopularListKeys": []
      },
      "simkl": {
        "catalogEnabledSet": [],
        "catalogOrder": []
      },
      "mdblist": {
        "hiddenPersonalListKeys": [],
        "selectedTopListKeys": [],
        "catalogOrder": []
      }
    },
    "playback": {
      "streamSelection": {
        "trackingProvider": "TRAKT"
      }
    },
    "formatter": {
      "enabled": true,
      "selectedTemplateId": "universal",
      "customTemplate": null
    },
    "legacyV1": {}
  }
  $json$::jsonb
$$;

create or replace function public.account_settings_extract_canonical_v2(p_payload jsonb)
returns jsonb
language plpgsql
immutable
set search_path = public
as $$
declare
  v_payload jsonb := coalesce(p_payload, '{}'::jsonb);
  v_defaults jsonb := public.account_settings_v2_default_payload();
begin
  if public.account_settings_is_v2_payload(v_payload) then
    return jsonb_build_object(
      'schemaVersion', 2,
      'integrations', jsonb_build_object(
        'debrid', jsonb_build_object(
          'premiumize',
            coalesce(v_defaults#>'{integrations,debrid,premiumize}', '{}'::jsonb)
            || coalesce(v_payload#>'{integrations,debrid,premiumize}', '{}'::jsonb),
          'realDebrid',
            coalesce(v_defaults#>'{integrations,debrid,realDebrid}', '{}'::jsonb)
            || coalesce(v_payload#>'{integrations,debrid,realDebrid}', '{}'::jsonb)
        ),
        'theIntroDb', coalesce(v_defaults#>'{integrations,theIntroDb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,theIntroDb}', '{}'::jsonb),
        'tmdb', coalesce(v_defaults#>'{integrations,tmdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,tmdb}', '{}'::jsonb),
        'tvdb', coalesce(v_defaults#>'{integrations,tvdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,tvdb}', '{}'::jsonb),
        'omdb', coalesce(v_defaults#>'{integrations,omdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,omdb}', '{}'::jsonb),
        'imdb', coalesce(v_defaults#>'{integrations,imdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,imdb}', '{}'::jsonb),
        'mdblist', (
          coalesce(v_defaults#>'{integrations,mdblist}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,mdblist}', '{}'::jsonb)
        ) - 'hiddenPersonalListKeys' - 'selectedTopListKeys' - 'catalogOrder',
        'animeSkip', coalesce(v_defaults#>'{integrations,animeSkip}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,animeSkip}', '{}'::jsonb),
        'subtitleTranslation',
          coalesce(v_defaults#>'{integrations,subtitleTranslation}', '{}'::jsonb)
          || case
            when v_payload#>'{integrations,subtitleTranslation}' is not null then
              coalesce(v_payload#>'{integrations,subtitleTranslation}', '{}'::jsonb)
            when coalesce(v_payload#>>'{integrations,gemini,enabled}', 'false')::boolean then
              jsonb_build_object(
                'enabled', true,
                'provider', 'GEMINI',
                'model', 'gemini-2.5-flash',
                'baseUrl', 'https://generativelanguage.googleapis.com/v1beta'
              )
            else '{}'::jsonb
          end,
        'gemini',
          coalesce(v_defaults#>'{integrations,gemini}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,gemini}', '{}'::jsonb),
        'posterRatings', coalesce(v_defaults#>'{integrations,posterRatings}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,posterRatings}', '{}'::jsonb),
        'kitsuAuth', coalesce(v_defaults#>'{integrations,kitsuAuth}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,kitsuAuth}', '{}'::jsonb),
        'simklAuth', coalesce(v_defaults#>'{integrations,simklAuth}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,simklAuth}', '{}'::jsonb),
        'traktAuth', coalesce(v_defaults#>'{integrations,traktAuth}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,traktAuth}', '{}'::jsonb)
      ),
      'catalogs', jsonb_build_object(
        'home', coalesce(v_defaults#>'{catalogs,home}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,home}', '{}'::jsonb),
        'trakt', coalesce(v_defaults#>'{catalogs,trakt}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,trakt}', '{}'::jsonb),
        'simkl', coalesce(v_defaults#>'{catalogs,simkl}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,simkl}', '{}'::jsonb),
        'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,mdblist}', '{}'::jsonb)
      ),
      'playback', coalesce(v_defaults->'playback', '{}'::jsonb)
        || coalesce(v_payload->'playback', '{}'::jsonb),
      'formatter', coalesce(v_defaults->'formatter', '{}'::jsonb)
        || coalesce(v_payload->'formatter', '{}'::jsonb),
      'legacyV1', coalesce(v_payload->'legacyV1', '{}'::jsonb)
    );
  end if;

  return jsonb_build_object(
    'schemaVersion', 2,
    'integrations', jsonb_build_object(
      'debrid', jsonb_build_object(
        'premiumize',
          coalesce(v_defaults#>'{integrations,debrid,premiumize}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,debrid,premiumize}', '{}'::jsonb),
        'realDebrid',
          coalesce(v_defaults#>'{integrations,debrid,realDebrid}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,debrid,realDebrid}', '{}'::jsonb)
      ),
      'theIntroDb', coalesce(v_defaults#>'{integrations,theIntroDb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,theIntroDb}', '{}'::jsonb),
      'tmdb', coalesce(v_defaults#>'{integrations,tmdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,tmdb}', '{}'::jsonb),
      'tvdb', coalesce(v_defaults#>'{integrations,tvdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,tvdb}', '{}'::jsonb),
      'omdb', coalesce(v_defaults#>'{integrations,omdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,omdb}', '{}'::jsonb),
      'imdb', coalesce(v_defaults#>'{integrations,imdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,imdb}', '{}'::jsonb),
      'mdblist', (
        coalesce(v_defaults#>'{integrations,mdblist}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,mdblist}', '{}'::jsonb)
      ) - 'hiddenPersonalListKeys' - 'selectedTopListKeys' - 'catalogOrder',
      'animeSkip', coalesce(v_defaults#>'{integrations,animeSkip}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,animeSkip}', '{}'::jsonb),
      'subtitleTranslation',
        coalesce(v_defaults#>'{integrations,subtitleTranslation}', '{}'::jsonb)
        || case
          when v_payload#>'{integrations,subtitleTranslation}' is not null then
            coalesce(v_payload#>'{integrations,subtitleTranslation}', '{}'::jsonb)
          when coalesce(v_payload#>>'{integrations,gemini,enabled}', 'false')::boolean then
            jsonb_build_object(
              'enabled', true,
              'provider', 'GEMINI',
              'model', 'gemini-2.5-flash',
              'baseUrl', 'https://generativelanguage.googleapis.com/v1beta'
            )
          else '{}'::jsonb
        end,
      'gemini',
        coalesce(v_defaults#>'{integrations,gemini}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,gemini}', '{}'::jsonb),
      'posterRatings', coalesce(v_defaults#>'{integrations,posterRatings}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,posterRatings}', '{}'::jsonb),
      'kitsuAuth', coalesce(v_defaults#>'{integrations,kitsuAuth}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,kitsuAuth}', '{}'::jsonb),
      'simklAuth', coalesce(v_defaults#>'{integrations,simklAuth}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,simklAuth}', '{}'::jsonb),
      'traktAuth', coalesce(v_defaults#>'{integrations,traktAuth}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,traktAuth}', '{}'::jsonb)
    ),
    'catalogs', jsonb_build_object(
      'home', coalesce(v_defaults#>'{catalogs,home}', '{}'::jsonb) || jsonb_build_object(
        'heroCatalogKeys', coalesce(v_payload#>'{layout,heroCatalogKeys}', '[]'::jsonb),
        'homeCatalogOrderKeys', coalesce(v_payload#>'{layout,homeCatalogOrderKeys}', '[]'::jsonb),
        'disabledHomeCatalogKeys', coalesce(v_payload#>'{layout,disabledHomeCatalogKeys}', '[]'::jsonb)
      ),
      'trakt', coalesce(v_defaults#>'{catalogs,trakt}', '{}'::jsonb) || jsonb_build_object(
        'catalogEnabledSet', coalesce(v_payload#>'{trakt,catalogEnabledSet}', '[]'::jsonb),
        'catalogOrder', coalesce(v_payload#>'{trakt,catalogOrder}', '[]'::jsonb),
        'selectedPopularListKeys', coalesce(v_payload#>'{trakt,selectedPopularListKeys}', '[]'::jsonb)
      ),
      'simkl', coalesce(v_defaults#>'{catalogs,simkl}', '{}'::jsonb) || jsonb_build_object(
        'catalogEnabledSet', coalesce(v_payload#>'{simkl,catalogEnabledSet}', '[]'::jsonb),
        'catalogOrder', coalesce(v_payload#>'{simkl,catalogOrder}', '[]'::jsonb)
      ),
      'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb) || jsonb_build_object(
        'hiddenPersonalListKeys', coalesce(v_payload#>'{integrations,mdblist,hiddenPersonalListKeys}', '[]'::jsonb),
        'selectedTopListKeys', coalesce(v_payload#>'{integrations,mdblist,selectedTopListKeys}', '[]'::jsonb),
        'catalogOrder', coalesce(v_payload#>'{integrations,mdblist,catalogOrder}', '[]'::jsonb)
      )
    ),
    'playback', coalesce(v_defaults->'playback', '{}'::jsonb)
      || coalesce(v_payload->'playback', '{}'::jsonb),
    'formatter', coalesce(v_defaults->'formatter', '{}'::jsonb)
      || coalesce(v_payload->'formatter', '{}'::jsonb),
    'legacyV1', v_payload
  );
end;
$$;

create or replace function public.account_settings_public_storage_payload(
  p_payload jsonb,
  p_existing_payload jsonb,
  p_contract_version integer
)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  v_contract_version integer := coalesce(p_contract_version, 1);
  v_defaults jsonb := public.account_settings_v2_default_payload();
  v_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_payload, '{}'::jsonb));
  v_existing_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_existing_payload, '{}'::jsonb));
  v_existing_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_existing_payload, '{}'::jsonb));
  v_incoming_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_payload, '{}'::jsonb));
  v_legacy jsonb := '{}'::jsonb;
  v_storage jsonb := '{}'::jsonb;
  v_existing_translation jsonb := null;
  v_incoming_translation_enabled jsonb := coalesce(
    coalesce(p_payload, '{}'::jsonb)#>'{integrations,gemini,enabled}',
    coalesce(p_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation,enabled}'
  );
  v_storage_translation_enabled jsonb := 'false'::jsonb;
begin
  if v_contract_version not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  if v_contract_version = 1 then
    v_legacy := v_incoming_legacy;
  else
    v_legacy := case
      when v_existing_legacy <> '{}'::jsonb then v_existing_legacy
      else v_incoming_legacy
    end;
  end if;

  v_storage := jsonb_build_object(
    'schemaVersion', 2,
    'integrations', coalesce(v_canonical->'integrations', '{}'::jsonb),
    'catalogs', coalesce(v_canonical->'catalogs', '{}'::jsonb),
    'playback', coalesce(v_canonical->'playback', v_defaults->'playback', '{}'::jsonb),
    'formatter', coalesce(v_canonical->'formatter', '{}'::jsonb),
    'legacyV1', coalesce(v_legacy, '{}'::jsonb)
  );

  if coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
      or (
        v_incoming_translation_enabled is null
        and coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,gemini}' is not null
      ) then
    v_existing_translation := v_existing_canonical#>'{integrations,subtitleTranslation}';
  end if;

  if v_contract_version < 6 then
    v_storage_translation_enabled := case
      when coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
        and upper(coalesce(v_existing_translation#>>'{provider}', '')) <> 'GEMINI'
      then coalesce(v_existing_translation#>'{enabled}', 'false'::jsonb)
      when coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
        and upper(coalesce(v_existing_translation#>>'{provider}', '')) = 'GEMINI'
      then coalesce(v_incoming_translation_enabled, v_existing_translation#>'{enabled}', 'false'::jsonb)
      else coalesce(v_incoming_translation_enabled, v_existing_translation#>'{enabled}', 'false'::jsonb)
    end;

    v_storage := jsonb_set(
      v_storage,
      '{integrations,subtitleTranslation}',
      coalesce(
        v_existing_translation,
        v_canonical#>'{integrations,subtitleTranslation}',
        v_defaults#>'{integrations,subtitleTranslation}'
      ) || jsonb_build_object(
        'enabled',
        v_storage_translation_enabled
      ),
      true
    );
  else
    v_storage := jsonb_set(
      v_storage,
      '{integrations,gemini,enabled}',
      case
        when v_storage#>'{integrations,subtitleTranslation}' is not null then
          case
            when v_storage#>'{integrations,subtitleTranslation,enabled}' = 'true'::jsonb
              and upper(coalesce(v_storage#>>'{integrations,subtitleTranslation,provider}', '')) = 'GEMINI'
            then 'true'::jsonb
            else 'false'::jsonb
          end
        else coalesce(v_storage#>'{integrations,gemini,enabled}', 'false'::jsonb)
      end,
      true
    );
  end if;

  return v_storage;
end;
$$;

create or replace function public.account_settings_v2_snapshot_payload(p_payload jsonb)
returns jsonb
language sql
immutable
set search_path = public
as $$
  select jsonb_build_object(
    'schemaVersion', 2,
    'integrations', coalesce(v.payload->'integrations', '{}'::jsonb),
    'catalogs', coalesce(v.payload->'catalogs', '{}'::jsonb),
    'playback', coalesce(v.payload->'playback', public.account_settings_v2_default_payload()->'playback', '{}'::jsonb),
    'formatter', coalesce(v.payload->'formatter', '{}'::jsonb)
  )
  from (select public.account_settings_extract_canonical_v2(p_payload) as payload) v
$$;

create or replace function public.sync_pull_account_snapshot(
  p_contract_version integer default 6
)
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
  v_settings_revision bigint := 0;
  v_addons_updated_at timestamptz := null;
  v_contract_version integer := coalesce(p_contract_version, 1);
  v_defaults jsonb := public.account_settings_v2_default_payload();
  v_integrations jsonb := '{}'::jsonb;
  v_legacy_gemini_enabled jsonb := 'false'::jsonb;
begin
  if v_contract_version not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  select settings_payload, updated_at, coalesce(sync_revision, 0)
    into v_settings, v_settings_updated_at, v_settings_revision
  from public.account_settings_public
  where user_id = v_user_id;

  v_settings := case
    when v_contract_version in (2, 5, 6, 7) then public.account_settings_v2_snapshot_payload(v_settings)
    else public.account_settings_v1_snapshot_payload(v_settings)
  end;

  if v_settings ? 'integrations' then
    v_integrations := coalesce(v_settings->'integrations', '{}'::jsonb);
    v_legacy_gemini_enabled := case
      when v_integrations#>'{subtitleTranslation}' is not null then
        case
          when v_integrations#>'{subtitleTranslation,enabled}' = 'true'::jsonb
            and upper(coalesce(v_integrations#>>'{subtitleTranslation,provider}', '')) = 'GEMINI'
          then 'true'::jsonb
          else 'false'::jsonb
        end
      else coalesce(v_integrations#>'{gemini,enabled}', 'false'::jsonb)
    end;

    if v_contract_version >= 6 then
      v_integrations := jsonb_set(
        v_integrations,
        '{subtitleTranslation}',
        coalesce(v_integrations#>'{subtitleTranslation}', v_defaults#>'{integrations,subtitleTranslation}'),
        true
      );
      v_integrations := jsonb_set(
        v_integrations,
        '{gemini,enabled}',
        v_legacy_gemini_enabled,
        true
      );
    else
      v_integrations := jsonb_set(
        v_integrations - 'subtitleTranslation',
        '{gemini,enabled}',
        v_legacy_gemini_enabled,
        true
      );
    end if;

    v_settings := jsonb_set(v_settings, '{integrations}', v_integrations, true);
  end if;

  if v_contract_version in (5, 6, 7) then
    v_settings := jsonb_set(v_settings, '{schemaVersion}', to_jsonb(v_contract_version), true);
  end if;

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
    'settings_revision', coalesce(v_settings_revision, 0),
    'updated_at', v_updated_at,
    'settings', coalesce(v_settings, '{}'::jsonb),
    'addons', v_addons
  );
end;
$$;

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text default 'app',
  p_contract_version integer default 6
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
  v_existing_payload jsonb := '{}'::jsonb;
  v_normalized_payload jsonb := '{}'::jsonb;
begin
  if coalesce(p_contract_version, 1) not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', p_contract_version
      using errcode = '22023';
  end if;

  select coalesce(settings_payload, '{}'::jsonb)
    into v_existing_payload
  from public.account_settings_public
  where user_id = v_user_id;

  v_normalized_payload := public.account_settings_public_storage_payload(
    p_payload => p_settings_payload,
    p_existing_payload => v_existing_payload,
    p_contract_version => p_contract_version
  );

  insert into public.account_settings_public (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, v_normalized_payload, v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;

create or replace function public.account_settings_public_storage_payload(
  p_payload jsonb,
  p_existing_payload jsonb,
  p_contract_version integer
)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  v_contract_version integer := coalesce(p_contract_version, 1);
  v_defaults jsonb := public.account_settings_v2_default_payload();
  v_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_payload, '{}'::jsonb));
  v_existing_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_existing_payload, '{}'::jsonb));
  v_existing_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_existing_payload, '{}'::jsonb));
  v_incoming_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_payload, '{}'::jsonb));
  v_legacy jsonb := '{}'::jsonb;
  v_storage jsonb := '{}'::jsonb;
  v_existing_translation jsonb := null;
  v_incoming_translation_enabled jsonb := coalesce(
    coalesce(p_payload, '{}'::jsonb)#>'{integrations,gemini,enabled}',
    coalesce(p_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation,enabled}'
  );
  v_storage_translation_enabled jsonb := 'false'::jsonb;
begin
  if v_contract_version not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  if v_contract_version = 1 then
    v_legacy := v_incoming_legacy;
  else
    v_legacy := case
      when v_existing_legacy <> '{}'::jsonb then v_existing_legacy
      else v_incoming_legacy
    end;
  end if;

  v_storage := jsonb_build_object(
    'schemaVersion', 2,
    'integrations', coalesce(v_canonical->'integrations', '{}'::jsonb),
    'catalogs', coalesce(v_canonical->'catalogs', '{}'::jsonb),
    'playback', coalesce(v_canonical->'playback', v_defaults->'playback', '{}'::jsonb),
    'formatter', coalesce(v_canonical->'formatter', '{}'::jsonb),
    'legacyV1', coalesce(v_legacy, '{}'::jsonb)
  );

  if coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
      or (
        v_incoming_translation_enabled is null
        and coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,gemini}' is not null
      ) then
    v_existing_translation := v_existing_canonical#>'{integrations,subtitleTranslation}';
  end if;

  if v_contract_version < 6 then
    v_storage_translation_enabled := case
      when coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
        and upper(coalesce(v_existing_translation#>>'{provider}', '')) <> 'GEMINI'
      then coalesce(v_existing_translation#>'{enabled}', 'false'::jsonb)
      when coalesce(p_existing_payload, '{}'::jsonb)#>'{integrations,subtitleTranslation}' is not null
        and upper(coalesce(v_existing_translation#>>'{provider}', '')) = 'GEMINI'
      then coalesce(v_incoming_translation_enabled, v_existing_translation#>'{enabled}', 'false'::jsonb)
      else coalesce(v_incoming_translation_enabled, v_existing_translation#>'{enabled}', 'false'::jsonb)
    end;

    v_storage := jsonb_set(
      v_storage,
      '{integrations,subtitleTranslation}',
      coalesce(
        v_existing_translation,
        v_canonical#>'{integrations,subtitleTranslation}',
        v_defaults#>'{integrations,subtitleTranslation}'
      ) || jsonb_build_object(
        'enabled',
        v_storage_translation_enabled
      ),
      true
    );
  else
    v_storage := jsonb_set(
      v_storage,
      '{integrations,gemini,enabled}',
      case
        when v_storage#>'{integrations,subtitleTranslation}' is not null then
          case
            when v_storage#>'{integrations,subtitleTranslation,enabled}' = 'true'::jsonb
              and upper(coalesce(v_storage#>>'{integrations,subtitleTranslation,provider}', '')) = 'GEMINI'
            then 'true'::jsonb
            else 'false'::jsonb
          end
        else coalesce(v_storage#>'{integrations,gemini,enabled}', 'false'::jsonb)
      end,
      true
    );
  end if;

  return v_storage;
end;
$$;

create or replace function public.sync_pull_account_snapshot(
  p_contract_version integer default 6
)
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
  v_settings_revision bigint := 0;
  v_addons_updated_at timestamptz := null;
  v_contract_version integer := coalesce(p_contract_version, 1);
  v_defaults jsonb := public.account_settings_v2_default_payload();
  v_integrations jsonb := '{}'::jsonb;
  v_legacy_gemini_enabled jsonb := 'false'::jsonb;
begin
  if v_contract_version not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  select settings_payload, updated_at, coalesce(sync_revision, 0)
    into v_settings, v_settings_updated_at, v_settings_revision
  from public.account_settings_public
  where user_id = v_user_id;

  v_settings := case
    when v_contract_version in (2, 5, 6, 7) then public.account_settings_v2_snapshot_payload(v_settings)
    else public.account_settings_v1_snapshot_payload(v_settings)
  end;

  if v_settings ? 'integrations' then
    v_integrations := coalesce(v_settings->'integrations', '{}'::jsonb);
    v_legacy_gemini_enabled := case
      when v_integrations#>'{subtitleTranslation}' is not null then
        case
          when v_integrations#>'{subtitleTranslation,enabled}' = 'true'::jsonb
            and upper(coalesce(v_integrations#>>'{subtitleTranslation,provider}', '')) = 'GEMINI'
          then 'true'::jsonb
          else 'false'::jsonb
        end
      else coalesce(v_integrations#>'{gemini,enabled}', 'false'::jsonb)
    end;

    if v_contract_version >= 6 then
      v_integrations := jsonb_set(
        v_integrations,
        '{subtitleTranslation}',
        coalesce(v_integrations#>'{subtitleTranslation}', v_defaults#>'{integrations,subtitleTranslation}'),
        true
      );
      v_integrations := jsonb_set(
        v_integrations,
        '{gemini,enabled}',
        v_legacy_gemini_enabled,
        true
      );
    else
      v_integrations := jsonb_set(
        v_integrations - 'subtitleTranslation',
        '{gemini,enabled}',
        v_legacy_gemini_enabled,
        true
      );
    end if;

    v_settings := jsonb_set(v_settings, '{integrations}', v_integrations, true);
  end if;

  if v_contract_version in (5, 6, 7) then
    v_settings := jsonb_set(v_settings, '{schemaVersion}', to_jsonb(v_contract_version), true);
  end if;

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
    'settings_revision', coalesce(v_settings_revision, 0),
    'updated_at', v_updated_at,
    'settings', coalesce(v_settings, '{}'::jsonb),
    'addons', v_addons
  );
end;
$$;

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text default 'app',
  p_contract_version integer default 6
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
  v_existing_payload jsonb := '{}'::jsonb;
  v_normalized_payload jsonb := '{}'::jsonb;
begin
  if coalesce(p_contract_version, 1) not in (1, 2, 5, 6, 7) then
    raise exception 'Unsupported account settings contract version: %', p_contract_version
      using errcode = '22023';
  end if;

  select coalesce(settings_payload, '{}'::jsonb)
    into v_existing_payload
  from public.account_settings_public
  where user_id = v_user_id;

  v_normalized_payload := public.account_settings_public_storage_payload(
    p_payload => p_settings_payload,
    p_existing_payload => v_existing_payload,
    p_contract_version => p_contract_version
  );

  insert into public.account_settings_public (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, v_normalized_payload, v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;

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

create or replace function public.account_settings_paths_overlap(p_left_path text, p_right_path text)
returns boolean
language sql
immutable
set search_path = public
as $$
  select
    v.left_path <> ''
    and v.right_path <> ''
    and (
      v.left_path = v.right_path
      or left(v.left_path, length(v.right_path) + 1) = v.right_path || '.'
      or left(v.right_path, length(v.left_path) + 1) = v.left_path || '.'
    )
  from (
    select
      trim(both '.' from coalesce(p_left_path, '')) as left_path,
      trim(both '.' from coalesce(p_right_path, '')) as right_path
  ) v
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

create or replace function public.account_settings_preserve_catalog_option_pins(
  p_existing_payload jsonb,
  p_incoming_payload jsonb,
  p_normalized_payload jsonb
)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
  v_result jsonb := coalesce(p_normalized_payload, '{}'::jsonb);
  v_existing jsonb := coalesce(p_existing_payload, '{}'::jsonb);
  v_incoming jsonb := coalesce(p_incoming_payload, '{}'::jsonb);
begin
  if v_incoming#>'{catalogs,trakt,pinnedListOptions}' is null
      and v_existing#>'{catalogs,trakt,pinnedListOptions}' is not null then
    v_result := jsonb_set(
      v_result,
      '{catalogs,trakt,pinnedListOptions}',
      v_existing#>'{catalogs,trakt,pinnedListOptions}',
      true
    );
  end if;

  if v_incoming#>'{catalogs,mdblist,pinnedTopListOptions}' is null
      and v_existing#>'{catalogs,mdblist,pinnedTopListOptions}' is not null then
    v_result := jsonb_set(
      v_result,
      '{catalogs,mdblist,pinnedTopListOptions}',
      v_existing#>'{catalogs,mdblist,pinnedTopListOptions}',
      true
    );
  end if;

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
  v_revision bigint := null;
  v_updated_at timestamptz := null;
  v_source text := coalesce(nullif(trim(p_source), ''), 'app');
  v_event_source text := coalesce(nullif(trim(p_source), ''), 'app') || ':v7';
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
  v_merged_payload jsonb := '{}'::jsonb;
  v_next_payload jsonb := '{}'::jsonb;
  v_snapshot_payload jsonb := '{}'::jsonb;
  v_has_untracked_post_base_revision boolean := false;
  v_write_applied boolean := false;
begin
  perform pg_advisory_xact_lock(hashtext('account_settings_public_v7'), hashtext(v_user_id::text));

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
  where user_id = v_user_id
  for update;

  v_current_revision := coalesce(v_current_revision, 0);
  v_revision := greatest(public.next_sync_revision(), v_current_revision + 1);
  v_updated_at := clock_timestamp();

  v_snapshot_payload := public.account_settings_v2_snapshot_payload(v_current_payload);

  select exists (
    select 1
    from public.account_sync_events event
    where event.user_id = v_user_id
      and event.event_type = 'settings_public'
      and event.revision > coalesce(p_base_revision, -1)
      and event.revision <= v_current_revision
      and (
        coalesce(event.source, '') !~ ':v7$'
        or not exists (
          select 1
          from public.account_settings_public_field_versions tracked
          where tracked.user_id = v_user_id
            and tracked.sync_revision = event.revision
        )
      )
  )
    into v_has_untracked_post_base_revision;

  if coalesce(p_base_revision, 0) > v_current_revision then
    return jsonb_build_object(
      'applied', false,
      'sync_revision', v_current_revision,
      'updated_at', v_current_updated_at,
      'updated_from', v_current_updated_from,
      'settings', coalesce(v_snapshot_payload, '{}'::jsonb),
      'conflict_paths', to_jsonb(v_changed_paths)
    );
  end if;

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

  select coalesce(array_agg(distinct changed_path.path order by changed_path.path), array[]::text[])
    into v_conflict_paths
  from unnest(v_changed_paths) as changed_path(path)
  where exists (
      select 1
      from public.account_settings_public_field_versions field_version
      where field_version.user_id = v_user_id
        and field_version.sync_revision > coalesce(p_base_revision, -1)
        and public.account_settings_paths_overlap(field_version.field_path, changed_path.path)
    )
    or (
      v_current_revision > coalesce(p_base_revision, 0)
      and v_has_untracked_post_base_revision
    );

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

  -- v7 changes conflict semantics only; storage normalization remains the v6 contract until payload shape changes.
  v_incoming_payload := public.account_settings_public_storage_payload(
    p_payload => p_settings_payload,
    p_existing_payload => v_current_payload,
    p_contract_version => 6
  );
  v_incoming_payload := public.account_settings_preserve_catalog_option_pins(
    p_existing_payload => v_current_payload,
    p_incoming_payload => p_settings_payload,
    p_normalized_payload => v_incoming_payload
  );

  if v_current_revision = coalesce(p_base_revision, 0) then
    v_next_payload := v_incoming_payload;
  else
    v_merged_payload := public.account_settings_merge_changed_paths(
      p_current_payload => v_current_payload,
      p_incoming_payload => v_incoming_payload,
      p_changed_paths => v_changed_paths
    );

    -- Re-normalize after path merge so derived storage fields stay consistent.
    v_next_payload := public.account_settings_public_storage_payload(
      p_payload => v_merged_payload,
      p_existing_payload => v_current_payload,
      p_contract_version => 6
    );
    v_next_payload := public.account_settings_preserve_catalog_option_pins(
      p_existing_payload => v_current_payload,
      p_incoming_payload => v_merged_payload,
      p_normalized_payload => v_next_payload
    );
  end if;

  with upserted as (
    insert into public.account_settings_public as current_row (
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
          updated_from = excluded.updated_from
      where current_row.sync_revision = v_current_revision
    returning 1
  )
  select exists(select 1 from upserted)
    into v_write_applied;

  if not v_write_applied then
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

    return jsonb_build_object(
      'applied', false,
      'sync_revision', coalesce(v_current_revision, 0),
      'updated_at', v_current_updated_at,
      'updated_from', v_current_updated_from,
      'settings', coalesce(public.account_settings_v2_snapshot_payload(v_current_payload), '{}'::jsonb),
      'conflict_paths', to_jsonb(v_changed_paths)
    );
  end if;

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

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', v_event_source);

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
revoke all on function public.account_settings_path_array(text) from public;
revoke all on function public.account_settings_path_value(jsonb, text) from public;
revoke all on function public.account_settings_paths_overlap(text, text) from public;
revoke all on function public.account_settings_merge_changed_paths(jsonb, jsonb, text[]) from public;
revoke all on function public.account_settings_preserve_catalog_option_pins(jsonb, jsonb, jsonb) from public;
