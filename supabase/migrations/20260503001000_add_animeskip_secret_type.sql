-- Add animeskip_api_key to the account_secrets secret type constraint and the
-- sync RPC function whitelists. Backfills existing animeSkip.clientId values
-- from account_settings_public.settings_payload JSON into the secrets table,
-- then strips clientId from the JSON payload. Drops clientId from the v2
-- default payload so future fresh accounts don't reintroduce the field.
--
-- AnimeSkip's clientId is sent as the X-Client-ID header on every GraphQL
-- request and is functionally a long-lived API key tied to the user's quota.
-- Storing it in the public IntegrationSettings JSON was the same architectural
-- mistake that wyzie_api_key just had to fix in 20260503000000.

-- 1. Add animeskip_api_key to the CHECK constraint.

alter table public.account_secrets
  drop constraint if exists account_secrets_secret_type_check;

alter table public.account_secrets
  add constraint account_secrets_secret_type_check
  check (
    secret_type = any (array[
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
      'wyzie_api_key',
      'animeskip_api_key',
      'realdebrid_access_token',
      'realdebrid_refresh_token',
      'simkl_access_token',
      'kitsu_access_token',
      'kitsu_refresh_token',
      'trakt_access_token',
      'trakt_refresh_token'
    ])
  );

-- 2. Update sync_set_account_secret whitelist.

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
    'wyzie_api_key',
    'animeskip_api_key',
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

-- 3. Update sync_delete_account_secret whitelist.

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
    'wyzie_api_key',
    'animeskip_api_key',
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

-- 4. Update sync_resolve_account_secret whitelist.

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
    'wyzie_api_key',
    'animeskip_api_key',
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

-- 5. Backfill: for every account that has a non-empty animeSkip.clientId in
-- their settings_payload, copy it into account_secrets via the existing
-- service helper (which writes the value to vault and creates the secret row).

do $$
declare
  rec record;
  v_client_id text;
  v_masked text;
begin
  for rec in
    select user_id, settings_payload
    from public.account_settings_public
    where coalesce(settings_payload#>>'{integrations,animeSkip,clientId}', '') <> ''
  loop
    v_client_id := rec.settings_payload#>>'{integrations,animeSkip,clientId}';
    v_masked := 'Stored ••••' || right(v_client_id, 4);
    perform public.service_set_account_secret(
      rec.user_id,
      'animeskip_api_key',
      'integration:animeSkip',
      jsonb_build_object('apiKey', v_client_id),
      v_masked,
      'configured',
      'migration'
    );
  end loop;
end $$;

-- 6. Strip the clientId field from the JSON settings_payload now that it lives
-- in the secrets table. Old app versions still in the wild that read
-- animeSkip.clientId from the JSON will see an empty value and treat it as
-- "not configured" — they'll continue to function for non-AnimeSkip features
-- and the next app upgrade will read the migrated value from the secrets table.

update public.account_settings_public
set settings_payload = jsonb_set(
  settings_payload,
  '{integrations,animeSkip}',
  coalesce(settings_payload#>'{integrations,animeSkip}', '{}'::jsonb) - 'clientId'
)
where settings_payload#>'{integrations,animeSkip}' is not null
  and (settings_payload#>'{integrations,animeSkip}') ? 'clientId';

-- 7. Deploy the default payload without animeSkip.clientId.

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
        "enabled": false
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
      "wyzie": {
        "enabled": true
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

-- Verification after deploy:
-- select public.account_settings_v2_default_payload() #> '{integrations,animeSkip}';
-- select count(*) from public.account_secrets where secret_type = 'animeskip_api_key';
-- select count(*) from public.account_settings_public
--   where (settings_payload#>'{integrations,animeSkip}') ? 'clientId';
-- (Last query should return 0 — no payloads still carry clientId.)
