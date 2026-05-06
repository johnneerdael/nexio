alter table public.account_addons_public
  add column if not exists is_anime boolean not null default false;

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
    parser_preset,
    is_anime,
    name,
    description,
    enabled,
    sort_order,
    public_query_params,
    install_kind,
    secret_ref,
    transport_schema_version,
    transport_base_url,
    transport_secret_ref,
    updated_at
  )
  select
    v_user_id,
    trim(entry->>'url'),
    nullif(trim(entry->>'manifest_url'), ''),
    coalesce(nullif(trim(entry->>'parser_preset'), ''), 'GENERIC'),
    coalesce((entry->>'is_anime')::boolean, false),
    nullif(trim(entry->>'name'), ''),
    nullif(trim(entry->>'description'), ''),
    coalesce((entry->>'enabled')::boolean, true),
    coalesce((entry->>'sort_order')::integer, ordinal - 1),
    coalesce(entry->'public_query_params', '{}'::jsonb),
    coalesce(nullif(trim(entry->>'install_kind'), ''), 'manifest'),
    nullif(trim(entry->>'secret_ref'), ''),
    coalesce((entry->>'transport_schema_version')::integer, 1),
    nullif(trim(entry->>'transport_base_url'), ''),
    nullif(trim(entry->>'transport_secret_ref'), ''),
    v_updated_at
  from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) with ordinality as rows(entry, ordinal)
  where trim(entry->>'url') <> '';

  perform public.publish_account_sync_event(v_user_id, v_revision, 'addons_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;

revoke all on function public.sync_push_account_addons(jsonb, text) from public;
grant execute on function public.sync_push_account_addons(jsonb, text) to authenticated;

create or replace function public.sync_pull_account_snapshot(
  p_contract_version integer default 8
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
  if v_contract_version not in (1, 2, 5, 6, 7, 8) then
    raise exception 'Unsupported account settings contract version: %', v_contract_version
      using errcode = '22023';
  end if;

  select settings_payload, updated_at, coalesce(sync_revision, 0)
    into v_settings, v_settings_updated_at, v_settings_revision
  from public.account_settings_public
  where user_id = v_user_id;

  v_settings := case
    when v_contract_version in (2, 5, 6, 7, 8) then public.account_settings_v2_snapshot_payload(v_settings)
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

  if v_contract_version in (5, 6, 7, 8) then
    v_settings := jsonb_set(v_settings, '{schemaVersion}', to_jsonb(least(v_contract_version, 7)), true);
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', id,
        'url', base_url,
        'manifest_url', coalesce(manifest_url, base_url || '/manifest.json'),
        'parser_preset', parser_preset,
        'is_anime', coalesce(is_anime, false),
        'name', name,
        'description', description,
        'enabled', enabled,
        'sort_order', sort_order,
        'public_query_params', public_query_params,
        'install_kind', install_kind,
        'secret_ref', secret_ref,
        'transport_schema_version', coalesce(transport_schema_version, 1),
        'transport_base_url', coalesce(transport_base_url, base_url),
        'transport_secret_ref', transport_secret_ref
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

revoke all on function public.sync_pull_account_snapshot(integer) from public;
grant execute on function public.sync_pull_account_snapshot(integer) to authenticated;
