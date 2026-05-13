-- Compatibility alias for Android builds that resolved effective sync owners
-- through get_sync_owner before the canonical RPC name was sync_owner_id.

create or replace function public.get_sync_owner()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select public.sync_owner_id()
$$;

alter function public.get_sync_owner() owner to postgres;

revoke all on function public.get_sync_owner() from public;
grant execute on function public.get_sync_owner() to authenticated;
