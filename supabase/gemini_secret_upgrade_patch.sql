-- Incremental patch for projects provisioned before Gemini moved into the secret channel.
-- Safe to run multiple times.

do $$
declare
  constraint_name text;
begin
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
  if not exists (
    select 1
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where n.nspname = 'public'
      and t.relname = 'account_secrets'
      and c.conname = 'account_secrets_secret_type_check'
  ) then
    alter table public.account_secrets
      add constraint account_secrets_secret_type_check
      check (
        secret_type in (
          'addon_credential',
          'tmdb_api_key',
          'mdblist_api_key',
          'gemini_api_key',
          'rpdb_api_key',
          'top_posters_api_key',
          'trakt_access_token',
          'trakt_refresh_token'
        )
      );
  end if;
end
$$;
