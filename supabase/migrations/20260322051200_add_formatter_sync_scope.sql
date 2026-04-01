-- Add formatter schema scope to account config sync
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
      "gemini": {
        "enabled": false
      },
      "posterRatings": {
        "rpdbEnabled": false,
        "topPostersEnabled": false
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
      "mdblist": {
        "hiddenPersonalListKeys": [],
        "selectedTopListKeys": [],
        "catalogOrder": []
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
        'tmdb', coalesce(v_defaults#>'{integrations,tmdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,tmdb}', '{}'::jsonb),
        'omdb', coalesce(v_defaults#>'{integrations,omdb}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,omdb}', '{}'::jsonb),
        'mdblist', (
          coalesce(v_defaults#>'{integrations,mdblist}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,mdblist}', '{}'::jsonb)
        ) - 'hiddenPersonalListKeys' - 'selectedTopListKeys' - 'catalogOrder',
        'animeSkip', coalesce(v_defaults#>'{integrations,animeSkip}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,animeSkip}', '{}'::jsonb),
        'gemini', coalesce(v_defaults#>'{integrations,gemini}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,gemini}', '{}'::jsonb),
        'posterRatings', coalesce(v_defaults#>'{integrations,posterRatings}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,posterRatings}', '{}'::jsonb),
        'traktAuth', coalesce(v_defaults#>'{integrations,traktAuth}', '{}'::jsonb)
          || coalesce(v_payload#>'{integrations,traktAuth}', '{}'::jsonb)
      ),
      'catalogs', jsonb_build_object(
        'home', coalesce(v_defaults#>'{catalogs,home}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,home}', '{}'::jsonb),
        'trakt', coalesce(v_defaults#>'{catalogs,trakt}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,trakt}', '{}'::jsonb),
        'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,mdblist}', '{}'::jsonb)
      ),
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
      'tmdb', coalesce(v_defaults#>'{integrations,tmdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,tmdb}', '{}'::jsonb),
      'omdb', coalesce(v_defaults#>'{integrations,omdb}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,omdb}', '{}'::jsonb),
      'mdblist', (
        coalesce(v_defaults#>'{integrations,mdblist}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,mdblist}', '{}'::jsonb)
      ) - 'hiddenPersonalListKeys' - 'selectedTopListKeys' - 'catalogOrder',
      'animeSkip', coalesce(v_defaults#>'{integrations,animeSkip}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,animeSkip}', '{}'::jsonb),
      'gemini', coalesce(v_defaults#>'{integrations,gemini}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,gemini}', '{}'::jsonb),
      'posterRatings', coalesce(v_defaults#>'{integrations,posterRatings}', '{}'::jsonb)
        || coalesce(v_payload#>'{integrations,posterRatings}', '{}'::jsonb),
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
      'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb) || jsonb_build_object(
        'hiddenPersonalListKeys', coalesce(v_payload#>'{integrations,mdblist,hiddenPersonalListKeys}', '[]'::jsonb),
        'selectedTopListKeys', coalesce(v_payload#>'{integrations,mdblist,selectedTopListKeys}', '[]'::jsonb),
        'catalogOrder', coalesce(v_payload#>'{integrations,mdblist,catalogOrder}', '[]'::jsonb)
      )
    ),
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
  v_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_payload, '{}'::jsonb));
  v_existing_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_existing_payload, '{}'::jsonb));
  v_incoming_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_payload, '{}'::jsonb));
  v_legacy jsonb := '{}'::jsonb;
begin
  if v_contract_version not in (1, 2) then
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

  return jsonb_build_object(
    'schemaVersion', 2,
    'integrations', coalesce(v_canonical->'integrations', '{}'::jsonb),
    'catalogs', coalesce(v_canonical->'catalogs', '{}'::jsonb),
    'formatter', coalesce(v_canonical->'formatter', '{}'::jsonb),
    'legacyV1', coalesce(v_legacy, '{}'::jsonb)
  );
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
    'integrations', coalesce(public.account_settings_extract_canonical_v2(p_payload)->'integrations', '{}'::jsonb),
    'catalogs', coalesce(public.account_settings_extract_canonical_v2(p_payload)->'catalogs', '{}'::jsonb),
    'formatter', coalesce(public.account_settings_extract_canonical_v2(p_payload)->'formatter', '{}'::jsonb)
  )
$$;

create or replace function public.account_settings_v1_snapshot_payload(p_payload jsonb)
returns jsonb
language plpgsql
immutable
set search_path = public
as $$
declare
  v_defaults jsonb := public.account_settings_v1_default_payload();
  v_legacy jsonb := public.account_settings_extract_legacy_v1_sidecar(coalesce(p_payload, '{}'::jsonb));
  v_canonical jsonb := public.account_settings_extract_canonical_v2(coalesce(p_payload, '{}'::jsonb));
  v_layout jsonb := coalesce(v_legacy->'layout', v_defaults->'layout');
  v_integrations jsonb := coalesce(v_canonical->'integrations', v_defaults->'integrations');
  v_trakt jsonb := coalesce(v_legacy->'trakt', v_defaults->'trakt');
begin
  v_layout := jsonb_set(v_layout, '{heroCatalogKeys}', coalesce(v_canonical#>'{catalogs,home,heroCatalogKeys}', '[]'::jsonb), true);
  v_layout := jsonb_set(v_layout, '{homeCatalogOrderKeys}', coalesce(v_canonical#>'{catalogs,home,homeCatalogOrderKeys}', '[]'::jsonb), true);
  v_layout := jsonb_set(v_layout, '{disabledHomeCatalogKeys}', coalesce(v_canonical#>'{catalogs,home,disabledHomeCatalogKeys}', '[]'::jsonb), true);

  v_integrations := jsonb_set(v_integrations, '{mdblist,hiddenPersonalListKeys}', coalesce(v_canonical#>'{catalogs,mdblist,hiddenPersonalListKeys}', '[]'::jsonb), true);
  v_integrations := jsonb_set(v_integrations, '{mdblist,selectedTopListKeys}', coalesce(v_canonical#>'{catalogs,mdblist,selectedTopListKeys}', '[]'::jsonb), true);
  v_integrations := jsonb_set(v_integrations, '{mdblist,catalogOrder}', coalesce(v_canonical#>'{catalogs,mdblist,catalogOrder}', '[]'::jsonb), true);

  v_trakt := jsonb_set(v_trakt, '{catalogEnabledSet}', coalesce(v_canonical#>'{catalogs,trakt,catalogEnabledSet}', '[]'::jsonb), true);
  v_trakt := jsonb_set(v_trakt, '{catalogOrder}', coalesce(v_canonical#>'{catalogs,trakt,catalogOrder}', '[]'::jsonb), true);
  v_trakt := jsonb_set(v_trakt, '{selectedPopularListKeys}', coalesce(v_canonical#>'{catalogs,trakt,selectedPopularListKeys}', '[]'::jsonb), true);

  return jsonb_build_object(
    'appearance', coalesce(v_legacy->'appearance', v_defaults->'appearance'),
    'layout', v_layout,
    'integrations', v_integrations,
    'playback', coalesce(v_legacy->'playback', v_defaults->'playback'),
    'trakt', v_trakt,
    'debug', coalesce(v_legacy->'debug', v_defaults->'debug'),
    'formatter', coalesce(v_canonical->'formatter', '{}'::jsonb)
  );
end;
$$;
