-- Contract v13: sectioned account settings.
-- Authoritative account settings live in account_settings_sections.

create table if not exists public.account_settings_sections (
  user_id uuid not null references auth.users(id) on delete cascade,
  section_key text not null,
  payload jsonb not null default '{}'::jsonb,
  schema_version integer not null default 1,
  sync_revision bigint not null default 0,
  updated_at timestamptz not null default now(),
  updated_from text not null default 'app',
  primary key (user_id, section_key)
);

alter table public.account_settings_sections enable row level security;

drop policy if exists "account_settings_sections_owner_select"
  on public.account_settings_sections;

create policy "account_settings_sections_owner_select"
  on public.account_settings_sections
  for select
  to authenticated
  using (user_id = public.sync_owner_id());

drop policy if exists "account_settings_sections_owner_write"
  on public.account_settings_sections;

create policy "account_settings_sections_owner_write"
  on public.account_settings_sections
  for all
  to authenticated
  using (user_id = public.sync_owner_id())
  with check (user_id = public.sync_owner_id());

create or replace function public.account_settings_section_key_allowed(p_section_key text)
returns boolean
language sql
immutable
parallel safe
set search_path = public
as $$
  select coalesce(p_section_key = trim(p_section_key), false)
    and p_section_key = any (array[
    'integrations.subtitleTranslation',
    'integrations.imdb',
    'integrations.gemini',
    'integrations.tmdb',
    'integrations.omdb',
    'integrations.posterRatings',
    'integrations.animeSkip',
    'integrations.mdblist',
    'integrations.kitsu',
    'integrations.traktAuth',
    'integrations.simklAuth',
    'integrations.kitsuAuth',
    'integrations.debrid.premiumize',
    'integrations.debrid.realDebrid',
    'integrations.debrid.torBox',
    'integrations.debrid.easyDebrid',
    'catalogs.mdblist',
    'catalogs.trakt',
    'catalogs.simkl',
    'catalogs.tmdb',
    'catalogs.kitsu',
    'catalogs.home',
    'playback.streamSelection',
    'formatter'
  ]);
$$;

alter table public.account_settings_sections
  drop constraint if exists account_settings_sections_section_key_check;

alter table public.account_settings_sections
  add constraint account_settings_sections_section_key_check
  check (public.account_settings_section_key_allowed(section_key));

create or replace function public.account_settings_section_payload(
  p_settings jsonb,
  p_section_key text
)
returns jsonb
language sql
immutable
parallel safe
set search_path = public
as $$
  select case trim(coalesce(p_section_key, ''))
    when 'integrations.subtitleTranslation' then p_settings #> '{integrations,subtitleTranslation}'
    when 'integrations.imdb' then p_settings #> '{integrations,imdb}'
    when 'integrations.gemini' then p_settings #> '{integrations,gemini}'
    when 'integrations.tmdb' then p_settings #> '{integrations,tmdb}'
    when 'integrations.omdb' then p_settings #> '{integrations,omdb}'
    when 'integrations.posterRatings' then p_settings #> '{integrations,posterRatings}'
    when 'integrations.animeSkip' then p_settings #> '{integrations,animeSkip}'
    when 'integrations.mdblist' then p_settings #> '{integrations,mdblist}'
    when 'integrations.kitsu' then p_settings #> '{integrations,kitsu}'
    when 'integrations.traktAuth' then p_settings #> '{integrations,traktAuth}'
    when 'integrations.simklAuth' then p_settings #> '{integrations,simklAuth}'
    when 'integrations.kitsuAuth' then p_settings #> '{integrations,kitsuAuth}'
    when 'integrations.debrid.premiumize' then p_settings #> '{integrations,debrid,premiumize}'
    when 'integrations.debrid.realDebrid' then p_settings #> '{integrations,debrid,realDebrid}'
    when 'integrations.debrid.torBox' then p_settings #> '{integrations,debrid,torBox}'
    when 'integrations.debrid.easyDebrid' then p_settings #> '{integrations,debrid,easyDebrid}'
    when 'catalogs.mdblist' then p_settings #> '{catalogs,mdblist}'
    when 'catalogs.trakt' then p_settings #> '{catalogs,trakt}'
    when 'catalogs.simkl' then p_settings #> '{catalogs,simkl}'
    when 'catalogs.tmdb' then p_settings #> '{catalogs,tmdb}'
    when 'catalogs.kitsu' then p_settings #> '{catalogs,kitsu}'
    when 'catalogs.home' then p_settings #> '{catalogs,home}'
    when 'playback.streamSelection' then p_settings #> '{playback,streamSelection}'
    when 'formatter' then p_settings #> '{formatter}'
    else null
  end;
$$;

create or replace function public.account_settings_sections_to_payload(p_user_id uuid)
returns jsonb
language plpgsql
stable
set search_path = public, pg_temp
as $$
declare
  v_payload jsonb := '{"integrations":{},"catalogs":{},"playback":{}}'::jsonb;
  v_row record;
begin
  for v_row in
    select section_key, payload
      from public.account_settings_sections
     where user_id = p_user_id
  loop
    v_payload := case v_row.section_key
      when 'integrations.subtitleTranslation' then jsonb_set(v_payload, '{integrations,subtitleTranslation}', v_row.payload, true)
      when 'integrations.imdb' then jsonb_set(v_payload, '{integrations,imdb}', v_row.payload, true)
      when 'integrations.gemini' then jsonb_set(v_payload, '{integrations,gemini}', v_row.payload, true)
      when 'integrations.tmdb' then jsonb_set(v_payload, '{integrations,tmdb}', v_row.payload, true)
      when 'integrations.omdb' then jsonb_set(v_payload, '{integrations,omdb}', v_row.payload, true)
      when 'integrations.posterRatings' then jsonb_set(v_payload, '{integrations,posterRatings}', v_row.payload, true)
      when 'integrations.animeSkip' then jsonb_set(v_payload, '{integrations,animeSkip}', v_row.payload, true)
      when 'integrations.mdblist' then jsonb_set(v_payload, '{integrations,mdblist}', v_row.payload, true)
      when 'integrations.kitsu' then jsonb_set(v_payload, '{integrations,kitsu}', v_row.payload, true)
      when 'integrations.traktAuth' then jsonb_set(v_payload, '{integrations,traktAuth}', v_row.payload, true)
      when 'integrations.simklAuth' then jsonb_set(v_payload, '{integrations,simklAuth}', v_row.payload, true)
      when 'integrations.kitsuAuth' then jsonb_set(v_payload, '{integrations,kitsuAuth}', v_row.payload, true)
      when 'integrations.debrid.premiumize' then jsonb_set(jsonb_set(v_payload, '{integrations,debrid}', coalesce(v_payload #> '{integrations,debrid}', '{}'::jsonb), true), '{integrations,debrid,premiumize}', v_row.payload, true)
      when 'integrations.debrid.realDebrid' then jsonb_set(jsonb_set(v_payload, '{integrations,debrid}', coalesce(v_payload #> '{integrations,debrid}', '{}'::jsonb), true), '{integrations,debrid,realDebrid}', v_row.payload, true)
      when 'integrations.debrid.torBox' then jsonb_set(jsonb_set(v_payload, '{integrations,debrid}', coalesce(v_payload #> '{integrations,debrid}', '{}'::jsonb), true), '{integrations,debrid,torBox}', v_row.payload, true)
      when 'integrations.debrid.easyDebrid' then jsonb_set(jsonb_set(v_payload, '{integrations,debrid}', coalesce(v_payload #> '{integrations,debrid}', '{}'::jsonb), true), '{integrations,debrid,easyDebrid}', v_row.payload, true)
      when 'catalogs.mdblist' then jsonb_set(v_payload, '{catalogs,mdblist}', v_row.payload, true)
      when 'catalogs.trakt' then jsonb_set(v_payload, '{catalogs,trakt}', v_row.payload, true)
      when 'catalogs.simkl' then jsonb_set(v_payload, '{catalogs,simkl}', v_row.payload, true)
      when 'catalogs.tmdb' then jsonb_set(v_payload, '{catalogs,tmdb}', v_row.payload, true)
      when 'catalogs.kitsu' then jsonb_set(v_payload, '{catalogs,kitsu}', v_row.payload, true)
      when 'catalogs.home' then jsonb_set(v_payload, '{catalogs,home}', v_row.payload, true)
      when 'playback.streamSelection' then jsonb_set(v_payload, '{playback,streamSelection}', v_row.payload, true)
      when 'formatter' then jsonb_set(v_payload, '{formatter}', v_row.payload, true)
      else v_payload
    end;
  end loop;

  return v_payload;
end;
$$;

with section_keys(section_key) as (
  values
    ('integrations.subtitleTranslation'),
    ('integrations.imdb'),
    ('integrations.gemini'),
    ('integrations.tmdb'),
    ('integrations.omdb'),
    ('integrations.posterRatings'),
    ('integrations.animeSkip'),
    ('integrations.mdblist'),
    ('integrations.kitsu'),
    ('integrations.traktAuth'),
    ('integrations.simklAuth'),
    ('integrations.kitsuAuth'),
    ('integrations.debrid.premiumize'),
    ('integrations.debrid.realDebrid'),
    ('integrations.debrid.torBox'),
    ('integrations.debrid.easyDebrid'),
    ('catalogs.mdblist'),
    ('catalogs.trakt'),
    ('catalogs.simkl'),
    ('catalogs.tmdb'),
    ('catalogs.kitsu'),
    ('catalogs.home'),
    ('playback.streamSelection'),
    ('formatter')
)
insert into public.account_settings_sections (
  user_id,
  section_key,
  payload,
  schema_version,
  sync_revision,
  updated_at,
  updated_from
)
select
  s.user_id,
  k.section_key,
  public.account_settings_section_payload(s.settings_payload, k.section_key),
  1,
  coalesce(s.sync_revision, 0),
  coalesce(s.updated_at, now()),
  coalesce(nullif(trim(s.updated_from), ''), 'v13-backfill')
from public.account_settings_public s
cross join section_keys k
where public.account_settings_section_payload(s.settings_payload, k.section_key) is not null
on conflict (user_id, section_key) do update
  set payload = excluded.payload,
      schema_version = excluded.schema_version,
      sync_revision = excluded.sync_revision,
      updated_at = excluded.updated_at,
      updated_from = excluded.updated_from;

create or replace function public.sync_pull_account_settings_sections_v13()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_sections jsonb;
  v_settings_ms bigint;
begin
  select
    coalesce(jsonb_agg(jsonb_build_object(
      'section_key', section_key,
      'payload', payload,
      'schema_version', schema_version,
      'sync_revision', sync_revision,
      'updated_at_ms', public.sync_to_ms(updated_at)
    ) order by section_key), '[]'::jsonb),
    coalesce(max(public.sync_to_ms(updated_at)), 0)
  into v_sections, v_settings_ms
  from public.account_settings_sections
  where user_id = v_user_id;

  return jsonb_build_object(
    'contract_version', 13,
    'settings', jsonb_build_object(
      'sections', v_sections,
      'updated_at_ms', v_settings_ms
    )
  );
end;
$$;

create or replace function public.sync_pull_account_snapshot_v13()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_settings jsonb;
  v_addons jsonb;
  v_addons_ms bigint;
  v_secrets jsonb;
  v_secrets_ms bigint;
begin
  v_settings := public.sync_pull_account_settings_sections_v13()->'settings';

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', a.id,
           'url', a.base_url,
           'manifest_url', coalesce(a.manifest_url, a.base_url || '/manifest.json'),
           'parser_preset', a.parser_preset,
           'is_anime', coalesce(a.is_anime, false),
           'name', a.name,
           'description', a.description,
           'enabled', a.enabled,
           'sort_order', a.sort_order,
           'public_query_params', a.public_query_params,
           'install_kind', a.install_kind,
           'secret_ref', a.secret_ref,
           'transport_schema_version', coalesce(a.transport_schema_version, 1),
           'transport_base_url', coalesce(a.transport_base_url, a.base_url),
           'transport_secret_ref', a.transport_secret_ref
         ) order by a.sort_order), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(a.updated_at)), 0)
  into v_addons, v_addons_ms
  from public.account_addons_public a
  where a.user_id = v_user_id;

  select coalesce(jsonb_agg(jsonb_build_object(
           'secret_type', s.secret_type,
           'secret_ref', s.secret_ref,
           'masked_preview', s.masked_preview,
           'status', s.status,
           'updated_at_ms', public.sync_to_ms(s.updated_at)
         )), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(s.updated_at)), 0)
  into v_secrets, v_secrets_ms
  from public.account_secrets s
  where s.user_id = v_user_id;

  return jsonb_build_object(
    'contract_version', 13,
    'settings', v_settings,
    'addons', jsonb_build_object('items', v_addons, 'updated_at_ms', v_addons_ms),
    'secrets', jsonb_build_object('items', v_secrets, 'updated_at_ms', v_secrets_ms)
  );
end;
$$;

create or replace function public.sync_push_account_settings_section_v13(
  p_section_key text,
  p_payload jsonb,
  p_base_updated_at_ms bigint,
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_key text := coalesce(p_section_key, '');
  v_current_ms bigint := 0;
  v_revision bigint;
  v_updated_at timestamptz;
begin
  if not public.account_settings_section_key_allowed(v_key) then
    raise exception 'Unsupported account settings section: %', v_key using errcode = '22023';
  end if;

  if jsonb_typeof(coalesce(p_payload, 'null'::jsonb)) <> 'object' then
    raise exception 'Account settings section payload must be a JSON object' using errcode = '22023';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || v_key, 0));

  select coalesce(public.sync_to_ms(updated_at), 0)
  into v_current_ms
  from public.account_settings_sections
  where user_id = v_user_id and section_key = v_key;

  v_current_ms := coalesce(v_current_ms, 0);

  -- current section updated_at stale-base guard
  if coalesce(p_base_updated_at_ms, 0) < v_current_ms then
    return jsonb_build_object(
      'applied', false,
      'section_key', v_key,
      'reason', 'stale_base',
      'current_updated_at_ms', v_current_ms
    );
  end if;

  v_revision := public.next_sync_revision();
  v_updated_at := greatest(now(), to_timestamp((v_current_ms + 1)::double precision / 1000.0));

  insert into public.account_settings_sections (
    user_id,
    section_key,
    payload,
    schema_version,
    sync_revision,
    updated_at,
    updated_from
  )
  values (
    v_user_id,
    v_key,
    p_payload,
    1,
    v_revision,
    v_updated_at,
    coalesce(nullif(trim(p_source), ''), 'app')
  )
  on conflict (user_id, section_key) do update
    set payload = excluded.payload,
        schema_version = excluded.schema_version,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', coalesce(nullif(trim(p_source), ''), 'app'));

  return jsonb_build_object(
    'applied', true,
    'section_key', v_key,
    'sync_revision', v_revision,
    'current_updated_at_ms', public.sync_to_ms(v_updated_at)
  );
end;
$$;

create or replace function public.sync_push_account_settings_sections_v13(
  p_sections jsonb,
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_item jsonb;
  v_section_key text;
  v_payload jsonb;
  v_base_updated_at_ms_text text;
  v_base_updated_at_ms bigint;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_all_applied boolean := true;
begin
  if jsonb_typeof(coalesce(p_sections, 'null'::jsonb)) <> 'array' then
    raise exception 'p_sections must be a JSON array' using errcode = '22023';
  end if;

  for v_item in select value from jsonb_array_elements(p_sections)
  loop
    v_section_key := coalesce(v_item->>'section_key', '');
    v_payload := v_item->'payload';
    v_base_updated_at_ms_text := v_item->>'base_updated_at_ms';
    v_base_updated_at_ms := 0;

    if not public.account_settings_section_key_allowed(v_section_key) then
      v_result := jsonb_build_object(
        'applied', false,
        'section_key', v_section_key,
        'reason', 'unsupported_section'
      );
    elsif not (v_item ? 'payload') or v_item->'payload' = 'null'::jsonb or jsonb_typeof(v_payload) <> 'object' then
      v_result := jsonb_build_object(
        'applied', false,
        'section_key', v_section_key,
        'reason', 'invalid_payload'
      );
    elsif (v_item ? 'base_updated_at_ms')
      and (
        coalesce(v_base_updated_at_ms_text, '') !~ '^[0-9]+$'
        or length(v_base_updated_at_ms_text) > 19
        or (length(v_base_updated_at_ms_text) = 19 and v_base_updated_at_ms_text > '9223372036854775807')
      ) then
      v_result := jsonb_build_object(
        'applied', false,
        'section_key', v_section_key,
        'reason', 'invalid_base_updated_at_ms'
      );
    else
      if v_base_updated_at_ms_text ~ '^[0-9]+$' then
        v_base_updated_at_ms := v_base_updated_at_ms_text::bigint;
      end if;

      v_result := public.sync_push_account_settings_section_v13(
        p_section_key => v_section_key,
        p_payload => v_payload,
        p_base_updated_at_ms => v_base_updated_at_ms,
        p_source => p_source
      );
    end if;

    v_results := v_results || jsonb_build_array(v_result);
    if coalesce((v_result->>'applied')::boolean, false) = false then
      v_all_applied := false;
    end if;
  end loop;

  return jsonb_build_object(
    'applied', v_all_applied,
    'sections', v_results
  );
end;
$$;

create or replace function public.sync_pull_account_snapshot_v10()
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := auth.uid();
  v_settings_payload jsonb;
  v_settings_revision bigint;
  v_settings_ms bigint;
  v_settings_section_count bigint;
  v_addons jsonb;
  v_addons_ms bigint;
  v_secrets jsonb;
  v_secrets_ms bigint;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;

  select public.account_settings_sections_to_payload(v_user_id),
         coalesce(max(sync_revision), 0),
         coalesce(max(public.sync_to_ms(updated_at)), 0),
         count(*)
  into v_settings_payload, v_settings_revision, v_settings_ms, v_settings_section_count
  from public.account_settings_sections
  where user_id = v_user_id;

  if coalesce(v_settings_section_count, 0) = 0 or v_settings_payload is null or v_settings_payload = '{}'::jsonb then
    select settings_payload, sync_revision, public.sync_to_ms(updated_at)
    into v_settings_payload, v_settings_revision, v_settings_ms
    from public.account_settings_public
    where user_id = v_user_id;
  end if;

  v_settings_payload := coalesce(v_settings_payload, public.account_settings_v1_default_payload());
  v_settings_revision := coalesce(v_settings_revision, 0);
  v_settings_ms := coalesce(v_settings_ms, 0);

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', a.id,
           'url', a.base_url,
           'manifest_url', coalesce(a.manifest_url, a.base_url || '/manifest.json'),
           'parser_preset', a.parser_preset,
           'is_anime', coalesce(a.is_anime, false),
           'name', a.name,
           'description', a.description,
           'enabled', a.enabled,
           'sort_order', a.sort_order,
           'public_query_params', a.public_query_params,
           'install_kind', a.install_kind,
           'secret_ref', a.secret_ref,
           'transport_schema_version', coalesce(a.transport_schema_version, 1),
           'transport_base_url', coalesce(a.transport_base_url, a.base_url),
           'transport_secret_ref', a.transport_secret_ref
         ) order by a.sort_order), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(a.updated_at)), 0)
  into v_addons, v_addons_ms
  from public.account_addons_public a
  where a.user_id = v_user_id;

  select coalesce(jsonb_agg(jsonb_build_object(
           'secret_type', s.secret_type,
           'secret_ref', s.secret_ref,
           'masked_preview', s.masked_preview,
           'status', s.status,
           'updated_at_ms', public.sync_to_ms(s.updated_at)
         )), '[]'::jsonb),
         coalesce(max(public.sync_to_ms(s.updated_at)), 0)
  into v_secrets, v_secrets_ms
  from public.account_secrets s
  where s.user_id = v_user_id;

  return jsonb_build_object(
    'contract_version', 12,
    'settings', jsonb_build_object(
      'payload', v_settings_payload,
      'sync_revision', v_settings_revision,
      'updated_at_ms', v_settings_ms
    ),
    'addons', jsonb_build_object(
      'items', v_addons,
      'updated_at_ms', v_addons_ms
    ),
    'secrets', jsonb_build_object(
      'items', v_secrets,
      'updated_at_ms', v_secrets_ms
    )
  );
end;
$$;

create or replace function public.sync_push_account_settings_v10(
  p_base_updated_at_ms bigint,
  p_settings_payload jsonb,
  p_base_revision bigint,
  p_changed_paths text[],
  p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_user_id uuid := auth.uid();
  v_sections jsonb;
  v_batch_result jsonb;
  v_failure_reason text;
  v_current_updated_at_ms bigint;
begin
  if v_user_id is null then
    raise exception 'Authentication required';
  end if;

  with changed(path) as (
    select unnest(coalesce(p_changed_paths, array[]::text[]))
  ),
  section_keys(section_key) as (
    values
      ('integrations.subtitleTranslation'),
      ('integrations.imdb'),
      ('integrations.gemini'),
      ('integrations.tmdb'),
      ('integrations.omdb'),
      ('integrations.posterRatings'),
      ('integrations.animeSkip'),
      ('integrations.mdblist'),
      ('integrations.kitsu'),
      ('integrations.traktAuth'),
      ('integrations.simklAuth'),
      ('integrations.kitsuAuth'),
      ('integrations.debrid.premiumize'),
      ('integrations.debrid.realDebrid'),
      ('integrations.debrid.torBox'),
      ('integrations.debrid.easyDebrid'),
      ('catalogs.mdblist'),
      ('catalogs.trakt'),
      ('catalogs.simkl'),
      ('catalogs.tmdb'),
      ('catalogs.kitsu'),
      ('catalogs.home'),
      ('playback.streamSelection'),
      ('formatter')
  )
  select coalesce(jsonb_agg(jsonb_build_object(
    'section_key', section_key,
    'payload', public.account_settings_section_payload(p_settings_payload, section_key),
    'base_updated_at_ms', p_base_updated_at_ms
  )), '[]'::jsonb)
  into v_sections
  from section_keys
  where public.account_settings_section_payload(p_settings_payload, section_key) is not null
    and (
      not exists (select 1 from changed)
      or exists (
        select 1
        from changed
        where path = section_key
           or path like section_key || '.%'
           or section_key like path || '.%'
      )
    );

  if v_sections = '[]'::jsonb then
    return jsonb_build_object(
      'applied', true,
      'sync_revision', 0,
      'current_updated_at_ms', public.sync_now_ms()
    );
  end if;

  v_batch_result := public.sync_push_account_settings_sections_v13(
    v_sections,
    coalesce(nullif(trim(p_source), ''), 'legacy-adapter')
  );

  if coalesce((v_batch_result->>'applied')::boolean, false) = false then
    select item->>'reason'
    into v_failure_reason
    from jsonb_array_elements(coalesce(v_batch_result->'sections', '[]'::jsonb)) item
    where coalesce((item->>'applied')::boolean, false) = false
    limit 1;

    select coalesce(max((item->>'current_updated_at_ms')::bigint), 0)
    into v_current_updated_at_ms
    from jsonb_array_elements(coalesce(v_batch_result->'sections', '[]'::jsonb)) item
    where item ? 'current_updated_at_ms';

    if v_failure_reason = 'stale_base' then
      return jsonb_build_object(
        'applied', false,
        'reason', 'stale_base',
        'current_updated_at_ms', coalesce(v_current_updated_at_ms, 0)
      );
    end if;

    return jsonb_build_object(
      'applied', false,
      'reason', coalesce(v_failure_reason, 'section_push_failed'),
      'current_updated_at_ms', coalesce(v_current_updated_at_ms, 0)
    );
  end if;

  return jsonb_build_object(
    'applied', true,
    'sync_revision', (
      select coalesce(max((item->>'sync_revision')::bigint), 0)
      from jsonb_array_elements(v_batch_result->'sections') item
    ),
    'current_updated_at_ms', (
      select coalesce(max((item->>'current_updated_at_ms')::bigint), public.sync_now_ms())
      from jsonb_array_elements(v_batch_result->'sections') item
    )
  );
end;
$$;

revoke all on function public.sync_pull_account_settings_sections_v13() from public;
grant execute on function public.sync_pull_account_settings_sections_v13() to authenticated;

revoke all on function public.sync_pull_account_snapshot_v13() from public;
grant execute on function public.sync_pull_account_snapshot_v13() to authenticated;

revoke all on function public.sync_push_account_settings_section_v13(text, jsonb, bigint, text) from public;
grant execute on function public.sync_push_account_settings_section_v13(text, jsonb, bigint, text) to authenticated;

revoke all on function public.sync_push_account_settings_sections_v13(jsonb, text) from public;
grant execute on function public.sync_push_account_settings_sections_v13(jsonb, text) to authenticated;

revoke all on function public.sync_pull_account_snapshot_v10() from public;
grant execute on function public.sync_pull_account_snapshot_v10() to authenticated;

revoke all on function public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) from public;
grant execute on function public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) to authenticated;
