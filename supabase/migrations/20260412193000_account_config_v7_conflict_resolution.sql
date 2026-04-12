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
