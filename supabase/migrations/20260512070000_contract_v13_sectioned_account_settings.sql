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
