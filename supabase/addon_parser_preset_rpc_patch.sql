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

revoke all on function public.sync_pull_account_snapshot() from public;
grant execute on function public.sync_pull_account_snapshot() to authenticated;


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
    coalesce(nullif(trim(entry->>'parser_preset'), ''), 'GENERIC'),
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
