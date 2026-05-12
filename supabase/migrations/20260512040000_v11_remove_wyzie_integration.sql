-- Contract v11: remove Wyzie integration from the server-side data model.
--
-- Wyzie subtitle search is now driven by a build-time API key shipped with
-- the Android client (BuildConfig.WYZIE_API_KEY) and no longer exposes a
-- per-account toggle or secret. The corresponding fields are dropped from
-- the synced payload (v11), the secret-type whitelist, and the canonical
-- extraction. The Android client + nexio-web portal have been updated to
-- match in the same release.
--
-- Order matters:
--   1. Delete existing wyzie_api_key rows so the CHECK constraint validates
--   2. Replace the CHECK constraint without 'wyzie_api_key'
--   3. Update the three secret-type whitelist RPCs (set/delete/resolve)
--   4. Strip 'integrations.wyzie' from stored settings_payload blobs
--   5. Replace account_settings_v2_default_payload() without the wyzie field
--   6. Replace account_settings_extract_canonical_v2() without the wyzie field

-- 1. Drop any existing Wyzie secrets. Clients no longer manage this key;
--    leaving rows behind would block the constraint update below.
delete from public.account_secrets
 where secret_type = 'wyzie_api_key';

-- 2. Rebuild the secret-type CHECK constraint without 'wyzie_api_key'.
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
      'realdebrid_access_token',
      'realdebrid_refresh_token',
      'simkl_access_token',
      'kitsu_access_token',
      'kitsu_refresh_token',
      'trakt_access_token',
      'trakt_refresh_token'
    ])
  );

-- 3. Update the three whitelist-enforcing secret RPCs.
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

-- 4. One-time cleanup: strip 'integrations.wyzie' from stored payloads so
--    old per-user blobs don't continue to surface a dead toggle once the
--    canonical extractor stops emitting one. No-op for rows that never had
--    a wyzie key.
update public.account_settings_public
   set settings_payload = settings_payload #- '{integrations,wyzie}'
 where settings_payload #> '{integrations,wyzie}' is not null;

-- 5. Default payload no longer includes a wyzie field.
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

-- 6. Canonical extraction no longer emits a wyzie field in either branch.
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

-- Verification after deploy:
-- select public.account_settings_v2_default_payload()#>'{integrations,wyzie}';  -- null
-- select count(*) from public.account_secrets where secret_type = 'wyzie_api_key';  -- 0
-- select public.sync_set_account_secret(
--   'wyzie_api_key', 'integration:wyzie',
--   '{"value":"x"}'::jsonb, 'x', 'configured', 'app'
-- );  -- raises "Unsupported secret type"
