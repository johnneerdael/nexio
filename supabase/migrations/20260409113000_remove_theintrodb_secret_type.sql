-- Remove legacy TheIntroDB API-key secrets now that TheIntroDB no longer requires authentication.
-- Safe to run multiple times.

do $$
declare
  constraint_name text;
begin
  delete from public.account_secrets
  where secret_type = 'theintrodb_api_key';

  for constraint_name in
    select c.conname
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where n.nspname = 'public'
      and t.relname = 'account_secrets'
      and c.contype = 'c'
      and pg_get_constraintdef(c.oid) ilike '%secret_type%'
  loop
    execute format('alter table public.account_secrets drop constraint if exists %I', constraint_name);
  end loop;
end
$$;

do $$
begin
  if to_regclass('public.account_secrets') is not null then
    alter table public.account_secrets
      add constraint account_secrets_secret_type_check
      check (secret_type in (
        'addon_credential',
        'tmdb_api_key',
        'omdb_api_key',
        'imdb_api_key',
        'mdblist_api_key',
        'premiumize_api_key',
        'torbox_api_key',
        'easydebrid_api_key',
        'gemini_api_key',
        'rpdb_api_key',
        'top_posters_api_key',
        'realdebrid_access_token',
        'realdebrid_refresh_token',
        'simkl_access_token',
        'trakt_access_token',
        'trakt_refresh_token'
      ));
  end if;
end
$$;
