create or replace function public.account_settings_v1_default_payload()
returns jsonb
language sql
immutable
set search_path = public
as $$
  select $json$
  {
    "appearance": {
      "theme": "WHITE",
      "font": "INTER",
      "localeTag": "system"
    },
    "layout": {
      "selectedLayout": "MODERN",
      "modernLandscapePostersEnabled": false,
      "heroCatalogKeys": [],
      "homeCatalogOrderKeys": [],
      "disabledHomeCatalogKeys": [],
      "sidebarCollapsedByDefault": false,
      "modernSidebarEnabled": false,
      "modernSidebarBlurEnabled": false,
      "heroSectionEnabled": true,
      "searchDiscoverEnabled": true,
      "posterLabelsEnabled": true,
      "catalogAddonNameEnabled": true,
      "catalogTypeSuffixEnabled": true,
      "hideUnreleasedContent": false,
      "blurUnwatchedEpisodes": false,
      "preferExternalMetaAddonDetail": false,
      "focusedPosterBackdropExpandEnabled": false,
      "focusedPosterBackdropExpandDelaySeconds": 3,
      "posterCardWidthDp": 126,
      "posterCardCornerRadiusDp": 12
    },
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
        "showMetacritic": true,
        "hiddenPersonalListKeys": [],
        "selectedTopListKeys": [],
        "catalogOrder": []
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
      "simklAuth": {
        "connected": false,
        "username": "",
        "accountId": null,
        "accountType": "",
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
    "playback": {
      "general": {
        "loadingOverlayEnabled": true,
        "pauseOverlayEnabled": true,
        "osdClockEnabled": true,
        "skipIntroEnabled": true,
        "frameRateMatchingMode": "OFF",
        "resolutionMatchingEnabled": false
      },
      "streamSelection": {
        "streamReuseLastLinkEnabled": false,
        "streamReuseLastLinkCacheHours": 24,
        "uniformStreamFormattingEnabled": true,
        "groupStreamsAcrossAddonsEnabled": true,
        "deduplicateGroupedStreamsEnabled": true,
        "filterEpisodeMismatchStreamsEnabled": true,
        "filterMovieYearMismatchStreamsEnabled": true,
        "streamAutoPlayMode": "MANUAL",
        "streamAutoPlaySource": "ALL_SOURCES",
        "trackingProvider": "TRAKT",
        "streamAutoPlaySelectedAddons": [],
        "streamAutoPlayRegex": "",
        "streamAutoPlayNextEpisodeEnabled": false,
        "streamAutoPlayPreferBingeGroupForNextEpisode": true,
        "nextEpisodeThresholdMode": "PERCENTAGE",
        "nextEpisodeThresholdPercent": 99,
        "nextEpisodeThresholdMinutesBeforeEnd": 2
      },
      "audio": {
        "preferredAudioLanguage": "device",
        "secondaryPreferredAudioLanguage": null,
        "skipSilence": false,
        "decoderPriority": 1,
        "tunnelingEnabled": false
      },
      "subtitles": {
        "preferredLanguage": "en",
        "secondaryPreferredLanguage": null,
        "subtitleOrganizationMode": "NONE",
        "addonSubtitleStartupMode": "ALL_SUBTITLES",
        "size": 100,
        "verticalOffset": 5,
        "bold": false,
        "textColor": -1,
        "backgroundColor": 0,
        "outlineEnabled": true,
        "outlineColor": -16777216,
        "useLibass": false
      },
      "bufferNetwork": {
        "minBufferMs": 20000,
        "maxBufferMs": 50000,
        "bufferForPlaybackMs": 3000,
        "bufferForPlaybackAfterRebufferMs": 5000,
        "targetBufferSizeMb": 100,
        "backBufferDurationMs": 0,
        "enableBufferLogs": false
      }
    },
    "trakt": {
      "continueWatchingDaysCap": 60,
      "showUnairedNextUp": true,
      "catalogEnabledSet": [],
      "catalogOrder": [],
      "selectedPopularListKeys": []
    },
    "debug": {
      "accountTabEnabled": false,
      "syncCodeFeaturesEnabled": false,
      "bufferLogsEnabled": false
    }
  }
  $json$::jsonb
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
    "legacyV1": {}
  }
  $json$::jsonb
$$;

create or replace function public.account_settings_is_v2_payload(p_payload jsonb)
returns boolean
language sql
immutable
set search_path = public
as $$
  select coalesce(p_payload ? 'catalogs', false)
      or coalesce((p_payload->>'schemaVersion')::integer, 0) = 2
$$;

create or replace function public.account_settings_extract_legacy_v1_sidecar(p_payload jsonb)
returns jsonb
language sql
immutable
set search_path = public
as $$
  select case
    when public.account_settings_is_v2_payload(coalesce(p_payload, '{}'::jsonb))
      then coalesce(p_payload->'legacyV1', '{}'::jsonb)
    else coalesce(p_payload, '{}'::jsonb)
  end
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
        'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb)
          || coalesce(v_payload#>'{catalogs,mdblist}', '{}'::jsonb)
      ),
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
      'mdblist', coalesce(v_defaults#>'{catalogs,mdblist}', '{}'::jsonb) || jsonb_build_object(
        'hiddenPersonalListKeys', coalesce(v_payload#>'{integrations,mdblist,hiddenPersonalListKeys}', '[]'::jsonb),
        'selectedTopListKeys', coalesce(v_payload#>'{integrations,mdblist,selectedTopListKeys}', '[]'::jsonb),
        'catalogOrder', coalesce(v_payload#>'{integrations,mdblist,catalogOrder}', '[]'::jsonb)
      )
    ),
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
    'catalogs', coalesce(public.account_settings_extract_canonical_v2(p_payload)->'catalogs', '{}'::jsonb)
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
    'debug', coalesce(v_legacy->'debug', v_defaults->'debug')
  );
end;
$$;

create or replace function public.sync_pull_account_snapshot(
  p_contract_version integer
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
  v_addons_updated_at timestamptz := null;
  v_contract_version integer := coalesce(p_contract_version, 1);
begin
  if v_contract_version not in (1, 2) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  select settings_payload, updated_at
    into v_settings, v_settings_updated_at
  from public.account_settings_public
  where user_id = v_user_id;

  v_settings := case
    when v_contract_version = 2 then public.account_settings_v2_snapshot_payload(v_settings)
    else public.account_settings_v1_snapshot_payload(v_settings)
  end;

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
    'settings', coalesce(v_settings, '{}'::jsonb),
    'addons', v_addons
  );
end;
$$;

create or replace function public.sync_pull_account_snapshot()
returns jsonb
language sql
security definer
set search_path = public
as $$
  select public.sync_pull_account_snapshot(1)
$$;

revoke all on function public.sync_pull_account_snapshot() from public;
grant execute on function public.sync_pull_account_snapshot() to authenticated;
revoke all on function public.sync_pull_account_snapshot(integer) from public;
grant execute on function public.sync_pull_account_snapshot(integer) to authenticated;

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text,
  p_contract_version integer
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
  if coalesce(p_contract_version, 1) not in (1, 2) then
    raise exception 'Unsupported account settings contract version: %', p_contract_version
      using errcode = '22023';
  end if;

  select settings_payload
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

create or replace function public.sync_push_account_settings(
  p_settings_payload jsonb,
  p_source text default 'app'
)
returns table(sync_revision bigint, updated_at timestamptz)
language sql
security definer
set search_path = public
as $$
  select * from public.sync_push_account_settings(p_settings_payload, p_source, 1)
$$;

revoke all on function public.sync_push_account_settings(jsonb, text) from public;
grant execute on function public.sync_push_account_settings(jsonb, text) to authenticated;
revoke all on function public.sync_push_account_settings(jsonb, text, integer) from public;
grant execute on function public.sync_push_account_settings(jsonb, text, integer) to authenticated;
