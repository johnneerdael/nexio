-- Add Kitsu public auth settings to the account settings default payload
-- and canonical extraction so integrations.kitsuAuth survives account
-- config push/pull sync.

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

-- Verification after deploy:
-- select public.account_settings_v2_default_payload()#>'{integrations,kitsuAuth}';
-- select public.account_settings_extract_canonical_v2(
--   '{"schemaVersion":2,"integrations":{"kitsuAuth":{"enabled":true,"includeNsfw":true}}}'::jsonb
-- )#>'{integrations,kitsuAuth}';
