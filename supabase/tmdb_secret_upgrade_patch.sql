-- Incremental patch for projects provisioned before TMDB moved into the secret channel.
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
          'rpdb_api_key',
          'top_posters_api_key',
          'trakt_access_token',
          'trakt_refresh_token'
        )
      );
  end if;
end
$$;

create or replace function public.sync_set_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_secret_payload jsonb,
    p_masked_preview text,
    p_status text default 'configured',
    p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
begin
  return public.service_set_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_secret_payload,
    p_masked_preview,
    p_status,
    p_source
  );
end;
$$;

revoke all on function public.sync_set_account_secret(text, text, jsonb, text, text, text) from public;
grant execute on function public.sync_set_account_secret(text, text, jsonb, text, text, text) to authenticated;

create or replace function public.sync_delete_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_source text default 'app'
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.service_delete_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;

revoke all on function public.sync_delete_account_secret(text, text, text) from public;
grant execute on function public.sync_delete_account_secret(text, text, text) to authenticated;

create or replace function public.sync_resolve_account_secret(
    p_secret_type text,
    p_secret_ref text,
    p_source text default 'app'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
begin
  return public.service_resolve_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;

revoke all on function public.sync_resolve_account_secret(text, text, text) from public;
grant execute on function public.sync_resolve_account_secret(text, text, text) to authenticated;
